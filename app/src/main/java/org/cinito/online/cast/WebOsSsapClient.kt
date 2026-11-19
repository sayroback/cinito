package org.cinito.online.cast

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.cinito.online.network.NetworkSafety
import java.io.EOFException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

interface SsapKeyStore {
    fun getKey(deviceKey: String): String?
    fun putKey(deviceKey: String, clientKey: String)
}

class SharedPrefsSsapKeyStore(context: Context) : SsapKeyStore {
    private val secretStorage = org.cinito.online.search.KeystoreSecretStorage(context.applicationContext)
    private val legacyPrefs = context.applicationContext.getSharedPreferences("cinito_webos_ssap", Context.MODE_PRIVATE)

    override fun getKey(deviceKey: String): String? {
        val encrypted = secretStorage.readString("ssap_key_$deviceKey")?.takeIf { it.isNotBlank() }
        if (encrypted != null) return encrypted
        val legacy = legacyPrefs.getString("key_$deviceKey", null)?.takeIf { it.isNotBlank() }
        if (legacy != null) {
            secretStorage.saveString("ssap_key_$deviceKey", legacy)
            legacyPrefs.edit().remove("key_$deviceKey").apply()
        }
        return legacy
    }

    override fun putKey(deviceKey: String, clientKey: String) {
        secretStorage.saveString("ssap_key_$deviceKey", clientKey)
        legacyPrefs.edit().remove("key_$deviceKey").apply()
    }
}

/**
 * Native OkHttp WebSocket client for LG webOS Second Screen Access Protocol (SSAP).
 * Connects over ws://3000 or wss://3001 (LAN-only), handles TV pairing prompt, persists client-key,
 * and launches LG apps / controls TV audio & playback natively.
 */
class WebOsSsapClient(
    private val keyStore: SsapKeyStore,
    var onDisconnected: (() -> Unit)? = null,
) {
    private enum class EndpointAttemptResult {
        Connected,
        PairingRejected,
        Failed,
    }

    private val requestCounter = AtomicInteger(1)
    private val connectionGeneration = AtomicLong(0L)
    private val connectedFlag = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SsapEnvelope>>()
    private val pointerMutex = Mutex()
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var pointerSocket: WebSocket? = null
    @Volatile private var connectedHost: String? = null

    @Volatile private var registerSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var promptNotified = false
    @Volatile var isConnected: Boolean = false
        private set

    private val localTlsClient: OkHttpClient by lazy {
        val trustLocalTv = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustLocalTv), SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .sslSocketFactory(sslCtx.socketFactory, trustLocalTv)
            .hostnameVerifier { hostname, _ -> NetworkSafety.isPrivateLanHost(hostname) }
            .build()
    }

    suspend fun connect(
        host: String,
        preferredPort: Int = 3000,
        onPairingPrompt: () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        require(NetworkSafety.isPrivateLanHost(host)) {
            "Bloqueada conexión LG SSAP fuera de la red local ($host)"
        }
        close()
        connectedHost = host
        val endpoints = buildList {
            add("wss://$host:3001")
            add("ws://$host:${preferredPort.takeIf { it > 0 && it != 3001 } ?: 3000}")
        }.distinct()

        for (wsUrl in endpoints) {
            when (tryEndpoint(host, wsUrl, onPairingPrompt)) {
                EndpointAttemptResult.Connected -> {
                    connectedHost = host
                    connectedFlag.set(true)
                    isConnected = true
                    return@withContext true
                }
                EndpointAttemptResult.PairingRejected -> {
                    CastLog.w("SSAP", "Pairing explicitly rejected on $wsUrl; aborting fallback endpoints")
                    closeTransportOnly(clearHost = false)
                    break
                }
                EndpointAttemptResult.Failed -> {
                    closeTransportOnly(clearHost = false)
                }
            }
        }
        connectedHost = null
        false
    }

    private suspend fun tryEndpoint(
        host: String,
        wsUrl: String,
        onPairingPrompt: () -> Unit,
    ): EndpointAttemptResult {
        val savedKey = keyStore.getKey(host)
        val regDeferred = CompletableDeferred<Boolean>()
        registerSignal = regDeferred
        promptNotified = false
        val pairingRejected = AtomicBoolean(false)
        val gen = connectionGeneration.incrementAndGet()

        val openSignal = CompletableDeferred<Boolean>()
        val req = Request.Builder().url(wsUrl).build()
        val ws = localTlsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionGeneration.get() != gen) return
                openSignal.complete(true)
                webSocket.send(WebOsSsapProtocol.buildRegisterPayload(savedKey, requestId = "reg_0"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionGeneration.get() != gen) return
                val env = WebOsSsapProtocol.parseEnvelope(text)
                if (env.id == "reg_0") {
                    when (env.type) {
                        SsapMessageType.Registered -> {
                            env.clientKey?.let { keyStore.putKey(host, it) }
                            regDeferred.complete(true)
                        }
                        SsapMessageType.Response -> {
                            if (env.isPairingPrompt && !promptNotified) {
                                promptNotified = true
                                onPairingPrompt()
                            }
                        }
                        SsapMessageType.Error -> {
                            CastLog.w("SSAP", "Registration rejected on $wsUrl error=${env.error}")
                            pairingRejected.set(true)
                            regDeferred.complete(false)
                        }
                        else -> Unit
                    }
                    return
                }
                env.id?.let { id ->
                    pending.remove(id)?.complete(env)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionGeneration.get() != gen) return
                val wasConnected = connectedFlag.compareAndSet(true, false)
                isConnected = false
                CastLog.w("SSAP", "ws onFailure $wsUrl httpCode=${response?.code} wasConnected=$wasConnected", t)
                openSignal.complete(false)
                regDeferred.complete(false)
                failAllPending(t)
                if (wasConnected && connectionGeneration.get() == gen) {
                    onDisconnected?.invoke()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionGeneration.get() != gen) return
                val wasConnected = connectedFlag.compareAndSet(true, false)
                isConnected = false
                CastLog.w("SSAP", "ws onClosed $wsUrl code=$code reason=$reason wasConnected=$wasConnected")
                openSignal.complete(false)
                regDeferred.complete(false)
                failAllPending(EOFException("SSAP connection closed ($code)"))
                if (wasConnected && connectionGeneration.get() == gen) {
                    onDisconnected?.invoke()
                }
            }
        })
        webSocket = ws

        val opened = runCatching { withTimeout(3_500L) { openSignal.await() } }.getOrDefault(false)
        if (!opened) return EndpointAttemptResult.Failed

        val registered = runCatching {
            withTimeout(2_500L) { regDeferred.await() }
        }.recoverCatching {
            val remainingTimeoutMs = if (savedKey != null && !promptNotified) 8_000L else 30_000L
            withTimeout(remainingTimeoutMs) { regDeferred.await() }
        }.getOrDefault(false)

        return when {
            registered -> EndpointAttemptResult.Connected
            pairingRejected.get() -> EndpointAttemptResult.PairingRejected
            else -> EndpointAttemptResult.Failed
        }
    }

    suspend fun request(uri: String, payload: JsonObject? = null, timeoutMs: Long = 5_000L): SsapEnvelope? {
        val ws = webSocket ?: return null
        val id = "req_${requestCounter.getAndIncrement()}"
        val deferred = CompletableDeferred<SsapEnvelope>()
        pending[id] = deferred
        val sent = ws.send(WebOsSsapProtocol.buildRequestPayload(id = id, uri = uri, payload = payload))
        if (!sent) {
            pending.remove(id)
            return null
        }
        return runCatching {
            withTimeout(timeoutMs) { deferred.await() }
        }.also {
            pending.remove(id)
        }.getOrNull()
    }

    suspend fun listLaunchPoints(): Set<String> {
        val env = request("ssap://com.webos.applicationManager/listLaunchPoints") ?: return emptySet()
        return WebOsSsapProtocol.extractLaunchPointIds(env)
    }

    suspend fun launchApp(appId: String, params: JsonObject? = null): Boolean {
        val payload = buildJsonObject {
            put("id", JsonPrimitive(appId))
            if (params != null) put("params", params)
        }
        val env = request("ssap://system.launcher/launch", payload, timeoutMs = 7_000L) ?: return false
        return env.type == SsapMessageType.Response && env.returnValue == true
    }

    suspend fun setVolume(percent: Int): Boolean {
        val env = request(
            uri = "ssap://audio/setVolume",
            payload = buildJsonObject { put("volume", JsonPrimitive(percent.coerceIn(0, 100))) },
        )
        return env?.returnValue == true
    }

    suspend fun setMuted(muted: Boolean): Boolean {
        val env = request(
            uri = "ssap://audio/setMute",
            payload = buildJsonObject { put("mute", JsonPrimitive(muted)) },
        )
        return env?.returnValue == true
    }

    suspend fun getVolumeAndMute(): Pair<Float?, Boolean?> {
        val env = request("ssap://audio/getVolume") ?: request("ssap://audio/getStatus") ?: return null to null
        val p = env.payload ?: return null to null
        val volInt = p["volume"]?.jsonPrimitive?.intOrNull
            ?: p["volumeStatus"]?.jsonObject?.get("volume")?.jsonPrimitive?.intOrNull
        val muted = p["muted"]?.jsonPrimitive?.booleanOrNull
            ?: p["volumeStatus"]?.jsonObject?.get("muteStatus")?.jsonPrimitive?.booleanOrNull
        return volInt?.let { (it / 100f).coerceIn(0f, 1f) } to muted
    }

    suspend fun showToast(message: String) {
        request(
            uri = "ssap://system.notifications/createToast",
            payload = buildJsonObject { put("message", JsonPrimitive(message)) },
            timeoutMs = 2_500L,
        )
    }

    suspend fun sendRemoteKey(key: WebOsRemoteKey): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext false
        val frame = WebOsSsapProtocol.buildPointerButtonFrame(key)
        pointerMutex.withLock {
            if (!isConnected) return@withContext false
            val gen = connectionGeneration.get()
            pointerSocket?.let { existing ->
                if (existing.send(frame)) return@withContext true
                runCatching { existing.close(1000, "reconnect") }
                pointerSocket = null
            }

            val env = request("ssap://com.webos.service.networkinput/getPointerInputSocket", timeoutMs = 4_000L)
            val socketUrl = env?.let(WebOsSsapProtocol::extractPointerSocketPath) ?: return@withContext false
            val sockHost = runCatching { java.net.URI(socketUrl).host }.getOrNull().orEmpty()
            if (!NetworkSafety.isPrivateLanHost(sockHost)) return@withContext false

            val opened = CompletableDeferred<Boolean>()
            val ptrWs = localTlsClient.newWebSocket(
                Request.Builder().url(socketUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(true)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        opened.complete(false)
                        if (pointerSocket === webSocket) pointerSocket = null
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (pointerSocket === webSocket) pointerSocket = null
                    }
                },
            )
            val ok = runCatching { withTimeout(3_000L) { opened.await() } }.getOrDefault(false)
            if (!ok || !isConnected || connectionGeneration.get() != gen) {
                runCatching { ptrWs.close(1000, if (ok) "stale" else "failed") }
                return@withContext false
            }
            pointerSocket = ptrWs
            ptrWs.send(frame)
        }
    }

    fun close() {
        closeTransportOnly(clearHost = true)
    }

    private fun closeTransportOnly(clearHost: Boolean) {
        connectionGeneration.incrementAndGet()
        connectedFlag.set(false)
        isConnected = false
        if (clearHost) {
            connectedHost = null
        }
        registerSignal?.cancel()
        registerSignal = null
        failAllPending(IllegalStateException("SSAP closed"))
        runCatching { pointerSocket?.close(1000, "bye") }
        pointerSocket = null
        runCatching { webSocket?.close(1000, "bye") }
        webSocket = null
    }

    private fun failAllPending(t: Throwable) {
        pending.keys.toList().forEach { key ->
            pending.remove(key)?.completeExceptionally(t)
        }
    }

    companion object {
        const val CINITO_RECEIVER_APP_ID = "org.cinito.tv"
        const val WEBOS_BROWSER_APP_ID = "com.webos.app.browser"
    }
}
