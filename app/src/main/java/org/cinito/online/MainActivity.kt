package org.cinito.online

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.cinito.online.cast.CastAudioTrack
import org.cinito.online.cast.CastDevice
import org.cinito.online.cast.CastMediaSource
import org.cinito.online.cast.CastRoutingHelper
import org.cinito.online.cast.CastSessionState
import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.EnginePreviewByteSource
import org.cinito.online.metadata.MediaContainerProbe
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.player.CinemaPlaybackRequest
import org.cinito.online.player.CinemaPlayerController
import org.cinito.online.player.CinemaPlayerState
import org.cinito.online.player.CinemaPlayerUiLogic
import org.cinito.online.player.SubtitleSource
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchUtils
import org.cinito.online.search.SwarmDiagnosticsCoordinator
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.settings.AppPreferences
import org.cinito.online.settings.SubtitleStyleConfig
import org.cinito.online.ui.discover.DiscoverScreen
import org.cinito.online.ui.explore.CatalogDetailsScreen
import org.cinito.online.ui.explore.ChooseFilesOverlay
import org.cinito.online.ui.explore.ExploreCoordinator
import org.cinito.online.ui.explore.QuickStreamSheet
import org.cinito.online.ui.library.GalleryLibraryScreen
import org.cinito.online.ui.library.LibraryGalleryBuilder
import org.cinito.online.ui.library.MediaDetailsScreen
import org.cinito.online.ui.player.CastDeviceSheetContent
import org.cinito.online.ui.player.CastMiniControlBar
import org.cinito.online.ui.player.CastRemoteOverlay
import org.cinito.online.ui.player.CinemaPlayerScreen
import org.cinito.online.ui.search.SearchScreen
import org.cinito.online.ui.settings.CineSettingsScreen
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.CiniToTheme
import webtor.app.AddSheet
import webtor.app.ClearAllDialog
import webtor.app.DeleteDialog
import webtor.app.DownloadEntry
import webtor.app.LibraryFilter
import webtor.app.LibrarySession
import webtor.app.LibrarySort
import webtor.app.PlaybackItem
import webtor.app.PlaybackProvider
import webtor.app.PrepareMode
import webtor.app.PrepareScreen
import webtor.app.Screen
import webtor.app.SettingsScreen
import webtor.app.TorrentFilesScreen
import webtor.app.UiEvent
import webtor.app.UiState
import webtor.app.WebtorApp
import webtor.app.applyFilter
import webtor.app.extractTorrentLink
import webtor.app.filterLibrary
import webtor.app.findMatchingEpisodeFileIndex
import webtor.app.hasMultipleVideos
import webtor.app.infoHashFromMagnet
import webtor.app.isTorrentFileSource
import webtor.app.mimeFor
import webtor.app.playbackFiles
import webtor.app.prefetchStatus
import webtor.app.returnedPlaybackUri
import webtor.app.stableLibrarySort
import webtor.app.torrentFileName

private enum class MainTab(val label: String) {
    Library("Biblioteca"),
    Discover("Descubrir"),
    Search("Buscar"),
    Settings("Ajustes"),
}

class CineViewModel(application: Application) : AndroidViewModel(application) {
    val app: WebtorApp = application as WebtorApp
    val session: LibrarySession = app.session

    private val _activeEvent = MutableStateFlow<UiEvent.OpenContent?>(null)
    val activePlaybackEvent: StateFlow<UiEvent.OpenContent?> = _activeEvent.asStateFlow()

    private val _playerMinimized = MutableStateFlow(false)
    val playerMinimized: StateFlow<Boolean> = _playerMinimized.asStateFlow()

    private val _libraryMetadata = MutableStateFlow<Map<String, MediaMetadata>>(emptyMap())
    val libraryMetadata: StateFlow<Map<String, MediaMetadata>> = _libraryMetadata.asStateFlow()
    private val _openTvRemoteRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val openTvRemoteRequests: SharedFlow<Unit> = _openTvRemoteRequests.asSharedFlow()
    private var castEpisodeSwitchJob: kotlinx.coroutines.Job? = null

    private val previewProbeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "swarm-preview-probe").apply { isDaemon = true }
    }

    private val swarmDiagnostics: SwarmDiagnosticsCoordinator = SwarmDiagnosticsCoordinator(
        engineClient = app.engineClient,
        activeEngineIdByHash = { hash ->
            val uiSnap = app.session.ui.value
            val inLibrary = uiSnap.library.firstOrNull { entry ->
                entry.key.equals(hash, ignoreCase = true) ||
                    entry.status?.infoHash?.equals(hash, ignoreCase = true) == true
            }?.engineId
            inLibrary ?: uiSnap.prepare?.takeIf { draft ->
                infoHashFromMagnet(draft.source).equals(hash, ignoreCase = true) ||
                    draft.torrent?.infoHash?.equals(hash, ignoreCase = true) == true
            }?.engineId
        },
        containerProbeFn = { engineId, fileIndex, fileLength ->
            withTimeoutOrNull(2_500L) {
                suspendCancellableCoroutine { cont ->
                    val source = EnginePreviewByteSource(app.engine, engineId, fileIndex, fileLength)
                    val closed = java.util.concurrent.atomic.AtomicBoolean(false)
                    val closeSource = {
                        if (closed.compareAndSet(false, true)) {
                            runCatching { source.close() }
                        }
                    }
                    val future = previewProbeExecutor.submit {
                        val probed = try {
                            if (source.isOpen) MediaContainerProbe.probe(source) else null
                        } finally {
                            closeSource()
                        }
                        if (cont.isActive) {
                            cont.resumeWith(Result.success(probed))
                        }
                    }
                    cont.invokeOnCancellation {
                        closeSource()
                        future.cancel(true)
                    }
                }
            }
        },
        canUseNetwork = { app.isAppNetworkAllowed(null) },
    )

    val explore: ExploreCoordinator = ExploreCoordinator(
        scope = viewModelScope,
        metadataRepository = app.metadataRepository,
        searchService = app.searchService,
        preferencesFlow = app.appSettings.preferences,
        torznabFlow = app.torznabStore.configs,
        onRecordRecentSearch = app.appSettings::addRecentSearchQuery,
        udpScraper = org.cinito.online.search.UdpTrackerScraper(
            dnsResolver = { host ->
                app.networkClient.lookupHost(host).firstOrNull() ?: java.net.InetAddress.getByName(host)
            },
            canUseNetwork = { app.isAppNetworkAllowed(null) },
        ),
        swarmDiagnostics = swarmDiagnostics,
        canAutoActiveProbe = {
            val net = app.latestNetworkSnapshot
            app.isAppNetworkAllowed(null) && net.wifi && !net.metered && !app.session.ui.value.batteryPaused
        },
    )

    val playerController: CinemaPlayerController = CinemaPlayerController(
        context = application,
        onProgressBookmark = { mediaUri, posMs, durMs ->
            val current = _activeEvent.value
            val bookmarkUri = current?.bookmarkUris?.get(mediaUri) ?: mediaUri
            session.recordPlaybackPosition(bookmarkUri, posMs, durMs, allowAutoNext = false)
        },
    )

    fun startIntegratedPlayback(event: UiEvent.OpenContent) {
        if (app.castManager.session.value.isConnected) {
            castEventToConnectedTv(event, openRemote = true)
            return
        }
        _playerMinimized.value = false
        _activeEvent.value?.let { previous ->
            app.httpPlaybackLease.finish(previous.uri)
            session.finishPlayback(previous.bookmarkUris[previous.uri] ?: previous.uri)
        }
        _activeEvent.value = event
        event.playbackLeaseKey?.let { app.httpPlaybackLease.begin(event.uri, it) }
        playerController.load(
            request = CinemaPlaybackRequest(
                mediaUri = event.uri,
                title = event.name,
                mimeType = event.mime,
                playlist = event.playlist,
                sidecarSubtitles = event.subtitles,
                startPositionMs = event.positionMs,
                playbackLeaseKey = event.playbackLeaseKey,
            ),
            prefs = app.appSettings.preferences.value,
        )
    }

    fun castEventToConnectedTv(
        event: UiEvent.OpenContent,
        explicitDevice: CastDevice? = null,
        openRemote: Boolean = false,
        overridePositionMs: Long? = null,
    ) {
        val castSnapshot = app.castManager.session.value
        val dev = explicitDevice ?: castSnapshot.connectedDevice ?: return
        castEpisodeSwitchJob?.cancel()
        castEpisodeSwitchJob = null

        _playerMinimized.value = true
        _activeEvent.value?.let { previous ->
            if (previous.uri != event.uri) {
                app.httpPlaybackLease.finish(previous.uri)
                session.finishPlayback(previous.bookmarkUris[previous.uri] ?: previous.uri)
            }
        }
        _activeEvent.value = event
        event.playbackLeaseKey?.let { app.httpPlaybackLease.begin(event.uri, it) }

        val activeEntry = findMatchingEntry(session.ui.value.library, event)
        val playlistIdx = event.playlist.indexOfFirst { it.uri == event.uri }.takeIf { it >= 0 } ?: 0
        val prevPlayerState = playerController.state.value
        playerController.syncCastEpisodeState(
            title = event.name,
            mediaUri = event.uri,
            playlist = event.playlist,
            currentPlaylistIndex = playlistIdx,
            sidecarSubtitles = event.subtitles,
        )

        val prefs = app.appSettings.preferences.value
        val media = buildCastMediaForPlayback(
            probeRepo = app.audioProbeRepository,
            entry = activeEntry,
            title = event.name,
            sourceUri = event.uri,
            mimeType = mimeFor(event.name).ifBlank { event.mime.ifBlank { "video/mp4" } },
            positionMs = overridePositionMs ?: event.positionMs,
            durationMs = 0L,
            sidecarSubtitles = event.subtitles,
            playerState = prevPlayerState,
            castSnapshot = castSnapshot,
            targetDevice = dev,
            preferredAudioLanguage = prefs.preferredAudioLanguage,
            prioritizeLatino = prefs.searchLanguageFilter == SearchLanguageFilter.PrioritizeLatino,
            playlist = event.playlist,
            currentPlaylistIndex = playlistIdx,
            subtitleStyle = prefs.subtitleStyle,
            leaseKey = event.playbackLeaseKey,
            bookmarkUri = event.bookmarkUris[event.uri] ?: event.uri,
        )
        app.castManager.castMedia(device = dev, media = media)
        if (openRemote) {
            _openTvRemoteRequests.tryEmit(Unit)
        }
    }

    fun stopTvPlaybackOnly() {
        app.castManager.stopPlaybackKeepConnection()
        closeIntegratedPlayback(reportLocalPlayer = false)
    }

    fun disconnectTvAndClearPlayback() {
        app.castManager.disconnectDevice()
        closeIntegratedPlayback(reportLocalPlayer = false)
    }

    fun switchActiveCastToPhone(prefs: AppPreferences) {
        val resumePos = app.castManager.disconnectDevice()
        val current = _activeEvent.value ?: return
        _playerMinimized.value = false
        playerController.load(
            request = CinemaPlaybackRequest(
                mediaUri = current.uri,
                title = current.name,
                mimeType = current.mime,
                playlist = current.playlist,
                sidecarSubtitles = current.subtitles,
                startPositionMs = resumePos,
                playbackLeaseKey = current.playbackLeaseKey,
            ),
            prefs = prefs,
        )
    }

    fun minimizePlayer() {
        _playerMinimized.value = true
    }

    fun restorePlayer() {
        _playerMinimized.value = false
    }

    fun switchPlaylistIndex(index: Int, activeEntry: DownloadEntry?) {
        val currentEvent = _activeEvent.value ?: return
        val item = currentEvent.playlist.getOrNull(index) ?: return
        val targetBookmarkUri = currentEvent.bookmarkUris[item.uri] ?: item.uri
        val matchedFile = activeEntry?.files?.find {
            PlaybackProvider.uriFor(activeEntry, it).toString() == targetBookmarkUri
        } ?: activeEntry?.files?.find { it.name == item.name }

        if (activeEntry != null && matchedFile != null) {
            // Re-enter via LibrarySession.play so engine file_only priority, next-episode prewarm,
            // HTTP lease, subtitles, and bookmark URIs all transition atomically to the new episode.
            session.play(activeEntry, matchedFile.index)
            return
        }

        val targetMime = mimeFor(item.name)
        val sidecarSubs = if (activeEntry != null && matchedFile != null) {
            playbackFiles(activeEntry, matchedFile).subtitles.map { sub ->
                PlaybackItem(PlaybackProvider.uriFor(activeEntry, sub).toString(), sub.name)
            }
        } else emptyList()
        currentEvent.playbackLeaseKey?.let { app.httpPlaybackLease.begin(item.uri, it) }
        playerController.playPlaylistItem(
            index = index,
            sidecarProvider = { sidecarSubs },
            prefs = app.appSettings.preferences.value,
            startPositionMsProvider = { matchedFile?.playbackPositionMs ?: 0L },
            mimeTypeProvider = { targetMime },
        )
        _activeEvent.value = currentEvent.copy(
            uri = item.uri,
            mime = targetMime,
            name = item.name,
            subtitles = sidecarSubs,
            positionMs = matchedFile?.playbackPositionMs ?: 0L,
        )
    }

    fun castPlaylistIndex(
        index: Int,
        activeEntry: DownloadEntry?,
        subtitleStyle: SubtitleStyleConfig,
    ) {
        val dev = app.castManager.session.value.connectedDevice ?: return
        val currentEvent = _activeEvent.value
        val playlist = currentEvent?.playlist ?: app.castManager.session.value.playlist
        val item = playlist.getOrNull(index) ?: return
        val targetBookmarkUri = currentEvent?.bookmarkUris?.get(item.uri) ?: item.uri
        val matchedFile = activeEntry?.files?.find {
            PlaybackProvider.uriFor(activeEntry, it).toString() == targetBookmarkUri
        } ?: activeEntry?.files?.find { it.name == item.name }

        castEpisodeSwitchJob?.cancel()
        castEpisodeSwitchJob = viewModelScope.launch {
            val resolvedEvent: UiEvent.OpenContent? = if (activeEntry != null && matchedFile != null) {
                session.prepareEpisodeForCast(activeEntry, matchedFile.index) ?: return@launch
            } else null

            val resolvedUri = resolvedEvent?.uri ?: item.uri
            val resolvedTitle = resolvedEvent?.name ?: item.name
            val resolvedMime = mimeFor(resolvedTitle).ifBlank { resolvedEvent?.mime ?: "video/mp4" }
            val resolvedPlaylist = resolvedEvent?.playlist ?: playlist
            val resolvedSubs = resolvedEvent?.subtitles ?: emptyList()
            val startPosMs = resolvedEvent?.positionMs ?: (matchedFile?.playbackPositionMs ?: 0L)
            val leaseKey = resolvedEvent?.playbackLeaseKey ?: currentEvent?.playbackLeaseKey ?: activeEntry?.key
            val bookmarkUri = resolvedEvent?.bookmarkUris?.get(resolvedUri) ?: targetBookmarkUri

            if (currentEvent != null && currentEvent.uri != resolvedUri) {
                app.httpPlaybackLease.finish(currentEvent.uri)
            }
            leaseKey?.let { app.httpPlaybackLease.begin(resolvedUri, it) }

            val nextActiveEvent = resolvedEvent ?: currentEvent?.copy(
                uri = resolvedUri,
                mime = resolvedMime,
                name = resolvedTitle,
                subtitles = resolvedSubs,
                positionMs = startPosMs,
            )
            if (nextActiveEvent != null) {
                _activeEvent.value = nextActiveEvent
            }

            val prevPlayerState = playerController.state.value
            playerController.syncCastEpisodeState(
                title = resolvedTitle,
                mediaUri = resolvedUri,
                playlist = resolvedPlaylist,
                currentPlaylistIndex = index,
                sidecarSubtitles = resolvedSubs,
            )

            val prefs = app.appSettings.preferences.value
            val castSnapshot = app.castManager.session.value
            app.castManager.castMedia(
                device = dev,
                media = buildCastMediaForPlayback(
                    probeRepo = app.audioProbeRepository,
                    entry = activeEntry,
                    title = resolvedTitle,
                    sourceUri = resolvedUri,
                    mimeType = resolvedMime,
                    positionMs = startPosMs,
                    durationMs = 0L,
                    sidecarSubtitles = resolvedSubs,
                    playerState = prevPlayerState,
                    castSnapshot = castSnapshot,
                    targetDevice = dev,
                    preferredAudioLanguage = prefs.preferredAudioLanguage,
                    prioritizeLatino = prefs.searchLanguageFilter == SearchLanguageFilter.PrioritizeLatino,
                    playlist = resolvedPlaylist,
                    currentPlaylistIndex = index,
                    subtitleStyle = subtitleStyle,
                    leaseKey = leaseKey,
                    bookmarkUri = bookmarkUri,
                ),
            )
        }
    }

    fun closeIntegratedPlayback(reportLocalPlayer: Boolean = true) {
        castEpisodeSwitchJob?.cancel()
        castEpisodeSwitchJob = null
        val current = _activeEvent.value ?: return
        if (reportLocalPlayer) {
            playerController.stopAndReport()
        } else {
            playerController.stopWithoutReporting()
        }
        app.httpPlaybackLease.finish(current.uri)
        session.finishPlayback(current.bookmarkUris[current.uri] ?: current.uri)
        _playerMinimized.value = false
        _activeEvent.value = null
    }

    fun enrichLibraryMetadata(entries: List<DownloadEntry>, enabled: Boolean) {
        if (!enabled) return
        viewModelScope.launch {
            val missing = entries
                .filter { !webtor.app.isTorrentFileSource(it.source) }
                .map { MediaTitleParser.parse(it.title) }
                .filter { it.kind == org.cinito.online.metadata.MediaKind.MOVIE || it.kind == org.cinito.online.metadata.MediaKind.SERIES || it.kind == org.cinito.online.metadata.MediaKind.ANIME }
                .distinctBy { it.lookupKey }
                .filter { !_libraryMetadata.value.containsKey(it.lookupKey) }
            if (missing.isEmpty()) return@launch
            coroutineScope {
                val fetched = missing.map { parsed ->
                    async {
                        parsed.lookupKey to app.metadataRepository.resolveForParsedTitle(parsed)
                    }
                }.awaitAll()
                val additions = fetched.mapNotNull { (k, v) -> if (v != null) k to v else null }.toMap()
                if (additions.isNotEmpty()) {
                    _libraryMetadata.update { it + additions }
                }
            }
        }
    }

    override fun onCleared() {
        castEpisodeSwitchJob?.cancel()
        castEpisodeSwitchJob = null
        if (!app.castManager.session.value.isConnected) {
            closeIntegratedPlayback()
        }
        playerController.release()
        previewProbeExecutor.shutdownNow()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: CineViewModel by viewModels()
    private val app get() = vm.app
    private val session get() = vm.session

    private var launchedPlaybackUri: String? = null
    private var launchedPlaybackItems: Set<String> = emptySet()
    private var launchedBookmarkUris: Map<String, String> = emptyMap()

    private val externalViewer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        @Suppress("DEPRECATION")
        fun number(key: String): Long? = (data?.extras?.get(key) as? Number)?.toLong()
        val position = number("extra_position") ?: number("position")
        val uri = returnedPlaybackUri(data?.data?.toString(), launchedPlaybackUri, launchedPlaybackItems)
        if (position != null && uri != null) {
            session.recordPlaybackPosition(
                launchedBookmarkUris[uri] ?: uri,
                position,
                number("extra_duration") ?: number("duration") ?: 0L,
            )
        }
        app.httpPlaybackLease.finish(launchedPlaybackUri)
        session.finishPlayback(launchedBookmarkUris[launchedPlaybackUri] ?: launchedPlaybackUri)
        launchedPlaybackUri = null
        launchedPlaybackItems = emptySet()
        launchedBookmarkUris = emptyMap()
    }

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = resolveDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitulo.srt"
            if (app.castManager.session.value.isConnected) {
                app.castManager.addLocalSubtitle(PlaybackItem(uri = uri.toString(), name = name))
            } else {
                vm.playerController.attachLocalSubtitle(uri, name)
            }
        }
    }

    private fun launchSubtitlePicker() {
        if (!app.castManager.session.value.isConnected) {
            vm.playerController.pause()
        }
        subtitlePicker.launch(
            arrayOf(
                "application/x-subrip",
                "text/vtt",
                "text/plain",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    private val torrentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(session::openTorrent)
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { session.persistFolder(it) }
    }

    private var pendingRepairTarget: Pair<String, Int>? = null
    private val fileRepairPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pickedUri ->
            pendingRepairTarget?.let { (k, idx) -> session.repairFileUri(k, idx, pickedUri) }
        }
        pendingRepairTarget = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("playbackUri", launchedPlaybackUri)
        outState.putStringArrayList("playbackItems", ArrayList(launchedPlaybackItems))
        outState.putStringArrayList("bookmarkUrisFrom", ArrayList(launchedBookmarkUris.keys))
        outState.putStringArrayList("bookmarkUrisTo", ArrayList(launchedBookmarkUris.values))
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                IntentFilter().apply {
                    addAction(ACTION_PIP_PLAY_PAUSE)
                    addAction(ACTION_PIP_SEEK_BACK)
                    addAction(ACTION_PIP_SEEK_FORWARD)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            lifecycleScope.launch {
                vm.playerController.state
                    .map { Triple(it.isInPipMode, it.isPlaying, it.videoWidth to it.videoHeight) }
                    .distinctUntilChanged()
                    .collect { (inPip, _, _) ->
                        if (inPip) {
                            runCatching { setPictureInPictureParams(buildPipParams()) }
                        }
                    }
            }
        }
        launchedPlaybackUri = savedInstanceState?.getString("playbackUri")
        launchedPlaybackItems = savedInstanceState?.getStringArrayList("playbackItems")?.toSet().orEmpty()
        launchedBookmarkUris = savedInstanceState?.getStringArrayList("bookmarkUrisFrom").orEmpty()
            .zip(savedInstanceState?.getStringArrayList("bookmarkUrisTo").orEmpty()).toMap()

        lifecycleScope.launch {
            session.awaitInitialization()
            intent.getStringExtra("open_key")?.let { key ->
                if (session.ui.value.library.any { it.key == key }) {
                    session.openDetails(key)
                }
            }
        }
        if (savedInstanceState == null) handleIncoming(intent)

        setContent {
            val engineState by session.ui.collectAsState()
            val prefs by app.appSettings.preferences.collectAsState()
            val netSnapshot by app.networkSnapshotState.collectAsState()
            val torznabConfigs by app.torznabStore.configs.collectAsState()
            val metadataMap by vm.libraryMetadata.collectAsState()
            val discoverState by vm.explore.discover.collectAsState()
            val searchState by vm.explore.search.collectAsState()
            val activePlayback by vm.activePlaybackEvent.collectAsState()
            val playerMinimized by vm.playerMinimized.collectAsState()
            val castState by app.castManager.session.collectAsState()

            val mobileDataBlocked = netSnapshot.connected && !netSnapshot.wifi && !prefs.allowMobileData

            var currentTab by rememberSaveable { mutableStateOf(MainTab.Library) }
            var selectedDetailsKey by rememberSaveable { mutableStateOf<String?>(null) }
            var showAdvancedEngineSettings by rememberSaveable { mutableStateOf(false) }
            var showAllowMobileConfirmDialog by rememberSaveable { mutableStateOf(false) }
            var libraryFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
            var librarySort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
            var librarySearchQuery by rememberSaveable { mutableStateOf("") }
            var showGlobalCastDevices by rememberSaveable { mutableStateOf(false) }
            var showGlobalCastRemote by rememberSaveable { mutableStateOf(false) }

            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(vm) {
                vm.openTvRemoteRequests.collect {
                    showGlobalCastRemote = true
                }
            }
            LaunchedEffect(castState.isConnected) {
                if (!castState.isConnected) {
                    showGlobalCastRemote = false
                }
            }
            var globalDiscoveryStartedAtMs by remember { mutableLongStateOf(0L) }
            LaunchedEffect(showGlobalCastDevices) {
                if (showGlobalCastDevices) {
                    globalDiscoveryStartedAtMs = System.currentTimeMillis()
                    app.castManager.startDiscovery()
                } else if (globalDiscoveryStartedAtMs > 0L) {
                    val elapsed = System.currentTimeMillis() - globalDiscoveryStartedAtMs
                    val remaining = (2_500L - elapsed).coerceAtLeast(0L)
                    if (remaining > 0L) delay(remaining)
                    globalDiscoveryStartedAtMs = 0L
                    app.castManager.stopDiscovery()
                }
            }

            LaunchedEffect(engineState.library, prefs.metadataLookupEnabled) {
                vm.enrichLibraryMetadata(engineState.library, prefs.metadataLookupEnabled)
            }
            LaunchedEffect(currentTab, prefs.metadataLookupEnabled, netSnapshot.wifi, prefs.allowMobileData) {
                val netReady = netSnapshot.wifi || (netSnapshot.connected && prefs.allowMobileData)
                if (currentTab == MainTab.Discover && prefs.metadataLookupEnabled && netReady) {
                    vm.explore.loadDiscover(force = false)
                }
            }
            LaunchedEffect(engineState.pendingDetailsKey) {
                val pendingKey = engineState.pendingDetailsKey
                if (pendingKey != null) {
                    selectedDetailsKey = pendingKey
                    session.consumePendingDetails()
                }
            }
            LaunchedEffect(engineState.message) {
                val msg = engineState.message
                if (!msg.isNullOrBlank()) {
                    session.consumeMessage()
                    if (msg == "Descarga iniciada en Biblioteca" && currentTab != MainTab.Library) {
                        val res = snackbarHostState.showSnackbar(
                            message = msg,
                            actionLabel = "Ver",
                            duration = SnackbarDuration.Short,
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            vm.explore.closeCatalogMedia()
                            currentTab = MainTab.Library
                        }
                    } else {
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
            LaunchedEffect(
                engineState.error,
                engineState.addSheetOpen,
                engineState.prepareMode,
                engineState.screen,
            ) {
                val err = engineState.error
                val handledByModal = engineState.addSheetOpen ||
                    engineState.prepareMode != PrepareMode.AddSheet ||
                    engineState.screen == Screen.Files ||
                    engineState.screen == Screen.Prepare
                if (!err.isNullOrBlank() && !handledByModal) {
                    snackbarHostState.showSnackbar(err)
                    session.consumeError()
                }
            }

            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    session.events.collect { event -> handleEvent(event) }
                }
            }
            LaunchedEffect(app.castManager) {
                app.castManager.autoNextEpisodeRequests.collect { nextIdx ->
                    val playbackNow = vm.activePlaybackEvent.value
                    val entryNow = playbackNow?.let { findMatchingEntry(session.ui.value.library, it) }
                    vm.castPlaylistIndex(nextIdx, entryNow, app.appSettings.preferences.value.subtitleStyle)
                }
            }

            CiniToTheme {
                val saveableStateHolder = rememberSaveableStateHolder()
                val stopOrDisconnectCast: () -> Unit = {
                    if (castState.hasActiveMedia) {
                        vm.stopTvPlaybackOnly()
                    } else {
                        vm.disconnectTvAndClearPlayback()
                    }
                }
                val nextCastEpisodeFn: (() -> Unit)? = if (castState.hasNextEpisode) {
                    {
                        val entryNow = vm.activePlaybackEvent.value?.let { findMatchingEntry(engineState.library, it) }
                        vm.castPlaylistIndex(castState.currentPlaylistIndex + 1, entryNow, prefs.subtitleStyle)
                    }
                } else {
                    null
                }
                // Fullscreen integrated Cinema Video Player overlay
                val playback = activePlayback
                if (playback != null && !playerMinimized) {
                    val activeEntry = findMatchingEntry(engineState.library, playback)
                    fun castCurrentPlaybackTo(device: CastDevice) {
                        val activeNow = vm.activePlaybackEvent.value ?: playback
                        val playerState = vm.playerController.state.value
                        val resolvedTitle = activeNow.name.ifBlank { playerState.title }
                        val resolvedUri = activeNow.uri
                        val resolvedPlaylist = activeNow.playlist.ifEmpty { playerState.playlist }
                        val resolvedIdx = if (castState.isConnected && resolvedPlaylist.isNotEmpty()) {
                            resolvedPlaylist.indexOfFirst { it.uri == resolvedUri }.takeIf { it >= 0 }
                                ?: castState.currentPlaylistIndex
                        } else {
                            playerState.currentPlaylistIndex
                        }
                        val currentPos = if (castState.isConnected) castState.positionMs else playerState.positionMs
                        val currentDur = if (castState.isConnected && castState.durationMs > 0L) castState.durationMs else playerState.durationMs
                        val castSnapshot = app.castManager.session.value
                        val media = buildCastMediaForPlayback(
                            probeRepo = app.audioProbeRepository,
                            entry = activeEntry,
                            title = resolvedTitle,
                            sourceUri = resolvedUri,
                            mimeType = mimeFor(resolvedTitle).ifBlank { activeNow.mime.ifBlank { "video/mp4" } },
                            positionMs = currentPos,
                            durationMs = currentDur,
                            sidecarSubtitles = activeNow.subtitles,
                            playerState = playerState,
                            castSnapshot = castSnapshot,
                            targetDevice = device,
                            preferredAudioLanguage = prefs.preferredAudioLanguage,
                            prioritizeLatino = prefs.searchLanguageFilter == SearchLanguageFilter.PrioritizeLatino,
                            playlist = resolvedPlaylist,
                            currentPlaylistIndex = resolvedIdx,
                            subtitleStyle = playerState.subtitleStyle,
                            leaseKey = activeNow.playbackLeaseKey,
                            bookmarkUri = activeNow.bookmarkUris[resolvedUri] ?: resolvedUri,
                        )
                        vm.playerController.pause()
                        app.castManager.castMedia(device = device, media = media)
                    }
                    CinemaPlayerScreen(
                        controller = vm.playerController,
                        activeEntry = activeEntry,
                        castState = castState,
                        bookmarkUris = playback.bookmarkUris,
                        showRawFilenames = prefs.showRawFilenames,
                        onUpdateSubtitleStyle = { style ->
                            app.appSettings.updateSubtitleStyle(style)
                            vm.playerController.updateSubtitleStyle(style)
                            app.castManager.updateSubtitleStyle(style)
                        },
                        onBack = {
                            if (castState.isConnected) vm.minimizePlayer()
                            else vm.closeIntegratedPlayback()
                        },
                        onRequestPip = ::enterPipIfSupported,
                        onPickLocalSubtitle = ::launchSubtitlePicker,
                        onSelectPlaylistIndex = { idx ->
                            if (castState.isConnected) {
                                vm.castPlaylistIndex(idx, activeEntry, prefs.subtitleStyle)
                            } else {
                                vm.switchPlaylistIndex(idx, activeEntry)
                            }
                        },
                        onStartCastDiscovery = app.castManager::startDiscovery,
                        onRefreshCastDiscovery = app.castManager::refreshDiscovery,
                        onStopCastDiscovery = app.castManager::stopDiscovery,
                        onSelectCastDevice = ::castCurrentPlaybackTo,
                        onConnectCastByIp = { rawIp, onError, onSuccess ->
                            app.castManager.connectByIp(
                                rawIp = rawIp,
                                onNotFound = onError,
                                onFound = { found ->
                                    onSuccess()
                                    castCurrentPlaybackTo(found)
                                },
                            )
                        },
                        onSelectWebOsMode = app.castManager::switchWebOsMode,
                        onRetryCast = app.castManager::retryCast,
                        onCastSelectPlaylistIndex = { idx ->
                            vm.castPlaylistIndex(idx, activeEntry, prefs.subtitleStyle)
                        },
                        onCastTogglePlayPause = app.castManager::togglePlayPause,
                        onCastSeekTo = app.castManager::seekTo,
                        onCastVolume = app.castManager::setVolume,
                        onCastToggleMute = app.castManager::setMuted,
                        onCastSelectSubtitle = app.castManager::selectSubtitleIndex,
                        onCastToggleTvSubtitles = app.castManager::setTvSubtitlesEnabled,
                        onCastSelectAudio = app.castManager::selectAudioIndex,
                        onCastSendRemoteKey = app.castManager::sendTvRemoteKey,
                        onCastStopPlaybackKeepTv = vm::stopTvPlaybackOnly,
                        onStopCastingToPhone = { vm.switchActiveCastToPhone(prefs) },
                        onCastDisconnectTv = vm::disconnectTvAndClearPlayback,
                        onSaveDirectPlay = session::promoteDirectPlayToDownload,
                        onOpenExternalCast = {
                            val currentPlayback = vm.activePlaybackEvent.value ?: playback
                            val wasConnected = castState.isConnected
                            if (wasConnected) app.castManager.stopCasting()
                            vm.closeIntegratedPlayback(reportLocalPlayer = !wasConnected)
                            openExternalContent(currentPlayback)
                        },
                    )
                    return@CiniToTheme
                }

                // Engine legacy or detail overlays and main 4-tab CiniTo Shell
                val detailsEntry = selectedDetailsKey?.let { key -> engineState.library.find { it.key == key } }
                val filesEntry = if (engineState.screen == Screen.Files) {
                    engineState.library.find { it.key == engineState.openTorrentKey }
                } else null
                val activeCatalogDetail = discoverState.activeDetail

                if (engineState.screen == Screen.Prepare && engineState.prepare != null) {
                    val draft = engineState.prepare!!
                    PrepareScreen(
                        draft = draft,
                        freeBytes = engineState.freeBytes,
                        onToggle = session::toggleFile,
                        onSelectAll = session::selectAllFiles,
                        onSelectNone = session::selectNoFiles,
                        onStart = session::startDownload,
                        onDirectPlay = session::startDirectPlay,
                        directPlayDefault = engineState.directPlayDefault,
                        onCancel = session::cancelPrepare,
                        onPlayFile = session::playPreparedFile,
                    )
                } else if (engineState.prepareMode == PrepareMode.ChooseFiles) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            val prepareHash = engineState.prepare?.torrent?.infoHash?.trim()?.lowercase()
                            val cachedPrepareProbe = prepareHash?.let { searchState.probeResultsByHash[it] }
                            ChooseFilesOverlay(
                                targetTitle = engineState.quickStreamTarget?.displayTitle,
                                target = engineState.quickStreamTarget,
                                draft = engineState.prepare,
                                error = engineState.error,
                                sourceStatus = engineState.sourceStatus,
                                freeBytes = engineState.freeBytes,
                                destination = engineState.storageFolderName,
                                directPlayDefault = engineState.directPlayDefault,
                                onToggle = session::toggleFile,
                                onSelectAll = session::selectAllFiles,
                                onSelectNone = session::selectNoFiles,
                                onSelectVideos = session::selectVideoFiles,
                                onDirectPlay = session::startDirectPlay,
                                onDownload = session::startDownload,
                                onPlayFile = session::playPreparedFile,
                                onRetry = session::retryQuickPrepare,
                                onCancel = session::cancelQuickPrepare,
                                cachedProbe = cachedPrepareProbe,
                                onMeasureSwarm = {
                                    val d = engineState.prepare
                                    if (d != null) {
                                        vm.explore.measurePreparedSwarm(d.engineId, d.torrent?.infoHash ?: d.source)
                                    }
                                },
                            )
                        }
                        ActiveCastMiniBar(
                            castState = castState,
                            onOpenRemote = { showGlobalCastRemote = true },
                            onTogglePlayPause = app.castManager::togglePlayPause,
                            onNextEpisode = nextCastEpisodeFn,
                            onStopCasting = stopOrDisconnectCast,
                        )
                    }
                } else if (filesEntry != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            TorrentFilesScreen(
                                entry = filesEntry,
                                error = engineState.error,
                                onBack = session::closeFiles,
                                onPlay = { index -> session.play(filesEntry, index) },
                                onToggleFile = { index -> session.toggleDownloadFile(filesEntry.key, index) },
                                onPause = { session.pause(filesEntry) },
                                onResume = { session.resume(filesEntry) },
                                onDrop = { session.drop(filesEntry) },
                                onDelete = { session.requestDelete(filesEntry) },
                            )
                        }
                        ActiveCastMiniBar(
                            castState = castState,
                            onOpenRemote = { showGlobalCastRemote = true },
                            onTogglePlayPause = app.castManager::togglePlayPause,
                            onNextEpisode = nextCastEpisodeFn,
                            onStopCasting = stopOrDisconnectCast,
                        )
                    }
                } else if (showAdvancedEngineSettings || engineState.screen == Screen.Settings) {
                    SettingsScreen(
                        state = engineState,
                        onBack = {
                            showAdvancedEngineSettings = false
                            session.closeSettings()
                        },
                        onMaxPeers = session::setMaxPeers,
                        onMaxPeersCommit = session::commitMaxPeers,
                        onRamCache = session::setRamCacheMiB,
                        onAutoRamCache = session::setAutoRamCache,
                        onSeeding = session::setSeedingEnabled,
                        onSeedRatio = session::setSeedRatio,
                        onSeedTime = session::setSeedTime,
                        onSeedUpload = session::setSeedUpload,
                        onSeedWiFi = session::setSeedWiFi,
                        onSeedCharging = session::setSeedCharging,
                        onDarkTheme = session::setDarkTheme,
                        onAppTheme = session::setAppTheme,
                        onDynamicColors = session::setDynamicColors,
                        onReducedEffects = session::setReducedEffects,
                        onDirectPlayDefault = session::setDirectPlayDefault,
                        onPlaybackMethod = session::setPlaybackMethod,
                        onAntiDpi = session::setAntiDpi,
                        onCleanup = session::cleanupCache,
                        onClearAll = session::requestClearAll,
                        onStopAll = session::stopAll,
                        onStopAllAndExit = { session.stopAllAndExit() },
                        onClearNativeCategory = { session.clearNativeCache(listOf(it)) },
                        onPickFolder = session::pickFolder,
                        onClearFolder = session::clearFolder,
                        onIdleDropMinutes = session::setIdleDropMinutes,
                        onNetworkPolicy = app::setTransferNetworkPolicySynced,
                        onDownloadRateKiB = session::setDownloadRateKiB,
                        onPauseOnLowBattery = session::setPauseOnLowBattery,
                    )
                } else if (detailsEntry != null) {
                    val parsed = remember(detailsEntry.title) { MediaTitleParser.parse(detailsEntry.title) }
                    val card = remember(detailsEntry, metadataMap[parsed.lookupKey]) {
                        LibraryGalleryBuilder.buildCardModel(detailsEntry, metadataMap[parsed.lookupKey])
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            MediaDetailsScreen(
                                card = card,
                                showRawFilenames = prefs.showRawFilenames,
                                castState = castState,
                                onOpenCast = { showGlobalCastDevices = true },
                                onBack = { selectedDetailsKey = null },
                                onPlayFile = { entry, file, fromStart ->
                                    session.play(entry, file.index, fromStart)
                                },
                                onPauseResume = { entry ->
                                    if (entry.paused) session.resume(entry) else session.pause(entry)
                                },
                                onOpenFiles = { entry ->
                                    session.showFiles(entry)
                                },
                                onDelete = { entry ->
                                    selectedDetailsKey = null
                                    session.requestDelete(entry)
                                },
                            )
                        }
                        ActiveCastMiniBar(
                            castState = castState,
                            onOpenRemote = { showGlobalCastRemote = true },
                            onTogglePlayPause = app.castManager::togglePlayPause,
                            onNextEpisode = nextCastEpisodeFn,
                            onStopCasting = stopOrDisconnectCast,
                        )
                    }
                } else if (currentTab == MainTab.Discover && activeCatalogDetail != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            CatalogDetailsScreen(
                                detail = activeCatalogDetail,
                                library = engineState.library,
                                isCastingToTv = castState.isConnected,
                                activeWebOsMode = if (castState.isConnected) castState.activeWebOsMode else null,
                                castState = castState,
                                onOpenCast = { showGlobalCastDevices = true },
                                onBack = vm.explore::closeCatalogMedia,
                                onSelectSeason = vm.explore::selectDetailSeason,
                                onSelectEpisode = vm.explore::selectDetailEpisode,
                                onLanguageFilterChange = vm.explore::setDetailLanguageFilter,
                                onSortOrderChange = vm.explore::setDetailSortOrder,
                                onToggleShowAll = vm.explore::toggleDetailShowAll,
                                onRetrySearch = vm.explore::retryDetailSearch,
                                onInspectResult = vm.explore::enrichDetailResult,
                                onDiagnoseTop = { vm.explore.diagnoseDetailTop() },
                                onDiagnoseResult = vm.explore::diagnoseDetailCandidate,
                                onStartScan = { n -> vm.explore.startProgressiveDetailScan(batchSize = n) },
                                onPauseScan = vm.explore::pauseDetailScan,
                                onResumeScan = vm.explore::resumeDetailScan,
                                onStreamResult = { res, season, ep, titleLabel ->
                                    openSearchResult(
                                        result = res,
                                        directPlay = true,
                                        season = season,
                                        episode = ep,
                                        displayTitle = titleLabel,
                                        onOpenExistingDetails = { key -> selectedDetailsKey = key },
                                    )
                                },
                                onChooseFilesResult = { res, titleLabel ->
                                    openSearchResult(
                                        result = res,
                                        directPlay = false,
                                        displayTitle = titleLabel,
                                        onOpenExistingDetails = { key -> selectedDetailsKey = key },
                                    )
                                },
                                onOpenLibraryEntry = { entry -> selectedDetailsKey = entry.key },
                                onPlayLibraryEntry = { entry, season, ep ->
                                    val epIdx = findMatchingEpisodeFileIndex(entry, season, ep)
                                    if (epIdx != null || !entry.hasMultipleVideos()) {
                                        session.play(entry, epIdx)
                                    } else {
                                        selectedDetailsKey = entry.key
                                    }
                                },
                                onSearchMoreInTab = {
                                    vm.explore.openSearchFromCatalogDetail()
                                    currentTab = MainTab.Search
                                },
                                onScrollChanged = vm.explore::saveDetailScroll,
                            )
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                            )
                        }
                        ActiveCastMiniBar(
                            castState = castState,
                            onOpenRemote = { showGlobalCastRemote = true },
                            onTogglePlayPause = app.castManager::togglePlayPause,
                            onNextEpisode = nextCastEpisodeFn,
                            onStopCasting = stopOrDisconnectCast,
                        )
                    }
                } else {
                    // Main 4-tab CiniTo Shell
                    Scaffold(
                        containerColor = CinemaColors.Background,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            Column {
                                ActiveCastMiniBar(
                                    castState = castState,
                                    onOpenRemote = { showGlobalCastRemote = true },
                                    onTogglePlayPause = app.castManager::togglePlayPause,
                                    onNextEpisode = nextCastEpisodeFn,
                                    onStopCasting = stopOrDisconnectCast,
                                )
                                HorizontalDivider(thickness = 0.5.dp, color = CinemaColors.HairlineBorder)
                                NavigationBar(
                                    containerColor = CinemaColors.Surface,
                                    contentColor = CinemaColors.TextPrimary,
                                    tonalElevation = 0.dp,
                                ) {
                                    MainTab.entries.forEach { tab ->
                                        val selected = currentTab == tab
                                        val icon = when (tab) {
                                            MainTab.Library -> if (selected) Icons.Default.VideoLibrary else Icons.Outlined.VideoLibrary
                                            MainTab.Discover -> if (selected) Icons.Default.Explore else Icons.Outlined.Explore
                                            MainTab.Search -> if (selected) Icons.Default.Search else Icons.Outlined.Search
                                            MainTab.Settings -> if (selected) Icons.Default.Settings else Icons.Outlined.Settings
                                        }
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { currentTab = tab },
                                            icon = { Icon(icon, contentDescription = tab.label) },
                                            label = { Text(tab.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = CinemaColors.PrimarySoft,
                                                selectedTextColor = CinemaColors.TextPrimary,
                                                indicatorColor = CinemaColors.Primary.copy(alpha = 0.14f),
                                                unselectedIconColor = CinemaColors.TextTertiary,
                                                unselectedTextColor = CinemaColors.TextTertiary,
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                            if (mobileDataBlocked) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = CinemaColors.Gold.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, CinemaColors.Gold.copy(alpha = 0.45f)),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WifiOff,
                                            contentDescription = null,
                                            tint = CinemaColors.Gold,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = "Solo Wi-Fi activo · Datos móviles bloqueados",
                                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                            color = CinemaColors.TextPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { currentTab = MainTab.Settings }) {
                                            Text("Ajustes", color = CinemaColors.Gold)
                                        }
                                        TextButton(onClick = { showAllowMobileConfirmDialog = true }) {
                                            Text("Permitir", color = CinemaColors.PrimarySoft)
                                        }
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                saveableStateHolder.SaveableStateProvider(currentTab.name) {
                                when (currentTab) {
                                    MainTab.Library -> {
                                        val libraryEntries = remember(engineState.library, prefs.hideAdultContent) {
                                            if (prefs.hideAdultContent) {
                                                engineState.library.filterNot { SearchUtils.isAdultContent(it.title) }
                                            } else {
                                                engineState.library
                                            }
                                        }
                                        val hiddenAdultCount = remember(engineState.library, libraryEntries) {
                                            (engineState.library.size - libraryEntries.size).coerceAtLeast(0)
                                        }
                                        val visibleEntries = remember(libraryEntries, libraryFilter, librarySort, librarySearchQuery) {
                                            libraryEntries
                                                .applyFilter(libraryFilter)
                                                .filterLibrary(librarySearchQuery)
                                                .stableLibrarySort(librarySort)
                                        }
                                        val galleryCards = remember(visibleEntries, metadataMap) {
                                            visibleEntries.map { entry ->
                                                val parsed = MediaTitleParser.parse(entry.title)
                                                LibraryGalleryBuilder.buildCardModel(entry, metadataMap[parsed.lookupKey])
                                            }
                                        }
                                        GalleryLibraryScreen(
                                            cards = galleryCards,
                                            rawEntries = libraryEntries,
                                            metadataMap = metadataMap,
                                            viewMode = prefs.libraryViewMode,
                                            filter = libraryFilter,
                                            sort = librarySort,
                                            searchQuery = librarySearchQuery,
                                            engineStatusLine = engineState.statusLine,
                                            onSearchQueryChange = { librarySearchQuery = it },
                                            onFilterChange = { libraryFilter = it },
                                            onSortChange = { librarySort = it },
                                            onViewModeChange = app.appSettings::setLibraryViewMode,
                                            onOpenDetails = { card -> selectedDetailsKey = card.entry.key },
                                            onQuickPlay = { entry, file -> session.play(entry, file.index) },
                                            onPauseResume = { entry ->
                                                if (entry.paused) session.resume(entry) else session.pause(entry)
                                            },
                                            onOpenAddSheet = session::openAddSheet,
                                            onNavigateDiscover = { currentTab = MainTab.Discover },
                                            onNavigateSearch = { currentTab = MainTab.Search },
                                            castState = castState,
                                            onOpenCast = { showGlobalCastDevices = true },
                                            hiddenAdultCount = hiddenAdultCount,
                                        )
                                    }

                                    MainTab.Discover -> DiscoverScreen(
                                        state = discoverState,
                                        metadataEnabled = prefs.metadataLookupEnabled,
                                        catalogRegion = prefs.catalogRegion,
                                        library = engineState.library,
                                        onRefresh = { vm.explore.loadDiscover(force = true) },
                                        onSelectCategoryTab = vm.explore::selectDiscoverTab,
                                        onSelectFeed = vm.explore::selectDiscoverFeed,
                                        onSelectGenre = vm.explore::selectDiscoverGenre,
                                        onLoadMore = vm.explore::loadMoreDiscover,
                                        onCatalogQueryChange = vm.explore::onCatalogQueryChange,
                                        onSubmitCatalogSearch = { vm.explore.submitCatalogSearch() },
                                        onClearCatalogSearch = vm.explore::clearCatalogSearch,
                                        onOpenMedia = vm.explore::openCatalogMedia,
                                        onGridScrollChanged = vm.explore::saveDiscoverGridScroll,
                                        onOpenSettings = { currentTab = MainTab.Settings },
                                        onFallbackDirectSearch = { q, cat ->
                                            vm.explore.searchDirectFromDiscover(q, cat)
                                            currentTab = MainTab.Search
                                        },
                                        castState = castState,
                                        onOpenCast = { showGlobalCastDevices = true },
                                    )

                                    MainTab.Search -> SearchScreen(
                                        state = searchState,
                                        recentQueries = prefs.recentSearchQueries,
                                        enabledProviderIds = prefs.enabledProviderIds,
                                        disabledAdultProviderIds = prefs.disabledAdultProviderIds,
                                        torznabConfigs = torznabConfigs,
                                        library = engineState.library,
                                        hideAdultContent = prefs.hideAdultContent,
                                        adultOnlySearch = prefs.adultOnlySearch,
                                        onDisableAdultOnlySearch = { app.appSettings.setAdultOnlySearch(false) },
                                        onToggleHideDeadResults = vm.explore::setHideDeadSearchResults,
                                        isCastingToTv = castState.isConnected,
                                        activeWebOsMode = if (castState.isConnected) castState.activeWebOsMode else null,
                                        castState = castState,
                                        onOpenCast = { showGlobalCastDevices = true },
                                        onQueryChange = vm.explore::onSearchQueryChange,
                                        onSubmitSearch = { override -> vm.explore.submitSearch(override) },
                                        onClearSearch = vm.explore::clearSearch,
                                        onCategoryChange = vm.explore::setSearchCategory,
                                        onSortOrderChange = vm.explore::setSearchSortOrder,
                                        onLanguageFilterChange = vm.explore::setSearchLanguageFilter,
                                        onRetrySearch = vm.explore::retrySearch,
                                        onInspectResult = vm.explore::enrichSearchResult,
                                        onDiagnoseTop = { vm.explore.diagnoseSearchTop() },
                                        onDiagnoseResult = vm.explore::diagnoseSearchCandidate,
                                        onStartScan = { n -> vm.explore.startProgressiveSearchScan(batchSize = n) },
                                        onPauseScan = vm.explore::pauseSearchScan,
                                        onResumeScan = vm.explore::resumeSearchScan,
                                        onStreamResult = { res, season, ep ->
                                            openSearchResult(
                                                result = res,
                                                directPlay = true,
                                                season = season,
                                                episode = ep,
                                                displayTitle = res.parsed.cleanTitle,
                                                onOpenExistingDetails = { key -> selectedDetailsKey = key },
                                            )
                                        },
                                        onChooseFilesResult = { res ->
                                            openSearchResult(
                                                result = res,
                                                directPlay = false,
                                                displayTitle = res.parsed.cleanTitle,
                                                onOpenExistingDetails = { key -> selectedDetailsKey = key },
                                            )
                                        },
                                        onRemoveRecentQuery = app.appSettings::removeRecentSearchQuery,
                                        onClearRecentQueries = app.appSettings::clearRecentSearchQueries,
                                        onReturnToCatalog = {
                                            if (vm.explore.returnFromSearchToCatalog()) {
                                                currentTab = MainTab.Discover
                                            }
                                        },
                                        onOpenMagnetSheet = session::openAddSheet,
                                        onScrollChanged = vm.explore::saveSearchScroll,
                                    )

                                    MainTab.Settings -> CineSettingsScreen(
                                        prefs = prefs,
                                        engineState = engineState,
                                        builtinProviders = app.searchService.allBuiltinInfos(),
                                        torznabConfigs = torznabConfigs,
                                        onPreferredAudioLang = app.appSettings::setPreferredAudioLanguage,
                                        onPreferredSubtitleLang = app.appSettings::setPreferredSubtitleLanguage,
                                        onSubtitlesDefault = app.appSettings::setSubtitlesEnabledByDefault,
                                        onSubtitleStyle = { style ->
                                            app.appSettings.updateSubtitleStyle(style)
                                            vm.playerController.updateSubtitleStyle(style)
                                            app.castManager.updateSubtitleStyle(style)
                                        },
                                        onUseExternalPlayer = app.appSettings::setUseExternalPlayer,
                                        onDohProvider = { provider ->
                                            app.appSettings.setDohProvider(provider)
                                            app.networkClient.setDohProvider(provider)
                                        },
                                        onMetadataLookup = { enabled ->
                                            app.appSettings.setMetadataLookupEnabled(enabled)
                                            app.metadataRepository.lookupEnabled = enabled
                                        },
                                        onCatalogRegion = { region ->
                                            app.appSettings.setCatalogRegion(region)
                                            app.metadataRepository.catalogRegion = region
                                            vm.explore.clearMetadataCacheAndReload(reloadDiscover = true)
                                        },
                                        onSearchLanguageFilter = app.appSettings::setSearchLanguageFilter,
                                        onPlayabilityDiagnosticsMode = app.appSettings::setPlayabilityDiagnosticsMode,
                                        onSaveTmdbApiKey = { key ->
                                            app.appSettings.setTmdbApiKey(key)
                                            app.metadataRepository.tmdbApiKey = app.appSettings.getTmdbApiKey()
                                            vm.explore.clearMetadataCacheAndReload(reloadDiscover = true)
                                        },
                                        onClearMetadataCache = {
                                            vm.explore.clearMetadataCacheAndReload(reloadDiscover = false)
                                        },
                                        onShowRawFilenames = app.appSettings::setShowRawFilenames,
                                        onBroadGeneralSearch = app.appSettings::setBroadGeneralSearchEnabled,
                                        onHideAdultContent = app.appSettings::setHideAdultContent,
                                        onAdultOnlySearch = app.appSettings::setAdultOnlySearch,
                                        onToggleProvider = app.appSettings::toggleSearchProvider,
                                        onToggleAdultProvider = app.appSettings::setAdultProviderEnabled,
                                        onAddTorznab = { name, url, apiKey, cats ->
                                            app.torznabStore.addOrUpdate(name, url, apiKey, cats)
                                        },
                                        onRemoveTorznab = app.torznabStore::remove,
                                        onPickFolder = session::pickFolder,
                                        onDirectPlayDefault = session::setDirectPlayDefault,
                                        onAllowMobileData = app::setAllowMobileDataEnabled,
                                        onNetworkPolicy = app::setTransferNetworkPolicySynced,
                                        onCleanupEngineCache = session::cleanupCache,
                                        onOpenAdvancedEngineSettings = { showAdvancedEngineSettings = true },
                                    )
                                }
                            }
                        }
                        }
                    }
                }

                if (showAllowMobileConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showAllowMobileConfirmDialog = false },
                        title = { Text("¿Permitir uso de datos móviles?") },
                        text = {
                            Text("La aplicación está configurada en modo Solo Wi-Fi por defecto para evitar consumo de tu plan de datos. ¿Quieres activar el uso con datos móviles ahora?")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    app.setAllowMobileDataEnabled(true)
                                    showAllowMobileConfirmDialog = false
                                },
                            ) {
                                Text("Activar datos móviles")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAllowMobileConfirmDialog = false }) {
                                Text("Mantener Solo Wi-Fi")
                            }
                        },
                    )
                }

                // 1-tap streaming preparing sheet overlay
                if (engineState.prepareMode == PrepareMode.QuickStream) {
                    QuickStreamSheet(
                        target = engineState.quickStreamTarget,
                        draft = engineState.prepare,
                        error = engineState.error,
                        sourceStatus = engineState.sourceStatus,
                        onSwitchToChooseFiles = session::switchQuickStreamToFileChooser,
                        onRetry = session::retryQuickPrepare,
                        onCancel = session::cancelQuickPrepare,
                    )
                }

                // Shared modal AddSheet / Delete / ClearAll / Diagnostics
                if (engineState.addSheetOpen) {
                    val prefetch = engineState.prepare?.takeIf {
                        it.source == engineState.magnetDraft.trim() ||
                            (engineState.magnetDraft.isBlank() && isTorrentFileSource(it.source))
                    }
                    val existing = existingLibraryEntry(engineState)
                    AddSheet(
                        magnet = engineState.magnetDraft,
                        engineReady = engineState.engineReady,
                        error = engineState.error,
                        status = engineState.sourceStatus ?: prefetchStatus(prefetch),
                        connecting = engineState.sourceStatus != null ||
                            prefetch != null && prefetch.torrent?.ready != true && existing == null,
                        sourceFileName = prefetch?.source?.takeIf(::isTorrentFileSource)?.let(::torrentFileName),
                        onMagnet = session::setMagnet,
                        onAdd = session::addCurrent,
                        onOpenFile = ::pickTorrentFile,
                        onDismiss = session::closeAddSheet,
                        draft = prefetch,
                        freeBytes = engineState.freeBytes,
                        destination = engineState.storageFolderName,
                        onToggle = session::toggleFile,
                        onSelectAll = session::selectAllFiles,
                        onSelectNone = session::selectNoFiles,
                        onSelectVideos = session::selectVideoFiles,
                        onDownload = session::startDownload,
                        onDirectPlay = session::startDirectPlay,
                        directPlayDefault = engineState.directPlayDefault,
                        existingEntry = existing,
                        onOpenExisting = existing?.let {
                            {
                                session.closeAddSheet()
                                selectedDetailsKey = it.key
                            }
                        },
                    )
                }
                engineState.deleteRequest?.let { req ->
                    DeleteDialog(
                        title = req.title,
                        onKeepFiles = session::deleteKeep,
                        onEraseFiles = session::deleteErase,
                        onDismiss = session::dismissDelete,
                    )
                }
                if (engineState.clearAllConfirm) {
                    ClearAllDialog(
                        onConfirm = session::confirmClearAll,
                        onDismiss = session::dismissClearAll,
                    )
                }
                engineState.diagnosticsPreview?.let { report ->
                    AlertDialog(
                        onDismissRequest = session::closeDiagnostics,
                        title = { Text("Diagnóstico") },
                        text = { Text(report) },
                        confirmButton = { TextButton(onClick = session::shareDiagnostics) { Text("Compartir") } },
                        dismissButton = { TextButton(onClick = session::closeDiagnostics) { Text("Cerrar") } },
                    )
                }

                if (showGlobalCastRemote && castState.isConnected) {
                    BackHandler { showGlobalCastRemote = false }
                    val activeEntry = playback?.let { findMatchingEntry(engineState.library, it) }
                    CastRemoteOverlay(
                        title = castState.mediaTitle.ifBlank { playback?.name.orEmpty() },
                        castState = castState,
                        subtitleStyle = prefs.subtitleStyle,
                        onUpdateSubtitleStyle = { style ->
                            app.appSettings.updateSubtitleStyle(style)
                            vm.playerController.updateSubtitleStyle(style)
                            app.castManager.updateSubtitleStyle(style)
                        },
                        onBack = { showGlobalCastRemote = false },
                        onOpenCastDevices = { showGlobalCastDevices = true },
                        onTogglePlayPause = app.castManager::togglePlayPause,
                        onSeekTo = app.castManager::seekTo,
                        onSeekBy = { delta -> app.castManager.seekTo((castState.positionMs + delta).coerceAtLeast(0L)) },
                        onVolumeChange = app.castManager::setVolume,
                        onToggleMute = app.castManager::setMuted,
                        onSelectAudio = app.castManager::selectAudioIndex,
                        onSelectSubtitle = app.castManager::selectSubtitleIndex,
                        onToggleTvSubtitles = app.castManager::setTvSubtitlesEnabled,
                        onPickLocalSubtitle = ::launchSubtitlePicker,
                        onSelectPlaylistIndex = { idx ->
                            vm.castPlaylistIndex(idx, activeEntry, prefs.subtitleStyle)
                        },
                        onSelectWebOsMode = app.castManager::switchWebOsMode,
                        onSendRemoteKey = app.castManager::sendTvRemoteKey,
                        onRetryCast = app.castManager::retryCast,
                        onStopPlaybackKeepTv = { vm.stopTvPlaybackOnly() },
                        onStopCastingToPhone = playback?.let {
                            {
                                showGlobalCastRemote = false
                                vm.switchActiveCastToPhone(prefs)
                            }
                        },
                        onDisconnectTv = {
                            showGlobalCastRemote = false
                            vm.disconnectTvAndClearPlayback()
                        },
                        isSavedInLibrary = activeEntry != null && !activeEntry.directPlay,
                        onSaveToLibrary = activeEntry?.takeIf { it.directPlay }?.let { entry ->
                            { session.promoteDirectPlayToDownload(entry) }
                        },
                    )
                }

                if (showGlobalCastDevices) {
                    GlobalCastDeviceSheet(
                        castState = castState,
                        onDismiss = { showGlobalCastDevices = false },
                        onRefreshDiscovery = app.castManager::refreshDiscovery,
                        onSelectDevice = { dev ->
                            showGlobalCastDevices = false
                            if (playback != null) {
                                vm.castEventToConnectedTv(
                                    event = playback,
                                    explicitDevice = dev,
                                    openRemote = true,
                                    overridePositionMs = if (castState.hasActiveMedia) castState.positionMs else null,
                                )
                            } else {
                                app.castManager.connectDevice(dev)
                            }
                        },
                        onConnectByIp = { rawIp, onError, onSuccess ->
                            app.castManager.connectByIp(
                                rawIp = rawIp,
                                onNotFound = onError,
                                onFound = { found ->
                                    onSuccess()
                                    showGlobalCastDevices = false
                                    if (playback != null) {
                                        vm.castEventToConnectedTv(
                                            event = playback,
                                            explicitDevice = found,
                                            openRemote = true,
                                            overridePositionMs = if (castState.hasActiveMedia) castState.positionMs else null,
                                        )
                                    } else {
                                        app.castManager.connectDevice(found)
                                    }
                                },
                            )
                        },
                        onSelectWebOsMode = app.castManager::switchWebOsMode,
                        onTogglePlayPause = app.castManager::togglePlayPause,
                        onVolumeChange = app.castManager::setVolume,
                        onToggleMute = app.castManager::setMuted,
                        onSelectSubtitle = app.castManager::selectSubtitleIndex,
                        onToggleTvSubtitles = app.castManager::setTvSubtitlesEnabled,
                        onSelectAudio = app.castManager::selectAudioIndex,
                        onStopPlaybackKeepTv = {
                            showGlobalCastDevices = false
                            vm.stopTvPlaybackOnly()
                        },
                        onOpenRemote = {
                            showGlobalCastDevices = false
                            showGlobalCastRemote = true
                        },
                        onStopCasting = {
                            showGlobalCastDevices = false
                            showGlobalCastRemote = false
                            vm.disconnectTvAndClearPlayback()
                        },
                        onOpenExternalFallback = playback?.let { activeNow ->
                            {
                                showGlobalCastDevices = false
                                showGlobalCastRemote = false
                                vm.disconnectTvAndClearPlayback()
                                openExternalContent(activeNow)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun openSearchResult(
        result: TorrentSearchResult,
        directPlay: Boolean,
        season: Int? = null,
        episode: Int? = null,
        displayTitle: String? = null,
        onOpenExistingDetails: (String) -> Unit,
    ) {
        vm.explore.cancelProbeAndClaimOwnership(result)
        val hash = result.normalizedInfoHash ?: result.infoHash ?: infoHashFromMagnet(result.magnetUri)
        val existing = hash?.let { h -> session.ui.value.library.find { it.key.equals(h, true) } }
        if (existing != null) {
            if (directPlay) {
                val epIdx = findMatchingEpisodeFileIndex(existing, season, episode)
                if (epIdx != null || !existing.hasMultipleVideos()) {
                    session.play(existing, epIdx)
                } else {
                    onOpenExistingDetails(existing.key)
                }
            } else {
                onOpenExistingDetails(existing.key)
            }
            return
        }
        val titleLabel = displayTitle?.takeIf { it.isNotBlank() } ?: result.parsed.cleanTitle
        if (directPlay) {
            session.startQuickStream(
                torrentId = result.magnetUri,
                displayTitle = titleLabel,
                season = season,
                episode = episode,
                reportedSeeders = result.seeders,
                reportedPeers = result.peers,
            )
        } else {
            session.openFileChooserForMagnet(
                torrentId = result.magnetUri,
                displayTitle = titleLabel,
                reportedSeeders = result.seeders,
                reportedPeers = result.peers,
            )
        }
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (vm.activePlaybackEvent.value == null) return
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> vm.playerController.togglePlayPause()
                ACTION_PIP_SEEK_BACK -> vm.playerController.seekBy(-10_000L)
                ACTION_PIP_SEEK_FORWARD -> vm.playerController.seekBy(10_000L)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val state = vm.playerController.state.value
        val (w, h) = CinemaPlayerUiLogic.pipAspectRatio(state.videoWidth, state.videoHeight)
        val rewIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_PIP_SEEK_BACK).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val fwdIntent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(ACTION_PIP_SEEK_FORWARD).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actions = listOf(
            RemoteAction(
                AndroidIcon.createWithResource(this, android.R.drawable.ic_media_rew),
                "Retroceder 10s",
                "Retroceder 10 segundos",
                rewIntent,
            ),
            RemoteAction(
                AndroidIcon.createWithResource(
                    this,
                    if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                ),
                if (state.isPlaying) "Pausar" else "Reproducir",
                if (state.isPlaying) "Pausar video" else "Reproducir video",
                playPauseIntent,
            ),
            RemoteAction(
                AndroidIcon.createWithResource(this, android.R.drawable.ic_media_ff),
                "Adelantar 10s",
                "Adelantar 10 segundos",
                fwdIntent,
            ),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(w, h))
            .setActions(actions)
            .build()
    }

    private fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.playerController.setInPipMode(isInPictureInPictureMode)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.activePlaybackEvent.value != null &&
            vm.playerController.state.value.isPlaying &&
            !app.castManager.session.value.isConnected
        ) {
            enterPipIfSupported()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        app.setCurrentActivity(this)
        session.onForeground()
    }

    override fun onStop() {
        if (!isInPictureInPictureMode && !isChangingConfigurations && !app.castManager.session.value.isConnected) {
            vm.playerController.pause()
        }
        if (isFinishing) app.clearCurrentActivity(this)
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActionReceiver) }
        app.clearCurrentActivity(this)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "org.cinito.online.action.PIP_PLAY_PAUSE"
        private const val ACTION_PIP_SEEK_BACK = "org.cinito.online.action.PIP_SEEK_BACK"
        private const val ACTION_PIP_SEEK_FORWARD = "org.cinito.online.action.PIP_SEEK_FORWARD"
    }

    private fun handleEvent(event: UiEvent) {
        when (event) {
            UiEvent.PickTorrent -> pickTorrentFile()
            UiEvent.PickFolder -> folderPicker.launch(null)
            is UiEvent.PickFileForRepair -> {
                pendingRepairTarget = Pair(event.entryKey, event.fileIndex)
                fileRepairPicker.launch(arrayOf("*/*"))
            }
            UiEvent.RequestNotifications -> requestNotifications()
            is UiEvent.OpenContent -> {
                val isVideo = event.mime.startsWith("video/") || event.mime.startsWith("audio/")
                if (isVideo && (app.castManager.session.value.isConnected || !app.appSettings.preferences.value.useExternalPlayer)) {
                    vm.startIntegratedPlayback(event)
                } else {
                    openExternalContent(event)
                }
            }
            is UiEvent.ShareText -> startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    },
                    "Compartir diagnóstico",
                ),
            )
            UiEvent.ExitApp -> finish()
        }
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) return
        try {
            val stream = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            val source = intent?.data ?: stream
            if (source?.scheme == "content") {
                session.openTorrent(source)
                return
            }
            val text = source?.toString() ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val supported = extractTorrentLink(text)
            if (supported != null) {
                val hash = infoHashFromMagnet(supported)
                val existing = hash?.let { h -> session.ui.value.library.find { it.key.equals(h, true) } }
                if (existing != null) {
                    session.openDetails(existing.key)
                } else {
                    session.add(supported)
                }
            } else {
                session.showError(
                    if (source?.scheme == "file") "Abre el archivo .torrent usando el selector dentro de Agregar."
                    else "Abre un enlace magnet, una URL .torrent o comparte un archivo .torrent.",
                )
            }
        } catch (_: RuntimeException) {
            session.showError("No se pudo leer este elemento compartido.")
        }
    }

    private fun pickTorrentFile() {
        try {
            torrentPicker.launch(arrayOf("application/x-bittorrent", "*/*"))
        } catch (_: ActivityNotFoundException) {
            session.showError("No hay selector de archivos disponible en este dispositivo.")
        } catch (_: SecurityException) {
            session.showError("No se pudo abrir el selector de archivos.")
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun openExternalContent(event: UiEvent.OpenContent) {
        val uri = Uri.parse(event.uri)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, event.mime)
            val grants = (listOf(PlaybackItem(event.uri, event.name)) + event.playlist + event.subtitles)
                .distinctBy { it.uri }.filter { Uri.parse(it.uri).scheme == "content" }
            if (grants.isNotEmpty()) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(grants.first().name, Uri.parse(grants.first().uri)).apply {
                    grants.drop(1).forEach { addItem(ClipData.Item(Uri.parse(it.uri))) }
                }
            }
            putExtra(Intent.EXTRA_TITLE, event.name)
            putExtra("title", event.name)
            putExtra("return_result", true)
            putExtra("position", event.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            putExtra("video_list", event.playlist.map { Uri.parse(it.uri) }.toTypedArray())
            putExtra("video_list.name", event.playlist.map { it.name }.toTypedArray())
            putExtra("video_list.filename", event.playlist.map { it.name }.toTypedArray())
            putExtra("video_list_is_explicit", true)
            putExtra("subs", event.subtitles.map { Uri.parse(it.uri) }.toTypedArray())
            putExtra("subs.name", event.subtitles.map { it.name }.toTypedArray())
            putExtra("subs.enable", event.subtitles.take(1).map { Uri.parse(it.uri) }.toTypedArray())
            event.subtitles.firstOrNull()?.let { putExtra("subtitles_location", it.uri) }
        }
        launchedPlaybackUri = event.uri
        launchedPlaybackItems = event.playlist.map { it.uri }.toSet()
        launchedBookmarkUris = event.bookmarkUris
        event.playbackLeaseKey?.let { app.httpPlaybackLease.begin(event.uri, it) }
        try {
            externalViewer.launch(view)
        } catch (_: Exception) {
            app.httpPlaybackLease.finish(event.uri)
            session.finishPlayback(event.bookmarkUris[event.uri] ?: event.uri)
            launchedPlaybackUri = null
            launchedPlaybackItems = emptySet()
            launchedBookmarkUris = emptyMap()
            session.showError("No se encontró un reproductor externo compatible (VLC, MPV, Just Player).")
        }
    }
}

private fun existingLibraryEntry(state: UiState): DownloadEntry? {
    val hash = infoHashFromMagnet(state.magnetDraft) ?: state.prepare?.torrent?.infoHash
    return state.library.find { entry ->
        (hash != null && entry.key.equals(hash, true)) ||
            (state.prepare?.engineId != null && entry.engineId == state.prepare.engineId)
    }
}

private fun findMatchingEntry(
    library: List<DownloadEntry>,
    playback: UiEvent.OpenContent,
): DownloadEntry? = library.find { e ->
    e.key == playback.playbackLeaseKey ||
        e.files.any { PlaybackProvider.uriFor(e, it).toString() == playback.uri } ||
        playback.bookmarkUris.values.any { bUri ->
            e.files.any { PlaybackProvider.uriFor(e, it).toString() == bUri }
        }
}

@Composable
private fun ActiveCastMiniBar(
    castState: CastSessionState,
    onOpenRemote: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onStopCasting: () -> Unit,
) {
    if (!castState.isConnected) return
    CastMiniControlBar(
        castState = castState,
        onOpenRemote = onOpenRemote,
        onTogglePlayPause = onTogglePlayPause,
        onNextEpisode = onNextEpisode,
        onStopCasting = onStopCasting,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalCastDeviceSheet(
    castState: CastSessionState,
    onDismiss: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onSelectDevice: (CastDevice) -> Unit,
    onConnectByIp: (String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit,
    onSelectWebOsMode: (WebOsCastMode) -> Unit,
    onTogglePlayPause: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
    onToggleTvSubtitles: (Boolean) -> Unit = {},
    onSelectAudio: (Int) -> Unit,
    onStopPlaybackKeepTv: (() -> Unit)?,
    onOpenRemote: () -> Unit,
    onStopCasting: () -> Unit,
    onOpenExternalFallback: (() -> Unit)?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CinemaColors.Surface,
        contentColor = CinemaColors.TextPrimary,
    ) {
        CastDeviceSheetContent(
            castState = castState,
            onRefreshDiscovery = onRefreshDiscovery,
            onSelectDevice = onSelectDevice,
            onConnectByIp = onConnectByIp,
            onSelectWebOsMode = onSelectWebOsMode,
            onTogglePlayPause = onTogglePlayPause,
            onVolumeChange = onVolumeChange,
            onToggleMute = onToggleMute,
            onSelectSubtitle = onSelectSubtitle,
            onToggleTvSubtitles = onToggleTvSubtitles,
            onSelectAudio = onSelectAudio,
            onStopPlaybackKeepTv = onStopPlaybackKeepTv,
            onOpenRemote = onOpenRemote,
            onStopCasting = onStopCasting,
            onOpenExternalFallback = onOpenExternalFallback,
        )
    }
}

private fun buildCastMediaForPlayback(
    probeRepo: AudioTrackProbeRepository,
    entry: DownloadEntry?,
    title: String,
    sourceUri: String,
    mimeType: String,
    positionMs: Long,
    durationMs: Long,
    sidecarSubtitles: List<PlaybackItem>,
    playerState: CinemaPlayerState,
    castSnapshot: CastSessionState,
    targetDevice: CastDevice,
    preferredAudioLanguage: String?,
    prioritizeLatino: Boolean,
    playlist: List<PlaybackItem>,
    currentPlaylistIndex: Int,
    subtitleStyle: SubtitleStyleConfig,
    leaseKey: String?,
    bookmarkUri: String,
): CastMediaSource {
    val sameDevice = castSnapshot.connectedDevice?.id == targetDevice.id
    val sameCastMedia = sameDevice && castSnapshot.hasActiveMedia && castSnapshot.activeSourceUri == sourceUri
    val samePlayerMedia = playerState.mediaUri == sourceUri

    val localSubs = if (samePlayerMedia) playerState.localSubtitles else emptyList()
    val combinedSubtitles = (sidecarSubtitles + localSubs).distinctBy { it.uri }

    val probedAudio = CastRoutingHelper.resolveAudioTracks(
        probeRepo = probeRepo,
        entry = entry,
        sourceUri = sourceUri,
    )
    val (audioTracks, explicitAudioIndex) = if (probedAudio.isNotEmpty()) {
        probedAudio to null
    } else if (samePlayerMedia && playerState.audioTracks.isNotEmpty()) {
        val fallbackTracks = playerState.audioTracks.mapIndexed { idx, opt ->
            CastAudioTrack(
                index = idx,
                name = opt.displayLabel,
                language = opt.languageCode,
                codec = opt.codec,
            )
        }
        val selectedIdx = playerState.audioTracks.indexOfFirst { it.isSelected }.takeIf { it >= 0 }
        fallbackTracks to selectedIdx
    } else {
        emptyList<CastAudioTrack>() to null
    }

    val (initialSubtitleIndex, preserveInitialSubtitle) = when {
        sameCastMedia -> castSnapshot.activeSubtitleIndex to true
        samePlayerMedia && (playerState.subtitleTracks.isNotEmpty() || localSubs.isNotEmpty()) -> {
            if (playerState.subtitlesDisabled) {
                null to true
            } else {
                val activeTrack = playerState.activeSubtitleTrack
                val mappedIdx = when (activeTrack?.source) {
                    SubtitleSource.TorrentSidecar -> {
                        val sidecarTracks = playerState.subtitleTracks.filter { it.source == SubtitleSource.TorrentSidecar }
                        val pos = sidecarTracks.indexOfFirst { it.id == activeTrack.id }
                        sidecarSubtitles.getOrNull(pos)?.let { item ->
                            combinedSubtitles.indexOfFirst { it.uri == item.uri }.takeIf { it >= 0 }
                        }
                    }
                    SubtitleSource.LocalFile -> {
                        val localTracks = playerState.subtitleTracks.filter { it.source == SubtitleSource.LocalFile }
                        val pos = localTracks.indexOfFirst { it.id == activeTrack.id }
                        localSubs.getOrNull(pos)?.let { item ->
                            combinedSubtitles.indexOfFirst { it.uri == item.uri }.takeIf { it >= 0 }
                        }
                    }
                    else -> null
                }
                if (mappedIdx != null) mappedIdx to true else null to false
            }
        }
        else -> null to false
    }

    val embeddedSubtitleLabels = CastRoutingHelper.resolveEmbeddedSubtitleLabels(
        probeRepo = probeRepo,
        entry = entry,
        sourceUri = sourceUri,
    ).ifEmpty {
        if (samePlayerMedia) {
            playerState.subtitleTracks
                .filter { it.source == SubtitleSource.Embedded }
                .map { it.displayLabel }
        } else {
            emptyList()
        }
    }

    val embeddedSubtitles = CastRoutingHelper.resolveEmbeddedSubtitles(
        probeRepo = probeRepo,
        entry = entry,
        sourceUri = sourceUri,
    )

    val parsedSource = runCatching { Uri.parse(sourceUri) }.getOrNull()
    val isPlaybackProviderStream = parsedSource?.scheme == "content" &&
        parsedSource.authority == "${BuildConfig.APPLICATION_ID}.playback"
    val torrentFileIdx = Regex("""/torrent/[^/]+/file/(\d+)(?:\?.*)?$""")
        .find(sourceUri)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: CastRoutingHelper.extractFileIndexFromStreamUri(sourceUri)
    val matchedFile = entry?.files?.firstOrNull { file ->
        file.uri == sourceUri ||
            PlaybackProvider.uriFor(entry, file).toString() == bookmarkUri ||
            PlaybackProvider.uriFor(entry, file).toString() == sourceUri ||
            (torrentFileIdx != null && file.index == torrentFileIdx)
    }
    val isSourceFileComplete = when {
        entry == null -> true
        sourceUri.startsWith("file://") -> true
        !entry.directPlay && matchedFile != null -> matchedFile.isVerifiedComplete && matchedFile.uri != null
        sourceUri.startsWith("content://") && !isPlaybackProviderStream -> true
        else -> false
    }

    return CastRoutingHelper.buildMediaSource(
        title = title,
        sourceUri = sourceUri,
        mimeType = mimeType,
        positionMs = positionMs,
        durationMs = durationMs,
        subtitles = combinedSubtitles,
        audioTracks = audioTracks,
        preferredAudioLanguage = preferredAudioLanguage,
        prioritizeLatino = prioritizeLatino,
        explicitAudioIndex = explicitAudioIndex,
        playlist = playlist,
        currentPlaylistIndex = currentPlaylistIndex,
        preferredWebOsMode = if (sameDevice) castSnapshot.activeWebOsMode else WebOsCastMode.Auto,
        subtitleStyle = subtitleStyle,
        initialSubtitleIndex = initialSubtitleIndex,
        preserveInitialSubtitle = preserveInitialSubtitle,
        embeddedSubtitleLabels = embeddedSubtitleLabels,
        embeddedSubtitles = embeddedSubtitles,
        isSourceFileComplete = isSourceFileComplete,
        leaseKey = leaseKey,
        bookmarkUri = bookmarkUri,
    )
}
