package org.cinito.online.cast

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.player.TrackLanguageFormatter
import org.cinito.online.settings.AppPreferences
import org.cinito.online.settings.SubtitleStyleConfig
import webtor.app.PlaybackItem
import webtor.app.PlaybackLeases
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private class InMemorySsapKeyStore : SsapKeyStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun getKey(deviceKey: String): String? = map[deviceKey]
    override fun putKey(deviceKey: String, clientKey: String) {
        map[deviceKey] = clientKey
    }
}

class CastManager(
    private val context: Context?,
    networkClient: HardenedNetworkClient,
    private val playbackLeases: PlaybackLeases,
    private val onProgressBookmark: (bookmarkUri: String, positionMs: Long, durationMs: Long) -> Unit,
    private val onRequestNextEpisode: (nextIndex: Int) -> Unit = {},
    hostIpProvider: () -> String? = { CastMediaServer.findLocalLanIpv4Address() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val clientFactory: ((CastDevice, WebOsCastMode, (WebOsCastMode, String, String?) -> Unit) -> CastSessionClient)? = null,
    private val preferencesProvider: () -> AppPreferences = {
        (context?.applicationContext as? webtor.app.WebtorApp)?.appSettings?.preferences?.value ?: AppPreferences()
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaServer = CastMediaServer(context, hostIpProvider = hostIpProvider)
    private val discovery: CastDiscoveryManager? = context?.let { CastDiscoveryManager(it, networkClient) }
    private val ssapKeyStore: SsapKeyStore = context?.let(::SharedPrefsSsapKeyStore) ?: InMemorySsapKeyStore()

    private val _session = MutableStateFlow(CastSessionState())
    val session: StateFlow<CastSessionState> = _session.asStateFlow()

    private val _autoNextEpisodeRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val autoNextEpisodeRequests: SharedFlow<Int> = _autoNextEpisodeRequests.asSharedFlow()

    private var activeClient: CastSessionClient? = null
    private var activeDeviceId: String? = null
    private var activeLease: AutoCloseable? = null
    private var activeBookmarkUri: String? = null
    private var currentSource: CastMediaSource? = null
    private var connectJob: Job? = null
    private var teardownJob: Job? = null
    private val sessionGeneration = AtomicLong(0L)
    private val connectionGeneration = AtomicLong(0L)
    private var lastPersistedAtMs: Long = 0L
    private val autoAdvancedFromEnded = AtomicBoolean(false)

    init {
        discovery?.let { disc ->
            scope.launch {
                combine(disc.isDiscovering, disc.devices) { scanning, devs ->
                    scanning to devs
                }.collect { (scanning, devs) ->
                    _session.update { it.copy(isDiscovering = scanning, devices = devs) }
                }
            }
        }
    }

    fun startDiscovery() {
        discovery?.startDiscovery()
    }

    fun refreshDiscovery() {
        discovery?.refreshDiscovery()
    }

    fun stopDiscovery() {
        discovery?.stopDiscovery()
    }

    fun connectByIp(rawIp: String, onNotFound: (String) -> Unit = {}, onFound: (CastDevice) -> Unit = {}) {
        val disc = discovery ?: return
        scope.launch {
            val found = disc.probeHost(rawIp)
            if (found != null) {
                onFound(found)
            } else {
                onNotFound("No se encontró TV LG, Chromecast ni DLNA en «$rawIp».")
            }
        }
    }

    fun connectDevice(device: CastDevice, preferredMode: WebOsCastMode = WebOsCastMode.Auto) {
        val gen = sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        val prevTeardown = teardownJob
        teardownJob = null

        connectJob = scope.launch {
            val existingClient = activeClient
            val canReuse = existingClient != null && existingClient.canReuseConnection && activeDeviceId == device.id
            val connGen = if (canReuse) {
                connectionGeneration.get()
            } else {
                connectionGeneration.incrementAndGet()
            }
            if (!canReuse) {
                runCatching { existingClient?.stop() }
                disconnectCurrentClientOnly(closeClient = true)
            } else {
                releaseMediaLeasesOnly()
            }
            currentSource = null
            autoAdvancedFromEnded.set(false)

            _session.update { cur ->
                cur.copy(
                    connectedDevice = device,
                    isConnectingDevice = true,
                    status = CastPlaybackStatus.Idle,
                    mediaTitle = "",
                    activeSourceUri = null,
                    positionMs = 0L,
                    durationMs = 0L,
                    subtitles = emptyList(),
                    embeddedSubtitleLabels = emptyList(),
                    activeSubtitleIndex = null,
                    subtitleControlCapability = CastSubtitleControlCapability.Unsupported,
                    subtitlesEnabledOnTv = null,
                    audioTracks = emptyList(),
                    activeAudioIndex = null,
                    audioControlCapability = CastAudioControlCapability.None,
                    playlist = emptyList(),
                    currentPlaylistIndex = 0,
                    activeWebOsMode = preferredMode,
                    canSwitchWebOsMode = device.protocol == CastProtocol.WebOs,
                    hasSsapControl = if (canReuse) cur.hasSsapControl else false,
                    hasCiniToTvApp = if (canReuse) (cur.hasCiniToTvApp || device.hasCiniToTvApp) else device.hasCiniToTvApp,
                    activeModeLabel = "Conectando con ${device.name}…",
                    pairingPromptDeviceName = null,
                    errorMessage = null,
                )
            }
            prevTeardown?.cancel()

            val idleSession = withContext(ioDispatcher) {
                mediaServer.prepareIdle(device.name)
            }
            if (sessionGeneration.get() != gen) return@launch

            val client = if (canReuse) {
                (existingClient as? WebOsCastClient)?.setPreferredMode(preferredMode)
                existingClient
            } else {
                createClientFor(device, preferredMode, connGen)
            }
            activeClient = client
            activeDeviceId = device.id

            var failError: String? = null
            val ok = try {
                client.connectIdle(device, idleSession?.baseUrl) { update ->
                    if (connectionGeneration.get() == connGen) handleRemoteUpdate(update)
                }
            } catch (ce: CancellationException) {
                if (activeClient === client) {
                    disconnectCurrentClientOnly(closeClient = true)
                } else {
                    scope.launch(ioDispatcher + NonCancellable) { runCatching { client.close() } }
                }
                throw ce
            } catch (err: Throwable) {
                CastLog.e("Manager", "connectDevice(${device.name}) failed", err)
                failError = err.message ?: "No se pudo conectar con ${device.name}"
                false
            }
            if (sessionGeneration.get() != gen) return@launch

            if (!ok) {
                if (activeClient === client) {
                    disconnectCurrentClientOnly(closeClient = true)
                } else {
                    scope.launch(ioDispatcher + NonCancellable) { runCatching { client.close() } }
                }
                val stopToken = mediaServer.currentSessionToken()
                if (stopToken.isNotEmpty()) {
                    mediaServer.stop(stopToken)
                }
                _session.update { cur ->
                    CastSessionState(
                        isDiscovering = cur.isDiscovering,
                        devices = cur.devices,
                        status = CastPlaybackStatus.Error,
                        errorMessage = failError ?: "No se pudo conectar con ${device.name}.",
                    )
                }
                return@launch
            }

            _session.update { cur ->
                val resolvedModeLabel = when {
                    cur.activeModeLabel.isNullOrBlank() || cur.activeModeLabel.startsWith("Conectando") ->
                        "${device.protocol.label} · Listo"
                    else -> cur.activeModeLabel
                }
                cur.copy(
                    isConnectingDevice = false,
                    status = CastPlaybackStatus.Idle,
                    activeModeLabel = resolvedModeLabel,
                    lanUrl = idleSession?.baseUrl ?: cur.lanUrl,
                )
            }
        }
    }

    fun castMedia(device: CastDevice, media: CastMediaSource) {
        val gen = sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        val prevTeardown = teardownJob
        teardownJob = null

        connectJob = scope.launch {
            val existingClient = activeClient
            val sameDevice = activeDeviceId == device.id
            val canReuse = existingClient != null &&
                existingClient.canReuseConnection &&
                sameDevice &&
                (device.protocol != CastProtocol.WebOs ||
                    media.preferredWebOsMode == WebOsCastMode.Auto ||
                    media.preferredWebOsMode == _session.value.activeWebOsMode)
            val connGen = if (canReuse) {
                connectionGeneration.get()
            } else {
                connectionGeneration.incrementAndGet()
            }

            if (!canReuse) {
                runCatching { existingClient?.stop() }
                mediaServer.clearMediaKeepSession(device.name)
                disconnectCurrentClientOnly(closeClient = true)
            } else {
                releaseMediaLeasesOnly()
            }

            currentSource = media
            autoAdvancedFromEnded.set(false)

            val prefs = preferencesProvider()
            val defaultAudioIdx = media.initialAudioIndex?.takeIf { idx ->
                media.audioTracks.any { it.index == idx }
            } ?: media.audioTracks.firstOrNull()?.index

            _session.update { cur ->
                cur.copy(
                    connectedDevice = device,
                    isConnectingDevice = false,
                    status = CastPlaybackStatus.Connecting,
                    mediaTitle = media.title,
                    activeSourceUri = media.sourceUri,
                    positionMs = media.positionMs,
                    durationMs = media.durationMs,
                    embeddedSubtitleLabels = media.embeddedSubtitleLabels,
                    subtitleControlCapability = CastSubtitleControlCapability.Unsupported,
                    subtitlesEnabledOnTv = null,
                    audioTracks = media.audioTracks,
                    activeAudioIndex = defaultAudioIdx,
                    audioControlCapability = computeAudioCapability(
                        device = device,
                        webOsMode = media.preferredWebOsMode,
                        trackCount = media.audioTracks.size,
                    ),
                    playlist = media.playlist,
                    currentPlaylistIndex = media.currentPlaylistIndex,
                    activeWebOsMode = media.preferredWebOsMode,
                    canSwitchWebOsMode = device.protocol == CastProtocol.WebOs,
                    hasSsapControl = if (canReuse) cur.hasSsapControl else false,
                    hasCiniToTvApp = if (canReuse) (cur.hasCiniToTvApp || device.hasCiniToTvApp) else device.hasCiniToTvApp,
                    activeModeLabel = if (canReuse) cur.activeModeLabel else device.protocol.label,
                    pairingPromptDeviceName = null,
                    errorMessage = null,
                )
            }
            prevTeardown?.cancel()

            val textEmbeddedSpecs = media.embeddedSubtitles.filter { it.isTextBased }
            val extractedFiles = if (media.isSourceFileComplete && textEmbeddedSpecs.isNotEmpty()) {
                withContext(ioDispatcher) {
                    withTimeoutOrNull(12_000L) {
                        runCatching {
                            MkvSubtitleExtractor.extractFromSource(
                                context = context,
                                sourceUri = media.sourceUri,
                                targetTracks = textEmbeddedSpecs,
                                cacheDir = context?.cacheDir,
                            )
                        }.onFailure { err ->
                            CastLog.w("Manager", "Embedded MKV subtitle extraction failed", err)
                        }.getOrDefault(emptyMap())
                    } ?: emptyMap()
                }
            } else {
                emptyMap()
            }
            if (sessionGeneration.get() != gen) return@launch

            val extractedItems = textEmbeddedSpecs.mapNotNull { spec ->
                val file = extractedFiles[spec.trackNumber] ?: return@mapNotNull null
                val langSuffix = spec.language
                    ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                    ?.let { " [$it]" }
                    .orEmpty()
                PlaybackItem(
                    uri = file.toURI().toString(),
                    name = "${spec.label}$langSuffix.vtt",
                )
            }
            val combinedSubtitles = media.subtitles + extractedItems
            val subtitleKinds = buildMap {
                media.subtitles.indices.forEach { idx -> put(idx, CastSubtitleKind.ExternalSidecar) }
                extractedItems.indices.forEach { offset ->
                    put(media.subtitles.size + offset, CastSubtitleKind.ExtractedEmbedded)
                }
            }

            val defaultSubIdx = when {
                media.preserveInitialSubtitle -> media.initialSubtitleIndex?.takeIf { it in combinedSubtitles.indices }
                media.initialSubtitleIndex != null -> media.initialSubtitleIndex.takeIf { it in combinedSubtitles.indices }
                !prefs.subtitlesEnabledByDefault || combinedSubtitles.isEmpty() -> null
                else -> {
                    val matchedIdx = combinedSubtitles.indexOfFirst { item ->
                        val inferred = TrackLanguageFormatter.inferLanguageFromFilename(item.name)
                        inferred != null && TrackLanguageFormatter.matchesLanguage(prefs.preferredSubtitleLanguage, inferred)
                    }
                    if (matchedIdx >= 0) matchedIdx else 0
                }
            }

            val published = withContext(ioDispatcher) {
                mediaServer.publish(
                    sourceUri = media.sourceUri,
                    mimeType = media.mimeType,
                    title = media.title,
                    positionMs = media.positionMs,
                    subtitles = combinedSubtitles,
                    activeSubtitleIndex = defaultSubIdx,
                    subtitleStyle = media.subtitleStyle,
                    deviceName = device.name,
                    audioTracks = media.audioTracks,
                    activeAudioIndex = defaultAudioIdx,
                    subtitleKinds = subtitleKinds,
                )
            }
            if (sessionGeneration.get() != gen) return@launch
            if (published == null) {
                _session.update {
                    it.copy(
                        connectedDevice = device,
                        status = CastPlaybackStatus.Error,
                        activeModeLabel = "Sin red Wi-Fi",
                        errorMessage = "Conecta el teléfono a la red Wi-Fi para transmitir al TV.",
                    )
                }
                return@launch
            }

            activeLease = media.leaseKey?.let { playbackLeases.acquire(it) }
            activeBookmarkUri = media.bookmarkUri

            val client: CastSessionClient = if (canReuse) {
                (existingClient as? WebOsCastClient)?.setPreferredMode(media.preferredWebOsMode)
                existingClient
            } else {
                createClientFor(device, media.preferredWebOsMode, connGen)
            }
            activeClient = client
            activeDeviceId = device.id

            val initialSubtitleCap = computeSubtitleCapability(
                device = device,
                webOsMode = media.preferredWebOsMode,
                hasExternalSubtitles = published.subtitles.isNotEmpty(),
            )

            _session.update {
                it.copy(
                    subtitles = published.subtitles,
                    activeSubtitleIndex = defaultSubIdx,
                    subtitleControlCapability = initialSubtitleCap,
                    subtitlesEnabledOnTv = null,
                    lanUrl = published.mediaUrl,
                )
            }

            try {
                CastLog.i("Manager", "castMedia device=${device.name}(${device.protocol}) title=${media.title} subs=${published.subtitles.size} audio=${media.audioTracks.size} defSub=$defaultSubIdx defAud=$defaultAudioIdx")
                client.connectAndLoad(
                    device = device,
                    mediaUrl = published.mediaUrl,
                    mimeType = media.mimeType,
                    title = media.title,
                    positionMs = media.positionMs,
                    subtitles = published.subtitles,
                    audioTracks = media.audioTracks,
                    initialAudioIndex = defaultAudioIdx,
                    initialSubtitleIndex = defaultSubIdx,
                    subtitleStyle = media.subtitleStyle,
                ) { update ->
                    if (connectionGeneration.get() == connGen) {
                        handleRemoteUpdate(update)
                    }
                }
            } catch (ce: CancellationException) {
                if (activeClient === client) {
                    disconnectCurrentClientOnly(closeClient = true)
                } else {
                    scope.launch(ioDispatcher + NonCancellable) { runCatching { client.close() } }
                }
                throw ce
            } catch (err: Throwable) {
                CastLog.e("Manager", "castMedia(${device.name}) failed", err)
                if (sessionGeneration.get() != gen) return@launch
                if (activeClient === client) {
                    releaseMediaLeasesOnly()
                }
                mediaServer.clearMediaKeepSession(device.name)
                _session.update {
                    it.copy(
                        connectedDevice = device,
                        pairingPromptDeviceName = null,
                        activeModeLabel = "Error de conexión",
                        status = CastPlaybackStatus.Error,
                        errorMessage = err.message ?: "No se pudo conectar con ${device.name}",
                    )
                }
            }
        }
    }

    private fun createClientFor(
        device: CastDevice,
        preferredMode: WebOsCastMode,
        connGen: Long,
    ): CastSessionClient {
        val onModeResolved: (WebOsCastMode, String, String?) -> Unit = { resolvedMode, modeLabel, pairingName ->
            if (connectionGeneration.get() == connGen) {
                _session.update { cur ->
                    cur.copy(
                        activeWebOsMode = resolvedMode,
                        activeModeLabel = modeLabel,
                        pairingPromptDeviceName = pairingName,
                        audioControlCapability = computeAudioCapability(
                            device = device,
                            webOsMode = resolvedMode,
                            trackCount = cur.audioTracks.size,
                        ),
                    )
                }
            }
        }
        clientFactory?.let { factory ->
            return factory(device, preferredMode, onModeResolved)
        }
        return when (device.protocol) {
            CastProtocol.GoogleCast -> GoogleCastClient()
            CastProtocol.Dlna -> DlnaCastClient()
            CastProtocol.WebOs -> WebOsCastClient(
                mediaServer = mediaServer,
                ssapKeyStore = ssapKeyStore,
                initialPreferredMode = preferredMode,
                onModeResolved = onModeResolved,
            )
        }
    }

    private fun computeAudioCapability(
        device: CastDevice,
        webOsMode: WebOsCastMode,
        trackCount: Int,
    ): CastAudioControlCapability {
        if (trackCount == 1) return CastAudioControlCapability.SingleTrack
        return when (device.protocol) {
            CastProtocol.GoogleCast -> CastAudioControlCapability.NativeSwitch
            CastProtocol.Dlna -> CastAudioControlCapability.AssistedRemoteMenu
            CastProtocol.WebOs -> when (webOsMode) {
                WebOsCastMode.Dlna -> CastAudioControlCapability.AssistedRemoteMenu
                WebOsCastMode.ReceiverApp, WebOsCastMode.Browser, WebOsCastMode.Auto ->
                    if (trackCount == 0 && !device.hasCiniToTvApp && device.hasDlna) {
                        CastAudioControlCapability.AssistedRemoteMenu
                    } else {
                        CastAudioControlCapability.NativeSwitch
                    }
            }
        }
    }

    private fun computeSubtitleCapability(
        device: CastDevice,
        webOsMode: WebOsCastMode,
        hasExternalSubtitles: Boolean,
    ): CastSubtitleControlCapability = when (device.protocol) {
        CastProtocol.GoogleCast -> if (hasExternalSubtitles) {
            CastSubtitleControlCapability.ExternalTrackSelect
        } else {
            CastSubtitleControlCapability.Unsupported
        }
        CastProtocol.Dlna -> if (hasExternalSubtitles) {
            CastSubtitleControlCapability.MixedExternalAndNative
        } else if (device.renderingControlUrl != null) {
            CastSubtitleControlCapability.DlnaNativeToggle
        } else {
            CastSubtitleControlCapability.Unsupported
        }
        CastProtocol.WebOs -> when (webOsMode) {
            WebOsCastMode.Dlna -> if (hasExternalSubtitles) {
                CastSubtitleControlCapability.MixedExternalAndNative
            } else {
                CastSubtitleControlCapability.DlnaNativeToggle
            }
            WebOsCastMode.ReceiverApp, WebOsCastMode.Browser, WebOsCastMode.Auto -> if (hasExternalSubtitles) {
                CastSubtitleControlCapability.ExternalTrackSelect
            } else {
                CastSubtitleControlCapability.Unsupported
            }
        }
    }

    fun switchWebOsMode(mode: WebOsCastMode) {
        val dev = _session.value.connectedDevice ?: return
        if (dev.protocol != CastProtocol.WebOs) return
        val src = currentSource
        if (src == null) {
            connectDevice(dev, preferredMode = mode)
            return
        }
        castMedia(
            device = dev,
            media = src.copy(
                positionMs = _session.value.positionMs,
                preferredWebOsMode = mode,
                initialSubtitleIndex = _session.value.activeSubtitleIndex,
                initialAudioIndex = _session.value.activeAudioIndex,
                preserveInitialSubtitle = true,
            ),
        )
    }

    fun retryCast() {
        val dev = _session.value.connectedDevice ?: return
        val src = currentSource ?: return connectDevice(dev, _session.value.activeWebOsMode)
        castMedia(
            device = dev,
            media = src.copy(
                positionMs = _session.value.positionMs,
                initialSubtitleIndex = _session.value.activeSubtitleIndex,
                initialAudioIndex = _session.value.activeAudioIndex,
                preserveInitialSubtitle = true,
            ),
        )
    }

    fun togglePlayPause() {
        val current = _session.value.status
        scope.launch {
            runCatching {
                if (current == CastPlaybackStatus.Playing) activeClient?.pause() else activeClient?.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val targetMs = positionMs.coerceAtLeast(0L)
        _session.update { it.copy(positionMs = targetMs) }
        scope.launch {
            runCatching { activeClient?.seekTo(targetMs) }
        }
    }

    fun seekBy(deltaMs: Long) = seekTo(_session.value.positionMs + deltaMs)

    fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        _session.update { it.copy(volume = clamped) }
        scope.launch { runCatching { activeClient?.setVolume(clamped) } }
    }

    fun setMuted(muted: Boolean) {
        _session.update { it.copy(isMuted = muted) }
        scope.launch { runCatching { activeClient?.setMuted(muted) } }
    }

    fun selectSubtitleIndex(index: Int?) {
        CastLog.i("Manager", "selectSubtitleIndex=$index")
        val previousIndex = _session.value.activeSubtitleIndex
        mediaServer.updateSubtitleSelection(index)
        _session.update { it.copy(activeSubtitleIndex = index) }
        scope.launch {
            runCatching { activeClient?.selectSubtitleIndex(index) }
                .onFailure { err ->
                    CastLog.w("Manager", "selectSubtitleIndex($index) failed", err)
                    mediaServer.updateSubtitleSelection(previousIndex)
                    _session.update {
                        it.copy(
                            activeSubtitleIndex = previousIndex,
                            errorMessage = err.message ?: "No se pudo cambiar el subtítulo.",
                        )
                    }
                }
        }
    }

    fun setTvSubtitlesEnabled(enabled: Boolean) {
        val prev = _session.value.subtitlesEnabledOnTv
        val client = activeClient ?: run {
            _session.update {
                it.copy(
                    subtitlesEnabledOnTv = prev,
                    errorMessage = "No hay una sesión de TV activa.",
                )
            }
            return
        }
        val expectedConnGen = connectionGeneration.get()
        _session.update { it.copy(subtitlesEnabledOnTv = enabled) }
        scope.launch(ioDispatcher) {
            val result = runCatching { client.setNativeSubtitlesEnabled(enabled) }
            if (connectionGeneration.get() != expectedConnGen) return@launch
            val ok = result.getOrDefault(false)
            if (!ok) {
                val err = result.exceptionOrNull()
                if (err != null) {
                    CastLog.w("Manager", "setTvSubtitlesEnabled($enabled) failed", err)
                } else {
                    CastLog.w("Manager", "setTvSubtitlesEnabled($enabled) returned false")
                }
                _session.update { cur ->
                    cur.copy(
                        subtitlesEnabledOnTv = prev,
                        errorMessage = err?.message ?: "El TV no permite cambiar los subtítulos desde la app.",
                    )
                }
            }
        }
    }

    fun addLocalSubtitle(item: PlaybackItem) {
        val addedTrack = mediaServer.addLocalSubtitle(item) ?: return
        currentSource = currentSource?.let { src ->
            src.copy(subtitles = src.subtitles + item)
        }
        _session.update { cur ->
            val updated = cur.subtitles.filterNot { it.index == addedTrack.index } + addedTrack
            val isDlna = cur.connectedDevice?.protocol == CastProtocol.Dlna ||
                (cur.connectedDevice?.protocol == CastProtocol.WebOs && cur.activeWebOsMode == WebOsCastMode.Dlna)
            val nextSubCap = if (isDlna) {
                CastSubtitleControlCapability.MixedExternalAndNative
            } else {
                CastSubtitleControlCapability.ExternalTrackSelect
            }
            cur.copy(
                subtitles = updated.sortedBy { it.index },
                subtitleControlCapability = nextSubCap,
            )
        }
        selectSubtitleIndex(addedTrack.index)
    }

    fun selectAudioIndex(index: Int) {
        val snapshot = _session.value
        when (snapshot.audioControlCapability) {
            CastAudioControlCapability.SingleTrack,
            CastAudioControlCapability.None,
            CastAudioControlCapability.AssistedRemoteMenu -> {
                CastLog.i("Manager", "selectAudioIndex($index) ignored: capability=${snapshot.audioControlCapability}")
                return
            }
            CastAudioControlCapability.NativeSwitch -> Unit
        }
        CastLog.i("Manager", "selectAudioIndex=$index")
        val previousIndex = snapshot.activeAudioIndex
        _session.update { it.copy(activeAudioIndex = index) }
        scope.launch {
            runCatching { activeClient?.selectAudioTrack(index) }
                .onFailure { err ->
                    CastLog.w("Manager", "selectAudioIndex($index) failed", err)
                    _session.update {
                        it.copy(
                            activeAudioIndex = previousIndex,
                            errorMessage = err.message ?: "No se pudo cambiar la pista de audio.",
                        )
                    }
                }
        }
    }

    fun sendTvRemoteKey(key: WebOsRemoteKey) {
        scope.launch { runCatching { activeClient?.sendTvRemoteKey(key) } }
    }

    fun updateSubtitleStyle(style: SubtitleStyleConfig) {
        currentSource = currentSource?.copy(subtitleStyle = style)
        mediaServer.updateSubtitleStyle(style)
        scope.launch(ioDispatcher) {
            runCatching { activeClient?.updateSubtitleStyle(style) }
                .onFailure { CastLog.w("Manager", "updateSubtitleStyle failed", it) }
        }
    }

    /** Stops the currently playing video on TV while keeping the TV session connected in standby. */
    fun stopPlaybackKeepConnection(): Long {
        val dev = _session.value.connectedDevice ?: return 0L
        val stopGen = sessionGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = null

        val pos = _session.value.positionMs
        val dur = _session.value.durationMs
        activeBookmarkUri?.let { bUri -> onProgressBookmark(bUri, pos, dur) }

        val client = activeClient
        releaseMediaLeasesOnly()
        currentSource = null

        scope.launch(ioDispatcher) {
            runCatching { client?.stop() }
            if (client != null) delay(350L)
            if (sessionGeneration.get() == stopGen) {
                mediaServer.clearMediaKeepSession(dev.name)
            }
        }
        _session.update { cur ->
            cur.copy(
                status = CastPlaybackStatus.Idle,
                mediaTitle = "",
                activeSourceUri = null,
                positionMs = 0L,
                durationMs = 0L,
                subtitles = emptyList(),
                embeddedSubtitleLabels = emptyList(),
                activeSubtitleIndex = null,
                subtitleControlCapability = CastSubtitleControlCapability.Unsupported,
                subtitlesEnabledOnTv = null,
                audioTracks = emptyList(),
                activeAudioIndex = null,
                audioControlCapability = CastAudioControlCapability.None,
                playlist = emptyList(),
                currentPlaylistIndex = 0,
                errorMessage = null,
            )
        }
        return pos
    }

    /** Fully disconnects from the TV and stops the local HTTP bridge. */
    fun disconnectDevice(): Long {
        sessionGeneration.incrementAndGet()
        connectionGeneration.incrementAndGet()
        connectJob?.cancel()
        connectJob = null
        val prevTeardown = teardownJob

        val pos = _session.value.positionMs
        val dur = _session.value.durationMs
        activeBookmarkUri?.let { bUri -> onProgressBookmark(bUri, pos, dur) }

        val clientToStop = activeClient
        val stopToken = mediaServer.currentSessionToken()
        activeClient = null
        activeDeviceId = null
        releaseMediaLeasesOnly()
        currentSource = null
        _session.update {
            CastSessionState(
                isDiscovering = it.isDiscovering,
                devices = it.devices,
            )
        }
        prevTeardown?.cancel()

        teardownJob = scope.launch(ioDispatcher) {
            try {
                runCatching { clientToStop?.stop() }
                if (clientToStop != null) delay(500L)
            } finally {
                withContext(NonCancellable) {
                    runCatching { clientToStop?.close() }
                    if (stopToken.isNotEmpty() && _session.value.connectedDevice == null) {
                        mediaServer.stop(stopToken)
                    }
                }
            }
        }
        return pos
    }

    fun stopCasting(): Long = disconnectDevice()

    fun clearError() {
        _session.update { it.copy(errorMessage = null) }
    }

    fun shutdown() {
        stopDiscovery()
        disconnectDevice()
    }

    private fun handleRemoteUpdate(update: CastRemoteUpdate) {
        if (update.errorMessage != null || update.status == CastPlaybackStatus.Error || update.ended) {
            CastLog.w("Manager", "remoteUpdate status=${update.status} ended=${update.ended} err=${update.errorMessage}")
        }
        _session.update { current ->
            val mediaActive = current.activeSourceUri != null
            val nextStatus = if (mediaActive) (update.status ?: current.status) else current.status
            val nextPos = when {
                !mediaActive -> current.positionMs
                update.status == CastPlaybackStatus.Error -> current.positionMs
                update.status != null && update.positionMs >= 0L -> update.positionMs
                update.positionMs > 0L -> update.positionMs
                else -> current.positionMs
            }
            val nextDur = if (mediaActive && update.durationMs > 0L) update.durationMs else current.durationMs
            val incomingTracks = update.audioTracksUpdate
            val nextTracks = if (
                mediaActive &&
                incomingTracks != null &&
                !(incomingTracks.size <= 1 && current.audioTracks.size > 1)
            ) {
                incomingTracks
            } else {
                current.audioTracks
            }
            val nextHasTvApp = update.hasCiniToTvApp ?: current.hasCiniToTvApp
            val nextCapability = when {
                mediaActive && update.audioCapabilityOverride != null -> update.audioCapabilityOverride
                mediaActive && incomingTracks != null && incomingTracks.size > 1 -> CastAudioControlCapability.NativeSwitch
                else -> current.audioControlCapability
            }
            val nextSubtitlesEnabledOnTv = if (mediaActive) {
                update.subtitleEnabledOnTv ?: current.subtitlesEnabledOnTv
            } else {
                current.subtitlesEnabledOnTv
            }
            val nextSubtitleCapability = if (mediaActive) {
                update.subtitleCapabilityOverride ?: current.subtitleControlCapability
            } else {
                current.subtitleControlCapability
            }
            val nextError = when {
                !mediaActive -> current.errorMessage
                nextStatus == CastPlaybackStatus.Playing || nextStatus == CastPlaybackStatus.Buffering -> null
                update.errorMessage != null -> update.errorMessage
                else -> current.errorMessage
            }
            current.copy(
                status = nextStatus,
                positionMs = nextPos,
                durationMs = nextDur,
                volume = update.volume ?: current.volume,
                isMuted = update.isMuted ?: current.isMuted,
                activeSubtitleIndex = if (mediaActive && update.hasSubtitleOverride) update.subtitleOverrideIndex else current.activeSubtitleIndex,
                subtitleControlCapability = nextSubtitleCapability,
                subtitlesEnabledOnTv = nextSubtitlesEnabledOnTv,
                activeAudioIndex = if (mediaActive && update.hasAudioOverride) update.audioOverrideIndex else current.activeAudioIndex,
                audioTracks = nextTracks,
                audioControlCapability = nextCapability,
                hasSsapControl = update.ssapConnected ?: current.hasSsapControl,
                hasCiniToTvApp = nextHasTvApp,
                errorMessage = nextError,
            )
        }
        val snapshot = _session.value
        if (snapshot.activeSourceUri == null) return
        val now = System.currentTimeMillis()
        if (now - lastPersistedAtMs >= 3_000L && update.positionMs > 0L) {
            lastPersistedAtMs = now
            activeBookmarkUri?.let { uri ->
                onProgressBookmark(uri, update.positionMs, update.durationMs)
            }
        }
        if (update.ended && snapshot.hasNextEpisode && autoAdvancedFromEnded.compareAndSet(false, true)) {
            val nextIdx = snapshot.currentPlaylistIndex + 1
            _autoNextEpisodeRequests.tryEmit(nextIdx)
            onRequestNextEpisode(nextIdx)
        }
    }

    private fun releaseMediaLeasesOnly() {
        activeLease?.close()
        activeLease = null
        activeBookmarkUri = null
    }

    private fun disconnectCurrentClientOnly(closeClient: Boolean) {
        if (closeClient) {
            val clientToClose = activeClient
            activeClient = null
            activeDeviceId = null
            if (clientToClose != null) {
                scope.launch(ioDispatcher + NonCancellable) {
                    runCatching { clientToClose.close() }
                }
            }
        }
        releaseMediaLeasesOnly()
    }
}
