package org.cinito.online.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.withFfmpegSubtitles
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cinito.online.settings.AppPreferences
import org.cinito.online.settings.SubtitleStyleConfig
import webtor.app.PlaybackItem

@OptIn(UnstableApi::class)
class CinemaPlayerController(
    context: Context,
    private val onProgressBookmark: (mediaUri: String, positionMs: Long, durationMs: Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CinemaPlayerState())
    val state: StateFlow<CinemaPlayerState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var tickerJob: Job? = null
    private var lastBookmarkPersistAtMs = 0L
    private var lastRequest: CinemaPlaybackRequest? = null

    fun getOrCreatePlayer(): ExoPlayer {
        player?.let { return it }

        val renderersFactory = NextRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val dataSourceFactory = DefaultDataSource.Factory(appContext)
        val extractorsFactory = FfmpegSubtitleExtractorsFactory()
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .withFfmpegSubtitles(dataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val exo = ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exo.addListener(playerListener)
        player = exo
        return exo
    }

    fun load(request: CinemaPlaybackRequest, prefs: AppPreferences) {
        lastRequest = request
        val exo = getOrCreatePlayer()
        val currentIdx = request.playlist.indexOfFirst { it.uri == request.mediaUri }
            .takeIf { it >= 0 } ?: 0

        _state.update {
            it.copy(
                title = request.title,
                mediaUri = request.mediaUri,
                playlist = request.playlist,
                currentPlaylistIndex = currentIdx,
                sidecarSubtitles = request.sidecarSubtitles,
                localSubtitles = emptyList(),
                positionMs = request.startPositionMs,
                durationMs = 0L,
                bufferedPositionMs = 0L,
                isEnded = false,
                isBuffering = true,
                playbackSpeed = prefs.defaultPlaybackSpeed,
                isTempSpeedBoost = false,
                speedBeforeBoost = prefs.defaultPlaybackSpeed,
                subtitlesDisabled = !prefs.subtitlesEnabledByDefault,
                subtitleDelayMs = 0L,
                subtitleStyle = prefs.subtitleStyle,
                errorMessage = null,
            )
        }

        val trackParamsBuilder = exo.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(prefs.preferredAudioLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitlesEnabledByDefault)
        if (prefs.subtitlesEnabledByDefault) {
            trackParamsBuilder.setPreferredTextLanguage(prefs.preferredSubtitleLanguage)
        }
        exo.trackSelectionParameters = trackParamsBuilder.build()
        exo.playbackParameters = PlaybackParameters(prefs.defaultPlaybackSpeed)
        exo.subtitleDelayMilliseconds = 0L

        val mediaItem = buildMediaItem(
            uri = request.mediaUri,
            title = request.title,
            mimeType = request.mimeType,
            sidecars = request.sidecarSubtitles,
            locals = emptyList(),
        )
        exo.setMediaItem(mediaItem, request.startPositionMs.coerceAtLeast(0L))
        exo.prepare()
        exo.playWhenReady = true
        startTicker()
    }

    /** Reloads and re-prepares the active stream after a playback error without losing position or local subs. */
    fun retry() {
        val current = _state.value
        if (current.mediaUri.isBlank()) return
        val exo = getOrCreatePlayer()
        val resumePos = current.positionMs.coerceAtLeast(0L)
        val baseSpeed = if (current.isTempSpeedBoost) current.speedBeforeBoost else current.playbackSpeed

        _state.update { it.withRetryStarted().withTemporarySpeedBoost(false) }

        exo.playbackParameters = PlaybackParameters(baseSpeed)
        val mediaItem = buildMediaItem(
            uri = current.mediaUri,
            title = current.title,
            mimeType = lastRequest?.mimeType,
            sidecars = current.sidecarSubtitles,
            locals = current.localSubtitles,
        )
        exo.setMediaItem(mediaItem, resumePos)
        exo.prepare()
        exo.playWhenReady = true
        startTicker()
    }

    fun playPlaylistItem(
        index: Int,
        sidecarProvider: (PlaybackItem) -> List<PlaybackItem>,
        prefs: AppPreferences,
        startPositionMsProvider: (PlaybackItem) -> Long = { 0L },
        mimeTypeProvider: (PlaybackItem) -> String? = { null },
    ) {
        val list = _state.value.playlist
        val target = list.getOrNull(index) ?: return
        persistProgressNow()
        load(
            request = CinemaPlaybackRequest(
                mediaUri = target.uri,
                title = target.name,
                mimeType = mimeTypeProvider(target),
                playlist = list,
                sidecarSubtitles = sidecarProvider(target),
                startPositionMs = startPositionMsProvider(target).coerceAtLeast(0L),
                playbackLeaseKey = lastRequest?.playbackLeaseKey,
            ),
            prefs = prefs,
        )
    }

    fun attachLocalSubtitle(uri: Uri, displayName: String) {
        val exo = player ?: return
        val current = _state.value
        val newItem = PlaybackItem(uri.toString(), displayName)
        val nextLocals = current.localSubtitles.filterNot { it.uri == newItem.uri } + newItem
        val resumePos = exo.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exo.playWhenReady

        val inferredLang = TrackLanguageFormatter.inferLanguageFromFilename(displayName)
        val mediaItem = buildMediaItem(
            uri = current.mediaUri,
            title = current.title,
            mimeType = lastRequest?.mimeType,
            sidecars = current.sidecarSubtitles,
            locals = nextLocals,
        )

        _state.update {
            it.copy(
                localSubtitles = nextLocals,
                subtitlesDisabled = false,
            )
        }

        val paramsBuilder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        if (inferredLang != null) {
            paramsBuilder.setPreferredTextLanguage(inferredLang)
        }
        exo.trackSelectionParameters = paramsBuilder.build()
        exo.setMediaItem(mediaItem, resumePos)
        exo.prepare()
        exo.playWhenReady = wasPlaying
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            persistProgressNow()
        } else {
            if (exo.playbackState == Player.STATE_ENDED) {
                exo.seekTo(0L)
            }
            exo.play()
        }
    }

    fun play() {
        _state.update { it.copy(isEnded = false, errorMessage = null) }
        player?.play()
    }

    fun pause() {
        player?.pause()
        persistProgressNow()
    }

    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        val clamped = if (exo.duration > 0L) positionMs.coerceIn(0L, exo.duration) else positionMs.coerceAtLeast(0L)
        exo.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped, isEnded = false) }
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        seekTo(exo.currentPosition + deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        player?.playbackParameters = PlaybackParameters(clamped)
        _state.update { it.copy(playbackSpeed = clamped, speedBeforeBoost = clamped, isTempSpeedBoost = false) }
    }

    fun setTemporarySpeedBoost(active: Boolean) {
        var appliedSpeed = 1.0f
        _state.update { current ->
            val next = current.withTemporarySpeedBoost(active)
            appliedSpeed = next.playbackSpeed
            next
        }
        player?.playbackParameters = PlaybackParameters(appliedSpeed)
    }

    fun setAspectMode(mode: PlayerAspectMode) {
        _state.update { it.copy(aspectMode = mode) }
    }

    fun selectAudioTrack(option: AudioTrackOption) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun selectSubtitleTrack(option: SubtitleTrackOption) {
        val exo = player ?: return
        val gi = option.groupIndex ?: return
        val ti = option.trackIndex ?: return
        val group = exo.currentTracks.groups.getOrNull(gi) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, ti))
            .build()
        _state.update { it.copy(subtitlesDisabled = false) }
    }

    fun disableSubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _state.update { it.copy(subtitlesDisabled = true) }
    }

    fun setSubtitleDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-10_000L, 10_000L)
        player?.subtitleDelayMilliseconds = clamped
        _state.update { it.copy(subtitleDelayMs = clamped) }
    }

    fun updateSubtitleStyle(style: SubtitleStyleConfig) {
        _state.update { it.copy(subtitleStyle = style) }
    }

    fun setLocked(locked: Boolean) {
        _state.update { it.copy(isLocked = locked) }
    }

    fun setInPipMode(inPip: Boolean) {
        _state.update { it.copy(isInPipMode = inPip) }
    }

    fun syncCastEpisodeState(
        title: String,
        mediaUri: String,
        playlist: List<webtor.app.PlaybackItem>,
        currentPlaylistIndex: Int,
        sidecarSubtitles: List<webtor.app.PlaybackItem>,
    ) {
        tickerJob?.cancel()
        tickerJob = null
        player?.stop()
        player?.clearMediaItems()
        _state.update {
            val sameMedia = it.mediaUri == mediaUri
            it.withTemporarySpeedBoost(false).copy(
                title = title,
                mediaUri = mediaUri,
                playlist = playlist,
                currentPlaylistIndex = currentPlaylistIndex,
                sidecarSubtitles = sidecarSubtitles,
                localSubtitles = if (sameMedia) it.localSubtitles else emptyList(),
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                isPlaying = false,
                isBuffering = false,
                isEnded = false,
            )
        }
    }

    fun needsMediaReload(): Boolean = (player?.mediaItemCount ?: 0) == 0

    fun stopWithoutReporting() {
        tickerJob?.cancel()
        tickerJob = null
        player?.stop()
        player?.clearMediaItems()
        _state.update {
            it.withTemporarySpeedBoost(false).copy(
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                isPlaying = false,
                isBuffering = false,
                isEnded = false,
            )
        }
    }

    fun stopAndReport(): Long {
        persistProgressNow()
        val finalPos = player?.currentPosition ?: _state.value.positionMs
        tickerJob?.cancel()
        tickerJob = null
        player?.stop()
        player?.clearMediaItems()
        _state.update {
            it.withTemporarySpeedBoost(false).copy(
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                isPlaying = false,
                isBuffering = false,
                isEnded = false,
            )
        }
        return finalPos
    }

    fun release() {
        persistProgressNow()
        tickerJob?.cancel()
        tickerJob = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val exo = player
                if (exo != null) {
                    val pos = exo.currentPosition.coerceAtLeast(0L)
                    val dur = exo.duration.coerceAtLeast(0L)
                    val buf = exo.bufferedPosition.coerceAtLeast(0L)
                    _state.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = dur,
                            bufferedPositionMs = buf,
                            isPlaying = exo.isPlaying,
                            isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (exo.isPlaying && dur > 0L && now - lastBookmarkPersistAtMs >= 3_000L && currentUriNonEmpty()) {
                        lastBookmarkPersistAtMs = now
                        onProgressBookmark(_state.value.mediaUri, pos, dur)
                    }
                }
                delay(250L)
            }
        }
    }

    private fun currentUriNonEmpty(): Boolean = _state.value.mediaUri.isNotBlank()

    private fun persistProgressNow() {
        val exo = player ?: return
        val uri = _state.value.mediaUri
        val dur = exo.duration.coerceAtLeast(0L)
        if (uri.isBlank() || dur <= 0L) return
        onProgressBookmark(uri, exo.currentPosition.coerceAtLeast(0L), dur)
    }

    private fun buildMediaItem(
        uri: String,
        title: String,
        mimeType: String?,
        sidecars: List<PlaybackItem>,
        locals: List<PlaybackItem>,
    ): MediaItem {
        val configs = mutableListOf<MediaItem.SubtitleConfiguration>()
        sidecars.forEachIndexed { idx, item ->
            configs += buildSubConfig(item, "sidecar_$idx", isLocal = false, isDefaultLocal = false)
        }
        locals.forEachIndexed { idx, item ->
            configs += buildSubConfig(item, "local_$idx", isLocal = true, isDefaultLocal = (idx == locals.lastIndex))
        }

        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .setSubtitleConfigurations(configs)
        if (!mimeType.isNullOrBlank()) {
            builder.setMimeType(mimeType)
        }
        return builder.build()
    }

    private fun buildSubConfig(
        item: PlaybackItem,
        idPrefix: String,
        isLocal: Boolean,
        isDefaultLocal: Boolean,
    ): MediaItem.SubtitleConfiguration {
        val lang = TrackLanguageFormatter.inferLanguageFromFilename(item.name)
        val label = item.name.substringBeforeLast('.')
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(item.uri))
            .setId("$idPrefix:${item.uri}")
            .setMimeType(TrackLanguageFormatter.subtitleMimeTypeForFile(item.name))
            .setLanguage(lang)
            .setLabel(if (isLocal) "Local · $label" else label)
            .setSelectionFlags(if (isDefaultLocal) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }

    private fun extractTracks(tracks: Tracks) {
        val audioList = mutableListOf<AudioTrackOption>()
        val subList = mutableListOf<SubtitleTrackOption>()
        val currentState = _state.value
        val sidecarUris = currentState.sidecarSubtitles.map { it.uri }.toSet()
        val localUris = currentState.localSubtitles.map { it.uri }.toSet()

        tracks.groups.forEachIndexed { gi, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (ti in 0 until group.length) {
                        if (!group.isTrackSupported(ti)) continue
                        val format = group.getTrackFormat(ti)
                        val langName = TrackLanguageFormatter.formatLanguage(format.language)
                        val titlePart = format.label?.takeIf {
                            it.isNotBlank() && !it.equals(langName, ignoreCase = true)
                        }
                        val codec = TrackLanguageFormatter.formatAudioCodec(format.sampleMimeType, format.codecs)
                        val channels = TrackLanguageFormatter.formatChannels(format.channelCount)
                        val kbps = format.bitrate.takeIf { it > 0 }?.div(1000)
                        val baseLabel = listOfNotNull(langName, titlePart).joinToString(" — ")
                        audioList += AudioTrackOption(
                            id = "aud:$gi:$ti",
                            groupIndex = gi,
                            trackIndex = ti,
                            languageCode = format.language,
                            displayLabel = baseLabel.ifBlank { "Pista ${audioList.size + 1}" },
                            codec = codec,
                            channelLabel = channels,
                            bitrateKbps = kbps,
                            isSelected = group.isTrackSelected(ti),
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (ti in 0 until group.length) {
                        val format = group.getTrackFormat(ti)
                        val fmtId = format.id.orEmpty()
                        val source = when {
                            fmtId.startsWith("local_") || localUris.any { it in fmtId } -> SubtitleSource.LocalFile
                            fmtId.startsWith("sidecar_") || sidecarUris.any { it in fmtId } -> SubtitleSource.TorrentSidecar
                            else -> SubtitleSource.Embedded
                        }
                        val langName = TrackLanguageFormatter.formatLanguage(format.language)
                        val label = format.label?.takeIf { it.isNotBlank() }
                        val display = when {
                            label != null && !label.equals(langName, ignoreCase = true) && format.language != null ->
                                "$langName — $label"
                            label != null -> label
                            format.language != null -> langName
                            else -> "Subtítulo ${subList.size + 1}"
                        }
                        subList += SubtitleTrackOption(
                            id = "sub:$gi:$ti",
                            source = source,
                            groupIndex = gi,
                            trackIndex = ti,
                            languageCode = format.language,
                            displayLabel = display,
                            formatLabel = TrackLanguageFormatter.formatSubtitleCodec(format.sampleMimeType, label),
                            isSelected = group.isTrackSelected(ti),
                            isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                        )
                    }
                }
            }
        }

        _state.update {
            it.copy(
                audioTracks = audioList,
                subtitleTracks = subList,
                subtitlesDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true,
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            extractTracks(tracks)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val ended = playbackState == Player.STATE_ENDED
            if (ended) {
                setTemporarySpeedBoost(false)
                persistProgressNow()
            }
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isEnded = ended,
                    durationMs = player?.duration?.coerceAtLeast(0L) ?: it.durationMs,
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update {
                it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    isBuffering = false,
                    errorMessage = error.localizedMessage ?: "Error al decodificar el video (${error.errorCodeName})",
                )
            }
        }
    }
}
