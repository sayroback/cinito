package org.cinito.online.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cinito.online.player.CinemaPlayerState
import org.cinito.online.player.CinemaPlayerUiLogic
import org.cinito.online.player.FormattedPlayerMedia
import org.cinito.online.player.NextEpisodePromptMode
import org.cinito.online.player.P2pBufferingStatus
import org.cinito.online.player.PlayerAspectMode
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import kotlin.math.abs

enum class GestureHudKind {
    Brightness,
    Volume,
    Seek,
}

enum class PlayerSheet {
    None,
    Audio,
    Subtitles,
    Speed,
    Aspect,
    Episodes,
    Cast,
}

@Composable
fun PlayerTopBar(
    formattedTitle: FormattedPlayerMedia,
    castConnected: Boolean,
    isLandscape: Boolean,
    isSavedInLibrary: Boolean = false,
    p2pTelemetryLabel: String? = null,
    onSaveToLibrary: (() -> Unit)? = null,
    onBack: () -> Unit,
    onOpenCast: () -> Unit,
    onRequestPip: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xD6000000), Color.Transparent),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = CinemaColors.TextPrimary)
        }

        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = formattedTitle.primaryTitle,
                style = MaterialTheme.typography.titleLarge,
                color = CinemaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = formattedTitle.episodeBadge ?: formattedTitle.secondaryTag
            if (subtitle != null || p2pTelemetryLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (p2pTelemetryLabel != null) {
                        Text(
                            text = p2pTelemetryLabel,
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = CinemaColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (onSaveToLibrary != null) {
            Surface(
                onClick = onSaveToLibrary,
                shape = RoundedCornerShape(18.dp),
                color = CinemaColors.PrimaryMuted,
                border = BorderStroke(1.dp, CinemaColors.Primary.copy(alpha = 0.55f)),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Descargar en Biblioteca",
                        tint = CinemaColors.PrimarySoft,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Descargar",
                        style = MaterialTheme.typography.labelMedium,
                        color = CinemaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else if (isSavedInLibrary) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CinemaColors.Secondary.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, CinemaColors.Secondary.copy(alpha = 0.45f)),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Guardado en Biblioteca",
                        tint = CinemaColors.Secondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "En biblioteca",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaColors.Secondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        IconButton(
            onClick = onOpenCast,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = if (castConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                contentDescription = "Transmitir a TV",
                tint = if (castConnected) CinemaColors.Secondary else CinemaColors.TextPrimary,
            )
        }
        IconButton(
            onClick = onRequestPip,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Imagen en imagen", tint = CinemaColors.TextPrimary)
        }
        IconButton(
            onClick = onToggleOrientation,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Icon(
                imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isLandscape) "Cambiar a vertical" else "Pantalla completa horizontal",
                tint = CinemaColors.TextPrimary,
            )
        }
    }
}

@Composable
fun PlayerCenterTransport(
    state: CinemaPlayerState,
    onPreviousEpisode: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextEpisode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val wide = maxWidth >= 520.dp
        val gap = if (wide) 44.dp else 18.dp
        val sideSize = if (wide) 56.dp else 48.dp
        val playSize = if (wide) 76.dp else 64.dp

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.hasPreviousEpisode) {
                BorderlessTransportButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "Episodio anterior",
                    size = sideSize,
                    iconSize = if (wide) 34.dp else 28.dp,
                    onClick = onPreviousEpisode,
                )
            }
            BorderlessTransportButton(
                icon = Icons.Default.Replay10,
                contentDescription = "Retroceder 10 segundos",
                size = sideSize,
                iconSize = if (wide) 36.dp else 32.dp,
                onClick = { onSeekBy(-10_000L) },
            )

            Box(
                modifier = Modifier
                    .size(playSize)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (state.isPlaying) "Pausar" else "Reproducir",
                        onClick = onTogglePlayPause,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBuffering && state.errorMessage == null) {
                    CircularProgressIndicator(
                        color = CinemaColors.Primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(playSize - 8.dp),
                    )
                }
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(120)) + scaleIn(initialScale = 0.85f, animationSpec = tween(120)))
                            .togetherWith(fadeOut(tween(90)))
                    },
                    label = "playPauseIcon",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pausar" else "Reproducir",
                        tint = CinemaColors.TextPrimary,
                        modifier = Modifier
                            .size(if (wide) 52.dp else 44.dp)
                            .then(if (!playing) Modifier.offset(x = 3.dp) else Modifier),
                    )
                }
            }

            BorderlessTransportButton(
                icon = Icons.Default.Forward10,
                contentDescription = "Adelantar 10 segundos",
                size = sideSize,
                iconSize = if (wide) 36.dp else 32.dp,
                onClick = { onSeekBy(10_000L) },
            )
            if (state.hasNextEpisode) {
                BorderlessTransportButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "Episodio siguiente",
                    size = sideSize,
                    iconSize = if (wide) 34.dp else 28.dp,
                    onClick = onNextEpisode,
                )
            }
        }
    }
}

@Composable
private fun BorderlessTransportButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = CinemaColors.TextPrimary,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun PlayerBottomBar(
    state: CinemaPlayerState,
    scrubPositionMs: Long,
    isScrubbing: Boolean,
    showSkipIntro: Boolean,
    bufferingStatus: P2pBufferingStatus?,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubFinish: (Long) -> Unit,
    onSkipIntro: () -> Unit,
    onLock: () -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xDE000000)),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (bufferingStatus != null || showSkipIntro) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (bufferingStatus != null) {
                    Surface(
                        modifier = Modifier.weight(1f, fill = false),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xB8131722),
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    ) {
                        Text(
                            text = "${bufferingStatus.headline} · ${bufferingStatus.detail}",
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = CinemaColors.Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                if (showSkipIntro) {
                    Surface(
                        onClick = onSkipIntro,
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xCC131722),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.FastForward, contentDescription = null, tint = CinemaColors.TextPrimary, modifier = Modifier.size(16.dp))
                            Text(
                                text = "Omitir intro +85s",
                                style = MaterialTheme.typography.labelMedium.tabular().copy(fontWeight = FontWeight.SemiBold),
                                color = CinemaColors.TextPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        CinemaPlayerTimeline(
            positionMs = state.positionMs,
            bufferedPositionMs = state.bufferedPositionMs,
            durationMs = state.durationMs,
            scrubPositionMs = scrubPositionMs,
            isScrubbing = isScrubbing,
            onScrubStart = onScrubStart,
            onScrubMove = onScrubMove,
            onScrubFinish = onScrubFinish,
        )

        val audioLang = state.activeAudioTrack?.languageCode?.uppercase()
        val audioLabel = if (audioLang != null) "Audio · $audioLang" else "Audio"
        val speedModified = abs(state.playbackSpeed - 1.0f) > 0.01f
        val speedLabel = if (speedModified) {
            "Velocidad · ${CinemaPlayerUiLogic.formatSpeedLabel(state.playbackSpeed)}"
        } else {
            "Velocidad"
        }
        val aspectModified = state.aspectMode != PlayerAspectMode.Fit
        val aspectLabel = if (aspectModified) state.aspectMode.label else "Pantalla"
        val subLang = state.activeSubtitleTrack?.languageCode?.uppercase()
        val subLabel = when {
            state.subtitlesDisabled -> "Sin subs"
            subLang != null -> "Subs · $subLang"
            else -> "Subtítulos"
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val arrangement = if (maxWidth >= 560.dp) {
                Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.spacedBy(4.dp, Alignment.Start)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = arrangement,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlinePlayerAction(
                    icon = Icons.Outlined.Lock,
                    label = "Bloquear",
                    highlighted = false,
                    onClick = onLock,
                )
                InlinePlayerAction(
                    icon = Icons.Default.Speed,
                    label = speedLabel,
                    highlighted = speedModified,
                    onClick = { onOpenSheet(PlayerSheet.Speed) },
                )
                InlinePlayerAction(
                    icon = Icons.Default.GraphicEq,
                    label = audioLabel,
                    highlighted = false,
                    onClick = { onOpenSheet(PlayerSheet.Audio) },
                )
                InlinePlayerAction(
                    icon = Icons.Default.Subtitles,
                    label = subLabel,
                    highlighted = state.localSubtitles.isNotEmpty() && !state.subtitlesDisabled,
                    onClick = { onOpenSheet(PlayerSheet.Subtitles) },
                )
                InlinePlayerAction(
                    icon = Icons.Default.AspectRatio,
                    label = aspectLabel,
                    highlighted = aspectModified,
                    onClick = { onOpenSheet(PlayerSheet.Aspect) },
                )
                if (state.playlist.size > 1) {
                    InlinePlayerAction(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        label = "Episodios",
                        highlighted = false,
                        onClick = { onOpenSheet(PlayerSheet.Episodes) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InlinePlayerAction(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (highlighted) CinemaColors.Primary else CinemaColors.TextPrimary
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun PlayerBufferingOverlay(
    status: P2pBufferingStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xC80B0D12),
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                color = CinemaColors.Primary,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = status.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                )
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = CinemaColors.Secondary,
                )
            }
        }
    }
}

@Composable
fun PlayerGestureHud(
    visible: Boolean,
    kind: GestureHudKind,
    label: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(110)) + scaleIn(initialScale = 0.94f, animationSpec = tween(110)),
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xCC0B0D12),
            border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val icon = when (kind) {
                        GestureHudKind.Brightness -> Icons.Default.Brightness6
                        GestureHudKind.Volume -> Icons.AutoMirrored.Filled.VolumeUp
                        GestureHudKind.Seek -> Icons.Default.FastForward
                    }
                    Icon(icon, contentDescription = null, tint = CinemaColors.TextPrimary, modifier = Modifier.size(20.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium.tabular(), color = CinemaColors.TextPrimary)
                }
                fraction?.let { frac ->
                    LinearProgressIndicator(
                        progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.width(124.dp).height(4.dp),
                        color = CinemaColors.Primary,
                        trackColor = CinemaColors.SurfaceBright,
                    )
                }
            }
        }
    }
}

@Composable
fun BoxScope.PlayerDoubleTapFeedback(
    deltaMs: Long,
    isLeftSide: Boolean,
    visible: Boolean,
) {
    AnimatedVisibility(
        visible = visible && deltaMs != 0L,
        enter = fadeIn(tween(90)) + scaleIn(initialScale = 0.88f, animationSpec = tween(90)),
        exit = fadeOut(tween(160)),
        modifier = Modifier
            .align(if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 28.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xA6000000),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (deltaMs < 0L) Icons.Default.FastRewind else Icons.Default.FastForward,
                    contentDescription = null,
                    tint = CinemaColors.TextPrimary,
                    modifier = Modifier.size(24.dp),
                )
                val secs = abs(deltaMs) / 1000L
                Text(
                    text = if (deltaMs < 0L) "-${secs}s" else "+${secs}s",
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    color = CinemaColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
fun PlayerSpeedBoostBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.92f, animationSpec = tween(100)),
        exit = fadeOut(tween(140)),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = 12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xCC0B0D12),
            border = BorderStroke(1.dp, CinemaColors.Primary.copy(alpha = 0.55f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.FastForward, contentDescription = null, tint = CinemaColors.Primary, modifier = Modifier.size(16.dp))
                Text(
                    text = "2x · Adelantando",
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = CinemaColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
fun PlayerLockedPill(
    visible: Boolean,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(150)),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(bottom = 20.dp),
    ) {
        Surface(
            onClick = onUnlock,
            shape = RoundedCornerShape(24.dp),
            color = Color(0xBF0B0D12),
            border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, tint = CinemaColors.TextPrimary, modifier = Modifier.size(18.dp))
                Text("Pantalla bloqueada · Desbloquear", style = MaterialTheme.typography.labelLarge, color = CinemaColors.TextPrimary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NextEpisodeOverlay(
    mode: NextEpisodePromptMode,
    nextEpisodeRawName: String,
    nextIndex: Int,
    countdownSec: Int,
    showRawFilenames: Boolean,
    onPlayNextNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = mode != NextEpisodePromptMode.Hidden,
        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.95f, animationSpec = tween(180)),
        exit = fadeOut(tween(150)),
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        val nextInfo = CinemaPlayerUiLogic.formatEpisodeRow(
            rawFileName = nextEpisodeRawName,
            index = nextIndex,
            showRawFilename = showRawFilenames,
        )
        Surface(
            modifier = Modifier.widthIn(max = 360.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xEB131722),
            border = BorderStroke(1.dp, CinemaColors.Primary.copy(alpha = 0.55f)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Siguiente episodio",
                        style = MaterialTheme.typography.labelMedium,
                        color = CinemaColors.Primary,
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CinemaColors.PrimaryMuted,
                    ) {
                        Text(
                            text = nextInfo.badge,
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = CinemaColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                Text(
                    text = nextInfo.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlayNextNow,
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        val label = if (mode == NextEpisodePromptMode.AutoCountdown && countdownSec > 0) {
                            "Siguiente (${countdownSec}s)"
                        } else {
                            "Reproducir ahora"
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge.tabular())
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text("Ocultar", color = CinemaColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerErrorCard(
    errorMessage: String,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 420.dp),
        shape = RoundedCornerShape(16.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No se pudo reproducir", style = MaterialTheme.typography.titleMedium, color = CinemaColors.Danger)
            Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Reintentar")
                }
                OutlinedButton(
                    onClick = onOpenExternal,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("App externa")
                }
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Text("Salir", color = CinemaColors.TextSecondary)
                }
            }
        }
    }
}
