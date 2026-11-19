package org.cinito.online.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import org.cinito.online.network.NetworkSafety
import org.cinito.online.settings.SubtitleEdgeStyle
import org.cinito.online.settings.SubtitleStyleConfig
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

/**
 * Pure-Kotlin Cast V2 TLS client (port 8009) targeting Default Media Receiver (CC1AD845).
 * Requires zero Google Play Services SDK and keeps all communication inside the private Wi-Fi LAN.
 */
class GoogleCastClient : CastSessionClient {
    override val canReuseConnection: Boolean
        get() = socket?.let { !it.isClosed && it.isConnected } == true && transportId != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicInteger(1)
    private val writeLock = Any()

    private var socket: SSLSocket? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile private var connectedHost: String? = null
    @Volatile private var transportId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var lastDurationMs: Long = 0L
    @Volatile private var activeAudioTrackId: Int? = null
    @Volatile private var activeTextTrackId: Int? = null
    @Volatile private var remoteAudioTrackIds: List<Int> = emptyList()
    @Volatile private var remoteTextTrackIds: List<Int> = emptyList()
    @Volatile private var currentSubtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig()
    @Volatile private var hasLoadedExternalSubtitles: Boolean = false
    @Volatile private var pendingTracksUpdate: Boolean = false
    @Volatile private var lastSubCommandAtMs: Long = 0L
    @Volatile private var lastAudioCommandAtMs: Long = 0L
    @Volatile private var lastRxAtMs: Long = 0L
    @Volatile private var transportReady: CompletableDeferred<String>? = null
    @Volatile private var closedByUser: Boolean = false
    private var statusCallback: ((CastRemoteUpdate) -> Unit)? = null

    override suspend fun connectIdle(
        device: CastDevice,
        mediaServerBaseUrl: String?,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        statusCallback = onUpdate
        ensureConnectedTransport(device)
        true
    }

    private suspend fun ensureConnectedTransport(device: CastDevice): String {
        val currentSock = socket
        val currentTid = transportId
        if (currentSock != null && !currentSock.isClosed && currentSock.isConnected &&
            currentTid != null && connectedHost == device.host
        ) {
            return currentTid
        }
        closeTransport(clearCallback = false)
        closedByUser = false
        require(NetworkSafety.isPrivateLanHost(device.host)) {
            "Bloqueada conexión Cast fuera de la red local (${device.host})"
        }

        val trustLocalCastDevice = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(null, arrayOf(trustLocalCastDevice), SecureRandom())

        val sock = (sslCtx.socketFactory.createSocket() as SSLSocket).apply {
            CastLog.i("GCast", "connecting TLS ${device.host}:${device.port}")
            connect(InetSocketAddress(device.host, device.port), 6_000)
            soTimeout = 6_000
            startHandshake()
            soTimeout = 10_000
            CastLog.i("GCast", "TLS connected ${device.host}:${device.port}")
        }
        socket = sock
        connectedHost = device.host
        outputStream = sock.outputStream

        val readySignal = CompletableDeferred<String>()
        transportReady = readySignal
        startReaderLoop(sock)
        startHeartbeatAndPoller()

        // Connect to receiver and launch Default Media Receiver
        sendJson(SENDER_ID, RECEIVER_ID, NS_CONNECTION, buildConnectPayload())
        val launchId = nextId()
        sendJson(SENDER_ID, RECEIVER_ID, NS_RECEIVER, buildJsonObject {
            put("type", JsonPrimitive("LAUNCH"))
            put("appId", JsonPrimitive(DEFAULT_MEDIA_RECEIVER_APP_ID))
            put("requestId", JsonPrimitive(launchId))
        })

        val appTransport = withTimeout(10_000L) { readySignal.await() }
        transportId = appTransport
        return appTransport
    }

    override suspend fun connectAndLoad(
        device: CastDevice,
        mediaUrl: String,
        mimeType: String,
        title: String,
        positionMs: Long,
        subtitles: List<CastSubtitleTrack>,
        audioTracks: List<CastAudioTrack>,
        initialAudioIndex: Int?,
        initialSubtitleIndex: Int?,
        subtitleStyle: SubtitleStyleConfig,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        statusCallback = onUpdate
        remoteAudioTrackIds = emptyList()
        remoteTextTrackIds = emptyList()
        activeAudioTrackId = null
        pendingTracksUpdate = false
        currentSubtitleStyle = subtitleStyle
        hasLoadedExternalSubtitles = subtitles.isNotEmpty()
        val validInitialSubIndex = initialSubtitleIndex?.takeIf { idx -> subtitles.any { it.index == idx } }
        activeTextTrackId = validInitialSubIndex?.let { SUBTITLE_TRACK_ID_OFFSET + it }
        mediaSessionId = null
        val appTransport = ensureConnectedTransport(device)

        val loadPayload = buildLoadPayload(
            mediaUrl = mediaUrl,
            mimeType = mimeType,
            title = title,
            positionMs = positionMs,
            subtitles = subtitles,
            initialSubtitleIndex = validInitialSubIndex,
            subtitleStyle = subtitleStyle,
        )
        sendJson(SENDER_ID, appTransport, NS_MEDIA, loadPayload)
        onUpdate(
            CastRemoteUpdate(
                subtitleCapabilityOverride = if (subtitles.isNotEmpty()) {
                    CastSubtitleControlCapability.ExternalTrackSelect
                } else {
                    CastSubtitleControlCapability.Unsupported
                },
            ),
        )
    }

    internal fun buildLoadPayload(
        mediaUrl: String,
        mimeType: String,
        title: String,
        positionMs: Long,
        subtitles: List<CastSubtitleTrack>,
        initialSubtitleIndex: Int?,
        subtitleStyle: SubtitleStyleConfig,
    ): JsonObject {
        val validInitialSubIndex = initialSubtitleIndex?.takeIf { idx -> subtitles.any { it.index == idx } }
        return buildJsonObject {
            put("type", JsonPrimitive("LOAD"))
            put("requestId", JsonPrimitive(nextId()))
            put("autoplay", JsonPrimitive(true))
            if (positionMs > 0L) {
                put("currentTime", JsonPrimitive(positionMs / 1000.0))
            }
            put("media", buildJsonObject {
                put("contentId", JsonPrimitive(mediaUrl))
                put("streamType", JsonPrimitive("BUFFERED"))
                put("contentType", JsonPrimitive(mimeType))
                put("metadata", buildJsonObject {
                    put("metadataType", JsonPrimitive(0))
                    put("title", JsonPrimitive(title))
                })
                if (subtitles.isNotEmpty()) {
                    putJsonArray("tracks") {
                        subtitles.forEach { sub ->
                            add(buildJsonObject {
                                put("trackId", JsonPrimitive(SUBTITLE_TRACK_ID_OFFSET + sub.index))
                                put("type", JsonPrimitive("TEXT"))
                                put("trackContentId", JsonPrimitive(sub.vttUrl))
                                put("trackContentType", JsonPrimitive("text/vtt"))
                                put("name", JsonPrimitive(sub.name))
                                put("language", JsonPrimitive(sub.language ?: "und"))
                                put("subtype", JsonPrimitive("SUBTITLES"))
                            })
                        }
                    }
                }
                put("textTrackStyle", buildTextTrackStyle(subtitleStyle))
            })
            if (validInitialSubIndex != null) {
                putJsonArray("activeTrackIds") {
                    add(JsonPrimitive(SUBTITLE_TRACK_ID_OFFSET + validInitialSubIndex))
                }
            }
        }
    }

    override suspend fun play() = sendMediaCommand("PLAY")
    override suspend fun pause() = sendMediaCommand("PAUSE")
    override suspend fun stop() = sendMediaCommand("STOP")

    override suspend fun seekTo(positionMs: Long) {
        val tId = transportId ?: return
        val mId = mediaSessionId ?: return
        sendJson(SENDER_ID, tId, NS_MEDIA, buildJsonObject {
            put("type", JsonPrimitive("SEEK"))
            put("requestId", JsonPrimitive(nextId()))
            put("mediaSessionId", JsonPrimitive(mId))
            put("currentTime", JsonPrimitive((positionMs.coerceAtLeast(0L)) / 1000.0))
        })
    }

    override suspend fun setVolume(fraction: Float) {
        sendJson(SENDER_ID, RECEIVER_ID, NS_RECEIVER, buildJsonObject {
            put("type", JsonPrimitive("SET_VOLUME"))
            put("requestId", JsonPrimitive(nextId()))
            put("volume", buildJsonObject {
                put("level", JsonPrimitive(fraction.coerceIn(0f, 1f)))
            })
        })
    }

    override suspend fun setMuted(muted: Boolean) {
        sendJson(SENDER_ID, RECEIVER_ID, NS_RECEIVER, buildJsonObject {
            put("type", JsonPrimitive("SET_VOLUME"))
            put("requestId", JsonPrimitive(nextId()))
            put("volume", buildJsonObject {
                put("muted", JsonPrimitive(muted))
            })
        })
    }

    override suspend fun selectSubtitleIndex(index: Int?) {
        activeTextTrackId = index?.let { SUBTITLE_TRACK_ID_OFFSET + it }
        lastSubCommandAtMs = System.currentTimeMillis()
        if (mediaSessionId == null) {
            pendingTracksUpdate = true
            return
        }
        sendActiveTracksUpdate()
    }

    override suspend fun updateSubtitleStyle(style: SubtitleStyleConfig) {
        currentSubtitleStyle = style
        if (mediaSessionId == null) {
            pendingTracksUpdate = true
            return
        }
        sendActiveTracksUpdate()
    }

    override suspend fun selectAudioTrack(index: Int) {
        val trackId = remoteAudioTrackIds.getOrNull(index) ?: return
        activeAudioTrackId = trackId
        lastAudioCommandAtMs = System.currentTimeMillis()
        if (mediaSessionId == null) {
            pendingTracksUpdate = true
            return
        }
        sendActiveTracksUpdate()
    }

    private suspend fun sendActiveTracksUpdate() {
        val tId = transportId ?: return
        val mId = mediaSessionId ?: return
        pendingTracksUpdate = false
        sendJson(SENDER_ID, tId, NS_MEDIA, buildJsonObject {
            put("type", JsonPrimitive("EDIT_TRACKS_INFO"))
            put("requestId", JsonPrimitive(nextId()))
            put("mediaSessionId", JsonPrimitive(mId))
            putJsonArray("activeTrackIds") {
                activeTextTrackId?.let { add(JsonPrimitive(it)) }
                activeAudioTrackId?.let { add(JsonPrimitive(it)) }
            }
            put("textTrackStyle", buildTextTrackStyle(currentSubtitleStyle))
        })
    }

    override fun close() {
        closedByUser = true
        closeTransport(clearCallback = true)
    }

    private fun closeTransport(clearCallback: Boolean) {
        connectedHost = null
        remoteAudioTrackIds = emptyList()
        remoteTextTrackIds = emptyList()
        activeAudioTrackId = null
        activeTextTrackId = null
        hasLoadedExternalSubtitles = false
        pendingTracksUpdate = false
        lastSubCommandAtMs = 0L
        lastAudioCommandAtMs = 0L
        lastDurationMs = 0L
        if (clearCallback) {
            statusCallback = null
        }
        keepAliveJob?.cancel()
        keepAliveJob = null
        readerJob?.cancel()
        readerJob = null
        transportReady?.cancel()
        transportReady = null
        transportId = null
        mediaSessionId = null
        runCatching { outputStream?.close() }
        runCatching { socket?.close() }
        outputStream = null
        socket = null
    }

    private suspend fun sendMediaCommand(type: String) {
        val tId = transportId ?: return
        val mId = mediaSessionId ?: return
        sendJson(SENDER_ID, tId, NS_MEDIA, buildJsonObject {
            put("type", JsonPrimitive(type))
            put("requestId", JsonPrimitive(nextId()))
            put("mediaSessionId", JsonPrimitive(mId))
        })
    }

    private fun startReaderLoop(sock: SSLSocket) {
        readerJob?.cancel()
        lastRxAtMs = System.currentTimeMillis()
        readerJob = scope.launch {
            val dis = DataInputStream(sock.inputStream)
            while (isActive && !sock.isClosed) {
                val len = try {
                    dis.readInt()
                } catch (_: SocketTimeoutException) {
                    val silenceMs = System.currentTimeMillis() - lastRxAtMs
                    if (silenceMs > WATCHDOG_TIMEOUT_MS) {
                        CastLog.w("GCast", "reader watchdog timeout after ${silenceMs}ms silence")
                        break
                    }
                    continue
                } catch (err: Throwable) {
                    if (isActive && !closedByUser) {
                        CastLog.w("GCast", "reader header failed", err)
                    }
                    break
                }
                if (len <= 0 || len > 512 * 1024) break

                val frameBytes = try {
                    ByteArray(len).also { dis.readFully(it) }
                } catch (err: Throwable) {
                    if (isActive && !closedByUser) {
                        CastLog.w("GCast", "reader body failed", err)
                    }
                    break
                }

                lastRxAtMs = System.currentTimeMillis()

                val frameOk = runCatching {
                    val msg = CastProtocolUtils.decodeCastMessage(frameBytes)
                    handleIncomingMessage(msg)
                }.onFailure { CastLog.w("GCast", "reader frame failed", it) }.isSuccess
                if (!frameOk) break
            }
            val isCurrentSocket = socket === sock
            CastLog.w("GCast", "readerLoop exited closedByUser=$closedByUser current=$isCurrentSocket statusCallback=${statusCallback != null}")
            if (isCurrentSocket) {
                keepAliveJob?.cancel()
                keepAliveJob = null
                runCatching { outputStream?.close() }
                runCatching { sock.close() }
                outputStream = null
                socket = null
                transportId = null
                mediaSessionId = null
                connectedHost = null
                transportReady?.completeExceptionally(
                    IllegalStateException("Se cerró la conexión con Chromecast."),
                )
                if (!closedByUser) {
                    statusCallback?.invoke(
                        CastRemoteUpdate(
                            status = CastPlaybackStatus.Error,
                            errorMessage = "Se cerró la conexión con Chromecast.",
                        ),
                    )
                }
            } else {
                runCatching { sock.close() }
            }
        }
    }

    private fun startHeartbeatAndPoller() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                runCatching {
                    sendJson(SENDER_ID, RECEIVER_ID, NS_HEARTBEAT, buildJsonObject {
                        put("type", JsonPrimitive("PING"))
                    })
                    val tId = transportId
                    if (tId != null && mediaSessionId != null) {
                        sendJson(SENDER_ID, tId, NS_MEDIA, buildJsonObject {
                            put("type", JsonPrimitive("GET_STATUS"))
                            put("requestId", JsonPrimitive(nextId()))
                        })
                    }
                }
                delay(2_000L)
            }
        }
    }

    internal suspend fun handleIncomingMessage(msg: CastWireMessage) {
        val rawJson = msg.payloadUtf8 ?: return
        if (msg.namespace != NS_HEARTBEAT) {
            CastLog.i("GCast", "rx ns=${msg.namespace.substringAfterLast('.')} src=${msg.sourceId} payload=${rawJson.take(260)}")
        }
        val obj = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()

        when (msg.namespace) {
            NS_CONNECTION -> {
                if (type == "CLOSE" && msg.sourceId == transportId) {
                    transportId = null
                    mediaSessionId = null
                }
            }
            NS_HEARTBEAT -> {
                if (type == "PING") {
                    val replyDest = msg.sourceId.ifBlank { RECEIVER_ID }
                    runCatching {
                        sendJson(SENDER_ID, replyDest, NS_HEARTBEAT, buildJsonObject {
                            put("type", JsonPrimitive("PONG"))
                        })
                    }
                }
            }
            NS_RECEIVER -> when (type) {
                "RECEIVER_STATUS" -> {
                    val statusObj = obj["status"] as? JsonObject ?: return
                    val apps = statusObj["applications"] as? JsonArray
                    val app = apps
                        ?.mapNotNull { it as? JsonObject }
                        ?.firstOrNull { appObj ->
                            appObj["appId"]?.jsonPrimitive?.contentOrNull == DEFAULT_MEDIA_RECEIVER_APP_ID
                        }
                    val tId = app?.get("transportId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    if (tId != null) {
                        val previousTid = transportId
                        transportId = tId
                        if (tId != previousTid) {
                            runCatching {
                                sendJson(SENDER_ID, tId, NS_CONNECTION, buildConnectPayload())
                            }
                        }
                        transportReady?.complete(tId)
                    } else {
                        transportId = null
                        mediaSessionId = null
                    }
                    val volObj = statusObj["volume"] as? JsonObject
                    val level = volObj?.get("level")?.jsonPrimitive?.doubleOrNull?.toFloat()
                    val muted = volObj?.get("muted")?.jsonPrimitive?.booleanOrNull
                    if (level != null || muted != null) {
                        statusCallback?.invoke(
                            CastRemoteUpdate(
                                status = null,
                                durationMs = lastDurationMs,
                                volume = level,
                                isMuted = muted,
                            ),
                        )
                    }
                }
                "LAUNCH_ERROR" -> {
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "LAUNCH_ERROR"
                    transportReady?.completeExceptionally(
                        IllegalStateException("No se pudo iniciar Cast receiver ($reason)"),
                    )
                }
            }
            NS_MEDIA -> {
                if (type == "MEDIA_STATUS") {
                    val arr = obj["status"] as? JsonArray ?: return
                    val first = arr.firstOrNull() as? JsonObject ?: return
                    val incomingMediaSessionId = first["mediaSessionId"]?.jsonPrimitive?.intOrNull
                    if (incomingMediaSessionId != null) {
                        mediaSessionId = incomingMediaSessionId
                    }
                    val hadPendingUpdate = pendingTracksUpdate
                    if (mediaSessionId != null && hadPendingUpdate) {
                        runCatching { sendActiveTracksUpdate() }
                    }

                    val playerState = first["playerState"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val idleReason = first["idleReason"]?.jsonPrimitive?.contentOrNull
                    val currentTimeSec = first["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val mediaObj = first["media"] as? JsonObject
                    val durSec = mediaObj?.get("duration")?.jsonPrimitive?.doubleOrNull
                    if (durSec != null && durSec > 0) {
                        lastDurationMs = (durSec * 1000.0).toLong()
                    }

                    val tracksList = (mediaObj?.get("tracks") as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        .orEmpty()
                    val textTracksFromCast = tracksList.filter {
                        it["type"]?.jsonPrimitive?.contentOrNull.equals("TEXT", ignoreCase = true)
                    }
                    val audioTracksFromCast = tracksList.filter {
                        it["type"]?.jsonPrimitive?.contentOrNull.equals("AUDIO", ignoreCase = true)
                    }
                    if (tracksList.isNotEmpty()) {
                        remoteTextTrackIds = textTracksFromCast.mapNotNull { it["trackId"]?.jsonPrimitive?.intOrNull }
                        remoteAudioTrackIds = audioTracksFromCast.mapNotNull { it["trackId"]?.jsonPrimitive?.intOrNull }
                    }
                    val audioTracksUpdate = if (audioTracksFromCast.isNotEmpty()) {
                        audioTracksFromCast.mapIndexed { idx, trackObj ->
                            CastAudioTrack(
                                index = idx,
                                name = trackObj["name"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Pista ${idx + 1}",
                                language = trackObj["language"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    } else {
                        null
                    }

                    val activeIds = (first["activeTrackIds"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    var hasSubtitleOverride = false
                    var subtitleOverrideIndex: Int? = null
                    var hasAudioOverride = false
                    var audioOverrideIndex: Int? = null
                    if (activeIds != null && !hadPendingUpdate) {
                        val now = System.currentTimeMillis()
                        val activeSubTrackId = activeIds.firstOrNull {
                            it >= SUBTITLE_TRACK_ID_OFFSET || it in remoteTextTrackIds
                        }
                        if (now - lastSubCommandAtMs > TRACK_COMMAND_GRACE_MS || activeSubTrackId == activeTextTrackId) {
                            hasSubtitleOverride = true
                            subtitleOverrideIndex = activeSubTrackId
                                ?.takeIf { it >= SUBTITLE_TRACK_ID_OFFSET }
                                ?.let { it - SUBTITLE_TRACK_ID_OFFSET }
                        }
                        if (remoteAudioTrackIds.isNotEmpty()) {
                            val activeAudTrackId = activeIds.firstOrNull { it in remoteAudioTrackIds }
                            val mappedAudIdx = activeAudTrackId?.let { remoteAudioTrackIds.indexOf(it) }?.takeIf { it >= 0 }
                            if (mappedAudIdx != null &&
                                (now - lastAudioCommandAtMs > TRACK_COMMAND_GRACE_MS || activeAudTrackId == activeAudioTrackId)
                            ) {
                                hasAudioOverride = true
                                audioOverrideIndex = mappedAudIdx
                            }
                        }
                    }

                    val mappedStatus = when (playerState) {
                        "PLAYING" -> CastPlaybackStatus.Playing
                        "PAUSED" -> CastPlaybackStatus.Paused
                        "BUFFERING" -> CastPlaybackStatus.Buffering
                        else -> CastPlaybackStatus.Idle
                    }
                    val finished = isPlaybackFinished(playerState, idleReason)
                    val subCapability = if (hasLoadedExternalSubtitles || textTracksFromCast.isNotEmpty()) {
                        CastSubtitleControlCapability.ExternalTrackSelect
                    } else {
                        CastSubtitleControlCapability.Unsupported
                    }
                    statusCallback?.invoke(
                        CastRemoteUpdate(
                            status = mappedStatus,
                            positionMs = (currentTimeSec * 1000.0).toLong().coerceAtLeast(0L),
                            durationMs = lastDurationMs,
                            ended = finished,
                            subtitleOverrideIndex = subtitleOverrideIndex,
                            hasSubtitleOverride = hasSubtitleOverride,
                            audioOverrideIndex = audioOverrideIndex,
                            hasAudioOverride = hasAudioOverride,
                            audioTracksUpdate = audioTracksUpdate,
                            subtitleCapabilityOverride = subCapability,
                        ),
                    )
                } else if (type == "LOAD_FAILED" || type == "INVALID_REQUEST") {
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "El TV no pudo abrir el formato del archivo"
                    statusCallback?.invoke(
                        CastRemoteUpdate(
                            status = CastPlaybackStatus.Error,
                            positionMs = 0L,
                            durationMs = lastDurationMs,
                            errorMessage = reason,
                        ),
                    )
                }
            }
        }
    }

    private fun buildConnectPayload(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("CONNECT"))
        put("origin", buildJsonObject {})
    }

    private suspend fun sendJson(
        source: String,
        destination: String,
        namespace: String,
        payload: JsonObject,
    ) = withContext(Dispatchers.IO) {
        val bytes = CastProtocolUtils.encodeCastMessage(
            CastWireMessage(
                sourceId = source,
                destinationId = destination,
                namespace = namespace,
                payloadUtf8 = payload.toString(),
            ),
        )
        synchronized(writeLock) {
            outputStream?.apply {
                write(bytes)
                flush()
            }
        }
    }

    private fun nextId(): Int = requestId.getAndIncrement()

    internal fun installTestTransportForUnitTest(
        out: OutputStream,
        readySignal: CompletableDeferred<String>? = null,
        onUpdate: ((CastRemoteUpdate) -> Unit)? = null,
    ) {
        outputStream = out
        transportReady = readySignal
        if (onUpdate != null) statusCallback = onUpdate
    }

    internal fun setStatusCallbackForUnitTest(onUpdate: (CastRemoteUpdate) -> Unit) {
        statusCallback = onUpdate
    }

    internal fun closeTransportForUnitTest(clearCallback: Boolean) = closeTransport(clearCallback)

    internal fun hasStatusCallbackForUnitTest(): Boolean = statusCallback != null

    internal fun currentTransportIdForUnitTest(): String? = transportId

    internal fun currentMediaSessionIdForUnitTest(): Int? = mediaSessionId

    companion object {
        private const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"
        private const val SENDER_ID = "sender-0"
        private const val RECEIVER_ID = "receiver-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        private const val SUBTITLE_TRACK_ID_OFFSET = 1000
        private const val WATCHDOG_TIMEOUT_MS = 12_000L
        private const val TRACK_COMMAND_GRACE_MS = 1_500L

        fun isPlaybackFinished(playerState: String, idleReason: String?): Boolean =
            playerState.equals("IDLE", ignoreCase = true) &&
                idleReason.equals("FINISHED", ignoreCase = true)

        internal fun buildTextTrackStyle(style: SubtitleStyleConfig): JsonObject {
            val fontScale = (style.fontSizeSp / 19f).coerceIn(0.6f, 2.0f)
            val bgAlphaByte = (style.backgroundOpacity.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val bgColor = String.format(Locale.ROOT, "#000000%02X", bgAlphaByte)
            val edgeType = when (style.edgeStyle) {
                SubtitleEdgeStyle.None -> "NONE"
                SubtitleEdgeStyle.DropShadow -> "DROP_SHADOW"
                SubtitleEdgeStyle.Outline -> "OUTLINE"
            }
            return buildJsonObject {
                put("fontScale", JsonPrimitive(fontScale))
                put("foregroundColor", JsonPrimitive("#FFFFFFFF"))
                put("backgroundColor", JsonPrimitive(bgColor))
                put("edgeType", JsonPrimitive(edgeType))
                if (style.edgeStyle != SubtitleEdgeStyle.None) {
                    put("edgeColor", JsonPrimitive("#000000FF"))
                }
            }
        }
    }
}
