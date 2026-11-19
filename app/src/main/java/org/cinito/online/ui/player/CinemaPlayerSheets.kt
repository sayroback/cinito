package org.cinito.online.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cinito.online.player.AudioTrackOption
import org.cinito.online.player.CinemaPlayerState
import org.cinito.online.player.CinemaPlayerUiLogic
import org.cinito.online.player.PlayerAspectMode
import org.cinito.online.player.SubtitleSource
import org.cinito.online.player.SubtitleTrackOption
import org.cinito.online.settings.SubtitleEdgeStyle
import org.cinito.online.settings.SubtitleStyleConfig
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.DownloadEntry
import webtor.app.PlaybackItem
import webtor.app.PlaybackProvider
import webtor.app.playbackTime
import java.util.Locale
import kotlin.math.abs

@Composable
private fun SelectableSheetCard(
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
        border = BorderStroke(1.dp, if (selected) CinemaColors.Primary else CinemaColors.HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = CinemaColors.Primary,
                    unselectedColor = CinemaColors.TextSecondary,
                ),
            )
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun AudioTrackSheet(
    state: CinemaPlayerState,
    onSelect: (AudioTrackOption) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Pista de audio", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
        }
        if (state.audioTracks.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Text(
                        text = "Este archivo tiene una sola pista o aún está terminando de analizar los canales.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
            }
        }
        items(state.audioTracks, key = { it.id }) { track ->
            SelectableSheetCard(
                selected = track.isSelected,
                onClick = { onSelect(track) },
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = track.displayLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CinemaColors.TextPrimary,
                    )
                    if (track.detailBadge.isNotBlank()) {
                        Text(
                            text = track.detailBadge,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleTrackSheet(
    state: CinemaPlayerState,
    onDisable: () -> Unit,
    onSelect: (SubtitleTrackOption) -> Unit,
    onDelayChange: (Long) -> Unit,
    onUpdateSubtitleStyle: (SubtitleStyleConfig) -> Unit,
    onPickLocalFile: () -> Unit,
) {
    var styleExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Subtítulos", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
                OutlinedButton(
                    onClick = onPickLocalFile,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cargar archivo")
                }
            }
        }

        // Sync delay card (-10s..+10s)
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CinemaColors.SurfaceElevated,
                border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sincronización", style = MaterialTheme.typography.bodyMedium, color = CinemaColors.TextPrimary)
                        Text(
                            text = String.format(Locale.US, "%+.2fs", state.subtitleDelayMs / 1000.0),
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = CinemaColors.Primary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val steps = listOf(-500L to "-0.5s", -250L to "-0.25s", 0L to "0s", 250L to "+0.25s", 500L to "+0.5s")
                        steps.forEach { (delta, label) ->
                            OutlinedButton(
                                onClick = { if (delta == 0L) onDelayChange(0L) else onDelayChange(state.subtitleDelayMs + delta) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium.tabular(), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        // Expandable live visual style editor (persisted to AppSettings)
        item {
            val style = state.subtitleStyle
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CinemaColors.SurfaceElevated,
                border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { styleExpanded = !styleExpanded }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = CinemaColors.TextSecondary, modifier = Modifier.size(18.dp))
                            Column {
                                Text("Apariencia en vivo", style = MaterialTheme.typography.bodyMedium, color = CinemaColors.TextPrimary)
                                Text(
                                    text = "${style.fontSizeSp.toInt()} sp · ${style.edgeStyle.label} · Fondo ${(style.backgroundOpacity * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = CinemaColors.TextSecondary,
                                )
                            }
                        }
                        Icon(
                            imageVector = if (styleExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (styleExpanded) "Ocultar apariencia" else "Mostrar apariencia",
                            tint = CinemaColors.TextSecondary,
                        )
                    }

                    AnimatedVisibility(visible = styleExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HorizontalDivider(color = CinemaColors.HairlineBorder)
                            Text("Tamaño", style = MaterialTheme.typography.labelMedium, color = CinemaColors.TextSecondary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(15f to "Chico", 19f to "Normal", 24f to "Grande", 29f to "Extra").forEach { (sp, label) ->
                                    FilterChip(
                                        selected = abs(style.fontSizeSp - sp) < 1.5f,
                                        onClick = { onUpdateSubtitleStyle(style.copy(fontSizeSp = sp)) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            Text("Fondo", style = MaterialTheme.typography.labelMedium, color = CinemaColors.TextSecondary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0f to "Sin caja", 0.45f to "Suave", 0.78f to "Alto contraste").forEach { (alpha, label) ->
                                    FilterChip(
                                        selected = abs(style.backgroundOpacity - alpha) < 0.12f,
                                        onClick = { onUpdateSubtitleStyle(style.copy(backgroundOpacity = alpha)) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                            Text("Contorno", style = MaterialTheme.typography.labelMedium, color = CinemaColors.TextSecondary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SubtitleEdgeStyle.entries.forEach { edge ->
                                    FilterChip(
                                        selected = style.edgeStyle == edge,
                                        onClick = { onUpdateSubtitleStyle(style.copy(edgeStyle = edge)) },
                                        label = { Text(edge.label) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SelectableSheetCard(
                selected = state.subtitlesDisabled,
                onClick = onDisable,
            ) {
                Text("Desactivar subtítulos", style = MaterialTheme.typography.bodyLarge, color = CinemaColors.TextPrimary)
            }
        }

        if (state.subtitleTracks.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Text(
                        text = "No se detectaron subtítulos internos o del torrent. Puedes cargar un archivo .srt, .ass o .vtt desde «Cargar archivo».",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
            }
        }

        items(state.subtitleTracks, key = { it.id }) { sub ->
            val selected = !state.subtitlesDisabled && sub.isSelected
            SelectableSheetCard(
                selected = selected,
                onClick = { onSelect(sub) },
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sub.displayLabel, style = MaterialTheme.typography.bodyLarge, color = CinemaColors.TextPrimary)
                    val meta = listOfNotNull(
                        when (sub.source) {
                            SubtitleSource.Embedded -> "Incrustado"
                            SubtitleSource.TorrentSidecar -> "Torrent"
                            SubtitleSource.LocalFile -> "Archivo local"
                        },
                        sub.formatLabel,
                        if (sub.isForced) "Forzado" else null,
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedPickerSheet(current: Float, onSelect: (Float) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Velocidad de reproducción", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CinemaPlayerUiLogic.PLAYER_SPEED_PRESETS.forEach { sp ->
                val selected = abs(current - sp) < 0.01f
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(sp) },
                    label = {
                        Text(
                            text = CinemaPlayerUiLogic.formatSpeedLabel(sp),
                            style = MaterialTheme.typography.labelLarge.tabular(),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CinemaColors.PrimaryMuted,
                        selectedLabelColor = CinemaColors.TextPrimary,
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = 68.dp, minHeight = 44.dp),
                )
            }
        }
        Text(
            text = "Consejo: mantén pulsado el video en cualquier momento para acelerar a 2x mientras sujeta el dedo.",
            style = MaterialTheme.typography.bodySmall,
            color = CinemaColors.TextSecondary,
        )
    }
}

@Composable
fun AspectPickerSheet(current: PlayerAspectMode, onSelect: (PlayerAspectMode) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Relación de aspecto", style = MaterialTheme.typography.titleLarge, color = CinemaColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
        }
        items(PlayerAspectMode.entries, key = { it.name }) { mode ->
            SelectableSheetCard(
                selected = mode == current,
                onClick = { onSelect(mode) },
                trailing = {
                    if (mode == current) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = CinemaColors.Primary)
                    }
                },
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge, color = CinemaColors.TextPrimary)
                    Text(mode.description, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
fun EpisodeListSheet(
    playlist: List<PlaybackItem>,
    currentIndex: Int,
    activeEntry: DownloadEntry?,
    bookmarkUris: Map<String, String> = emptyMap(),
    showRawFilenames: Boolean,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Episodios en el torrent (${playlist.size})",
                style = MaterialTheme.typography.titleLarge,
                color = CinemaColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
        }
        itemsIndexed(playlist, key = { idx, item -> "$idx:${item.uri}" }) { idx, item ->
            val active = idx == currentIndex
            val presentation = remember(item.name, idx, showRawFilenames) {
                CinemaPlayerUiLogic.formatEpisodeRow(item.name, idx, showRawFilename = showRawFilenames)
            }
            val canonicalBookmarkUri = bookmarkUris[item.uri] ?: item.uri
            val matchedFile = activeEntry?.files?.find { PlaybackProvider.uriFor(activeEntry, it).toString() == canonicalBookmarkUri }
                ?: activeEntry?.files?.find { it.name == item.name }
            val progressFrac = if (matchedFile != null && matchedFile.playbackDurationMs > 0L) {
                (matchedFile.playbackPositionMs.toFloat() / matchedFile.playbackDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(idx) },
                shape = RoundedCornerShape(12.dp),
                color = if (active) CinemaColors.PrimaryMuted else CinemaColors.SurfaceElevated,
                border = BorderStroke(1.dp, if (active) CinemaColors.Primary else CinemaColors.HairlineBorder),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) CinemaColors.Primary else CinemaColors.SurfaceBright,
                        ) {
                            Text(
                                text = presentation.badge,
                                style = MaterialTheme.typography.labelMedium.tabular().copy(fontWeight = FontWeight.SemiBold),
                                color = if (active) CinemaColors.TextPrimary else CinemaColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = presentation.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = CinemaColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val metaText = buildList {
                                presentation.detail?.let { add(it) }
                                if (matchedFile != null && matchedFile.playbackPositionMs > 0L) {
                                    add("En ${playbackTime(matchedFile.playbackPositionMs)}")
                                }
                            }.joinToString(" · ")
                            if (metaText.isNotEmpty()) {
                                Text(
                                    text = metaText,
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = CinemaColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (active) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Reproduciendo ahora",
                                tint = CinemaColors.Primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    if (progressFrac != null && progressFrac > 0.02f) {
                        LinearProgressIndicator(
                            progress = { progressFrac },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = CinemaColors.Primary,
                            trackColor = CinemaColors.SurfaceBright,
                        )
                    }
                }
            }
        }
    }
}
