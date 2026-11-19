package org.cinito.online.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cinito.online.cast.CastAudioControlCapability
import org.cinito.online.cast.CastDevice
import org.cinito.online.cast.CastPlaybackStatus
import org.cinito.online.cast.CastProtocol
import org.cinito.online.cast.CastSessionState
import org.cinito.online.cast.CastSubtitleControlCapability
import org.cinito.online.cast.CastSubtitleKind
import org.cinito.online.cast.CastVendor
import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.playbackTime

@Composable
fun CastTopBarActionButton(
    castState: CastSessionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overHeroScrim: Boolean = false,
    compact: Boolean = false,
) {
    if (castState.isConnected) {
        val devName = castState.connectedDevice?.name ?: "TV"
        val tint = if (castState.hasActiveMedia) CinemaColors.Secondary else CinemaColors.PrimarySoft
        if (compact && !overHeroScrim) {
            Surface(
                modifier = modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .clickable(role = Role.Button, onClick = onClick),
                shape = RoundedCornerShape(19.dp),
                color = CinemaColors.PrimaryMuted,
                border = BorderStroke(1.dp, tint.copy(alpha = 0.65f)),
            ) {
                Icon(
                    imageVector = Icons.Default.CastConnected,
                    contentDescription = "Conectado a $devName",
                    tint = tint,
                    modifier = Modifier.padding(10.dp).size(18.dp),
                )
            }
        } else {
            Surface(
                modifier = modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button, onClick = onClick),
                shape = RoundedCornerShape(18.dp),
                color = if (overHeroScrim) Color.Black.copy(alpha = 0.68f) else CinemaColors.PrimaryMuted,
                border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CastConnected,
                        contentDescription = "Conectado a $devName",
                        tint = tint,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = devName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 112.dp),
                    )
                }
            }
        }
    } else if (overHeroScrim) {
        Surface(
            modifier = modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onClick),
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.56f),
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Conectar a TV",
                tint = Color.White,
                modifier = Modifier.padding(10.dp),
            )
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = "Conectar a TV",
                tint = CinemaColors.TextPrimary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CastDeviceSheetContent(
    castState: CastSessionState,
    onRefreshDiscovery: () -> Unit,
    onSelectDevice: (CastDevice) -> Unit,
    onConnectByIp: (String, onError: (String) -> Unit, onSuccess: () -> Unit) -> Unit = { _, _, _ -> },
    onSelectWebOsMode: (WebOsCastMode) -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: (Boolean) -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit,
    onToggleTvSubtitles: (Boolean) -> Unit = {},
    onSelectAudio: (Int) -> Unit = {},
    onStopPlaybackKeepTv: (() -> Unit)? = null,
    onOpenRemote: (() -> Unit)? = null,
    onStopCasting: () -> Unit,
    onOpenExternalFallback: (() -> Unit)? = null,
) {
    var showManualIp by remember { mutableStateOf(false) }
    var manualIpInput by remember { mutableStateOf("") }
    var manualIpBusy by remember { mutableStateOf(false) }
    var manualIpError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (castState.isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                        contentDescription = null,
                        tint = CinemaColors.Primary,
                    )
                    Text(
                        text = when {
                            castState.hasActiveMedia -> "Transmitiendo en TV"
                            castState.isConnected -> "TV conectada · En espera"
                            else -> "Conectar con TV / LG webOS"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showManualIp = !showManualIp },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("IP")
                    }
                    IconButton(
                        onClick = onRefreshDiscovery,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Volver a buscar", tint = CinemaColors.TextSecondary)
                    }
                }
            }
            if (castState.isDiscovering || castState.isConnectingDevice) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CinemaColors.Primary,
                    trackColor = CinemaColors.SurfaceBright,
                )
            }
        }

        item {
            AnimatedVisibility(visible = showManualIp) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Conectar directo por IP local (útil si el router bloquea SSDP)",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = manualIpInput,
                                onValueChange = {
                                    manualIpInput = it
                                    manualIpError = null
                                },
                                placeholder = { Text("192.168.1.100") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        val clean = manualIpInput.trim()
                                        if (clean.isNotEmpty() && !manualIpBusy) {
                                            manualIpBusy = true
                                            onConnectByIp(
                                                clean,
                                                { err ->
                                                    manualIpBusy = false
                                                    manualIpError = err
                                                },
                                                {
                                                    manualIpBusy = false
                                                    showManualIp = false
                                                },
                                            )
                                        }
                                    },
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                enabled = manualIpInput.isNotBlank() && !manualIpBusy,
                                onClick = {
                                    manualIpBusy = true
                                    manualIpError = null
                                    onConnectByIp(
                                        manualIpInput.trim(),
                                        { err ->
                                            manualIpBusy = false
                                            manualIpError = err
                                        },
                                        {
                                            manualIpBusy = false
                                            showManualIp = false
                                        },
                                    )
                                },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                if (manualIpBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = CinemaColors.TextPrimary,
                                    )
                                } else {
                                    Text("Conectar")
                                }
                            }
                        }
                        manualIpError?.let { err ->
                            Text(err, style = MaterialTheme.typography.bodySmall, color = CinemaColors.Danger)
                        }
                    }
                }
            }
        }

        castState.pairingPromptDeviceName?.let { tvName ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.PrimaryMuted,
                    border = BorderStroke(1.dp, CinemaColors.Primary),
                ) {
                    Text(
                        text = "Mira tu TV LG ($tvName): acepta la solicitud de conexión de «CiniTo» con el control remoto (solo la primera vez).",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextPrimary,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }

        castState.errorMessage?.let { err ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.Danger.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.Danger,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }

        castState.connectedDevice?.let { activeTv ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.Primary.copy(alpha = 0.5f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(activeTv.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                val modeTag = castState.activeModeLabel ?: activeTv.protocol.label
                                val subtitle = if (castState.hasActiveMedia) {
                                    "$modeTag · ${playbackTime(castState.positionMs)} / ${playbackTime(castState.durationMs)}"
                                } else {
                                    "$modeTag · Tu celular actúa como control remoto"
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = CinemaColors.Secondary,
                                )
                            }
                            if (castState.hasActiveMedia) {
                                IconButton(
                                    onClick = onTogglePlayPause,
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                ) {
                                    Icon(
                                        imageVector = if (castState.status == CastPlaybackStatus.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Pausar o reproducir",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }

                        if (!castState.hasActiveMedia) {
                            Text(
                                text = "Toca «Enviar a TV» o «Reproducir» en cualquier película, serie o fuente de búsqueda para mandarlo directo a esta pantalla.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                            )
                        }

                        if (castState.canSwitchWebOsMode) {
                            val modeHint = if (castState.hasCiniToTvApp) {
                                "Modo webOS (App CiniTo TV detectada en Modo Desarrollador)"
                            } else {
                                "Modo de envío en LG webOS (TV stock sin root)"
                            }
                            Text(modeHint, style = MaterialTheme.typography.labelMedium, color = CinemaColors.TextSecondary)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                WebOsCastMode.entries.forEach { mode ->
                                    if (mode == WebOsCastMode.ReceiverApp && !castState.hasCiniToTvApp && castState.activeWebOsMode != WebOsCastMode.ReceiverApp) {
                                        return@forEach
                                    }
                                    val modeEnabled = if (mode == WebOsCastMode.Dlna) activeTv.hasDlna else true
                                    FilterChip(
                                        selected = castState.activeWebOsMode == mode,
                                        enabled = modeEnabled,
                                        onClick = { onSelectWebOsMode(mode) },
                                        label = { Text(mode.label, maxLines = 1) },
                                    )
                                }
                            }
                            if (!activeTv.hasDlna) {
                                Text(
                                    text = if (castState.isDiscovering) {
                                        "Buscando servicios DLNA Hardware de esta TV…"
                                    } else {
                                        "DLNA Hardware aún no detectado por SSDP en esta TV (toca «Volver a buscar» arriba si lo necesitas)."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaColors.TextSecondary,
                                )
                            }
                        }

                        var scrubbingVol by remember { mutableStateOf(false) }
                        var volDraft by remember { mutableFloatStateOf(castState.volume) }
                        var lastVolChangeAtMs by remember { mutableLongStateOf(0L) }
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
                        val shownVol = if (scrubbingVol) volDraft else castState.volume
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { onToggleMute(!castState.isMuted) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = if (castState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (castState.isMuted) "Activar sonido" else "Silenciar TV",
                                    tint = if (castState.isMuted) CinemaColors.Danger else CinemaColors.TextSecondary,
                                    modifier = Modifier.size(20.dp),
                                )
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
                                colors = SliderDefaults.colors(
                                    thumbColor = CinemaColors.Primary,
                                    activeTrackColor = CinemaColors.Primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "Volumen del televisor"
                                        stateDescription = "${(shownVol * 100).toInt()} por ciento"
                                    },
                            )
                        }

                        if (castState.hasActiveMedia && castState.audioTracks.isNotEmpty()) {
                            val canSwitchAudio = castState.audioControlCapability == CastAudioControlCapability.NativeSwitch
                            Text("Pista de audio en TV", style = MaterialTheme.typography.labelMedium, color = CinemaColors.TextSecondary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                castState.audioTracks.forEach { aud ->
                                    val label = buildString {
                                        append(aud.name)
                                        aud.codec?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                                    }
                                    FilterChip(
                                        selected = castState.activeAudioIndex == aud.index,
                                        enabled = canSwitchAudio,
                                        onClick = { onSelectAudio(aud.index) },
                                        leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(15.dp)) },
                                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                            when (castState.audioControlCapability) {
                                CastAudioControlCapability.AssistedRemoteMenu -> {
                                    Text(
                                        text = "En modo DLNA cambia el audio desde el menú de tu TV (usa el control direccional).",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CinemaColors.TextSecondary,
                                    )
                                }
                                CastAudioControlCapability.SingleTrack, CastAudioControlCapability.None -> {
                                    Text(
                                        text = "Este reproductor/navegador no permite cambiar pistas de audio remotamente.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CinemaColors.TextSecondary,
                                    )
                                }
                                CastAudioControlCapability.NativeSwitch -> Unit
                            }
                        }

                        if (castState.hasActiveMedia) {
                            val isDlnaSubCapable = castState.subtitleControlCapability == CastSubtitleControlCapability.DlnaNativeToggle ||
                                castState.subtitleControlCapability == CastSubtitleControlCapability.MixedExternalAndNative
                            if (isDlnaSubCapable) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Subtítulos en el televisor (DLNA)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = CinemaColors.TextPrimary,
                                        )
                                        Text(
                                            text = when (castState.subtitlesEnabledOnTv) {
                                                true -> "Encendidos en el TV"
                                                false -> "Apagados en el TV"
                                                null -> "Activa o desactiva los subtítulos del TV"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CinemaColors.TextSecondary,
                                        )
                                    }
                                    Switch(
                                        checked = castState.subtitlesEnabledOnTv ?: false,
                                        onCheckedChange = onToggleTvSubtitles,
                                    )
                                }
                                if (castState.embeddedSubtitleLabels.isNotEmpty()) {
                                    Text(
                                        text = "${castState.embeddedSubtitleLabels.size} pistas embebidas en el archivo · Cambia la pista desde el menú del TV.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CinemaColors.TextSecondary,
                                    )
                                }
                            }

                            if (castState.subtitles.isNotEmpty()) {
                                Text(
                                    text = if (isDlnaSubCapable) "Pistas externas / extraídas" else "Subtítulos en TV",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CinemaColors.TextSecondary,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = castState.activeSubtitleIndex == null,
                                        onClick = { onSelectSubtitle(null) },
                                        label = { Text("Sin subs") },
                                    )
                                    castState.subtitles.forEach { sub ->
                                        val chipLabel = when (sub.kind) {
                                            CastSubtitleKind.ExtractedEmbedded ->
                                                sub.language?.takeIf { it.isNotBlank() }?.uppercase()?.let { "$it · MKV" } ?: "MKV · ${sub.name}"
                                            CastSubtitleKind.LocalFile ->
                                                sub.language?.takeIf { it.isNotBlank() }?.uppercase()?.let { "$it · Local" } ?: "Local · ${sub.name}"
                                            else -> sub.language?.takeIf { it.isNotBlank() }?.uppercase() ?: sub.name
                                        }
                                        FilterChip(
                                            selected = castState.activeSubtitleIndex == sub.index,
                                            onClick = { onSelectSubtitle(sub.index) },
                                            label = { Text(chipLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        )
                                    }
                                }
                            } else if (!isDlnaSubCapable &&
                                castState.subtitleControlCapability == CastSubtitleControlCapability.Unsupported &&
                                castState.embeddedSubtitleLabels.isNotEmpty()
                            ) {
                                Text(
                                    text = "${castState.embeddedSubtitleLabels.size} subtítulos embebidos en el MKV (en streaming; no extraíbles hasta completar la descarga).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaColors.TextSecondary,
                                )
                            }
                        }

                        if (onOpenRemote != null) {
                            Button(
                                onClick = onOpenRemote,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp),
                            ) {
                                Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Abrir control remoto completo")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (castState.hasActiveMedia && onStopPlaybackKeepTv != null) {
                                OutlinedButton(
                                    onClick = onStopPlaybackKeepTv,
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 46.dp),
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Detener video", maxLines = 1)
                                }
                            }
                            Button(
                                onClick = onStopCasting,
                                colors = ButtonDefaults.buttonColors(containerColor = CinemaColors.Danger),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 46.dp),
                            ) {
                                Text("Desconectar TV", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (castState.devices.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (castState.isDiscovering) "Buscando televisores en tu Wi-Fi…" else "No se detectaron televisores todavía",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Compatibles: LG webOS (SSAP + App TV + DLNA + Navegador), Chromecast, Google TV, Samsung Smart TV y DLNA. Si tu router separa redes, usa el botón «IP» arriba.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                }
            }
        } else {
            items(castState.devices, key = { it.id }) { dev ->
                val isCurrent = castState.connectedDevice?.id == dev.id
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = isCurrent,
                            onClick = { onSelectDevice(dev) },
                            role = Role.RadioButton,
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isCurrent) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, if (isCurrent) CinemaColors.Primary else CinemaColors.HairlineBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = if (isCurrent) CinemaColors.Primary else CinemaColors.TextPrimary,
                            modifier = Modifier.size(28.dp),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = dev.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val protoDetail = when {
                                dev.protocol == CastProtocol.WebOs && dev.hasDlna -> "LG webOS (SSAP + DLNA)"
                                dev.protocol == CastProtocol.WebOs -> "LG webOS (SSAP)"
                                dev.protocol == CastProtocol.GoogleCast -> "Chromecast / Google TV"
                                dev.vendor != CastVendor.Generic -> "${dev.vendor.label} · DLNA"
                                else -> "DLNA / Smart TV"
                            }
                            val detail = buildList {
                                add(protoDetail)
                                dev.model?.let { add(it) }
                                add(dev.host)
                            }.joinToString(" · ")
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = CinemaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isCurrent) {
                            Text(
                                text = "Activa",
                                style = MaterialTheme.typography.labelSmall,
                                color = CinemaColors.Secondary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        if (onOpenExternalFallback != null) {
            item {
                HorizontalDivider(color = CinemaColors.HairlineBorder)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onOpenExternalFallback,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enviar a app Cast externa (VLC / Web Video Caster)")
                }
            }
        }
    }
}

@Composable
fun CastMiniControlBar(
    castState: CastSessionState,
    onOpenRemote: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onStopCasting: () -> Unit,
) {
    val dev = castState.connectedDevice ?: return
    val isPlayingOrLoading = castState.hasActiveMedia
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onOpenRemote),
        shape = RoundedCornerShape(14.dp),
        color = CinemaColors.SurfaceBright,
        border = BorderStroke(
            width = 1.dp,
            color = if (isPlayingOrLoading) CinemaColors.Secondary.copy(alpha = 0.5f) else CinemaColors.Primary.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CastConnected,
                contentDescription = null,
                tint = if (isPlayingOrLoading) CinemaColors.Secondary else CinemaColors.PrimarySoft,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPlayingOrLoading) {
                        castState.mediaTitle.ifBlank { dev.name }
                    } else {
                        "Conectado a ${dev.name}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusSubtitle = if (isPlayingOrLoading) {
                    val label = castState.activeModeLabel ?: dev.name
                    "$label · ${playbackTime(castState.positionMs)}"
                } else if (castState.isConnectingDevice) {
                    "Estableciendo conexión con ${dev.name}…"
                } else {
                    "Modo TV activo · Toca cualquier video para enviar"
                }
                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = if (isPlayingOrLoading) CinemaColors.Secondary else CinemaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isPlayingOrLoading) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
                ) {
                    Icon(
                        imageVector = if (castState.status == CastPlaybackStatus.Playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Pausar / Reanudar",
                        tint = CinemaColors.TextPrimary,
                    )
                }
                if (castState.hasNextEpisode && onNextEpisode != null) {
                    IconButton(
                        onClick = onNextEpisode,
                        modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Siguiente episodio",
                            tint = CinemaColors.TextPrimary,
                        )
                    }
                }
            }
            IconButton(
                onClick = onStopCasting,
                modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (isPlayingOrLoading) "Detener video" else "Desconectar TV",
                    tint = CinemaColors.TextSecondary,
                )
            }
        }
    }
}
