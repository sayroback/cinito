package org.cinito.online.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cinito.online.player.TrackLanguageFormatter
import org.cinito.online.settings.SubtitleStyleConfig
import webtor.app.PlaybackItem
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class CastIdleSession(
    val baseUrl: String,
    val watchUrl: String,
    val sessionUrl: String,
)

data class CastPublishedMedia(
    val mediaId: Long = 0L,
    val baseUrl: String,
    val mediaUrl: String,
    val watchUrl: String,
    val sessionUrl: String,
    val subtitles: List<CastSubtitleTrack>,
)

data class TvBridgeReport(
    val mediaId: Long,
    val status: CastPlaybackStatus,
    val positionMs: Long,
    val durationMs: Long,
    val errorMessage: String? = null,
    val ended: Boolean = false,
    val subtitleOverrideIndex: Int? = null,
    val hasSubtitleOverride: Boolean = false,
    val audioOverrideIndex: Int? = null,
    val hasAudioOverride: Boolean = false,
    val reportedAudioTracks: List<CastAudioTrack>? = null,
    val audioCapabilityOverride: CastAudioControlCapability? = null,
)

/**
 * Ephemeral LAN HTTP/1.1 streaming & receiver bridge bound strictly while casting to a TV.
 * Protects local streams behind a per-session 128-bit random URL path token and serves:
 * - Range-capable video streaming with real container extension & LG/Samsung DLNA headers
 * - CORS-enabled WebVTT / SRT subtitle tracks
 * - Shared CiniTo TV HTML5 receiver + `/tv/session.json` and `/tv/report` bridge
 */
class CastMediaServer(
    private val context: Context?,
    private val assetReader: (String) -> ByteArray? = { path ->
        runCatching { context?.assets?.open(path)?.use { it.readBytes() } }.getOrNull()
    },
    private val hostIpProvider: () -> String? = { findLocalLanIpv4Address() },
) {
    private val localClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    private data class PendingCommand(
        val seq: Long,
        val type: String,
        val positionMs: Long,
        val audioIndex: Int? = null,
    )

    private data class PublishedSnapshot(
        val mediaId: Long,
        val deviceName: String,
        val baseUrl: String,
        val mediaUrl: String,
        val sourceUri: String,
        val mimeType: String,
        val title: String,
        val startPositionMs: Long,
        val subtitles: List<PlaybackItem>,
        val publishedSubtitles: List<CastSubtitleTrack>,
        val activeSubtitleIndex: Int?,
        val audioTracks: List<CastAudioTrack>,
        val activeAudioIndex: Int?,
        val subtitleStyle: SubtitleStyleConfig,
        val commands: List<PendingCommand> = emptyList(),
    )

    private data class ScopedListener(
        val mediaId: Long,
        val listener: (TvBridgeReport) -> Unit,
    )

    private val stateLock = Any()
    private val activeSockets = Collections.synchronizedSet(HashSet<Socket>())
    private val mediaSeq = AtomicLong(0L)
    private val commandSeq = AtomicLong(0L)

    @Volatile private var sessionTokenHex: String = ""
    @Volatile private var boundIp: String? = null
    @Volatile private var idleDeviceName: String = ""
    @Volatile private var currentSnapshot: PublishedSnapshot? = null
    @Volatile private var boundListener: ScopedListener? = null
    @Volatile private var lastLoggedReportStatus: CastPlaybackStatus? = null

    fun currentMediaId(): Long = currentSnapshot?.mediaId ?: mediaSeq.get()

    fun currentSessionToken(): String = sessionTokenHex

    fun prepareIdle(deviceName: String): CastIdleSession? {
        val lanIp = (if (context != null) CastDebugOverrides.hostOverride() else null) ?: hostIpProvider() ?: return null
        ensureStarted(lanIp)
        val port = serverSocket?.localPort ?: return null
        val baseUrl = "http://$lanIp:$port/$sessionTokenHex"
        CastLog.i("Server", "prepareIdle device=$deviceName baseUrl=$baseUrl")
        synchronized(stateLock) {
            idleDeviceName = deviceName
            currentSnapshot = null
        }
        return CastIdleSession(
            baseUrl = baseUrl,
            watchUrl = "$baseUrl/watch",
            sessionUrl = "$baseUrl/tv/session.json",
        )
    }

    fun clearMediaKeepSession(deviceName: String) {
        synchronized(stateLock) {
            idleDeviceName = deviceName
            currentSnapshot = null
            lastLoggedReportStatus = null
        }
    }

    fun publish(
        sourceUri: String,
        mimeType: String,
        title: String = "",
        positionMs: Long = 0L,
        subtitles: List<PlaybackItem>,
        activeSubtitleIndex: Int? = null,
        subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
        deviceName: String = idleDeviceName,
        audioTracks: List<CastAudioTrack> = emptyList(),
        activeAudioIndex: Int? = null,
        subtitleKinds: Map<Int, CastSubtitleKind> = emptyMap(),
    ): CastPublishedMedia? {
        val lanIp = (if (context != null) CastDebugOverrides.hostOverride() else null) ?: hostIpProvider() ?: return null
        ensureStarted(lanIp)
        val port = serverSocket?.localPort ?: return null
        CastLog.i("Server", "publish media=$title mime=$mimeType lanIp=$lanIp port=$port subs=${subtitles.size} audio=${audioTracks.size}")

        val resolvedMime = mimeType.ifBlank { "video/mp4" }
        val ext = CastProtocolUtils.mediaExtensionFor(title.ifBlank { sourceUri }, resolvedMime)
        val baseUrl = "http://$lanIp:$port/$sessionTokenHex"
        val subTracks = subtitles.mapIndexed { idx, item ->
            val lang = TrackLanguageFormatter.inferLanguageFromFilename(item.name)
            CastSubtitleTrack(
                index = idx,
                name = item.name.substringBeforeLast('.'),
                language = lang,
                vttUrl = "$baseUrl/sub/$idx.vtt",
                srtUrl = "$baseUrl/sub/$idx.srt",
                kind = subtitleKinds[idx] ?: CastSubtitleKind.ExternalSidecar,
            )
        }

        val snap = synchronized(stateLock) {
            if (deviceName.isNotBlank()) idleDeviceName = deviceName
            lastLoggedReportStatus = null
            PublishedSnapshot(
                mediaId = mediaSeq.incrementAndGet(),
                deviceName = deviceName.ifBlank { idleDeviceName },
                baseUrl = baseUrl,
                mediaUrl = "$baseUrl/media$ext",
                sourceUri = sourceUri,
                mimeType = resolvedMime,
                title = title,
                startPositionMs = positionMs.coerceAtLeast(0L),
                subtitles = subtitles.toList(),
                publishedSubtitles = subTracks,
                activeSubtitleIndex = activeSubtitleIndex,
                audioTracks = audioTracks,
                activeAudioIndex = activeAudioIndex,
                subtitleStyle = subtitleStyle,
                commands = emptyList(),
            ).also { currentSnapshot = it }
        }

        return CastPublishedMedia(
            mediaId = snap.mediaId,
            baseUrl = snap.baseUrl,
            mediaUrl = snap.mediaUrl,
            watchUrl = "$baseUrl/watch",
            sessionUrl = "$baseUrl/tv/session.json",
            subtitles = snap.publishedSubtitles,
        )
    }

    /** Attaches a local subtitle file to the active Cast session without restarting the HTTP server. */
    fun addLocalSubtitle(item: PlaybackItem): CastSubtitleTrack? = synchronized(stateLock) {
        val snap = currentSnapshot ?: return@synchronized null
        val idx = snap.subtitles.size
        val lang = TrackLanguageFormatter.inferLanguageFromFilename(item.name)
        val track = CastSubtitleTrack(
            index = idx,
            name = item.name.substringBeforeLast('.'),
            language = lang,
            vttUrl = "${snap.baseUrl}/sub/$idx.vtt",
            srtUrl = "${snap.baseUrl}/sub/$idx.srt",
            kind = CastSubtitleKind.LocalFile,
        )
        currentSnapshot = snap.copy(
            subtitles = snap.subtitles + item,
            publishedSubtitles = snap.publishedSubtitles + track,
        )
        track
    }

    fun updateSubtitleSelection(index: Int?) {
        synchronized(stateLock) {
            currentSnapshot = currentSnapshot?.copy(activeSubtitleIndex = index)
        }
    }

    fun updateAudioSelection(index: Int?) {
        synchronized(stateLock) {
            currentSnapshot = currentSnapshot?.copy(activeAudioIndex = index)
        }
    }

    fun updateSubtitleStyle(style: SubtitleStyleConfig) {
        synchronized(stateLock) {
            currentSnapshot = currentSnapshot?.copy(subtitleStyle = style)
        }
    }

    fun enqueueTvCommand(type: String, positionMs: Long = 0L, audioIndex: Int? = null) {
        synchronized(stateLock) {
            val snap = currentSnapshot ?: return
            val cmd = PendingCommand(
                seq = commandSeq.incrementAndGet(),
                type = type,
                positionMs = positionMs.coerceAtLeast(0L),
                audioIndex = audioIndex,
            )
            currentSnapshot = snap.copy(commands = (snap.commands + cmd).takeLast(MAX_COMMAND_QUEUE_SIZE))
        }
    }

    private fun acknowledgeCommands(ackCmdSeq: Long) {
        if (ackCmdSeq <= 0L) return
        synchronized(stateLock) {
            val snap = currentSnapshot ?: return
            val remaining = snap.commands.filter { it.seq > ackCmdSeq }
            if (remaining.size != snap.commands.size) {
                currentSnapshot = snap.copy(commands = remaining)
            }
        }
    }

    fun setTvReportListener(expectedMediaId: Long = currentMediaId(), listener: ((TvBridgeReport) -> Unit)?) {
        boundListener = if (listener == null) null else ScopedListener(expectedMediaId, listener)
    }

    fun clearTvReportListener(expectedMediaId: Long) {
        val current = boundListener
        if (current != null && current.mediaId == expectedMediaId) {
            boundListener = null
        }
    }

    @Synchronized
    private fun ensureStarted(bindIp: String? = hostIpProvider()) {
        val overridePort = if (context != null) CastDebugOverrides.portOverride() else null
        val bindHost = if (overridePort != null) "0.0.0.0" else (bindIp?.takeIf { it.isNotBlank() } ?: "127.0.0.1")
        if (running.get() && serverSocket != null && !serverSocket!!.isClosed) {
            if (overridePort != null || boundIp == bindHost) return
            CastLog.i("Server", "LAN IP changed ($boundIp -> $bindHost); restarting ServerSocket")
            stopSocketOnly()
        }

        if (sessionTokenHex.isEmpty()) {
            val tokenBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            sessionTokenHex = tokenBytes.joinToString("") { "%02x".format(it) }
        }

        val bindAddr = runCatching {
            InetAddress.getByName(bindHost)
        }.getOrElse { InetAddress.getByName("127.0.0.1") }
        val socket = ServerSocket(overridePort ?: 0, 16, bindAddr)
        serverSocket = socket
        boundIp = bindHost
        CastLog.i("Server", "ensureStarted bound=${bindAddr.hostAddress}:${socket.localPort}")
        workers = java.util.concurrent.ThreadPoolExecutor(
            2,
            32,
            30L,
            TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(64),
        )
        running.set(true)

        acceptThread = Thread({
            while (running.get() && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (ioe: IOException) {
                    if (!running.get() || socket.isClosed) break
                    CastLog.w("Server", "accept() transient error", ioe)
                    continue
                } catch (t: Throwable) {
                    if (!running.get() || socket.isClosed) break
                    CastLog.w("Server", "accept() unexpected error", t)
                    continue
                }
                val pool = workers
                if (pool == null || !running.get()) {
                    runCatching { client.close() }
                    break
                }
                try {
                    pool.execute {
                        activeSockets.add(client)
                        try {
                            handleClient(client)
                        } finally {
                            activeSockets.remove(client)
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        }, "CiniTo-CastHttp").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopSocketOnly() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundIp = null
        val toClose = synchronized(activeSockets) {
            activeSockets.toList().also { activeSockets.clear() }
        }
        toClose.forEach { sock -> runCatching { sock.close() } }
        workers?.shutdownNow()
        workers = null
        acceptThread = null
    }

    @Synchronized
    fun stop(expectedTokenHex: String? = null) {
        if (expectedTokenHex != null && sessionTokenHex != expectedTokenHex) {
            return
        }
        boundListener = null
        synchronized(stateLock) {
            currentSnapshot = null
            lastLoggedReportStatus = null
        }
        sessionTokenHex = ""
        runCatching { localClient.dispatcher.cancelAll() }
        stopSocketOnly()
    }

    private fun handleClient(socket: Socket) {
        val remote = socket.remoteSocketAddress?.toString() ?: "?"
        val res = runCatching {
            socket.use { sock ->
                sock.soTimeout = 15_000
                val input = BufferedInputStream(sock.getInputStream())
                val out = BufferedOutputStream(sock.getOutputStream())

                val reqLines = readHttpHeaders(input) ?: return
                if (reqLines.isEmpty()) return
                val parts = reqLines.first().split(' ')
                if (parts.size < 2) {
                    writeSimpleResponse(out, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase(Locale.ROOT)
                val rawPath = parts[1].substringBefore('?')
                val rawQuery = parts[1].substringAfter('?', "")
                if (!rawPath.endsWith("/tv/session.json") && !rawPath.endsWith("/tv/report")) {
                    CastLog.i("HTTP", "$remote $method $rawPath")
                }

                if (method == "OPTIONS") {
                    writeCorsPreflight(out)
                    return
                }

                val headers = mutableMapOf<String, String>()
                for (i in 1 until reqLines.size) {
                    val idx = reqLines[i].indexOf(':')
                    if (idx > 0) {
                        headers[reqLines[i].substring(0, idx).trim().lowercase(Locale.ROOT)] =
                            reqLines[i].substring(idx + 1).trim()
                    }
                }

                val segments = rawPath.trim('/').split('/')
                if (segments.size < 2) {
                    writeSimpleResponse(out, 404, "Not Found")
                    return
                }
                val providedToken = segments[0].toByteArray(Charsets.UTF_8)
                val expectedToken = sessionTokenHex.toByteArray(Charsets.UTF_8)
                if (expectedToken.isEmpty() || !MessageDigest.isEqual(providedToken, expectedToken)) {
                    writeSimpleResponse(out, 403, "Forbidden")
                    return
                }

                if (method == "POST") {
                    if (segments.size == 3 && segments[1] == "tv" && segments[2] == "report") {
                        handleTvReport(input, headers, out)
                    } else {
                        writeSimpleResponse(out, 405, "Method Not Allowed")
                    }
                    return
                }

                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(out, 405, "Method Not Allowed")
                    return
                }

                val isHead = method == "HEAD"
                val snap = currentSnapshot
                when {
                    segments.size == 2 && segments[1].startsWith("media") -> {
                        serveMedia(out, snap, isHead, headers["range"])
                    }
                    segments.size == 3 && segments[1] == "sub" -> {
                        val filePart = segments[2]
                        val wantsVtt = filePart.endsWith(".vtt", ignoreCase = true)
                        val idx = filePart.substringBefore('.').toIntOrNull()
                        val subItem = idx?.let { snap?.subtitles?.getOrNull(it) }
                        if (subItem == null) {
                            writeSimpleResponse(out, 404, "Subtitle Not Found")
                        } else {
                            serveSubtitle(out, isHead, subItem, wantsVtt)
                        }
                    }
                    segments.size == 2 && segments[1] == "watch" -> {
                        serveAsset(out, isHead, "webos_receiver/index.html", "text/html; charset=utf-8")
                    }
                    segments.size == 2 && segments[1] == "styles.css" -> {
                        serveAsset(out, isHead, "webos_receiver/styles.css", "text/css; charset=utf-8")
                    }
                    segments.size == 2 && segments[1] == "player.js" -> {
                        serveAsset(out, isHead, "webos_receiver/player.js", "application/javascript; charset=utf-8")
                    }
                    segments.size == 3 && segments[1] == "tv" && segments[2] == "session.json" -> {
                        val afterSeq = parseQueryLong(rawQuery, "afterSeq")
                        serveTvSessionJson(out, isHead, afterSeq)
                    }
                    else -> writeSimpleResponse(out, 404, "Not Found")
                }
            }
        }
        res.onFailure { CastLog.w("HTTP", "handleClient failed from $remote", it) }
    }

    private fun parseQueryLong(rawQuery: String, key: String): Long? {
        if (rawQuery.isBlank()) return null
        return rawQuery.split('&')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
    }

    private fun handleTvReport(
        input: InputStream,
        headers: Map<String, String>,
        out: OutputStream,
    ) {
        val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 16 * 1024) ?: 0
        if (len == 0) {
            writeJsonResponse(out, 200, """{"ok":true}""", isHead = false)
            return
        }
        val bodyBytes = ByteArray(len)
        var readTotal = 0
        while (readTotal < len) {
            val n = input.read(bodyBytes, readTotal, len - readTotal)
            if (n <= 0) break
            readTotal += n
        }
        val rawJson = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
        runCatching {
            val snap = currentSnapshot ?: return@runCatching
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val mediaId = obj["mediaId"]?.jsonPrimitive?.longOrNull ?: snap.mediaId
            if (mediaId != snap.mediaId) return@runCatching
            val ackCmdSeq = obj["ackCmdSeq"]?.jsonPrimitive?.longOrNull
            if (ackCmdSeq != null) {
                acknowledgeCommands(ackCmdSeq)
            }
            val statusStr = obj["status"]?.jsonPrimitive?.content.orEmpty()
            val posMs = obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val durMs = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val errMsg = obj["errorMessage"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val subChanged = obj["subtitleSelectionChanged"]?.jsonPrimitive?.booleanOrNull == true
            val tvSubIdx = if (subChanged) {
                val rawSub = obj["activeSubtitleIndex"]
                if (rawSub == null || rawSub is JsonNull) null else rawSub.jsonPrimitive.intOrNull
            } else null
            if (subChanged) {
                updateSubtitleSelection(tvSubIdx)
            }
            val audioChanged = obj["audioSelectionChanged"]?.jsonPrimitive?.booleanOrNull == true
            val tvAudioIdx = if (audioChanged) {
                val rawAud = obj["activeAudioIndex"]
                if (rawAud == null || rawAud is JsonNull) null else rawAud.jsonPrimitive.intOrNull
            } else null
            if (audioChanged) {
                updateAudioSelection(tvAudioIdx)
            }
            val audioSwitchingSupported = obj["audioSwitchingSupported"]?.jsonPrimitive?.booleanOrNull
            val parsedAudioTracks = (obj["audioTracks"] as? JsonArray)?.mapIndexedNotNull { idx, elem ->
                val trObj = elem as? kotlinx.serialization.json.JsonObject ?: return@mapIndexedNotNull null
                val trIndex = trObj["index"]?.jsonPrimitive?.intOrNull ?: idx
                val trLang = trObj["language"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val trLabel = trObj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: trLang?.let(TrackLanguageFormatter::formatLanguage)
                    ?: "Pista ${trIndex + 1}"
                val region = org.cinito.online.metadata.MediaLanguageClassifier.classifyTrackMetadata(
                    languageCode = trLang,
                    bcp47 = trLang,
                    trackName = trLabel,
                )
                CastAudioTrack(
                    index = trIndex,
                    name = trLabel,
                    language = trLang,
                    regionTag = region,
                )
            }?.takeIf { it.isNotEmpty() }
            val audioCapabilityOverride = when (audioSwitchingSupported) {
                false -> if (snap.audioTracks.size == 1 || parsedAudioTracks?.size == 1) {
                    CastAudioControlCapability.SingleTrack
                } else {
                    CastAudioControlCapability.AssistedRemoteMenu
                }
                true -> if ((parsedAudioTracks?.size ?: snap.audioTracks.size) > 1) {
                    CastAudioControlCapability.NativeSwitch
                } else {
                    null
                }
                null -> null
            }
            val reportedAudioTracks = if (audioSwitchingSupported == false && parsedAudioTracks == null) {
                null
            } else {
                parsedAudioTracks
            }
            val mappedStatus = when (statusStr) {
                "PLAYING" -> CastPlaybackStatus.Playing
                "PAUSED" -> CastPlaybackStatus.Paused
                "ENDED" -> CastPlaybackStatus.Idle
                "ERROR" -> CastPlaybackStatus.Error
                else -> CastPlaybackStatus.Buffering
            }
            val ended = statusStr == "ENDED"
            if (errMsg != null || ended || subChanged || audioChanged || mappedStatus != lastLoggedReportStatus) {
                lastLoggedReportStatus = mappedStatus
                val summary = "mediaId=$mediaId status=$statusStr pos=$posMs dur=$durMs subChg=$subChanged audChg=$audioChanged err=$errMsg"
                if (errMsg != null || mappedStatus == CastPlaybackStatus.Error) {
                    CastLog.w("TVReport", summary)
                } else {
                    CastLog.i("TVReport", summary)
                }
            }
            val listener = boundListener
            if (listener != null && (listener.mediaId == 0L || listener.mediaId == mediaId)) {
                listener.listener(
                    TvBridgeReport(
                        mediaId = mediaId,
                        status = mappedStatus,
                        positionMs = posMs.coerceAtLeast(0L),
                        durationMs = durMs.coerceAtLeast(0L),
                        errorMessage = errMsg,
                        ended = ended,
                        subtitleOverrideIndex = tvSubIdx,
                        hasSubtitleOverride = subChanged,
                        audioOverrideIndex = tvAudioIdx,
                        hasAudioOverride = audioChanged,
                        reportedAudioTracks = reportedAudioTracks,
                        audioCapabilityOverride = audioCapabilityOverride,
                    ),
                )
            }
        }
        writeJsonResponse(out, 200, """{"ok":true}""", isHead = false)
    }

    private fun serveTvSessionJson(out: OutputStream, isHead: Boolean, afterSeq: Long? = null) {
        val snap = synchronized(stateLock) {
            val cur = currentSnapshot ?: return@synchronized null
            if (afterSeq != null && afterSeq > 0L) {
                val remaining = cur.commands.filter { it.seq > afterSeq }
                if (remaining.size != cur.commands.size) {
                    cur.copy(commands = remaining).also { currentSnapshot = it }
                } else {
                    cur
                }
            } else {
                cur
            }
        }
        if (snap == null) {
            val idleJson = buildJsonObject {
                put("idle", JsonPrimitive(true))
                put("deviceName", JsonPrimitive(idleDeviceName))
                put("mediaId", JsonPrimitive(0))
                put("mediaUrl", JsonPrimitive(""))
            }.toString()
            writeJsonResponse(out, 200, idleJson, isHead)
            return
        }
        val subList: JsonArray = buildJsonArray {
            snap.publishedSubtitles.forEach { sub ->
                add(buildJsonObject {
                    put("index", JsonPrimitive(sub.index))
                    put("name", JsonPrimitive(sub.name))
                    put("language", JsonPrimitive(sub.language ?: "und"))
                    put("vttUrl", JsonPrimitive(sub.vttUrl))
                })
            }
        }
        val audioList: JsonArray = buildJsonArray {
            snap.audioTracks.forEach { aud ->
                add(buildJsonObject {
                    put("index", JsonPrimitive(aud.index))
                    put("name", JsonPrimitive(aud.name))
                    put("language", JsonPrimitive(aud.language ?: "und"))
                    aud.codec?.let { put("codec", JsonPrimitive(it)) }
                })
            }
        }
        val activeIdx = snap.activeSubtitleIndex
        val activeAudIdx = snap.activeAudioIndex
        val commandsArr = buildJsonArray {
            snap.commands.forEach { cmd ->
                add(buildJsonObject {
                    put("seq", JsonPrimitive(cmd.seq))
                    put("type", JsonPrimitive(cmd.type))
                    put("positionMs", JsonPrimitive(cmd.positionMs))
                    cmd.audioIndex?.let { put("audioIndex", JsonPrimitive(it)) }
                })
            }
        }
        val cmdObj = snap.commands.lastOrNull()?.let { cmd ->
            buildJsonObject {
                put("seq", JsonPrimitive(cmd.seq))
                put("type", JsonPrimitive(cmd.type))
                put("positionMs", JsonPrimitive(cmd.positionMs))
                cmd.audioIndex?.let { put("audioIndex", JsonPrimitive(it)) }
            }
        }

        val payload = buildJsonObject {
            put("idle", JsonPrimitive(false))
            put("deviceName", JsonPrimitive(snap.deviceName))
            put("mediaId", JsonPrimitive(snap.mediaId))
            put("mediaUrl", JsonPrimitive(snap.mediaUrl))
            put("mimeType", JsonPrimitive(snap.mimeType))
            put("title", JsonPrimitive(snap.title))
            put("startPositionMs", JsonPrimitive(snap.startPositionMs))
            put("subtitles", subList)
            put("activeSubtitleIndex", activeIdx?.let { JsonPrimitive(it) } ?: JsonNull)
            put("audioTracks", audioList)
            put("activeAudioIndex", activeAudIdx?.let { JsonPrimitive(it) } ?: JsonNull)
            put("subtitleStyle", buildJsonObject {
                put("fontSizeSp", JsonPrimitive(snap.subtitleStyle.fontSizeSp))
                put("backgroundOpacity", JsonPrimitive(snap.subtitleStyle.backgroundOpacity))
                put("edgeStyle", JsonPrimitive(snap.subtitleStyle.edgeStyle.name))
            })
            put("commands", commandsArr)
            if (cmdObj != null) put("command", cmdObj)
        }.toString()

        writeJsonResponse(out, 200, payload, isHead)
    }

    private fun serveAsset(out: OutputStream, isHead: Boolean, assetPath: String, contentType: String) {
        val bytes = assetReader(assetPath)
        if (bytes == null) {
            writeSimpleResponse(out, 404, "Asset Not Found")
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!isHead) out.write(bytes)
        out.flush()
    }

    private fun serveMedia(out: OutputStream, snap: PublishedSnapshot?, isHead: Boolean, rangeHeader: String?) {
        val source = snap?.sourceUri.orEmpty()
        if (source.isBlank() || snap == null) {
            writeSimpleResponse(out, 404, "No Active Media")
            return
        }
        val scheme = runCatching { java.net.URI(source).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        when (scheme) {
            "http" -> relayLoopbackHttp(out, snap, source, snap.mimeType, isHead, rangeHeader)
            "content", "file" -> serveContentUri(out, snap, Uri.parse(source), snap.mimeType, isHead, rangeHeader)
            else -> writeSimpleResponse(out, 400, "Unsupported Source")
        }
    }

    private fun appendSubtitleHeaders(sb: StringBuilder, snap: PublishedSnapshot) {
        val activeIdx = snap.activeSubtitleIndex ?: return
        val srtUrl = snap.publishedSubtitles.getOrNull(activeIdx)?.srtUrl ?: return
        sb.append("CaptionInfo.sec: $srtUrl\r\n")
        sb.append("PV-SubtitleLocation: $srtUrl\r\n")
    }

    private fun relayLoopbackHttp(
        out: OutputStream,
        snap: PublishedSnapshot,
        sourceUrl: String,
        mimeType: String,
        isHead: Boolean,
        rangeHeader: String?,
    ) {
        val parsed = runCatching { java.net.URI(sourceUrl) }.getOrNull()
        if (parsed?.host != "127.0.0.1" && parsed?.host != "localhost") {
            writeSimpleResponse(out, 403, "Invalid Loopback Source")
            return
        }

        val reqBuilder = Request.Builder()
            .url(sourceUrl)
            .header("Connection", "close")
        if (isHead) reqBuilder.head() else reqBuilder.get()
        if (!rangeHeader.isNullOrBlank()) reqBuilder.header("Range", rangeHeader)

        val call = localClient.newCall(reqBuilder.build())
        val resp = try {
            call.execute()
        } catch (_: IOException) {
            writeSimpleResponse(out, 502, "Bad Gateway")
            return
        }
        resp.use { upResp ->
            val statusCode = upResp.code
            val statusText = upResp.message.ifBlank { if (statusCode == 206) "Partial Content" else "OK" }
            val contentLength = upResp.header("Content-Length") ?: upResp.body?.contentLength()?.takeIf { it >= 0L }?.toString()
            val contentRange = upResp.header("Content-Range")
            val upstreamMime = upResp.header("Content-Type")?.takeIf {
                it.isNotBlank() && it != "application/octet-stream"
            }
            val resolvedMime = upstreamMime ?: mimeType

            val headerBlock = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: $resolvedMime\r\n")
                append("Accept-Ranges: bytes\r\n")
                contentLength?.let { append("Content-Length: $it\r\n") }
                contentRange?.let { append("Content-Range: $it\r\n") }
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                appendSubtitleHeaders(this, snap)
                append("Connection: close\r\n\r\n")
            }
            out.write(headerBlock.toByteArray(Charsets.ISO_8859_1))

            if (!isHead) {
                val stream = upResp.body?.byteStream()
                if (stream != null) {
                    val buf = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            val read = stream.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    } catch (_: IOException) {
                        // TV aborted stream connection on seek or stop
                    }
                }
            }
            runCatching { out.flush() }
        }
    }

    private fun serveContentUri(
        out: OutputStream,
        snap: PublishedSnapshot,
        uri: Uri,
        mimeType: String,
        isHead: Boolean,
        rangeHeader: String?,
    ) {
        val resolver = context?.contentResolver
        if (resolver == null) {
            writeSimpleResponse(out, 404, "ContentResolver Unavailable")
            return
        }
        val afd = runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        if (afd != null) {
            afd.use { descriptor ->
                serveDescriptor(
                    out = out,
                    snap = snap,
                    uri = uri,
                    fd = descriptor.fileDescriptor,
                    declaredSize = descriptor.length.takeIf { it >= 0L } ?: descriptor.parcelFileDescriptor.statSize,
                    mimeType = mimeType,
                    isHead = isHead,
                    rangeHeader = rangeHeader,
                )
            }
            return
        }
        val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd == null) {
            writeSimpleResponse(out, 404, "File Unavailable")
            return
        }
        pfd.use { descriptor ->
            serveDescriptor(
                out = out,
                snap = snap,
                uri = uri,
                fd = descriptor.fileDescriptor,
                declaredSize = descriptor.statSize,
                mimeType = mimeType,
                isHead = isHead,
                rangeHeader = rangeHeader,
            )
        }
    }

    private fun serveDescriptor(
        out: OutputStream,
        snap: PublishedSnapshot,
        uri: Uri,
        fd: java.io.FileDescriptor,
        declaredSize: Long,
        mimeType: String,
        isHead: Boolean,
        rangeHeader: String?,
    ) {
        val totalLength = declaredSize.takeIf { it >= 0L } ?: queryContentSize(uri) ?: -1L
        if (totalLength <= 0L) {
            if (rangeHeader != null) {
                writeSimpleResponse(out, 416, "Range Not Satisfiable")
                return
            }
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: none\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                appendSubtitleHeaders(this, snap)
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            if (!isHead) {
                runCatching {
                    FileInputStream(fd).use { fis -> copyBounded(fis, out, Long.MAX_VALUE) }
                }
            }
            runCatching { out.flush() }
            return
        }

        if (!rangeHeader.isNullOrBlank()) {
            val range = CastProtocolUtils.parseHttpRangeHeader(rangeHeader, totalLength)
            if (range == null) {
                val header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                runCatching { out.flush() }
                return
            }
            val len = range.last - range.first + 1L
            val header = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $len\r\n")
                append("Content-Range: bytes ${range.first}-${range.last}/$totalLength\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                appendSubtitleHeaders(this, snap)
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            if (!isHead) {
                runCatching {
                    FileInputStream(fd).use { fis ->
                        fis.channel.position(range.first)
                        copyBounded(fis, out, len)
                    }
                }
            }
            runCatching { out.flush() }
        } else {
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $totalLength\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                appendSubtitleHeaders(this, snap)
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            if (!isHead) {
                runCatching {
                    FileInputStream(fd).use { fis -> copyBounded(fis, out, totalLength) }
                }
            }
            runCatching { out.flush() }
        }
    }

    private fun serveSubtitle(
        out: OutputStream,
        isHead: Boolean,
        item: PlaybackItem,
        asWebVtt: Boolean,
    ) {
        val rawBytes = runCatching { readSmallSourceBytes(item.uri) }.getOrNull()
        if (rawBytes == null) {
            writeSimpleResponse(out, 404, "Subtitle Not Found")
            return
        }
        val decodedText = CastProtocolUtils.decodeSubtitleBytes(rawBytes)
        val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val payloadText = if (asWebVtt) {
            when (ext) {
                "ass", "ssa" -> CastProtocolUtils.assToWebVtt(decodedText)
                "vtt" -> decodedText
                else -> CastProtocolUtils.srtToWebVtt(decodedText)
            }
        } else {
            when (ext) {
                "vtt" -> CastProtocolUtils.vttToSrt(decodedText)
                "ass", "ssa" -> CastProtocolUtils.vttToSrt(CastProtocolUtils.assToWebVtt(decodedText))
                else -> decodedText
            }
        }
        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
        val mime = if (asWebVtt) "text/vtt; charset=utf-8" else "text/srt; charset=utf-8"
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: ${payloadBytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!isHead) {
            out.write(payloadBytes)
        }
        out.flush()
    }

    private fun readSmallSourceBytes(uriStr: String): ByteArray? {
        val parsed = runCatching { java.net.URI(uriStr) }.getOrNull() ?: return null
        return when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "http" -> {
                if (parsed.host != "127.0.0.1" && parsed.host != "localhost") return null
                localClient.newCall(Request.Builder().url(uriStr).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }
            }
            "content" -> {
                context?.contentResolver?.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
            }
            "file" -> {
                context?.contentResolver?.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
                    ?: runCatching { java.io.File(parsed).takeIf { it.isFile }?.readBytes() }.getOrNull()
            }
            else -> null
        }
    }

    private fun queryContentSize(uri: Uri): Long? = runCatching {
        context?.contentResolver?.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun copyBounded(input: InputStream, out: OutputStream, bytesToCopy: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = bytesToCopy
        while (remaining > 0L) {
            val chunk = minOf(buf.size.toLong(), remaining).toInt()
            val n = input.read(buf, 0, chunk)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun readHttpHeaders(input: InputStream): List<String>? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        var lineEndCount = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            if (b == '\n'.code) {
                if (prev == '\r'.code || prev == '\n'.code) {
                    lineEndCount++
                    if (lineEndCount >= 2) break
                } else {
                    lineEndCount = 1
                }
            } else if (b != '\r'.code) {
                lineEndCount = 0
            }
            prev = b
        }
        return buf.toString(Charsets.ISO_8859_1.name())
            .split("\r\n", "\n")
            .filter { it.isNotEmpty() }
    }

    private fun writeCorsPreflight(out: OutputStream) {
        val resp = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Range, Content-Type\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(resp.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeJsonResponse(out: OutputStream, code: Int, bodyJson: String, isHead: Boolean) {
        val bytes = bodyJson.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!isHead) out.write(bytes)
        out.flush()
    }

    private fun writeSimpleResponse(out: OutputStream, code: Int, reason: String) {
        val resp = "HTTP/1.1 $code $reason\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(resp.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    companion object {
        private const val MAX_COMMAND_QUEUE_SIZE = 16
        fun findLocalLanIpv4Address(): String? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()
            for (iface in interfaces) {
                val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                if (!up) continue
                val name = iface.name.orEmpty().lowercase(Locale.ROOT)
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("ap") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("tun") || name.startsWith("wg") -> 5
                    else -> 2
                }
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        addr.hostAddress?.let { candidates += priority to it }
                    }
                }
            }
            return candidates.minByOrNull { it.first }?.second
        }
    }
}
