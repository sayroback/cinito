package org.cinito.online.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.cinito.online.player.CinemaPlayerUiLogic
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.playbackTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact single-row cinema timeline keeping timestamps directly inline with the track
 * so portrait and landscape HUDs stay visually tight with zero dead vertical gap.
 */
@Composable
fun CinemaPlayerTimeline(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    scrubPositionMs: Long,
    isScrubbing: Boolean,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubFinish: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRemaining by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var pillWidthPx by remember { mutableIntStateOf(0) }

    val safeDuration = durationMs.coerceAtLeast(0L)
    val rawPos = if (isScrubbing) scrubPositionMs else positionMs
    val displayPos = if (safeDuration > 0L) rawPos.coerceIn(0L, safeDuration) else rawPos.coerceAtLeast(0L)
    val activeFraction = if (safeDuration > 0L) (displayPos.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (safeDuration > 0L) {
        (bufferedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(activeFraction, 1f)
    } else {
        0f
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbing) 5.dp else 3.dp,
        animationSpec = tween(durationMillis = 120),
        label = "timelineTrackHeight",
    )
    val thumbDiameter by animateDpAsState(
        targetValue = if (isScrubbing) 18.dp else 12.dp,
        animationSpec = tween(durationMillis = 120),
        label = "timelineThumbSize",
    )

    val latestDuration by rememberUpdatedState(safeDuration)
    val latestOnStart by rememberUpdatedState(onScrubStart)
    val latestOnMove by rememberUpdatedState(onScrubMove)
    val latestOnFinish by rememberUpdatedState(onScrubFinish)

    fun xToTargetMs(xPx: Float, widthPx: Int): Long {
        if (widthPx <= 0 || latestDuration <= 0L) return 0L
        val frac = (xPx / widthPx.toFloat()).coerceIn(0f, 1f)
        return (frac * latestDuration).toLong()
    }

    val density = LocalDensity.current

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = playbackTime(displayPos),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = if (isScrubbing) CinemaColors.Primary else CinemaColors.TextPrimary,
            modifier = Modifier.defaultMinSize(minWidth = 38.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .onSizeChanged { trackWidthPx = it.width }
                .semantics {
                    contentDescription = "Línea de tiempo del video"
                    progressBarRangeInfo = ProgressBarRangeInfo(activeFraction, 0f..1f)
                    stateDescription = if (safeDuration > 0L) {
                        "${playbackTime(displayPos)} de ${playbackTime(safeDuration)}"
                    } else {
                        playbackTime(displayPos)
                    }
                    if (safeDuration > 0L) {
                        setProgress { targetFrac ->
                            val clamped = (targetFrac.coerceIn(0f, 1f) * safeDuration).toLong()
                            latestOnFinish(clamped)
                            true
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (latestDuration > 0L) {
                                latestOnFinish(xToTargetMs(offset.x, size.width))
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var dragTargetMs = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { startOffset ->
                            if (latestDuration > 0L) {
                                dragTargetMs = xToTargetMs(startOffset.x, size.width)
                                latestOnStart(dragTargetMs)
                            }
                        },
                        onDragEnd = {
                            if (latestDuration > 0L) {
                                latestOnFinish(dragTargetMs)
                            }
                        },
                        onDragCancel = {
                            if (latestDuration > 0L) {
                                latestOnFinish(dragTargetMs)
                            }
                        },
                        onHorizontalDrag = { change, _ ->
                            if (latestDuration > 0L) {
                                change.consume()
                                dragTargetMs = xToTargetMs(change.position.x, size.width)
                                latestOnMove(dragTargetMs)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isScrubbing && safeDuration > 0L,
                enter = fadeIn(tween(100)) + scaleIn(initialScale = 0.92f, animationSpec = tween(100)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { pillWidthPx = it.width }
                    .offset {
                        val centerPx = (activeFraction * trackWidthPx).roundToInt()
                        val maxOffset = (trackWidthPx - pillWidthPx).coerceAtLeast(0)
                        IntOffset(
                            x = (centerPx - pillWidthPx / 2).coerceIn(0, maxOffset),
                            y = (-32).dp.roundToPx(),
                        )
                    },
            ) {
                val deltaMs = scrubPositionMs - positionMs
                val deltaText = if (abs(deltaMs) >= 1_000L) {
                    val sign = if (deltaMs > 0L) "+" else "-"
                    " · $sign${playbackTime(abs(deltaMs))}"
                } else {
                    ""
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xEB131722),
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Text(
                        text = "${playbackTime(scrubPositionMs)}$deltaText",
                        style = MaterialTheme.typography.labelLarge.tabular().copy(fontWeight = FontWeight.SemiBold),
                        color = CinemaColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackPx = with(density) { trackHeight.toPx() }
                val thumbRadius = with(density) { (thumbDiameter / 2).toPx() }
                val usableWidth = size.width
                val centerY = size.height / 2f
                val trackTop = centerY - trackPx / 2f
                val corner = CornerRadius(trackPx / 2f, trackPx / 2f)

                drawRoundRect(
                    color = Color.White.copy(alpha = 0.28f),
                    topLeft = Offset(0f, trackTop),
                    size = Size(usableWidth, trackPx),
                    cornerRadius = corner,
                )

                if (bufferedFraction > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.50f),
                        topLeft = Offset(0f, trackTop),
                        size = Size(usableWidth * bufferedFraction, trackPx),
                        cornerRadius = corner,
                    )
                }

                val activeWidth = (usableWidth * activeFraction).coerceIn(0f, usableWidth)
                if (activeWidth > 0f) {
                    drawRoundRect(
                        color = CinemaColors.Primary,
                        topLeft = Offset(0f, trackTop),
                        size = Size(activeWidth, trackPx),
                        cornerRadius = corner,
                    )
                }

                val thumbX = activeWidth.coerceIn(thumbRadius, (usableWidth - thumbRadius).coerceAtLeast(thumbRadius))
                if (isScrubbing) {
                    drawCircle(
                        color = CinemaColors.Primary.copy(alpha = 0.30f),
                        radius = thumbRadius * 1.75f,
                        center = Offset(thumbX, centerY),
                    )
                }
                drawCircle(
                    color = if (isScrubbing) CinemaColors.Primary else CinemaColors.TextPrimary,
                    radius = thumbRadius,
                    center = Offset(thumbX, centerY),
                )
            }
        }

        val rightLabel = if (showRemaining && safeDuration > 0L) {
            CinemaPlayerUiLogic.formatRemainingTime(displayPos, safeDuration)
        } else {
            playbackTime(safeDuration)
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    enabled = safeDuration > 0L,
                    role = Role.Button,
                    onClickLabel = "Alternar tiempo restante",
                    onClick = { showRemaining = !showRemaining },
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = CinemaColors.TextSecondary,
            )
        }
    }
}
