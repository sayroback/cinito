package org.cinito.online.ui.explore

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.ui.library.InlineTrackBreakdownPanel
import org.cinito.online.ui.library.VerifiedAudioSummaryPill
import org.cinito.online.ui.library.isInspectableContainerName
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.DraftVideoPreview
import webtor.app.PrepareDraft
import webtor.app.QuickStreamTarget
import webtor.app.WebtorApp
import webtor.app.formatBytes
import webtor.app.isAudioName
import webtor.app.isSubtitleName
import webtor.app.isVideoName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickStreamSheet(
    target: QuickStreamTarget?,
    draft: PrepareDraft?,
    error: String?,
    sourceStatus: String?,
    onSwitchToChooseFiles: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = draft?.busy == true
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue -> !currentBusy || targetValue != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onCancel() },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy),
        containerColor = CinemaColors.Surface,
        contentColor = CinemaColors.TextPrimary,
    ) {
        BackHandler(enabled = !busy, onBack = onCancel)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = target?.displayTitle ?: draft?.torrent?.name ?: "Preparando reproducción",
                style = MaterialTheme.typography.titleLarge,
                color = CinemaColors.TextPrimary,
            )

            if (target?.season != null && target.episode != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CinemaColors.PrimaryMuted,
                ) {
                    Text(
                        text = formatSeasonEpisodeCode(target.season, target.episode),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = CinemaColors.TextPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            val torrent = draft?.torrent
            val activeError = error ?: draft?.error
            if (activeError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CinemaColors.Danger.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = activeError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextPrimary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onRetry,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Reintentar")
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) {
                                Text("Elegir otra fuente")
                            }
                        }
                    }
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CinemaColors.Primary,
                    trackColor = CinemaColors.SurfaceBright,
                )
                val statusText = when {
                    busy -> "Iniciando streaming Direct Play en memoria…"
                    torrent?.ready == true -> "Metadatos listos, preparando reproductor…"
                    sourceStatus != null -> sourceStatus
                    (torrent?.numPeers ?: 0) > 0 -> "Conectado con ${torrent?.numPeers} peers · leyendo lista de archivos…"
                    else -> "Buscando peers en la red BitTorrent…"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium.tabular(),
                    color = CinemaColors.TextSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSwitchToChooseFiles,
                    enabled = !busy && activeError == null,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Elegir archivos", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text("Cancelar", maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChooseFilesOverlay(
    targetTitle: String?,
    draft: PrepareDraft?,
    error: String?,
    sourceStatus: String?,
    freeBytes: Long,
    destination: String,
    directPlayDefault: Boolean,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onSelectVideos: () -> Unit,
    onDirectPlay: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy = draft?.busy == true
    BackHandler(enabled = !busy, onBack = onCancel)

    val torrent = draft?.torrent
    val ready = torrent?.ready == true
    val files = torrent?.files.orEmpty()
    val selection = draft?.selected.orEmpty()
    val selectedBytes = files.filter { it.index in selection }.sumOf { it.length }
    var query by rememberSaveable(draft?.engineId) { mutableStateOf("") }
    var videosOnly by rememberSaveable(draft?.engineId) { mutableStateOf(false) }

    val context = LocalContext.current
    val probeRepo = remember(context) { (context.applicationContext as WebtorApp).audioProbeRepository }
    val probesMap by probeRepo.probes.collectAsState()
    val probingKeys by probeRepo.probingKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val draftEngineId = draft?.engineId
    val draftInfoHash = torrent?.infoHash ?: draftEngineId?.takeIf { it.length == 40 }
    val draftEntryKey = draftEngineId?.let { "prepare:$it" }

    LaunchedEffect(ready, draftEngineId, draftInfoHash, files.size) {
        if (!ready || draftEngineId.isNullOrBlank()) return@LaunchedEffect
        files.filter { isInspectableContainerName(it.name) && it.length > 0L }
            .take(3)
            .forEach { file ->
                if (AudioTrackProbeRepository.lookupFromMap(probesMap, draftEntryKey, draftInfoHash, file.index) == null) {
                    probeRepo.probeFile(
                        entryKey = draftEntryKey,
                        infoHash = draftInfoHash,
                        fileIndex = file.index,
                        fileName = file.name,
                        fileLength = file.length,
                        engineId = draftEngineId,
                        contentUri = null,
                    )
                }
            }
    }

    val visibleFiles = remember(files, query, videosOnly) {
        val trimmed = query.trim()
        files.filter { file ->
            (!videosOnly || file.name.isVideoName() || file.path.isVideoName()) &&
                (trimmed.isEmpty() || file.path.contains(trimmed, ignoreCase = true) || file.name.contains(trimmed, ignoreCase = true))
        }
    }
    val hasVideo = files.any { it.name.isVideoName() || it.path.isVideoName() }
    val storageKnown = freeBytes >= 0L
    val insufficientSpace = storageKnown && freeBytes < selectedBytes
    val canStream = ready && selection.isNotEmpty() && !busy
    val canDownload = ready && selection.isNotEmpty() && !busy && !insufficientSpace

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = CinemaColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Elegir archivos",
                            style = MaterialTheme.typography.titleLarge,
                            color = CinemaColors.TextPrimary,
                        )
                        if (!targetTitle.isNullOrBlank()) {
                            Text(
                                text = targetTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onCancel, enabled = !busy) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CinemaColors.Surface,
                    titleContentColor = CinemaColors.TextPrimary,
                    actionIconContentColor = CinemaColors.TextPrimary,
                ),
            )
        },
        bottomBar = {
            if (ready) {
                Surface(
                    color = CinemaColors.Surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "$destination · " + if (storageKnown) "${formatBytes(freeBytes)} libres" else "Espacio disponible desconocido",
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.TextSecondary,
                        )
                        if (insufficientSpace) {
                            Text(
                                text = "No hay espacio suficiente para descargar ${formatBytes(selectedBytes)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.Danger,
                            )
                        } else if (selection.isEmpty()) {
                            Text(
                                text = "Selecciona al menos un archivo para continuar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                            )
                        }

                        if (directPlayDefault) {
                            Button(
                                onClick = onDirectPlay,
                                enabled = canStream,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Ver ahora (Direct Play · sin guardar)", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onDownload,
                                enabled = canDownload,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Descargar para ver sin conexión", maxLines = 1)
                            }
                        } else {
                            Button(
                                onClick = onDownload,
                                enabled = canDownload,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Descargar para ver sin conexión", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onDirectPlay,
                                enabled = canStream,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Ver ahora (Direct Play · sin guardar)", maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        val activeError = error ?: draft?.error
        if (!ready) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = CinemaColors.Surface,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (activeError != null) {
                            Text("No se pudo leer este torrent", style = MaterialTheme.typography.titleMedium, color = CinemaColors.Danger)
                            Text(activeError, style = MaterialTheme.typography.bodyMedium, color = CinemaColors.TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onRetry, modifier = Modifier.weight(1f).height(44.dp)) {
                                    Text("Reintentar")
                                }
                                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(44.dp)) {
                                    Text("Volver")
                                }
                            }
                        } else {
                            Text("Obteniendo lista de archivos…", style = MaterialTheme.typography.titleMedium, color = CinemaColors.TextPrimary)
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = CinemaColors.Primary,
                                trackColor = CinemaColors.SurfaceBright,
                            )
                            val waitLabel = when {
                                sourceStatus != null -> sourceStatus
                                (torrent?.numPeers ?: 0) > 0 -> "${torrent?.numPeers} peers conectados · leyendo metadatos…"
                                else -> "Buscando peers en la red DHT…"
                            }
                            Text(waitLabel, style = MaterialTheme.typography.bodySmall.tabular(), color = CinemaColors.TextSecondary)
                            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                                Text("Cancelar")
                            }
                        }
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (activeError != null) {
                item(key = "err_banner") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CinemaColors.Danger.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = activeError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            item(key = "preview") {
                draft?.let { DraftVideoPreview(it) }
            }
            item(key = "header_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = torrent.name ?: "Contenido del torrent",
                        style = MaterialTheme.typography.titleMedium,
                        color = CinemaColors.TextPrimary,
                    )
                    Text(
                        text = "${selection.size} de ${files.size} archivos seleccionados · ${formatBytes(selectedBytes)}",
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = CinemaColors.TextSecondary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onSelectAll, enabled = !busy) { Text("Todos") }
                        TextButton(onClick = onSelectNone, enabled = !busy) { Text("Ninguno") }
                        TextButton(onClick = onSelectVideos, enabled = !busy && hasVideo) { Text("Videos y subs") }
                        FilterChip(
                            selected = videosOnly,
                            onClick = { videosOnly = !videosOnly },
                            label = { Text("Solo videos") },
                        )
                    }
                    if (files.size >= 6 || query.isNotEmpty()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Filtrar archivos o episodios") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                                    }
                                }
                            } else {
                                null
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
            if (visibleFiles.isEmpty()) {
                item(key = "no_files") {
                    Text(
                        text = "Ningún archivo coincide con el filtro.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }
            items(visibleFiles, key = { it.index }) { file ->
                val checked = file.index in selection
                val fileProbe = AudioTrackProbeRepository.lookupFromMap(probesMap, draftEntryKey, draftInfoHash, file.index)
                val fileProbing = draftEntryKey != null && AudioTrackProbeRepository.entryKey(draftEntryKey, file.index) in probingKeys
                val canInspectFile = isInspectableContainerName(file.name) && !draftEngineId.isNullOrBlank() && file.length > 0L
                var tracksExpanded by remember(file.index) { mutableStateOf(false) }
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .toggleable(
                                value = checked,
                                enabled = !busy,
                                role = Role.Checkbox,
                                onValueChange = { onToggle(file.index) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = file.path.ifBlank { file.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = CinemaColors.TextPrimary,
                            )
                            val kindLabel = when {
                                file.name.isVideoName() || file.path.isVideoName() -> "Video"
                                file.name.isSubtitleName() || file.path.isSubtitleName() -> "Subtítulo"
                                file.name.isAudioName() || file.path.isAudioName() -> "Audio"
                                else -> "Otro"
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "$kindLabel · ${formatBytes(file.length)}",
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = CinemaColors.TextSecondary,
                                )
                                if (fileProbe != null || fileProbing) {
                                    VerifiedAudioSummaryPill(
                                        probe = fileProbe,
                                        isProbing = fileProbing,
                                        expanded = if (fileProbe != null) tracksExpanded else null,
                                        onClick = if (fileProbe != null) {
                                            { tracksExpanded = !tracksExpanded }
                                        } else {
                                            null
                                        },
                                    )
                                } else if (canInspectFile) {
                                    Surface(
                                        modifier = Modifier.clickable {
                                            scope.launch {
                                                probeRepo.probeFile(
                                                    entryKey = draftEntryKey,
                                                    infoHash = draftInfoHash,
                                                    fileIndex = file.index,
                                                    fileName = file.name,
                                                    fileLength = file.length,
                                                    engineId = draftEngineId,
                                                    contentUri = null,
                                                    forceRefresh = true,
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(7.dp),
                                        color = CinemaColors.PrimaryMuted.copy(alpha = 0.42f),
                                        border = BorderStroke(1.dp, CinemaColors.Primary.copy(alpha = 0.35f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Audiotrack,
                                                contentDescription = "Ver audio",
                                                tint = CinemaColors.PrimarySoft,
                                                modifier = Modifier.size(11.dp),
                                            )
                                            Text(
                                                text = "Ver audio",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CinemaColors.PrimarySoft,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = tracksExpanded && fileProbe != null) {
                        fileProbe?.let { probe ->
                            InlineTrackBreakdownPanel(
                                probe = probe,
                                modifier = Modifier.padding(start = 36.dp, bottom = 10.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = CinemaColors.HairlineBorder)
                }
            }
        }
    }
}
