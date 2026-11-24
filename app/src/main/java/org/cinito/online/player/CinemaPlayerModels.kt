package org.cinito.online.player

import org.cinito.online.metadata.MediaKind
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.settings.SubtitleStyleConfig
import webtor.app.PlaybackItem
import webtor.app.formatSpeed
import webtor.app.playbackTime
import java.util.Locale
import kotlin.math.abs

enum class SubtitleSource {
    Embedded,
    TorrentSidecar,
    LocalFile,
}

enum class PlayerAspectMode(val label: String, val description: String) {
    Fit("Ajustar", "Respeta la proporción original del video"),
    Fill("Llenar", "Expande hasta cubrir toda la pantalla"),
    Zoom("Zoom", "Amplía recortando bordes negros"),
    Ratio16x9("16:9", "Encuadre panorámico clásico"),
    Ratio4x3("4:3", "Encuadre tradicional de TV"),
}

data class AudioTrackOption(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val languageCode: String?,
    val displayLabel: String,
    val codec: String?,
    val channelLabel: String?,
    val bitrateKbps: Int?,
    val isSelected: Boolean,
) {
    val detailBadge: String
        get() = listOfNotNull(codec, channelLabel, bitrateKbps?.let { "${it} kbps" })
            .joinToString(" · ")
}

data class SubtitleTrackOption(
    val id: String,
    val source: SubtitleSource,
    val groupIndex: Int? = null,
    val trackIndex: Int? = null,
    val languageCode: String?,
    val displayLabel: String,
    val formatLabel: String?,
    val isSelected: Boolean,
    val isForced: Boolean = false,
)

data class CinemaPlaybackRequest(
    val mediaUri: String,
    val title: String,
    val mimeType: String? = null,
    val playlist: List<PlaybackItem> = emptyList(),
    val sidecarSubtitles: List<PlaybackItem> = emptyList(),
    val startPositionMs: Long = 0L,
    val playbackLeaseKey: String? = null,
)

data class CinemaPlayerState(
    val title: String = "",
    val mediaUri: String = "",
    val playlist: List<PlaybackItem> = emptyList(),
    val currentPlaylistIndex: Int = 0,
    val sidecarSubtitles: List<PlaybackItem> = emptyList(),
    val localSubtitles: List<PlaybackItem> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val isTempSpeedBoost: Boolean = false,
    val speedBeforeBoost: Float = 1.0f,
    val aspectMode: PlayerAspectMode = PlayerAspectMode.Fit,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val subtitleTracks: List<SubtitleTrackOption> = emptyList(),
    val subtitlesDisabled: Boolean = false,
    val subtitleDelayMs: Long = 0L,
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val isLocked: Boolean = false,
    val isInPipMode: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasNextEpisode: Boolean
        get() = playlist.size > 1 && currentPlaylistIndex in 0 until (playlist.size - 1)

    val hasPreviousEpisode: Boolean
        get() = playlist.size > 1 && currentPlaylistIndex > 0

    val nextPlaylistItem: PlaybackItem?
        get() = playlist.getOrNull(currentPlaylistIndex + 1)

    val previousPlaylistItem: PlaybackItem?
        get() = playlist.getOrNull(currentPlaylistIndex - 1)

    val activeAudioTrack: AudioTrackOption?
        get() = audioTracks.firstOrNull { it.isSelected }

    val activeSubtitleTrack: SubtitleTrackOption?
        get() = if (subtitlesDisabled) null else subtitleTracks.firstOrNull { it.isSelected }

    /** Activates or releases temporary 2x playback boost while preserving the user's chosen base speed. */
    fun withTemporarySpeedBoost(active: Boolean): CinemaPlayerState =
        if (active) {
            if (isTempSpeedBoost) this
            else copy(
                isTempSpeedBoost = true,
                speedBeforeBoost = playbackSpeed,
                playbackSpeed = maxOf(2.0f, playbackSpeed),
            )
        } else {
            if (!isTempSpeedBoost) this
            else copy(
                isTempSpeedBoost = false,
                playbackSpeed = speedBeforeBoost,
            )
        }

    fun withRetryStarted(): CinemaPlayerState =
        copy(
            errorMessage = null,
            isEnded = false,
            isBuffering = true,
        )
}

data class FormattedPlayerMedia(
    val primaryTitle: String,
    val episodeBadge: String?,
    val secondaryTag: String?,
)

data class EpisodeRowPresentation(
    val badge: String,
    val title: String,
    val detail: String?,
)

data class AspectViewportSize(
    val width: Float,
    val height: Float,
)

enum class NextEpisodePromptMode {
    Hidden,
    PreEnd,
    AutoCountdown,
}

data class P2pBufferingStatus(
    val headline: String,
    val detail: String,
)

/**
 * Pure presentation, geometry, and gesture calculations for the Cinema Player,
 * kept framework-free so every rule is unit-tested cleanly on the JVM.
 */
object CinemaPlayerUiLogic {
    val PLAYER_SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    const val SKIP_INTRO_DELTA_MS: Long = 85_000L
    private const val NEXT_EPISODE_PRE_END_WINDOW_MS: Long = 25_000L
    private const val DOUBLE_TAP_CHAIN_WINDOW_MS: Long = 900L
    private const val SEEK_STEP_MS: Long = 10_000L
    private const val MIN_PIP_RATIO = 100f / 239f
    private const val MAX_PIP_RATIO = 239f / 100f

    fun formatMediaTitle(rawTitle: String, showRawFilename: Boolean = false): FormattedPlayerMedia {
        val trimmed = rawTitle.trim()
        if (showRawFilename || trimmed.isEmpty()) {
            return FormattedPlayerMedia(
                primaryTitle = trimmed,
                episodeBadge = null,
                secondaryTag = null,
            )
        }
        val parsed = MediaTitleParser.parse(trimmed)
        val secondary = when {
            parsed.kind == MediaKind.MOVIE && parsed.year != null -> parsed.year.toString()
            else -> parsed.qualityBadges.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }
        }
        return FormattedPlayerMedia(
            primaryTitle = parsed.cleanTitle,
            episodeBadge = parsed.episodeBadge,
            secondaryTag = secondary,
        )
    }

    fun formatEpisodeRow(
        rawFileName: String,
        index: Int,
        showRawFilename: Boolean = false,
    ): EpisodeRowPresentation {
        val trimmed = rawFileName.trim()
        if (showRawFilename) {
            return EpisodeRowPresentation(
                badge = "#${index + 1}",
                title = trimmed,
                detail = null,
            )
        }
        val parsed = MediaTitleParser.parse(trimmed)
        val badge = parsed.episodeBadge ?: "#${index + 1}"
        val episodeTitle = MediaTitleParser.extractEpisodeTitle(trimmed)
        val quality = parsed.qualityBadges.take(3).joinToString(" · ").takeIf { it.isNotEmpty() }
        return EpisodeRowPresentation(
            badge = badge,
            title = episodeTitle ?: parsed.cleanTitle,
            detail = quality,
        )
    }

    fun computeAspectViewportSize(
        containerWidth: Float,
        containerHeight: Float,
        mode: PlayerAspectMode,
    ): AspectViewportSize {
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return AspectViewportSize(0f, 0f)
        }
        val targetRatio = when (mode) {
            PlayerAspectMode.Ratio16x9 -> 16f / 9f
            PlayerAspectMode.Ratio4x3 -> 4f / 3f
            PlayerAspectMode.Fit, PlayerAspectMode.Fill, PlayerAspectMode.Zoom ->
                return AspectViewportSize(containerWidth, containerHeight)
        }
        val containerRatio = containerWidth / containerHeight
        return if (containerRatio > targetRatio) {
            AspectViewportSize(
                width = (containerHeight * targetRatio).coerceAtMost(containerWidth),
                height = containerHeight,
            )
        } else {
            AspectViewportSize(
                width = containerWidth,
                height = (containerWidth / targetRatio).coerceAtMost(containerHeight),
            )
        }
    }

    /** Android throws IllegalArgumentException if PiP aspect ratio lies outside [100/239, 239/100]. */
    fun pipAspectRatio(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        if (videoWidth <= 0 || videoHeight <= 0) return 16 to 9
        val ratio = videoWidth.toFloat() / videoHeight.toFloat()
        return when {
            ratio > MAX_PIP_RATIO -> 239 to 100
            ratio < MIN_PIP_RATIO -> 100 to 239
            else -> videoWidth to videoHeight
        }
    }

    fun computePipSubtitleSizeSp(preferredFontSizeSp: Float): Float =
        (preferredFontSizeSp * 0.62f).coerceIn(10f, 15f)

    fun formatSpeedLabel(speed: Float): String {
        val roundedInt = speed.toInt()
        return if (abs(speed - roundedInt.toFloat()) < 0.01f) {
            "${roundedInt}x"
        } else {
            val formatted = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
            "${formatted}x"
        }
    }

    fun formatRemainingTime(positionMs: Long, durationMs: Long): String {
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        return "-${playbackTime(remaining)}"
    }

    fun shouldShowSkipIntro(
        title: String,
        playlistSize: Int,
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (durationMs < 180_000L) return false
        if (positionMs !in 0L..240_000L) return false
        if (positionMs + SKIP_INTRO_DELTA_MS >= durationMs - 30_000L) return false
        val parsed = MediaTitleParser.parse(title)
        val isEpisodic = playlistSize > 1 ||
            parsed.kind == MediaKind.SERIES ||
            parsed.kind == MediaKind.ANIME ||
            parsed.episode != null
        return isEpisodic
    }

    fun evaluateNextEpisodePrompt(
        state: CinemaPlayerState,
        dismissed: Boolean,
    ): NextEpisodePromptMode {
        if (!state.hasNextEpisode || state.isInPipMode || state.isLocked || state.errorMessage != null || dismissed) {
            return NextEpisodePromptMode.Hidden
        }
        if (state.isEnded) return NextEpisodePromptMode.AutoCountdown
        if (state.durationMs >= 90_000L) {
            val remainingMs = state.durationMs - state.positionMs
            if (remainingMs in 1L..NEXT_EPISODE_PRE_END_WINDOW_MS) {
                return NextEpisodePromptMode.PreEnd
            }
        }
        return NextEpisodePromptMode.Hidden
    }

    fun describeP2pBufferingStatus(
        downloadSpeedBytesPerSec: Long,
        numPeers: Int,
    ): P2pBufferingStatus =
        if (numPeers <= 0 && downloadSpeedBytesPerSec <= 0L) {
            P2pBufferingStatus(
                headline = "Conectando con peers…",
                detail = "Buscando semillas disponibles",
            )
        } else {
            val detail = buildList {
                add("↓ ${formatSpeed(downloadSpeedBytesPerSec)}")
                if (numPeers > 0) add("$numPeers peers")
            }.joinToString(" · ")
            P2pBufferingStatus(
                headline = "Cargando búfer de video…",
                detail = detail,
            )
        }

    fun nextDoubleTapSeek(
        previousDeltaMs: Long,
        previousSideIsLeft: Boolean?,
        lastTapAtMs: Long,
        nowMs: Long,
        isLeft: Boolean,
    ): Long {
        val base = if (isLeft) -SEEK_STEP_MS else SEEK_STEP_MS
        val withinChain = previousSideIsLeft == isLeft && (nowMs - lastTapAtMs) in 0L..DOUBLE_TAP_CHAIN_WINDOW_MS
        return if (withinChain) previousDeltaMs + base else base
    }

    fun isInsideSafeDragZone(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        edgeGuardPx: Float,
    ): Boolean {
        if (width <= edgeGuardPx * 2f || height <= edgeGuardPx * 2f) return true
        return x >= edgeGuardPx && x <= (width - edgeGuardPx) &&
            y >= edgeGuardPx && y <= (height - edgeGuardPx)
    }
}
