package org.cinito.online.cast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.cinito.online.network.NetworkSafety
import org.cinito.online.settings.SubtitleStyleConfig
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Open-source pure-Kotlin UPnP/DLNA AVTransport + RenderingControl SOAP client
 * tuned for LG webOS, Samsung Smart TV, Sony, TCL, Hisense, Roku, and Kodi.
 */
class DlnaCastClient(
    private val allowLoopbackForTest: Boolean = false,
) : CastSessionClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val longSoapHttp = http.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null
    private var device: CastDevice? = null
    private var currentMediaUrl: String = ""
    private var currentMimeType: String = "video/mp4"
    private var currentTitle: String = ""
    private var currentSubtitles: List<CastSubtitleTrack> = emptyList()
    private var statusCallback: ((CastRemoteUpdate) -> Unit)? = null

    @Volatile private var lastKnownPositionMs: Long = 0L
    @Volatile private var lastKnownDurationMs: Long = 0L
    @Volatile private var pendingResumeSeekMs: Long = 0L
    @Volatile private var previousTransportState: String? = null
    @Volatile private var supportsNativeSubtitleToggle: Boolean = true
    @Volatile private var pendingInitialNativeSub: Boolean = false

    override suspend fun connectIdle(
        device: CastDevice,
        mediaServerBaseUrl: String?,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Boolean {
        val avUrl = device.avTransportControlUrl ?: return false
        validateLanUrl(avUrl)
        device.renderingControlUrl?.let(::validateLanUrl)
        this@DlnaCastClient.device = device
        supportsNativeSubtitleToggle = device.renderingControlUrl != null
        return true
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
    ) = withContext(Dispatchers.IO) {
        close()
        val avUrl = requireNotNull(device.avTransportControlUrl) { "El dispositivo DLNA no expone AVTransport" }
        validateLanUrl(avUrl)
        device.renderingControlUrl?.let(::validateLanUrl)

        this@DlnaCastClient.device = device
        currentMediaUrl = mediaUrl
        currentMimeType = mimeType
        currentTitle = title
        currentSubtitles = subtitles
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        lastKnownDurationMs = 0L
        previousTransportState = null
        pendingResumeSeekMs = if (positionMs > 4_000L) positionMs else 0L
        supportsNativeSubtitleToggle = device.renderingControlUrl != null
        pendingInitialNativeSub = subtitles.isEmpty() &&
            initialSubtitleIndex != null &&
            device.renderingControlUrl != null
        statusCallback = onUpdate

        val initialSub = initialSubtitleIndex?.let { idx ->
            subtitles.firstOrNull { it.index == idx }?.srtUrl ?: subtitles.getOrNull(idx)?.srtUrl
        }
        sendSetUriAndPlay(avUrl, mediaUrl, mimeType, title, positionMs, initialSub)
        val initialSubCapability = when {
            subtitles.isNotEmpty() -> CastSubtitleControlCapability.MixedExternalAndNative
            device.renderingControlUrl != null -> CastSubtitleControlCapability.DlnaNativeToggle
            else -> CastSubtitleControlCapability.Unsupported
        }
        onUpdate(CastRemoteUpdate(subtitleCapabilityOverride = initialSubCapability))
        startPolling(device)
    }

    override suspend fun play() {
        val avUrl = device?.avTransportControlUrl ?: return
        soapAction(
            url = avUrl,
            service = NS_AV_TRANSPORT,
            action = "Play",
            innerXml = "<InstanceID>0</InstanceID><Speed>1</Speed>",
            useLongTimeout = true,
        )
    }

    override suspend fun pause() {
        val avUrl = device?.avTransportControlUrl ?: return
        soapAction(
            url = avUrl,
            service = NS_AV_TRANSPORT,
            action = "Pause",
            innerXml = "<InstanceID>0</InstanceID>",
        )
    }

    override suspend fun seekTo(positionMs: Long) {
        pendingResumeSeekMs = 0L
        val avUrl = device?.avTransportControlUrl ?: return
        val formatted = CastProtocolUtils.formatDlnaTime(positionMs)
        soapAction(
            url = avUrl,
            service = NS_AV_TRANSPORT,
            action = "Seek",
            innerXml = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$formatted</Target>",
        )
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
    }

    override suspend fun setVolume(fraction: Float) {
        val rcUrl = device?.renderingControlUrl ?: return
        val vol = (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
        soapAction(
            url = rcUrl,
            service = NS_RENDERING_CONTROL,
            action = "SetVolume",
            innerXml = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$vol</DesiredVolume>",
        )
    }

    override suspend fun setMuted(muted: Boolean) {
        val rcUrl = device?.renderingControlUrl ?: return
        val valStr = if (muted) "1" else "0"
        soapAction(
            url = rcUrl,
            service = NS_RENDERING_CONTROL,
            action = "SetMute",
            innerXml = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>$valStr</DesiredMute>",
        )
    }

    override suspend fun setNativeSubtitlesEnabled(enabled: Boolean): Boolean {
        val rcUrl = device?.renderingControlUrl ?: return false
        if (!supportsNativeSubtitleToggle) return false
        val desired = if (enabled) "ON" else "OFF"
        return try {
            soapAction(
                url = rcUrl,
                service = NS_RENDERING_CONTROL,
                action = "X_SetSubtitle",
                innerXml = "<InstanceID>0</InstanceID><DesiredSubtitle>$desired</DesiredSubtitle>",
            )
            statusCallback?.invoke(CastRemoteUpdate(subtitleEnabledOnTv = enabled))
            true
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            val msg = err.message.orEmpty()
            if (isUnsupportedUpnpAction(msg)) {
                CastLog.i("DLNA", "X_SetSubtitle unsupported ($msg); disabling native subtitle toggle")
                disableNativeSubtitleToggle()
            } else {
                CastLog.w("DLNA", "X_SetSubtitle($desired) failed", err)
            }
            false
        }
    }

    override suspend fun selectSubtitleIndex(index: Int?) {
        pendingInitialNativeSub = false
        if (currentSubtitles.isEmpty()) {
            if (index == null) {
                setNativeSubtitlesEnabled(false)
            } else if (supportsNativeSubtitleToggle) {
                setNativeSubtitlesEnabled(true)
            }
            return
        }

        val avUrl = device?.avTransportControlUrl ?: return
        if (index == null) {
            if (supportsNativeSubtitleToggle && setNativeSubtitlesEnabled(false)) {
                return
            }
            val resumeTarget = lastKnownPositionMs
            pendingResumeSeekMs = 0L
            sendSetUriAndPlay(avUrl, currentMediaUrl, currentMimeType, currentTitle, resumeTarget, subtitleUrl = null)
            if (resumeTarget > 4_000L) {
                pendingResumeSeekMs = resumeTarget
            }
            return
        }

        val subUrl = currentSubtitles.firstOrNull { it.index == index }?.srtUrl
            ?: currentSubtitles.getOrNull(index)?.srtUrl
            ?: return
        val resumeTarget = lastKnownPositionMs
        pendingResumeSeekMs = 0L
        sendSetUriAndPlay(avUrl, currentMediaUrl, currentMimeType, currentTitle, resumeTarget, subUrl)
        if (resumeTarget > 4_000L) {
            pendingResumeSeekMs = resumeTarget
        }
        if (supportsNativeSubtitleToggle) {
            setNativeSubtitlesEnabled(true)
        }
    }

    override suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        val avUrl = device?.avTransportControlUrl ?: return
        soapAction(
            url = avUrl,
            service = NS_AV_TRANSPORT,
            action = "Stop",
            innerXml = "<InstanceID>0</InstanceID>",
        )
    }

    override fun close() {
        pollJob?.cancel()
        pollJob = null
        statusCallback = null
        device = null
        supportsNativeSubtitleToggle = true
        pendingInitialNativeSub = false
        pendingResumeSeekMs = 0L
        previousTransportState = null
        currentSubtitles = emptyList()
    }

    private suspend fun sendSetUriAndPlay(
        avUrl: String,
        mediaUrl: String,
        mimeType: String,
        title: String,
        positionMs: Long,
        subtitleUrl: String?,
    ) {
        // Clear any stuck LG_TRANSITIONING / previous DMR playback state before SetAVTransportURI
        runCatching {
            soapAction(
                url = avUrl,
                service = NS_AV_TRANSPORT,
                action = "Stop",
                innerXml = "<InstanceID>0</InstanceID>",
                useLongTimeout = true,
            )
            delay(250L)
        }

        val didl = CastProtocolUtils.dlnaDidlMetadataXml(title, mediaUrl, mimeType, subtitleUrl)
        val escUri = CastProtocolUtils.escapeXml(mediaUrl)
        val escDidl = CastProtocolUtils.escapeXml(didl)
        CastLog.i("DLNA", "SetAVTransportURI uri=$mediaUrl sub=$subtitleUrl")
        setAvTransportUriWithRetry(avUrl, escUri, escDidl)

        delay(300L)
        playWithRetry(avUrl)
        // Note: initial resume seek is deferred to startPolling once PLAYING state arrives (avoids LG HTTP 500 Transition errors)
        if (positionMs <= 4_000L) pendingResumeSeekMs = 0L
    }

    private suspend fun setAvTransportUriWithRetry(
        avUrl: String,
        escUri: String,
        escDidl: String,
    ) {
        var useEmptyMeta = false
        val backoffs = longArrayOf(400L, 700L)
        for (attempt in 0..backoffs.size) {
            val meta = if (useEmptyMeta) "" else escDidl
            try {
                soapAction(
                    url = avUrl,
                    service = NS_AV_TRANSPORT,
                    action = "SetAVTransportURI",
                    innerXml = "<InstanceID>0</InstanceID><CurrentURI>$escUri</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>",
                    useLongTimeout = true,
                )
                return
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                val msg = err.message.orEmpty()
                val shouldFallbackEmptyMeta = !useEmptyMeta && (
                    msg.contains("714") ||
                        msg.contains("402") ||
                        msg.contains("Illegal MIME", ignoreCase = true) ||
                        msg.contains("Invalid Args", ignoreCase = true)
                    )
                if (shouldFallbackEmptyMeta) {
                    CastLog.w("DLNA", "SetAVTransportURI DIDL rejected ($msg), retrying with empty CurrentURIMetaData")
                    useEmptyMeta = true
                    continue
                }
                val isTransition701 = msg.contains("701") ||
                    msg.contains("Transition not available", ignoreCase = true)
                if (!isTransition701 || attempt >= backoffs.size) throw err
                val waitMs = backoffs[attempt]
                CastLog.w("DLNA", "SetAVTransportURI 701 ($msg), sending Stop and retrying in ${waitMs}ms")
                runCatching {
                    soapAction(
                        url = avUrl,
                        service = NS_AV_TRANSPORT,
                        action = "Stop",
                        innerXml = "<InstanceID>0</InstanceID>",
                        useLongTimeout = true,
                    )
                }
                delay(waitMs)
            }
        }
    }

    private suspend fun playWithRetry(avUrl: String) {
        val backoffs = longArrayOf(350L, 600L)
        for (attempt in 0..backoffs.size) {
            try {
                play()
                return
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                if (err is SocketTimeoutException) {
                    val stateXml = runCatching {
                        soapAction(
                            url = avUrl,
                            service = NS_AV_TRANSPORT,
                            action = "GetTransportInfo",
                            innerXml = "<InstanceID>0</InstanceID>",
                        )
                    }.getOrNull()
                    val stateStr = stateXml?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
                        ?.selectFirst("CurrentTransportState")?.ownText().orEmpty()
                    CastLog.w("DLNA", "Play SOAP timed out; TV transport state=$stateStr")
                    if (stateStr.contains("PLAYING", ignoreCase = true) ||
                        stateStr.contains("TRANSITIONING", ignoreCase = true)
                    ) {
                        return
                    }
                }
                val msg = err.message.orEmpty()
                val retryable = err is SocketTimeoutException ||
                    msg.contains("500") ||
                    msg.contains("701") ||
                    msg.contains("Transition not available", ignoreCase = true)
                if (!retryable || attempt >= backoffs.size) throw err
                val waitMs = backoffs[attempt]
                CastLog.w("DLNA", "Play failed ($msg), retrying in ${waitMs}ms (attempt ${attempt + 1}/${backoffs.size})")
                delay(waitMs)
            }
        }
    }

    private fun startPolling(targetDevice: CastDevice) {
        pollJob?.cancel()
        val avUrl = targetDevice.avTransportControlUrl ?: return
        val rcUrl = targetDevice.renderingControlUrl
        pollJob = scope.launch {
            var tick = 0
            var consecutivePollFailures = 0
            var hadSuccessfulPoll = false
            var prevCycleHadFailure = false

            while (isActive) {
                val posXml = runCatching {
                    soapAction(
                        url = avUrl,
                        service = NS_AV_TRANSPORT,
                        action = "GetPositionInfo",
                        innerXml = "<InstanceID>0</InstanceID>",
                    )
                }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    null
                }

                val transportXml = runCatching {
                    soapAction(
                        url = avUrl,
                        service = NS_AV_TRANSPORT,
                        action = "GetTransportInfo",
                        innerXml = "<InstanceID>0</InstanceID>",
                    )
                }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    null
                }

                if (posXml != null || transportXml != null) {
                    consecutivePollFailures = 0
                    val posDoc = posXml?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
                    val relTimeMs = CastProtocolUtils.parseDlnaTimeToMs(posDoc?.selectFirst("RelTime")?.ownText())
                    val durMs = CastProtocolUtils.parseDlnaTimeToMs(posDoc?.selectFirst("TrackDuration")?.ownText())
                    if (relTimeMs > 0L) lastKnownPositionMs = relTimeMs
                    if (durMs > 0L) lastKnownDurationMs = durMs

                    val transportDoc = transportXml?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
                    val stateStr = transportDoc?.selectFirst("CurrentTransportState")?.ownText().orEmpty()
                    val transportStatus = transportDoc?.selectFirst("CurrentTransportStatus")?.ownText().orEmpty()
                    val canDetectEnded = hadSuccessfulPoll && !prevCycleHadFailure && transportXml != null
                    val ended = canDetectEnded && didReachNaturalEnd(
                        previousState = previousTransportState,
                        currentState = stateStr,
                        lastPosMs = maxOf(relTimeMs, lastKnownPositionMs),
                        durationMs = maxOf(durMs, lastKnownDurationMs),
                    )
                    if (posXml != null && transportXml != null) {
                        hadSuccessfulPoll = true
                        prevCycleHadFailure = false
                    } else {
                        prevCycleHadFailure = true
                    }
                    if (stateStr.isNotBlank()) previousTransportState = stateStr
                    val status = when {
                        transportStatus.contains("ERROR", ignoreCase = true) -> CastPlaybackStatus.Error
                        stateStr.contains("PLAYING", ignoreCase = true) -> CastPlaybackStatus.Playing
                        stateStr.contains("PAUSED", ignoreCase = true) -> CastPlaybackStatus.Paused
                        stateStr.contains("TRANSITIONING", ignoreCase = true) -> CastPlaybackStatus.Buffering
                        stateStr.contains("STOPPED", ignoreCase = true) -> CastPlaybackStatus.Idle
                        else -> CastPlaybackStatus.Buffering
                    }

                    if (status == CastPlaybackStatus.Playing && pendingResumeSeekMs > 4_000L) {
                        val resumeTarget = pendingResumeSeekMs
                        pendingResumeSeekMs = 0L
                        runCatching { seekTo(resumeTarget) }
                    }
                    if (status == CastPlaybackStatus.Playing && pendingInitialNativeSub) {
                        pendingInitialNativeSub = false
                        runCatching { setNativeSubtitlesEnabled(true) }
                    }

                    statusCallback?.invoke(
                        CastRemoteUpdate(
                            status = status,
                            positionMs = relTimeMs,
                            durationMs = durMs,
                            ended = ended,
                            errorMessage = if (status == CastPlaybackStatus.Error) {
                                "El TV reportó error reproduciendo el formato DLNA."
                            } else null,
                        ),
                    )
                } else {
                    consecutivePollFailures++
                    prevCycleHadFailure = true
                    if (consecutivePollFailures >= 4) {
                        CastLog.w("DLNA", "Consecutive SOAP poll failures=$consecutivePollFailures; reporting connection lost")
                        statusCallback?.invoke(
                            CastRemoteUpdate(
                                status = CastPlaybackStatus.Error,
                                errorMessage = "Se perdió la conexión DLNA con el televisor.",
                            ),
                        )
                    }
                }

                if (rcUrl != null && tick % 2 == 0) {
                    val volXml = runCatching {
                        soapAction(
                            url = rcUrl,
                            service = NS_RENDERING_CONTROL,
                            action = "GetVolume",
                            innerXml = "<InstanceID>0</InstanceID><Channel>Master</Channel>",
                        )
                    }.getOrElse { err ->
                        if (err is CancellationException) throw err
                        null
                    }
                    val vol = volXml?.let(CastProtocolUtils::parseDlnaVolume)
                    if (vol != null) {
                        statusCallback?.invoke(CastRemoteUpdate(status = null, volume = vol))
                    }

                    if (supportsNativeSubtitleToggle) {
                        val subXml = runCatching {
                            soapAction(
                                url = rcUrl,
                                service = NS_RENDERING_CONTROL,
                                action = "X_GetSubtitle",
                                innerXml = "<InstanceID>0</InstanceID>",
                            )
                        }.getOrElse { err ->
                            if (err is CancellationException) throw err
                            val msg = err.message.orEmpty()
                            if (isUnsupportedUpnpAction(msg)) {
                                CastLog.i("DLNA", "X_GetSubtitle unsupported ($msg); disabling native subtitle toggle")
                                disableNativeSubtitleToggle()
                            }
                            null
                        }
                        val subEnabled = subXml?.let(::parseDlnaCurrentSubtitle)
                        if (subEnabled != null) {
                            statusCallback?.invoke(CastRemoteUpdate(subtitleEnabledOnTv = subEnabled))
                        }
                    }
                }
                tick++
                delay(1_500L)
            }
        }
    }

    private suspend fun soapAction(
        url: String,
        service: String,
        action: String,
        innerXml: String,
        useLongTimeout: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        validateLanUrl(url)
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="$service">
                  $innerXml
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val req = Request.Builder()
            .url(url)
            .header("SOAPACTION", "\"$service#$action\"")
            .post(envelope.toRequestBody(SOAP_MEDIA_TYPE))
            .build()

        val client = if (useLongTimeout) longSoapHttp else http
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val upnpErr = CastProtocolUtils.parseUpnpFaultMessage(body)
                error(if (upnpErr != null) "DLNA HTTP ${resp.code} ($action): $upnpErr" else "DLNA HTTP ${resp.code} ($action)")
            }
            body
        }
    }

    private fun disableNativeSubtitleToggle() {
        supportsNativeSubtitleToggle = false
        statusCallback?.invoke(
            CastRemoteUpdate(
                subtitleCapabilityOverride = if (currentSubtitles.isNotEmpty()) {
                    CastSubtitleControlCapability.ExternalTrackSelect
                } else {
                    CastSubtitleControlCapability.Unsupported
                },
            ),
        )
    }

    private fun isUnsupportedUpnpAction(message: String): Boolean =
        UNSUPPORTED_UPNP_CODE_REGEX.containsMatchIn(message) ||
            message.contains("Invalid Action", ignoreCase = true)

    private fun parseDlnaCurrentSubtitle(xml: String): Boolean? {
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return null
        return when (doc.selectFirst("CurrentSubtitle")?.ownText()?.trim()?.uppercase(Locale.ROOT)) {
            "ON" -> true
            "OFF" -> false
            else -> null
        }
    }

    private fun validateLanUrl(url: String) {
        val host = URI(url).host.orEmpty()
        val allowed = if (allowLoopbackForTest) {
            NetworkSafety.isCleartextPermittedHost(host)
        } else {
            NetworkSafety.isPrivateLanHost(host)
        }
        require(allowed) {
            "Bloqueada URL DLNA fuera de la red local ($host)"
        }
    }

    companion object {
        private const val NS_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val NS_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        private val SOAP_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()
        private val UNSUPPORTED_UPNP_CODE_REGEX = Regex("""\b(?:401|501)\b""")

        fun didReachNaturalEnd(
            previousState: String?,
            currentState: String,
            lastPosMs: Long,
            durationMs: Long,
        ): Boolean {
            if (previousState?.contains("PLAYING", ignoreCase = true) != true) return false
            if (!currentState.contains("STOPPED", ignoreCase = true)) return false
            if (durationMs <= 10_000L) return false
            val threshold = (durationMs - 20_000L).coerceAtLeast((durationMs * 85L) / 100L)
            return lastPosMs >= threshold
        }
    }
}
