package org.cinito.online.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.cinito.online.cast.CastDevice
import org.cinito.online.cast.CastSessionState
import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.cast.WebOsRemoteKey
import org.cinito.online.player.CinemaPlayerController
import org.cinito.online.player.CinemaPlayerUiLogic
import org.cinito.online.player.NextEpisodePromptMode
import org.cinito.online.player.PlayerAspectMode
import org.cinito.online.settings.SubtitleEdgeStyle
import org.cinito.online.settings.SubtitleStyleConfig
import org.cinito.online.ui.theme.CinemaColors
import webtor.app.DownloadEntry
import webtor.app.liveDownloadSpeed
import webtor.app.liveStatus
import webtor.app.playbackTime
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaPlayerScreen(
    controller: CinemaPlayerController,
    activeEntry: DownloadEntry?,
    castState: CastSessionState,
    bookmarkUris: Map<String, String> = emptyMap(),
    showRawFilenames: Boolean = false,
    onUpdateSubtitleStyle: (SubtitleStyleConfig) -> Unit = controller::updateSubtitleStyle,
    onBack: () -> Unit,
    onRequestPip: () -> Unit,
    onPickLocalSubtitle: () -> Unit,
    onSelectPlaylistIndex: (Int) -> Unit,
    onStartCastDiscovery: () -> Unit,
    onRefreshCastDiscovery: () -> Unit = onStartCastDiscovery,
    onStopCastDiscovery: () -> Unit,
    onSelectCastDevice: (CastDevice) -> Unit,
    onConnectCastByIp: (String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit = { _, _, _ -> },
    onSelectWebOsMode: (WebOsCastMode) -> Unit = {},
    onRetryCast: () -> Unit = {},
    onCastSelectPlaylistIndex: (Int) -> Unit = onSelectPlaylistIndex,
    onCastTogglePlayPause: () -> Unit,
    onCastSeekTo: (Long) -> Unit,
    onCastVolume: (Float) -> Unit,
    onCastToggleMute: (Boolean) -> Unit = {},
    onCastSelectSubtitle: (Int?) -> Unit,
    onCastToggleTvSubtitles: (Boolean) -> Unit = {},
    onCastSelectAudio: (Int) -> Unit = {},
    onCastSendRemoteKey: (WebOsRemoteKey) -> Unit = {},
    onCastStopPlaybackKeepTv: (() -> Unit)? = null,
    onStopCastingToPhone: () -> Unit,
    onCastDisconnectTv: (() -> Unit)? = null,
    onSaveDirectPlay: ((DownloadEntry) -> Unit)? = null,
    onOpenExternalCast: () -> Unit,
) {
    BackHandler {
        if (controller.state.value.isLocked) {
            controller.setLocked(false)
        } else {
            onBack()
        }
    }

    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val exoPlayer = remember { controller.getOrCreatePlayer() }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactTick by remember { mutableIntStateOf(0) }
    var activeSheet by remember { mutableStateOf(PlayerSheet.None) }
    var desiredLandscape by rememberSaveable { mutableStateOf(true) }

    // Timeline scrubbing state
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    // Transient HUD state (brightness / volume / swipe-seek)
    var gestureHudKind by remember { mutableStateOf(GestureHudKind.Seek) }
    var gestureHudLabel by remember { mutableStateOf("") }
    var gestureHudFraction by remember { mutableStateOf<Float?>(null) }
    var gestureHudVisible by remember { mutableStateOf(false) }
    var gestureDragging by remember { mutableStateOf(false) }
    var gestureEventSeq by remember { mutableIntStateOf(0) }

    // Lateral double-tap accumulator state
    var doubleTapVisible by remember { mutableStateOf(false) }
    var doubleTapDeltaMs by remember { mutableLongStateOf(0L) }
    var doubleTapIsLeft by remember { mutableStateOf(false) }
    var lastDoubleTapAtMs by remember { mutableLongStateOf(0L) }
    var lastDoubleTapSide by remember { mutableStateOf<Boolean?>(null) }
    var doubleTapSeq by remember { mutableIntStateOf(0) }

    // Next episode prompt dismissal + countdown
    var nextEpisodeDismissed by remember { mutableStateOf(false) }
    var autoNextCountdownSec by remember { mutableIntStateOf(5) }

    LaunchedEffect(state.mediaUri, state.currentPlaylistIndex) {
        nextEpisodeDismissed = false
        autoNextCountdownSec = 5
        isScrubbing = false
    }

    // Ensure transient UI overlays/boost reset whenever entering PiP, locking, casting, or pausing
    LaunchedEffect(state.isInPipMode, castState.isConnected) {
        if (state.isInPipMode || castState.isConnected) {
            activeSheet = PlayerSheet.None
        } else {
            runCatching { focusRequester.requestFocus() }
        }
    }

    LaunchedEffect(state.isInPipMode, state.isLocked, castState.isConnected, state.isPlaying) {
        if (state.isInPipMode || state.isLocked || castState.isConnected) {
            isScrubbing = false
            gestureDragging = false
            gestureHudVisible = false
            controller.setTemporarySpeedBoost(false)
        } else if (!state.isPlaying) {
            controller.setTemporarySpeedBoost(false)
        }
    }

    // Immersive fullscreen, keep screen on, and restore default brightness & orientation on exit
    LaunchedEffect(activity, desiredLandscape, state.isInPipMode, castState.isConnected) {
        activity?.requestedOrientation = when {
            state.isInPipMode || castState.isConnected -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            desiredLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.setTemporarySpeedBoost(false)
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val attrs = window.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Auto-hide controls safely without vanishing during timeline scrub, 2x hold, sheets, or end screen
    LaunchedEffect(
        controlsVisible,
        state.isPlaying,
        activeSheet,
        state.isLocked,
        state.isInPipMode,
        isScrubbing,
        state.isTempSpeedBoost,
        state.isEnded,
        interactTick,
    ) {
        val blocked = !controlsVisible ||
            activeSheet != PlayerSheet.None ||
            state.isInPipMode ||
            isScrubbing ||
            state.isTempSpeedBoost ||
            state.isEnded
        if (blocked) return@LaunchedEffect
        if (state.isLocked) {
            delay(2_400L)
            controlsVisible = false
        } else if (state.isPlaying) {
            delay(3_500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureDragging, gestureEventSeq) {
        if (!gestureDragging && gestureHudVisible) {
            delay(550L)
            gestureHudVisible = false
        }
    }

    LaunchedEffect(doubleTapSeq) {
        if (doubleTapSeq > 0) {
            doubleTapVisible = true
            delay(850L)
            doubleTapVisible = false
            doubleTapDeltaMs = 0L
        }
    }

    val nextPromptMode = CinemaPlayerUiLogic.evaluateNextEpisodePrompt(state, nextEpisodeDismissed)
    LaunchedEffect(nextPromptMode, state.currentPlaylistIndex, state.mediaUri) {
        if (nextPromptMode == NextEpisodePromptMode.AutoCountdown) {
            for (sec in 5 downTo 1) {
                autoNextCountdownSec = sec
                delay(1_000L)
            }
            onSelectPlaylistIndex(state.currentPlaylistIndex + 1)
        } else {
            autoNextCountdownSec = 5
        }
    }

    var castDiscoveryStartedAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(activeSheet) {
        if (activeSheet == PlayerSheet.Cast) {
            castDiscoveryStartedAtMs = System.currentTimeMillis()
            onStartCastDiscovery()
        } else if (castDiscoveryStartedAtMs > 0L) {
            val elapsed = System.currentTimeMillis() - castDiscoveryStartedAtMs
            val remaining = (2_500L - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            castDiscoveryStartedAtMs = 0L
            onStopCastDiscovery()
        }
    }
    DisposableEffect(Unit) {
        onDispose { onStopCastDiscovery() }
    }

    fun triggerSeekFeedback(deltaMs: Long, isLeft: Boolean) {
        val now = System.currentTimeMillis()
        val nextDelta = CinemaPlayerUiLogic.nextDoubleTapSeek(
            previousDeltaMs = doubleTapDeltaMs,
            previousSideIsLeft = lastDoubleTapSide,
            lastTapAtMs = lastDoubleTapAtMs,
            nowMs = now,
            isLeft = isLeft,
        )
        lastDoubleTapAtMs = now
        lastDoubleTapSide = isLeft
        doubleTapIsLeft = isLeft
        doubleTapDeltaMs = nextDelta
        doubleTapSeq++
        controller.seekBy(deltaMs)
    }

    val latestState by rememberUpdatedState(state)
    val maxVolume = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown || state.isInPipMode || castState.isConnected) {
                    return@onKeyEvent false
                }
                if (state.isLocked) {
                    controlsVisible = true
                    return@onKeyEvent false
                }
                if (activeSheet != PlayerSheet.None || state.errorMessage != null) {
                    return@onKeyEvent false
                }
                val isFirstPress = keyEvent.nativeKeyEvent.repeatCount == 0
                when (keyEvent.key) {
                    Key.Spacebar, Key.MediaPlayPause, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (isFirstPress) {
                            controller.togglePlayPause()
                            controlsVisible = true
                            interactTick++
                        }
                        true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        triggerSeekFeedback(-10_000L, isLeft = true)
                        interactTick++
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        triggerSeekFeedback(10_000L, isLeft = false)
                        interactTick++
                        true
                    }
                    Key.DirectionUp -> {
                        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val next = (cur + 1).coerceAtMost(maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                        val frac = next.toFloat() / maxVolume.toFloat()
                        gestureHudKind = GestureHudKind.Volume
                        gestureHudLabel = "${(frac * 100).roundToInt()}%"
                        gestureHudFraction = frac
                        gestureHudVisible = true
                        gestureDragging = false
                        gestureEventSeq++
                        true
                    }
                    Key.DirectionDown -> {
                        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val next = (cur - 1).coerceAtLeast(0)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                        val frac = next.toFloat() / maxVolume.toFloat()
                        gestureHudKind = GestureHudKind.Volume
                        gestureHudLabel = "${(frac * 100).roundToInt()}%"
                        gestureHudFraction = frac
                        gestureHudVisible = true
                        gestureDragging = false
                        gestureEventSeq++
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Bounded video viewport preventing vertical overflow when 16:9 / 4:3 are picked in landscape
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()
            val fittedPx = CinemaPlayerUiLogic.computeAspectViewportSize(containerW, containerH, state.aspectMode)
            val videoAspectModifier = when (state.aspectMode) {
                PlayerAspectMode.Ratio16x9, PlayerAspectMode.Ratio4x3 -> with(density) {
                    Modifier.width(fittedPx.width.toDp()).height(fittedPx.height.toDp())
                }
                else -> Modifier.fillMaxSize()
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) {
                        view.player = exoPlayer
                    }
                    view.resizeMode = when (state.aspectMode) {
                        PlayerAspectMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerAspectMode.Fill, PlayerAspectMode.Ratio16x9, PlayerAspectMode.Ratio4x3 ->
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                        PlayerAspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    view.subtitleView?.let { subView ->
                        val style = state.subtitleStyle
                        val bgAlpha = (style.backgroundOpacity.coerceIn(0f, 0.9f) * 255).roundToInt()
                        val edgeType = when (style.edgeStyle) {
                            SubtitleEdgeStyle.DropShadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                            SubtitleEdgeStyle.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                            SubtitleEdgeStyle.None -> CaptionStyleCompat.EDGE_TYPE_NONE
                        }
                        val targetSizeSp = if (state.isInPipMode) {
                            CinemaPlayerUiLogic.computePipSubtitleSizeSp(style.fontSizeSp)
                        } else {
                            style.fontSizeSp
                        }
                        subView.setApplyEmbeddedStyles(true)
                        // Disable embedded font size overrides so user size & PiP scaling always apply
                        subView.setApplyEmbeddedFontSizes(false)
                        subView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, targetSizeSp)
                        subView.setStyle(
                            CaptionStyleCompat(
                                AndroidColor.WHITE,
                                AndroidColor.argb(bgAlpha, 8, 10, 14),
                                AndroidColor.TRANSPARENT,
                                edgeType,
                                AndroidColor.BLACK,
                                null,
                            ),
                        )
                    }
                },
                modifier = videoAspectModifier,
            )
        }

        if (!state.isInPipMode && !castState.isConnected) {
            val edgeGuardPx = with(density) { 28.dp.toPx() }
            var brightnessFraction by remember {
                mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it in 0f..1f } ?: 0.5f)
            }
            var volumeFraction by remember {
                mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat())
            }
            var dragMode by remember { mutableStateOf<String?>(null) }
            var dragRejected by remember { mutableStateOf(false) }
            var initialDragSeekMs by remember { mutableLongStateOf(0L) }
            var dragOffsetAccum by remember { mutableFloatStateOf(0f) }

            // Touch gesture surface keyed cleanly on Unit to survive layout size transitions mid-drag
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (state.isLocked) {
                            Modifier.pointerInput("lockedTap") {
                                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                            }
                        } else {
                            Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { controlsVisible = !controlsVisible },
                                        onDoubleTap = { offset ->
                                            if (size.width <= 0) return@detectTapGestures
                                            triggerSeekFeedback(
                                                deltaMs = if (offset.x < size.width / 2f) -10_000L else 10_000L,
                                                isLeft = offset.x < size.width / 2f,
                                            )
                                        },
                                        onLongPress = {
                                            if (latestState.isPlaying) {
                                                controller.setTemporarySpeedBoost(true)
                                            }
                                        },
                                    )
                                }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Final)
                                                if (event.changes.none { it.pressed }) break
                                            }
                                        } finally {
                                            if (controller.state.value.isTempSpeedBoost) {
                                                controller.setTemporarySpeedBoost(false)
                                            }
                                        }
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            dragMode = null
                                            dragOffsetAccum = 0f
                                            dragRejected = latestState.isTempSpeedBoost ||
                                                !CinemaPlayerUiLogic.isInsideSafeDragZone(
                                                    x = startOffset.x,
                                                    y = startOffset.y,
                                                    width = size.width.toFloat(),
                                                    height = size.height.toFloat(),
                                                    edgeGuardPx = edgeGuardPx,
                                                )
                                            if (dragRejected) return@detectDragGestures
                                            initialDragSeekMs = latestState.positionMs
                                            volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
                                            brightnessFraction = activity?.window?.attributes?.screenBrightness?.takeIf { it in 0f..1f } ?: brightnessFraction
                                        },
                                        onDragEnd = {
                                            if (!dragRejected && dragMode == "seek" && isScrubbing) {
                                                controller.seekTo(scrubPositionMs)
                                            }
                                            isScrubbing = false
                                            dragMode = null
                                            dragRejected = false
                                            gestureDragging = false
                                        },
                                        onDragCancel = {
                                            isScrubbing = false
                                            dragMode = null
                                            dragRejected = false
                                            gestureDragging = false
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (dragRejected || latestState.isTempSpeedBoost) return@detectDragGestures
                                            change.consume()
                                            if (dragMode == null) {
                                                dragMode = when {
                                                    abs(dragAmount.x) > abs(dragAmount.y) -> "seek"
                                                    change.position.x < size.width / 2f -> "brightness"
                                                    else -> "volume"
                                                }
                                            }
                                            gestureDragging = true
                                            when (dragMode) {
                                                "seek" -> if (latestState.durationMs > 0L && size.width > 0) {
                                                    dragOffsetAccum += dragAmount.x
                                                    val deltaMs = ((dragOffsetAccum / size.width.toFloat()) * 120_000f).toLong()
                                                    scrubPositionMs = (initialDragSeekMs + deltaMs).coerceIn(0L, latestState.durationMs)
                                                    isScrubbing = true
                                                    gestureHudKind = GestureHudKind.Seek
                                                    gestureHudLabel = "${playbackTime(scrubPositionMs)} / ${playbackTime(latestState.durationMs)}"
                                                    gestureHudFraction = null
                                                    gestureHudVisible = true
                                                    gestureEventSeq++
                                                }
                                                "brightness" -> if (size.height > 0 && activity != null) {
                                                    val delta = -dragAmount.y / (size.height * 0.65f)
                                                    brightnessFraction = (brightnessFraction + delta).coerceIn(0.02f, 1.0f)
                                                    val attrs = activity.window.attributes
                                                    attrs.screenBrightness = brightnessFraction
                                                    activity.window.attributes = attrs
                                                    gestureHudKind = GestureHudKind.Brightness
                                                    gestureHudLabel = "${(brightnessFraction * 100).roundToInt()}%"
                                                    gestureHudFraction = brightnessFraction
                                                    gestureHudVisible = true
                                                    gestureEventSeq++
                                                }
                                                "volume" -> if (size.height > 0) {
                                                    val delta = -dragAmount.y / (size.height * 0.65f)
                                                    volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
                                                    val targetVol = (volumeFraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                                    gestureHudKind = GestureHudKind.Volume
                                                    gestureHudLabel = "${(volumeFraction * 100).roundToInt()}%"
                                                    gestureHudFraction = volumeFraction
                                                    gestureHudVisible = true
                                                    gestureEventSeq++
                                                }
                                            }
                                        },
                                    )
                                }
                        },
                    ),
            )

            // Informative P2P buffering overlay when stalled / starting without overlapping controls
            val dlSpeed = activeEntry?.liveDownloadSpeed() ?: 0L
            val numPeers = activeEntry?.liveStatus()?.numPeers ?: 0
            val p2pBufferingStatus = if (state.isBuffering && state.errorMessage == null && !isScrubbing) {
                CinemaPlayerUiLogic.describeP2pBufferingStatus(dlSpeed, numPeers)
            } else null
            if (p2pBufferingStatus != null && (!controlsVisible || state.isLocked)) {
                PlayerBufferingOverlay(status = p2pBufferingStatus)
            }

            PlayerGestureHud(
                visible = gestureHudVisible,
                kind = gestureHudKind,
                label = gestureHudLabel,
                fraction = gestureHudFraction,
            )

            PlayerDoubleTapFeedback(
                deltaMs = doubleTapDeltaMs,
                isLeftSide = doubleTapIsLeft,
                visible = doubleTapVisible,
            )

            PlayerSpeedBoostBadge(
                visible = state.isTempSpeedBoost,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            state.errorMessage?.let { err ->
                PlayerErrorCard(
                    errorMessage = err,
                    onRetry = { controller.retry() },
                    onOpenExternal = onOpenExternalCast,
                    onBack = onBack,
                )
            }

            PlayerLockedPill(
                visible = controlsVisible && state.isLocked,
                onUnlock = { controller.setLocked(false) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Full local Controls HUD over soft full-frame cinema scrim
            AnimatedVisibility(
                visible = controlsVisible && !state.isLocked,
                enter = fadeIn(tween(170)),
                exit = fadeOut(tween(130)),
                modifier = Modifier.fillMaxSize(),
            ) {
                val formattedTitle = remember(state.title, showRawFilenames) {
                    CinemaPlayerUiLogic.formatMediaTitle(state.title, showRawFilename = showRawFilenames)
                }
                val showSkipIntro = remember(state.title, state.playlist.size, state.positionMs, state.durationMs) {
                    CinemaPlayerUiLogic.shouldShowSkipIntro(
                        title = state.title,
                        playlistSize = state.playlist.size,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x5A000000)),
                ) {
                    val canSaveDirectPlay = activeEntry != null && activeEntry.directPlay && onSaveDirectPlay != null
                    PlayerTopBar(
                        formattedTitle = formattedTitle,
                        castConnected = castState.isConnected,
                        isLandscape = isLandscape,
                        isSavedInLibrary = activeEntry != null && !activeEntry.directPlay,
                        onSaveToLibrary = if (canSaveDirectPlay) {
                            {
                                interactTick++
                                onSaveDirectPlay!!(activeEntry!!)
                            }
                        } else null,
                        onBack = onBack,
                        onOpenCast = {
                            interactTick++
                            activeSheet = PlayerSheet.Cast
                        },
                        onRequestPip = {
                            interactTick++
                            onRequestPip()
                        },
                        onToggleOrientation = {
                            interactTick++
                            desiredLandscape = !isLandscape
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    if (nextPromptMode != NextEpisodePromptMode.AutoCountdown && state.errorMessage == null) {
                        PlayerCenterTransport(
                            state = state,
                            onPreviousEpisode = {
                                interactTick++
                                onSelectPlaylistIndex(state.currentPlaylistIndex - 1)
                            },
                            onSeekBy = { delta ->
                                interactTick++
                                controller.seekBy(delta)
                            },
                            onTogglePlayPause = {
                                interactTick++
                                controller.togglePlayPause()
                            },
                            onNextEpisode = {
                                interactTick++
                                onSelectPlaylistIndex(state.currentPlaylistIndex + 1)
                            },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    if (nextPromptMode != NextEpisodePromptMode.AutoCountdown) {
                        PlayerBottomBar(
                            state = state,
                            scrubPositionMs = scrubPositionMs,
                            isScrubbing = isScrubbing,
                            showSkipIntro = showSkipIntro,
                            bufferingStatus = p2pBufferingStatus,
                            onScrubStart = { targetMs ->
                                scrubPositionMs = targetMs
                                isScrubbing = true
                            },
                            onScrubMove = { targetMs ->
                                scrubPositionMs = targetMs
                            },
                            onScrubFinish = { targetMs ->
                                controller.seekTo(targetMs)
                                isScrubbing = false
                                interactTick++
                            },
                            onSkipIntro = {
                                interactTick++
                                controller.seekBy(CinemaPlayerUiLogic.SKIP_INTRO_DELTA_MS)
                            },
                            onLock = { controller.setLocked(true) },
                            onOpenSheet = { sheet ->
                                interactTick++
                                activeSheet = sheet
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }

            state.nextPlaylistItem?.let { nextItem ->
                val promptAlign = if (nextPromptMode == NextEpisodePromptMode.AutoCountdown) {
                    Alignment.Center
                } else {
                    Alignment.BottomEnd
                }
                val visiblePromptMode = if (nextPromptMode == NextEpisodePromptMode.PreEnd && controlsVisible && !state.isLocked) {
                    NextEpisodePromptMode.Hidden
                } else {
                    nextPromptMode
                }
                NextEpisodeOverlay(
                    mode = visiblePromptMode,
                    nextEpisodeRawName = nextItem.name,
                    nextIndex = state.currentPlaylistIndex + 1,
                    countdownSec = autoNextCountdownSec,
                    showRawFilenames = showRawFilenames,
                    onPlayNextNow = { onSelectPlaylistIndex(state.currentPlaylistIndex + 1) },
                    onDismiss = { nextEpisodeDismissed = true },
                    modifier = Modifier.align(promptAlign),
                )
            }
        } else if (!state.isInPipMode && castState.isConnected) {
            CastRemoteOverlay(
                title = castState.mediaTitle.ifBlank { state.title },
                castState = castState,
                subtitleStyle = state.subtitleStyle,
                onUpdateSubtitleStyle = onUpdateSubtitleStyle,
                onBack = onBack,
                onOpenCastDevices = { activeSheet = PlayerSheet.Cast },
                onTogglePlayPause = onCastTogglePlayPause,
                onSeekTo = onCastSeekTo,
                onSeekBy = { delta -> onCastSeekTo((castState.positionMs + delta).coerceAtLeast(0L)) },
                onVolumeChange = onCastVolume,
                onToggleMute = onCastToggleMute,
                onSelectAudio = onCastSelectAudio,
                onSelectSubtitle = onCastSelectSubtitle,
                onToggleTvSubtitles = onCastToggleTvSubtitles,
                onPickLocalSubtitle = onPickLocalSubtitle,
                onSelectPlaylistIndex = onCastSelectPlaylistIndex,
                onSelectWebOsMode = onSelectWebOsMode,
                onSendRemoteKey = onCastSendRemoteKey,
                onRetryCast = onRetryCast,
                onStopPlaybackKeepTv = onCastStopPlaybackKeepTv,
                onStopCastingToPhone = onStopCastingToPhone,
                onDisconnectTv = onCastDisconnectTv,
                isSavedInLibrary = activeEntry != null && !activeEntry.directPlay,
                onSaveToLibrary = if (activeEntry != null && activeEntry.directPlay && onSaveDirectPlay != null) {
                    { onSaveDirectPlay(activeEntry) }
                } else null,
            )
        } else if (state.isInPipMode && castState.isConnected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF50B0D12))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = null,
                    tint = CinemaColors.Secondary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = castState.connectedDevice?.name ?: "TV",
                    style = MaterialTheme.typography.labelLarge,
                    color = CinemaColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pipTitle = castState.mediaTitle.ifBlank { state.title }
                if (pipTitle.isNotBlank()) {
                    Text(
                        text = pipTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (!state.isInPipMode && activeSheet != PlayerSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = PlayerSheet.None },
            containerColor = CinemaColors.Surface,
            contentColor = CinemaColors.TextPrimary,
        ) {
            when (activeSheet) {
                PlayerSheet.Audio -> AudioTrackSheet(
                    state = state,
                    onSelect = {
                        controller.selectAudioTrack(it)
                        activeSheet = PlayerSheet.None
                    },
                )
                PlayerSheet.Subtitles -> SubtitleTrackSheet(
                    state = state,
                    onDisable = {
                        controller.disableSubtitles()
                        activeSheet = PlayerSheet.None
                    },
                    onSelect = {
                        controller.selectSubtitleTrack(it)
                        activeSheet = PlayerSheet.None
                    },
                    onDelayChange = { controller.setSubtitleDelayMs(it) },
                    onUpdateSubtitleStyle = onUpdateSubtitleStyle,
                    onPickLocalFile = {
                        activeSheet = PlayerSheet.None
                        onPickLocalSubtitle()
                    },
                )
                PlayerSheet.Speed -> SpeedPickerSheet(
                    current = state.playbackSpeed,
                    onSelect = {
                        controller.setPlaybackSpeed(it)
                        activeSheet = PlayerSheet.None
                    },
                )
                PlayerSheet.Aspect -> AspectPickerSheet(
                    current = state.aspectMode,
                    onSelect = {
                        controller.setAspectMode(it)
                        activeSheet = PlayerSheet.None
                    },
                )
                PlayerSheet.Episodes -> EpisodeListSheet(
                    playlist = state.playlist,
                    currentIndex = state.currentPlaylistIndex,
                    activeEntry = activeEntry,
                    bookmarkUris = bookmarkUris,
                    showRawFilenames = showRawFilenames,
                    onSelect = { idx ->
                        activeSheet = PlayerSheet.None
                        onSelectPlaylistIndex(idx)
                    },
                )
                PlayerSheet.Cast -> CastDeviceSheetContent(
                    castState = castState,
                    onRefreshDiscovery = onRefreshCastDiscovery,
                    onSelectDevice = { dev ->
                        activeSheet = PlayerSheet.None
                        onSelectCastDevice(dev)
                    },
                    onConnectByIp = { ip, onError, onSuccess ->
                        onConnectCastByIp(ip, onError) {
                            onSuccess()
                            activeSheet = PlayerSheet.None
                        }
                    },
                    onSelectWebOsMode = onSelectWebOsMode,
                    onTogglePlayPause = onCastTogglePlayPause,
                    onVolumeChange = onCastVolume,
                    onToggleMute = onCastToggleMute,
                    onSelectSubtitle = onCastSelectSubtitle,
                    onToggleTvSubtitles = onCastToggleTvSubtitles,
                    onSelectAudio = onCastSelectAudio,
                    onStopPlaybackKeepTv = onCastStopPlaybackKeepTv?.let { stopFn ->
                        {
                            activeSheet = PlayerSheet.None
                            stopFn()
                        }
                    },
                    onStopCasting = {
                        activeSheet = PlayerSheet.None
                        onStopCastingToPhone()
                    },
                    onOpenExternalFallback = {
                        activeSheet = PlayerSheet.None
                        onOpenExternalCast()
                    },
                )
                PlayerSheet.None -> Unit
            }
        }
    }
}
