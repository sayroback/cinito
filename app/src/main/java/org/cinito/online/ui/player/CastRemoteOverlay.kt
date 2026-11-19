package org.cinito.online.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cinito.online.cast.CastAudioControlCapability
import org.cinito.online.cast.CastPlaybackStatus
import org.cinito.online.cast.CastSessionState
import org.cinito.online.cast.CastSubtitleControlCapability
import org.cinito.online.cast.CastSubtitleKind
import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.cast.WebOsRemoteKey
import org.cinito.online.player.CinemaPlayerUiLogic
import org.cinito.online.settings.SubtitleStyleConfig
import org.cinito.online.ui.library.languageBadgeColors
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.playbackTime

private enum class RemoteSheetTab {
    None,
    Audio,
    Subtitles,
    Episodes,
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CastRemoteOverlay(
    title: String,
    castState: CastSessionState,
    subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    onUpdateSubtitleStyle: (SubtitleStyleConfig) -> Unit = {},
    onBack: () -> Unit,
    onOpenCastDevices: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onSelectAudio: (Int) -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit = {},
    onToggleTvSubtitles: (Boolean) -> Unit = {},
    onPickLocalSubtitle: () -> Unit = {},
    onSelectPlaylistIndex: (Int) -> Unit = {},
    onSelectWebOsMode: (WebOsCastMode) -> Unit = {},
    onSendRemoteKey: (WebOsRemoteKey) -> Unit = {},
    onRetryCast: () -> Unit = {},
    onStopPlaybackKeepTv: (() -> Unit)? = null,
    onStopCastingToPhone: (() -> Unit)? = null,
    onDisconnectTv: (() -> Unit)? = null,
    isSavedInLibrary: Boolean = false,
    onSaveToLibrary: (() -> Unit)? = null,
) {
    val dev = castState.connectedDevice ?: return
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableLongStateOf(0L) }
    var scrubbingVol by remember { mutableStateOf(false) }
    var volDraft by remember { mutableFloatStateOf(castState.volume) }
    var lastVolChangeAtMs by remember { mutableLongStateOf(0L) }
    var activeRemoteSheet by remember { mutableStateOf(RemoteSheetTab.None) }
    var dpadExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(castState.activeSourceUri, castState.hasActiveMedia) {
        scrubbing = false
    }
    LaunchedEffect(castState.volume) {
        if (scrubbingVol && System.currentTimeMillis() - lastVolChangeAtMs > 1_200L) {
            scrubbingVol = false
        }
        if (!scrubbingVol) {
            volDraft = castState.volume
        }
    }
    LaunchedEffect(scrubbingVol, lastVolChangeAtMs) {
        if (scrubbingVol) {
            delay(1_800L)
            scrubbingVol = false
        }
    }

    val sliderColors = SliderDefaults.colors(
        thumbColor = CinemaColors.Primary,
        activeTrackColor = CinemaColors.Primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.24f),
    )

    val resolvedTitle = title.ifBlank { castState.mediaTitle }
    val formattedTitle = remember(resolvedTitle) {
        if (resolvedTitle.isBlank()) null else CinemaPlayerUiLogic.formatMediaTitle(resolvedTitle)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Minimizar control remoto", tint = CinemaColors.TextPrimary)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = dev.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                castState.activeModeLabel?.let { mode ->
                    Text(
                        text = mode,
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onOpenCastDevices,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(Icons.Default.CastConnected, contentDescription = "Cambiar TV o modo", tint = CinemaColors.Primary)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = CinemaColors.SurfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (castState.hasActiveMedia) CinemaColors.Secondary.copy(alpha = 0.55f) else CinemaColors.Primary.copy(alpha = 0.45f),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = if (castState.hasActiveMedia) CinemaColors.Secondary else CinemaColors.Primary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            if (formattedTitle != null && castState.hasActiveMedia) {
                Text(
                    text = formattedTitle.primaryTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = CinemaColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                formattedTitle.episodeBadge?.let { epBadge ->
                    Surface(shape = RoundedCornerShape(8.dp), color = CinemaColors.PrimaryMuted) {
                        Text(
                            text = epBadge,
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = CinemaColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            } else {
                Text(
                    text = "TV lista para recibir contenido",
                    style = MaterialTheme.typography.titleLarge,
                    color = CinemaColors.TextPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Vuelve atrás o explora el catálogo: cualquier película, capítulo o enlace que elijas se enviará directo a ${dev.name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            castState.pairingPromptDeviceName?.let { tvName ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.PrimaryMuted,
                    border = BorderStroke(1.dp, CinemaColors.Primary),
                ) {
                    Text(
                        text = "Acepta «CiniTo» en la pantalla de tu TV LG ($tvName)",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }

            if (castState.hasActiveMedia) {
                val statusText = when {
                    castState.status == CastPlaybackStatus.Error && castState.errorMessage != null -> castState.errorMessage!!
                    castState.status == CastPlaybackStatus.Connecting -> "Conectando con ${dev.name}…"
                    castState.status == CastPlaybackStatus.Buffering -> "Cargando en el televisor…"
                    castState.status == CastPlaybackStatus.Playing -> "${dev.name} · Reproduciendo"
                    castState.status == CastPlaybackStatus.Paused -> "${dev.name} · En pausa"
                    castState.status == CastPlaybackStatus.Error -> "Error en el televisor"
                    else -> dev.name
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (castState.status == CastPlaybackStatus.Error) CinemaColors.Danger else CinemaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            if (castState.status == CastPlaybackStatus.Error) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRetryCast,
                        modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                    ) {
                        Text("Reintentar")
                    }
                    if (castState.canSwitchWebOsMode) {
                        val altMode = if (castState.activeWebOsMode == WebOsCastMode.Browser && dev.hasDlna) {
                            WebOsCastMode.Dlna
                        } else {
                            WebOsCastMode.Browser
                        }
                        OutlinedButton(
                            onClick = { onSelectWebOsMode(altMode) },
                            modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                        ) {
                            Text("Probar ${altMode.label}")
                        }
                    }
                }
            }

            // Primary transport controls when media is loaded
            if (castState.hasActiveMedia) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (castState.hasPreviousEpisode) {
                        IconButton(
                            onClick = { onSelectPlaylistIndex(castState.currentPlaylistIndex - 1) },
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Episodio anterior", tint = CinemaColors.TextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                    IconButton(onClick = { onSeekBy(-10_000L) }, modifier = Modifier.size(50.dp)) {
                        Icon(Icons.Default.Replay10, contentDescription = "Retroceder 10 segundos", tint = CinemaColors.TextPrimary, modifier = Modifier.size(32.dp))
                    }
                    val isCastPlaying = castState.status == CastPlaybackStatus.Playing
                    Surface(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(68.dp),
                        shape = CircleShape,
                        color = CinemaColors.Primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isCastPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isCastPlaying) "Pausar" else "Reproducir",
                                tint = CinemaColors.TextPrimary,
                                modifier = Modifier
                                    .size(36.dp)
                                    .then(if (!isCastPlaying) Modifier.offset(x = 2.dp) else Modifier),
                            )
                        }
                    }
                    IconButton(onClick = { onSeekBy(10_000L) }, modifier = Modifier.size(50.dp)) {
                        Icon(Icons.Default.Forward10, contentDescription = "Adelantar 10 segundos", tint = CinemaColors.TextPrimary, modifier = Modifier.size(32.dp))
                    }
                    if (castState.hasNextEpisode) {
                        IconButton(
                            onClick = { onSelectPlaylistIndex(castState.currentPlaylistIndex + 1) },
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Siguiente episodio", tint = CinemaColors.TextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                }

                // Quick Track & Episode Pills (Audio / Subtitles / Playlist)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val activeAud = castState.audioTracks.firstOrNull { it.index == castState.activeAudioIndex }
                        ?: castState.audioTracks.firstOrNull()
                    RemoteQuickOptionChip(
                        icon = Icons.Default.Audiotrack,
                        label = when {
                            activeAud != null -> "Audio: ${activeAud.name}"
                            castState.audioTracks.isEmpty() -> "Audio TV"
                            else -> "Audio"
                        },
                        highlighted = castState.audioTracks.size > 1,
                        onClick = { activeRemoteSheet = RemoteSheetTab.Audio },
                    )
                    val activeSub = castState.activeSubtitleIndex?.let { idx ->
                        castState.subtitles.firstOrNull { it.index == idx } ?: castState.subtitles.getOrNull(idx)
                    }
                    val isDlnaSubCapable = castState.subtitleControlCapability == CastSubtitleControlCapability.DlnaNativeToggle ||
                        castState.subtitleControlCapability == CastSubtitleControlCapability.MixedExternalAndNative
                    val subtitleChipLabel = when {
                        activeSub != null -> "Sub: ${activeSub.name}"
                        isDlnaSubCapable && castState.subtitlesEnabledOnTv != null ->
                            if (castState.subtitlesEnabledOnTv) "Subs TV: ON" else "Subs TV: OFF"
                        castState.subtitles.isNotEmpty() -> "Subs: Apagados"
                        else -> "Subtítulos"
                    }
                    RemoteQuickOptionChip(
                        icon = Icons.Default.Subtitles,
                        label = subtitleChipLabel,
                        highlighted = activeSub != null || (isDlnaSubCapable && castState.subtitlesEnabledOnTv == true),
                        onClick = { activeRemoteSheet = RemoteSheetTab.Subtitles },
                    )
                    if (castState.playlist.size > 1) {
                        RemoteQuickOptionChip(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            label = "Episodios (${castState.currentPlaylistIndex + 1}/${castState.playlist.size})",
                            highlighted = false,
                            onClick = { activeRemoteSheet = RemoteSheetTab.Episodes },
                        )
                    }
                    if (onSaveToLibrary != null) {
                        RemoteQuickOptionChip(
                            icon = Icons.Default.CloudDownload,
                            label = "Descargar",
                            highlighted = true,
                            onClick = onSaveToLibrary,
                        )
                    } else if (isSavedInLibrary) {
                        RemoteQuickOptionChip(
                            icon = Icons.Default.CheckCircle,
                            label = "En biblioteca",
                            highlighted = false,
                            onClick = {},
                        )
                    }
                }
            }

            // Real TV volume card with Mute & +/- precision step buttons
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CinemaColors.Surface,
                border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val shownVol = if (scrubbingVol) volDraft else castState.volume
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { onToggleMute(!castState.isMuted) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (castState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Silenciar",
                                tint = if (castState.isMuted) CinemaColors.Danger else CinemaColors.TextSecondary,
                            )
                        }
                        IconButton(
                            onClick = {
                                val next = (shownVol - 0.05f).coerceIn(0f, 1f)
                                scrubbingVol = false
                                volDraft = next
                                onVolumeChange(next)
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Bajar volumen", tint = CinemaColors.TextPrimary)
                        }
                        Slider(
                            value = shownVol,
                            onValueChange = {
                                scrubbingVol = true
                                volDraft = it
                                lastVolChangeAtMs = System.currentTimeMillis()
                            },
                            onValueChangeFinished = {
                                scrubbingVol = false
                                onVolumeChange(volDraft)
                            },
                            valueRange = 0f..1f,
                            colors = sliderColors,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Volumen del televisor"
                                    stateDescription = "${(shownVol * 100).toInt()} por ciento"
                                },
                        )
                        IconButton(
                            onClick = {
                                val next = (shownVol + 0.05f).coerceIn(0f, 1f)
                                scrubbingVol = false
                                volDraft = next
                                onVolumeChange(next)
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Subir volumen", tint = CinemaColors.TextPrimary)
                        }
                    }
                }
            }

            // Collapsible LG SSAP D-Pad Remote for native TV menus (audio/subs inside LG DLNA player)
            if (castState.hasSsapControl) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CinemaColors.Surface,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dpadExpanded = !dpadExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Gamepad, contentDescription = null, tint = CinemaColors.PrimarySoft, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Control remoto direccional LG",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CinemaColors.TextPrimary,
                                )
                            }
                            Icon(
                                imageVector = if (dpadExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (dpadExpanded) "Ocultar control" else "Mostrar control",
                                tint = CinemaColors.TextSecondary,
                            )
                        }
                        AnimatedVisibility(visible = dpadExpanded) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Navega los menús del televisor (útil en DLNA para abrir opciones nativas de audio/imagen).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaColors.TextSecondary,
                                    textAlign = TextAlign.Center,
                                )
                                RemoteDpadKeyButton(Icons.Default.KeyboardArrowUp, "Arriba") {
                                    onSendRemoteKey(WebOsRemoteKey.UP)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RemoteDpadKeyButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Izquierda") {
                                        onSendRemoteKey(WebOsRemoteKey.LEFT)
                                    }
                                    Surface(
                                        onClick = { onSendRemoteKey(WebOsRemoteKey.ENTER) },
                                        modifier = Modifier.size(54.dp),
                                        shape = CircleShape,
                                        color = CinemaColors.PrimaryMuted,
                                        border = BorderStroke(1.dp, CinemaColors.Primary),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("OK", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    RemoteDpadKeyButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Derecha") {
                                        onSendRemoteKey(WebOsRemoteKey.RIGHT)
                                    }
                                }
                                RemoteDpadKeyButton(Icons.Default.KeyboardArrowDown, "Abajo") {
                                    onSendRemoteKey(WebOsRemoteKey.DOWN)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                ) {
                                    TextButton(onClick = { onSendRemoteKey(WebOsRemoteKey.BACK) }) {
                                        Text("Atrás")
                                    }
                                    TextButton(onClick = { onSendRemoteKey(WebOsRemoteKey.INFO) }) {
                                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Info")
                                    }
                                    TextButton(onClick = { onSendRemoteKey(WebOsRemoteKey.HOME) }) {
                                        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Home")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Output / session actions
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (castState.hasActiveMedia && onStopPlaybackKeepTv != null) {
                    OutlinedButton(
                        onClick = onStopPlaybackKeepTv,
                        modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Detener video")
                    }
                }
                if (castState.hasActiveMedia && onStopCastingToPhone != null) {
                    Button(
                        onClick = onStopCastingToPhone,
                        modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ver en teléfono")
                    }
                }
                if (onDisconnectTv != null) {
                    OutlinedButton(
                        onClick = onDisconnectTv,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CinemaColors.Danger),
                        border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.5f)),
                        modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                    ) {
                        Text("Desconectar TV")
                    }
                }
            }
        }

        // Bottom timeline scrubber when media is active
        if (castState.hasActiveMedia) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                val safeDuration = castState.durationMs.coerceAtLeast(0L)
                val hasDuration = safeDuration > 0L
                val pos = if (scrubbing && hasDuration) scrubPos else castState.positionMs
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playbackTime(pos), style = MaterialTheme.typography.labelMedium.tabular(), color = CinemaColors.TextPrimary)
                    Text(
                        if (hasDuration) playbackTime(safeDuration) else "--:--",
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = CinemaColors.TextSecondary,
                    )
                }
                Slider(
                    enabled = hasDuration,
                    value = if (hasDuration) (pos.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac ->
                        if (!hasDuration) return@Slider
                        scrubbing = true
                        scrubPos = (frac * safeDuration).toLong()
                    },
                    onValueChangeFinished = {
                        if (scrubbing && hasDuration) {
                            onSeekTo(scrubPos)
                        }
                        scrubbing = false
                    },
                    colors = sliderColors,
                )
            }
        }
    }

    if (activeRemoteSheet != RemoteSheetTab.None) {
        ModalBottomSheet(
            onDismissRequest = { activeRemoteSheet = RemoteSheetTab.None },
            containerColor = CinemaColors.Surface,
            contentColor = CinemaColors.TextPrimary,
        ) {
            when (activeRemoteSheet) {
                RemoteSheetTab.Audio -> RemoteAudioSheet(
                    castState = castState,
                    onSelectAudio = { idx ->
                        onSelectAudio(idx)
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                    onSwitchMode = { mode ->
                        onSelectWebOsMode(mode)
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                    onOpenDpad = {
                        dpadExpanded = true
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                )
                RemoteSheetTab.Subtitles -> RemoteSubtitleSheet(
                    castState = castState,
                    subtitleStyle = subtitleStyle,
                    onSelectSubtitle = { idx ->
                        onSelectSubtitle(idx)
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                    onToggleTvSubtitles = onToggleTvSubtitles,
                    onPickLocalSubtitle = {
                        activeRemoteSheet = RemoteSheetTab.None
                        onPickLocalSubtitle()
                    },
                    onUpdateSubtitleStyle = onUpdateSubtitleStyle,
                    onSwitchMode = { mode ->
                        onSelectWebOsMode(mode)
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                    onRemoteKey = onSendRemoteKey,
                    onOpenDpad = {
                        dpadExpanded = true
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                )
                RemoteSheetTab.Episodes -> RemoteEpisodesSheet(
                    castState = castState,
                    onSelectIndex = { idx ->
                        onSelectPlaylistIndex(idx)
                        activeRemoteSheet = RemoteSheetTab.None
                    },
                )
                RemoteSheetTab.None -> Unit
            }
        }
    }
}

@Composable
private fun RemoteQuickOptionChip(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
        border = BorderStroke(1.dp, if (highlighted) CinemaColors.Primary.copy(alpha = 0.55f) else CinemaColors.HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) CinemaColors.PrimarySoft else CinemaColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = CinemaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RemoteDpadKeyButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = CinemaColors.SurfaceElevated,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = CinemaColors.TextPrimary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteAudioSheet(
    castState: CastSessionState,
    onSelectAudio: (Int) -> Unit,
    onSwitchMode: (WebOsCastMode) -> Unit,
    onOpenDpad: () -> Unit,
) {
    val canSwitchDirectly = castState.audioControlCapability == CastAudioControlCapability.NativeSwitch
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Pistas de audio en TV", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
        if (castState.audioTracks.isEmpty()) {
            Text(
                text = "No se detectaron metadatos de múltiples pistas de audio en el contenedor actual.",
                style = MaterialTheme.typography.bodyMedium,
                color = CinemaColors.TextSecondary,
            )
        } else {
            castState.audioTracks.forEach { track ->
                val selected = castState.activeAudioIndex == track.index
                val (_, accent) = languageBadgeColors(track.regionTag)
                Surface(
                    onClick = { onSelectAudio(track.index) },
                    enabled = canSwitchDirectly,
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, if (selected) CinemaColors.Primary else CinemaColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = if (canSwitchDirectly) accent else accent.copy(alpha = 0.45f),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pista ${track.index + 1} · ${track.name}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (canSwitchDirectly) CinemaColors.TextPrimary else CinemaColors.TextSecondary,
                            )
                            val detail = listOfNotNull(track.language, track.codec).joinToString(" · ")
                            if (detail.isNotBlank()) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
                            }
                        }
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = "Activa", tint = CinemaColors.Secondary)
                        }
                    }
                }
            }
        }

        when (castState.audioControlCapability) {
            CastAudioControlCapability.AssistedRemoteMenu -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "En modo DLNA cambia el audio desde el menú de tu TV (usa el control direccional).",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (castState.hasSsapControl) {
                                OutlinedButton(onClick = onOpenDpad) {
                                    Text("Abrir Control TV")
                                }
                            }
                            if (castState.hasCiniToTvApp) {
                                OutlinedButton(onClick = { onSwitchMode(WebOsCastMode.ReceiverApp) }) {
                                    Text("Usar App CiniTo TV")
                                }
                            }
                            OutlinedButton(onClick = { onSwitchMode(WebOsCastMode.Browser) }) {
                                Text("Usar Navegador webOS")
                            }
                        }
                    }
                }
            }
            CastAudioControlCapability.SingleTrack, CastAudioControlCapability.None -> {
                if (castState.audioTracks.isNotEmpty() || castState.audioControlCapability == CastAudioControlCapability.SingleTrack) {
                    Text(
                        text = "Este reproductor/navegador no permite cambiar pistas de audio remotamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }
            CastAudioControlCapability.NativeSwitch -> Unit
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RemoteSubtitleSheet(
    castState: CastSessionState,
    subtitleStyle: SubtitleStyleConfig,
    onSelectSubtitle: (Int?) -> Unit,
    onToggleTvSubtitles: (Boolean) -> Unit = {},
    onPickLocalSubtitle: () -> Unit,
    onUpdateSubtitleStyle: (SubtitleStyleConfig) -> Unit,
    onSwitchMode: (WebOsCastMode) -> Unit = {},
    onRemoteKey: (WebOsRemoteKey) -> Unit = {},
    onOpenDpad: () -> Unit = {},
) {
    val capability = castState.subtitleControlCapability
    val isDlnaCapable = capability == CastSubtitleControlCapability.DlnaNativeToggle ||
        capability == CastSubtitleControlCapability.MixedExternalAndNative

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Subtítulos en TV", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
        OutlinedButton(
            onClick = onPickLocalSubtitle,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp),
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Cargar archivo (.srt / .vtt / .ass)")
        }

        if (isDlnaCapable) {
            DlnaTvSubtitleCard(
                castState = castState,
                onToggleTvSubtitles = onToggleTvSubtitles,
                onRemoteKey = onRemoteKey,
                onOpenDpad = onOpenDpad,
            )
        }

        if (castState.subtitles.isNotEmpty()) {
            if (isDlnaCapable) {
                Text(
                    text = "Pistas externas / extraídas",
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaColors.TextSecondary,
                )
            }
            Surface(
                onClick = { onSelectSubtitle(null) },
                shape = RoundedCornerShape(12.dp),
                color = if (castState.activeSubtitleIndex == null) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
                border = BorderStroke(1.dp, if (castState.activeSubtitleIndex == null) CinemaColors.Primary else CinemaColors.HairlineBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Apagados (sin subtítulos)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (castState.activeSubtitleIndex == null) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = CinemaColors.Secondary)
                    }
                }
            }
            castState.subtitles.forEach { sub ->
                val selected = castState.activeSubtitleIndex == sub.index
                val originDetail = formatSubtitleOriginDetail(sub.kind, sub.language)
                Surface(
                    onClick = { onSelectSubtitle(sub.index) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, if (selected) CinemaColors.Primary else CinemaColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sub.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            originDetail?.let { detail ->
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
                            }
                        }
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = CinemaColors.Secondary)
                        }
                    }
                }
            }
            if (capability == CastSubtitleControlCapability.ExternalTrackSelect) {
                HorizontalDivider(color = CinemaColors.HairlineBorder)
                Text("Tamaño en TV: ${subtitleStyle.fontSizeSp.toInt()} pt", style = MaterialTheme.typography.labelMedium, color = CinemaColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onUpdateSubtitleStyle(subtitleStyle.copy(fontSizeSp = (subtitleStyle.fontSizeSp - 2f).coerceAtLeast(14f))) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Más pequeño", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("A-")
                    }
                    OutlinedButton(
                        onClick = { onUpdateSubtitleStyle(subtitleStyle.copy(fontSizeSp = (subtitleStyle.fontSizeSp + 2f).coerceAtMost(32f))) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Más grande", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("A+")
                    }
                }
            }
        } else if (!isDlnaCapable) {
            val labels = castState.embeddedSubtitleLabels
            if (labels.isNotEmpty()) {
                val message = if (capability == CastSubtitleControlCapability.Unsupported) {
                    "Este MKV está en streaming con ${labels.size} pistas embebidas (${labels.take(3).joinToString()}) que no se pueden extraer hasta completar la descarga."
                } else {
                    "Este video incluye ${labels.size} pistas embebidas (${labels.take(3).joinToString()}) que no están disponibles como pistas externas aquí. Puedes cargar un archivo .srt/.vtt/.ass arriba."
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextSecondary,
                        )
                        if (castState.canSwitchWebOsMode && castState.connectedDevice?.hasDlna == true) {
                            Button(
                                onClick = { onSwitchMode(WebOsCastMode.Dlna) },
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp),
                            ) {
                                Text("Cambiar a DLNA Hardware")
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Este video no incluye archivos de subtítulos externos (.srt / .vtt) en el torrent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DlnaTvSubtitleCard(
    castState: CastSessionState,
    onToggleTvSubtitles: (Boolean) -> Unit,
    onRemoteKey: (WebOsRemoteKey) -> Unit,
    onOpenDpad: () -> Unit,
) {
    val tvSubsEnabled = castState.subtitlesEnabledOnTv ?: false
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (tvSubsEnabled) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (tvSubsEnabled) CinemaColors.Primary else CinemaColors.HairlineBorder,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Subtítulos en el televisor (DLNA)",
                        style = MaterialTheme.typography.titleMedium,
                        color = CinemaColors.TextPrimary,
                    )
                    Text(
                        text = when (castState.subtitlesEnabledOnTv) {
                            true -> "Encendidos en el reproductor del TV"
                            false -> "Apagados en el reproductor del TV"
                            null -> "Controla los subtítulos nativos en tu TV"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
                Switch(
                    checked = tvSubsEnabled,
                    onCheckedChange = onToggleTvSubtitles,
                )
            }

            if (castState.embeddedSubtitleLabels.isNotEmpty()) {
                val labels = castState.embeddedSubtitleLabels
                HorizontalDivider(color = CinemaColors.HairlineBorder)
                Text(
                    text = "${labels.size} pistas embebidas en el archivo",
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaColors.TextPrimary,
                )
                Text(
                    text = labels.take(5).joinToString(" · ") +
                        if (labels.size > 5) " (+${labels.size - 5})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
                if (castState.hasSsapControl) {
                    OutlinedButton(
                        onClick = {
                            onRemoteKey(WebOsRemoteKey.INFO)
                            onOpenDpad()
                        },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Abrir menú TV para cambiar pista")
                    }
                }
            }
        }
    }
}

private fun formatSubtitleOriginDetail(kind: CastSubtitleKind, language: String?): String? {
    val lang = language?.takeIf { it.isNotBlank() }?.uppercase()
    return when (kind) {
        CastSubtitleKind.ExtractedEmbedded -> listOfNotNull("Embebido MKV", lang).joinToString(" · ")
        CastSubtitleKind.LocalFile -> listOfNotNull("Archivo local", lang).joinToString(" · ")
        CastSubtitleKind.NativeEmbedded -> listOfNotNull("Nativo TV", lang).joinToString(" · ")
        CastSubtitleKind.ExternalSidecar -> lang
    }
}

@Composable
private fun RemoteEpisodesSheet(
    castState: CastSessionState,
    onSelectIndex: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Episodios / Lista en TV", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            itemsIndexed(castState.playlist, key = { idx, item -> "$idx:${item.uri}" }) { idx, item ->
                val isCurrent = idx == castState.currentPlaylistIndex
                val formatted = remember(item.name) { CinemaPlayerUiLogic.formatMediaTitle(item.name) }
                Surface(
                    onClick = { onSelectIndex(idx) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isCurrent) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, if (isCurrent) CinemaColors.Primary else CinemaColors.HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = formatted.episodeBadge ?: "#${idx + 1}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = if (isCurrent) CinemaColors.Secondary else CinemaColors.PrimarySoft,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = formatted.primaryTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "En TV", tint = CinemaColors.Secondary)
                        }
                    }
                }
            }
        }
    }
}
