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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.cinito.online.settings.SubtitleStyleConfig
import kotlin.math.roundToInt

/**
 * LG webOS Cast orchestrator that works across ALL LG webOS TVs:
 * 1. With Developer Mode + CiniTo TV (`org.cinito.tv`) installed -> Native receiver via SSAP + HTTP bridge.
 * 2. Stock LG TV without Developer Mode -> Hardware DLNA for MKV/HEVC/HDR + SSAP hardware volume/mute + pointer D-Pad, OR
 *    automatic/user-selectable launch into LG webOS Browser receiver (`/watch`) with bidirectional sync.
 */
class WebOsCastClient(
    private val mediaServer: CastMediaServer,
    private val ssapKeyStore: SsapKeyStore,
    private val initialPreferredMode: WebOsCastMode = WebOsCastMode.Auto,
    private val onModeResolved: (mode: WebOsCastMode, modeLabel: String, pairingPromptDevice: String?) -> Unit = { _, _, _ -> },
) : CastSessionClient {
    override val canReuseConnection: Boolean
        get() = (transport == ActiveTransport.Bridge && ssap.isConnected && activeBridgeAlive) ||
            (transport == ActiveTransport.Dlna)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ssap = WebOsSsapClient(ssapKeyStore)
    private val dlna = DlnaCastClient()

    private enum class ActiveTransport {
        None,
        Bridge,
        Dlna,
    }

    @Volatile private var transport: ActiveTransport = ActiveTransport.None
    @Volatile private var activeBridgeAlive: Boolean = false
    @Volatile private var ssapAudioJob: Job? = null
    @Volatile private var ssapReconnectJob: Job? = null
    @Volatile private var boundMediaId: Long = -1L
    @Volatile private var connectedHost: String? = null
    @Volatile private var activeDevice: CastDevice? = null
    @Volatile private var detectedTvApp: Boolean = false
    @Volatile private var activeModeResolved: WebOsCastMode? = null
    @Volatile private var bridgeReportedAudioTracksSeen: Boolean = false
    @Volatile private var phoneAudioTracks: List<CastAudioTrack> = emptyList()
    @Volatile private var currentSubtitlesCount: Int = 0
    private var currentPreferredMode: WebOsCastMode = initialPreferredMode
    private var statusCallback: ((CastRemoteUpdate) -> Unit)? = null

    init {
        ssap.onDisconnected = {
            activeBridgeAlive = false
            ssapAudioJob?.cancel()
            ssapAudioJob = null
            val cb = statusCallback
            cb?.invoke(
                CastRemoteUpdate(
                    status = null,
                    ssapConnected = false,
                ),
            )
            val dev = activeDevice
            if (dev != null && connectedHost == dev.host && cb != null) {
                scheduleBackgroundSsapReconnect(dev, cb)
            }
        }
    }

    private fun scheduleBackgroundSsapReconnect(
        device: CastDevice,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ) {
        if (ssapKeyStore.getKey(device.host).isNullOrBlank()) return
        ssapReconnectJob?.cancel()
        ssapReconnectJob = scope.launch {
            delay(2_000L)
            if (!isActive || connectedHost != device.host || ssap.isConnected || statusCallback == null) {
                return@launch
            }
            val ok = runCatching {
                ssap.connect(
                    host = device.host,
                    preferredPort = device.ssapPort ?: 3000,
                    onPairingPrompt = {},
                )
            }.getOrDefault(false)
            if (!ok || !isActive || connectedHost != device.host || statusCallback == null) {
                return@launch
            }
            detectedTvApp = runCatching {
                WebOsSsapClient.CINITO_RECEIVER_APP_ID in ssap.listLaunchPoints()
            }.getOrDefault(detectedTvApp)
            onUpdate(
                CastRemoteUpdate(
                    status = null,
                    ssapConnected = true,
                    hasCiniToTvApp = detectedTvApp,
                ),
            )
            startSsapAudioSync(onUpdate)
        }
    }

    private suspend fun ensureSsapConnected(
        device: CastDevice,
        modeForLabel: WebOsCastMode,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Boolean {
        if (ssap.isConnected && connectedHost == device.host) {
            return true
        }
        ssapAudioJob?.cancel()
        ssapAudioJob = null
        val ssapPort = device.ssapPort ?: 3000
        val ok = ssap.connect(
            host = device.host,
            preferredPort = ssapPort,
            onPairingPrompt = {
                onModeResolved(modeForLabel, "Acepta en tu TV LG…", device.name)
            },
        )
        connectedHost = if (ok) device.host else null
        detectedTvApp = if (ok) {
            runCatching { WebOsSsapClient.CINITO_RECEIVER_APP_ID in ssap.listLaunchPoints() }.getOrDefault(false)
        } else {
            false
        }
        onUpdate(
            CastRemoteUpdate(
                status = null,
                ssapConnected = ok,
                hasCiniToTvApp = detectedTvApp,
            ),
        )
        if (ok) {
            startSsapAudioSync(onUpdate)
        }
        return ok
    }

    override suspend fun connectIdle(
        device: CastDevice,
        mediaServerBaseUrl: String?,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        ssapReconnectJob?.cancel()
        ssapReconnectJob = null
        activeDevice = device
        statusCallback = onUpdate
        val ssapOk = ensureSsapConnected(device, currentPreferredMode, onUpdate)

        if (ssapOk && mediaServerBaseUrl != null &&
            (detectedTvApp && (currentPreferredMode == WebOsCastMode.Auto || currentPreferredMode == WebOsCastMode.ReceiverApp))
        ) {
            val launchParams = buildJsonObject {
                put("serverBase", JsonPrimitive(mediaServerBaseUrl))
                put("sessionUrl", JsonPrimitive("$mediaServerBaseUrl/tv/session.json"))
            }
            if (ssap.launchApp(WebOsSsapClient.CINITO_RECEIVER_APP_ID, launchParams)) {
                transport = ActiveTransport.Bridge
                activeBridgeAlive = true
                activeModeResolved = WebOsCastMode.ReceiverApp
                onModeResolved(WebOsCastMode.ReceiverApp, "App CiniTo TV · Lista", null)
                runCatching { ssap.showToast("CiniTo TV listo en ${device.name}") }
                return@withContext true
            }
        }

        if (ssapOk && mediaServerBaseUrl != null && currentPreferredMode == WebOsCastMode.Browser) {
            val browserParams = buildJsonObject {
                put("target", JsonPrimitive("$mediaServerBaseUrl/watch"))
            }
            if (ssap.launchApp(WebOsSsapClient.WEBOS_BROWSER_APP_ID, browserParams)) {
                transport = ActiveTransport.Bridge
                activeBridgeAlive = true
                activeModeResolved = WebOsCastMode.Browser
                onModeResolved(WebOsCastMode.Browser, "Navegador webOS · Listo", null)
                return@withContext true
            }
        }

        if (ssapOk) {
            val label = if (device.hasDlna) "LG webOS · Lista para enviar" else "LG webOS conectado"
            onModeResolved(currentPreferredMode, label, null)
            runCatching { ssap.showToast("CiniTo conectado · Elige qué ver") }
            return@withContext true
        }

        if (device.hasDlna) {
            onModeResolved(WebOsCastMode.Dlna, "LG webOS · DLNA listo", null)
            return@withContext true
        }

        false
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
        ssapReconnectJob?.cancel()
        ssapReconnectJob = null
        statusCallback = onUpdate
        phoneAudioTracks = audioTracks
        currentSubtitlesCount = subtitles.size
        bridgeReportedAudioTracksSeen = false
        val lastBaseUrl = mediaUrl.substringBeforeLast('/')
        val sessionUrl = "$lastBaseUrl/tv/session.json"
        val watchUrl = "$lastBaseUrl/watch"

        val canReuseBridge = transport == ActiveTransport.Bridge &&
            connectedHost == device.host &&
            ssap.isConnected &&
            activeBridgeAlive &&
            (currentPreferredMode == WebOsCastMode.Auto || currentPreferredMode == activeModeResolved)
        if (canReuseBridge) {
            activeDevice = device
            val reusedMode = activeModeResolved ?: WebOsCastMode.ReceiverApp
            val reusedLabel = if (reusedMode == WebOsCastMode.ReceiverApp) "App CiniTo TV" else "LG Navegador webOS"
            activateBridgeTransport(device, reusedMode, reusedLabel, positionMs, announceToast = false, onUpdate = onUpdate)
            return@withContext
        }

        // Clean up only active media transport when switching devices/modes; preserve SSAP if same TV host
        dlna.close()
        if (connectedHost != device.host) {
            close()
        } else {
            unbindMediaListener()
            transport = ActiveTransport.None
            activeBridgeAlive = false
        }
        activeDevice = device
        statusCallback = onUpdate
        phoneAudioTracks = audioTracks
        currentSubtitlesCount = subtitles.size
        bridgeReportedAudioTracksSeen = false

        val ssapConnected = ensureSsapConnected(device, currentPreferredMode, onUpdate)
        onModeResolved(currentPreferredMode, "Conectando…", null)

        // Strategy 1: Custom CiniTo TV app (`org.cinito.tv`) when installed in Dev Mode.
        // In Auto mode, skip launching when listLaunchPoints() already confirmed it isn't installed.
        val shouldTryReceiverApp = ssapConnected && (
            currentPreferredMode == WebOsCastMode.ReceiverApp ||
                (currentPreferredMode == WebOsCastMode.Auto && detectedTvApp)
            )
        if (shouldTryReceiverApp) {
            val launchParams = buildJsonObject {
                put("serverBase", JsonPrimitive(lastBaseUrl))
                put("sessionUrl", JsonPrimitive(sessionUrl))
            }
            val receiverLaunched = ssap.launchApp(WebOsSsapClient.CINITO_RECEIVER_APP_ID, launchParams)
            if (receiverLaunched) {
                detectedTvApp = true
                activeModeResolved = WebOsCastMode.ReceiverApp
                activateBridgeTransport(
                    device = device,
                    mode = WebOsCastMode.ReceiverApp,
                    modeLabel = "App CiniTo TV",
                    positionMs = positionMs,
                    announceToast = true,
                    onUpdate = onUpdate,
                )
                return@withContext
            }
            if (currentPreferredMode == WebOsCastMode.ReceiverApp) {
                error("CiniTo TV (org.cinito.tv) no está instalada en ${device.name}. Usa modo Automático o DLNA.")
            }
        }

        val preferBrowserForSubtitles = currentPreferredMode == WebOsCastMode.Auto &&
            ssapConnected &&
            subtitles.isNotEmpty() &&
            initialSubtitleIndex != null

        if (preferBrowserForSubtitles) {
            if (tryBrowser(device, watchUrl, positionMs, onUpdate)) {
                return@withContext
            }
            if (device.hasDlna) {
                tryDlnaOrThrow(
                    device = device,
                    mediaUrl = mediaUrl,
                    mimeType = mimeType,
                    title = title,
                    positionMs = positionMs,
                    subtitles = subtitles,
                    audioTracks = audioTracks,
                    initialAudioIndex = initialAudioIndex,
                    initialSubtitleIndex = initialSubtitleIndex,
                    subtitleStyle = subtitleStyle,
                    onUpdate = onUpdate,
                    allowFallback = false,
                )
                return@withContext
            }
        } else {
            // Strategy 2: LG hardware DLNA (best for 4K MKV / HEVC / AC3 / HDR on stock retail LG TVs) + SSAP volume
            if ((currentPreferredMode == WebOsCastMode.Auto || currentPreferredMode == WebOsCastMode.Dlna) && device.hasDlna) {
                if (ssapConnected && (currentPreferredMode == WebOsCastMode.Dlna || activeModeResolved == WebOsCastMode.Browser)) {
                    runCatching {
                        ssap.request(
                            "ssap://system.launcher/close",
                            buildJsonObject { put("id", JsonPrimitive(WebOsSsapClient.WEBOS_BROWSER_APP_ID)) },
                        )
                        delay(300L)
                    }
                }
                val dlnaOk = tryDlnaOrThrow(
                    device = device,
                    mediaUrl = mediaUrl,
                    mimeType = mimeType,
                    title = title,
                    positionMs = positionMs,
                    subtitles = subtitles,
                    audioTracks = audioTracks,
                    initialAudioIndex = initialAudioIndex,
                    initialSubtitleIndex = initialSubtitleIndex,
                    subtitleStyle = subtitleStyle,
                    onUpdate = onUpdate,
                    allowFallback = currentPreferredMode == WebOsCastMode.Auto && ssapConnected,
                )
                if (dlnaOk) return@withContext
            }

            // Strategy 3: LG built-in Web Browser receiver (`/watch`) on stock TVs without Dev Mode or DLNA
            if (ssapConnected && (currentPreferredMode == WebOsCastMode.Auto || currentPreferredMode == WebOsCastMode.Browser)) {
                if (tryBrowser(device, watchUrl, positionMs, onUpdate)) {
                    return@withContext
                }
            }
        }

        error("No se pudo iniciar transmisión en ${device.name}. Activa «LG Connect Apps» en Ajustes de red del TV o conecta por DLNA.")
    }

    private suspend fun tryDlnaOrThrow(
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
        allowFallback: Boolean,
    ): Boolean {
        val dlnaAudioCap = if (audioTracks.size == 1) {
            CastAudioControlCapability.SingleTrack
        } else {
            CastAudioControlCapability.AssistedRemoteMenu
        }
        val dlnaSubCap = when {
            subtitles.isNotEmpty() -> CastSubtitleControlCapability.MixedExternalAndNative
            device.renderingControlUrl != null -> CastSubtitleControlCapability.DlnaNativeToggle
            else -> CastSubtitleControlCapability.Unsupported
        }
        var dlnaReportedSubCap = false
        val wrappedUpdate: (CastRemoteUpdate) -> Unit = { update ->
            if (update.subtitleCapabilityOverride != null) {
                dlnaReportedSubCap = true
            }
            val resolvedSubCap = when {
                update.subtitleCapabilityOverride != null -> update.subtitleCapabilityOverride
                !dlnaReportedSubCap -> dlnaSubCap
                else -> null
            }
            onUpdate(
                update.copy(
                    audioCapabilityOverride = update.audioCapabilityOverride ?: dlnaAudioCap,
                    subtitleCapabilityOverride = resolvedSubCap,
                ),
            )
        }
        val dlnaResult = runCatching {
            activeBridgeAlive = false
            transport = ActiveTransport.Dlna
            activeModeResolved = WebOsCastMode.Dlna
            onModeResolved(WebOsCastMode.Dlna, "LG webOS · DLNA", null)
            dlna.connectAndLoad(
                device = device,
                mediaUrl = mediaUrl,
                mimeType = mimeType,
                title = title,
                positionMs = positionMs,
                subtitles = subtitles,
                audioTracks = audioTracks,
                initialAudioIndex = initialAudioIndex,
                initialSubtitleIndex = initialSubtitleIndex,
                subtitleStyle = subtitleStyle,
                onUpdate = wrappedUpdate,
            )
        }
        if (dlnaResult.isSuccess) {
            CastLog.i("WebOS", "connectAndLoad using DLNA")
            return true
        }
        val err = dlnaResult.exceptionOrNull()
        if (err is CancellationException) throw err
        CastLog.w("WebOS", "DLNA failed, fallback check", err)
        transport = ActiveTransport.None
        activeModeResolved = null
        if (!allowFallback) {
            throw err!!
        }
        return false
    }

    private suspend fun tryBrowser(
        device: CastDevice,
        watchUrl: String,
        positionMs: Long,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ): Boolean {
        val browserParams = buildJsonObject {
            put("target", JsonPrimitive(watchUrl))
        }
        val browserLaunched = ssap.launchApp(WebOsSsapClient.WEBOS_BROWSER_APP_ID, browserParams)
        CastLog.i("WebOS", "launch Browser($watchUrl) ok=$browserLaunched")
        if (!browserLaunched) return false
        activeModeResolved = WebOsCastMode.Browser
        activateBridgeTransport(
            device = device,
            mode = WebOsCastMode.Browser,
            modeLabel = "LG Navegador webOS",
            positionMs = positionMs,
            announceToast = true,
            onUpdate = onUpdate,
        )
        return true
    }

    fun setPreferredMode(mode: WebOsCastMode) {
        currentPreferredMode = mode
    }

    private suspend fun activateBridgeTransport(
        device: CastDevice,
        mode: WebOsCastMode,
        modeLabel: String,
        positionMs: Long,
        announceToast: Boolean,
        onUpdate: (CastRemoteUpdate) -> Unit,
    ) {
        transport = ActiveTransport.Bridge
        activeBridgeAlive = true
        bridgeReportedAudioTracksSeen = false
        val bridgeSubCap = if (currentSubtitlesCount > 0) {
            CastSubtitleControlCapability.ExternalTrackSelect
        } else {
            CastSubtitleControlCapability.Unsupported
        }
        val mediaId = mediaServer.currentMediaId()
        boundMediaId = mediaId
        onModeResolved(mode, modeLabel, null)
        onUpdate(CastRemoteUpdate(subtitleCapabilityOverride = bridgeSubCap))
        mediaServer.setTvReportListener(expectedMediaId = mediaId) { report ->
            val reportedTracks = report.reportedAudioTracks
            val hasNonEmptyReported = !reportedTracks.isNullOrEmpty()
            if (hasNonEmptyReported) {
                bridgeReportedAudioTracksSeen = true
            }
            if (report.status == CastPlaybackStatus.Error) {
                activeBridgeAlive = false
            }
            val audioTracksUpdate = if (hasNonEmptyReported && (reportedTracks!!.size > 1 || phoneAudioTracks.isEmpty())) {
                reportedTracks
            } else {
                null
            }
            val audioCapOverride = when {
                hasNonEmptyReported -> if (reportedTracks!!.size > 1) {
                    CastAudioControlCapability.NativeSwitch
                } else {
                    CastAudioControlCapability.SingleTrack
                }
                reportedTracks != null && reportedTracks.isEmpty() ->
                    CastAudioControlCapability.SingleTrack
                report.status == CastPlaybackStatus.Playing && !bridgeReportedAudioTracksSeen ->
                    CastAudioControlCapability.SingleTrack
                else -> null
            }
            statusCallback?.invoke(
                CastRemoteUpdate(
                    status = report.status,
                    positionMs = report.positionMs,
                    durationMs = report.durationMs,
                    errorMessage = report.errorMessage,
                    ended = report.ended,
                    subtitleOverrideIndex = report.subtitleOverrideIndex,
                    hasSubtitleOverride = report.hasSubtitleOverride,
                    audioOverrideIndex = report.audioOverrideIndex,
                    hasAudioOverride = report.hasAudioOverride,
                    audioTracksUpdate = audioTracksUpdate,
                    audioCapabilityOverride = audioCapOverride,
                ),
            )
        }
        if (positionMs > 0L) {
            onUpdate(CastRemoteUpdate(CastPlaybackStatus.Buffering, positionMs, 0L))
        }
        if (announceToast) {
            runCatching { ssap.showToast("CiniTo conectado en ${device.name}") }
        }
    }

    private fun startSsapAudioSync(onUpdate: (CastRemoteUpdate) -> Unit) {
        ssapAudioJob?.cancel()
        ssapAudioJob = scope.launch {
            while (isActive && ssap.isConnected) {
                val (vol, muted) = ssap.getVolumeAndMute()
                if (vol != null || muted != null) {
                    onUpdate(
                        CastRemoteUpdate(
                            status = null,
                            volume = vol,
                            isMuted = muted,
                        ),
                    )
                }
                delay(4_500L)
            }
        }
    }

    override suspend fun play() {
        when (transport) {
            ActiveTransport.Bridge -> mediaServer.enqueueTvCommand("PLAY")
            ActiveTransport.Dlna -> dlna.play()
            ActiveTransport.None -> if (ssap.isConnected) ssap.request("ssap://media.controls/play")
        }
    }

    override suspend fun pause() {
        when (transport) {
            ActiveTransport.Bridge -> mediaServer.enqueueTvCommand("PAUSE")
            ActiveTransport.Dlna -> dlna.pause()
            ActiveTransport.None -> if (ssap.isConnected) ssap.request("ssap://media.controls/pause")
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        when (transport) {
            ActiveTransport.Bridge -> mediaServer.enqueueTvCommand("SEEK", positionMs)
            ActiveTransport.Dlna -> dlna.seekTo(positionMs)
            ActiveTransport.None -> Unit
        }
    }

    override suspend fun setVolume(fraction: Float) {
        val percent = (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
        if (ssap.isConnected && ssap.setVolume(percent)) return
        if (transport == ActiveTransport.Dlna) dlna.setVolume(fraction)
    }

    override suspend fun setMuted(muted: Boolean) {
        if (ssap.isConnected && ssap.setMuted(muted)) return
        if (transport == ActiveTransport.Dlna) dlna.setMuted(muted)
    }

    override suspend fun selectSubtitleIndex(index: Int?) {
        mediaServer.updateSubtitleSelection(index)
        if (transport == ActiveTransport.Dlna) {
            dlna.selectSubtitleIndex(index)
        }
    }

    override suspend fun setNativeSubtitlesEnabled(enabled: Boolean): Boolean {
        return if (transport == ActiveTransport.Dlna) {
            dlna.setNativeSubtitlesEnabled(enabled)
        } else {
            false
        }
    }

    override suspend fun selectAudioTrack(index: Int) {
        mediaServer.updateAudioSelection(index)
        if (transport == ActiveTransport.Bridge) {
            mediaServer.enqueueTvCommand("SELECT_AUDIO", audioIndex = index)
        }
    }

    override suspend fun sendTvRemoteKey(key: WebOsRemoteKey): Boolean {
        if (!ssap.isConnected) return false
        return ssap.sendRemoteKey(key)
    }

    override suspend fun stop() {
        when (transport) {
            ActiveTransport.Bridge -> mediaServer.enqueueTvCommand("STOP")
            ActiveTransport.Dlna -> {
                dlna.stop()
                transport = ActiveTransport.None
                activeModeResolved = null
            }
            ActiveTransport.None -> Unit
        }
        unbindMediaListener()
    }

    private fun unbindMediaListener() {
        val mediaId = boundMediaId
        boundMediaId = -1L
        if (mediaId >= 0L) {
            mediaServer.clearTvReportListener(mediaId)
        }
    }

    override fun close() {
        ssapReconnectJob?.cancel()
        ssapReconnectJob = null
        ssapAudioJob?.cancel()
        ssapAudioJob = null
        unbindMediaListener()
        statusCallback = null
        dlna.close()
        ssap.close()
        activeBridgeAlive = false
        connectedHost = null
        activeDevice = null
        detectedTvApp = false
        activeModeResolved = null
        phoneAudioTracks = emptyList()
        currentSubtitlesCount = 0
        bridgeReportedAudioTracksSeen = false
        transport = ActiveTransport.None
    }
}
