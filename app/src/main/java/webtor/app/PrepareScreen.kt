package webtor.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import webtor.core.TorrentFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(
    draft: PrepareDraft,
    freeBytes: Long,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDirectPlay: () -> Unit,
    directPlayDefault: Boolean = false,
    onPlayFile: ((Int) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    BackHandler(onBack = onCancel)
    val torrent = draft.torrent
    val ready = torrent?.ready == true
    val selectedSize = torrent?.files?.filter { it.index in draft.selected }?.sumOf { it.length } ?: 0L
    val storageKnown = freeBytes >= 0
    val insufficientSpace = storageKnown && freeBytes < selectedSize
    val canChoose = ready && !draft.busy && draft.selected.isNotEmpty()
    val canStart = canChoose && (directPlayDefault || !insufficientSpace)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Elegir archivos", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 2.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (insufficientSpace) {
                        Text(
                            "No hay espacio suficiente para descargar ${formatBytes(selectedSize)}.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = if (directPlayDefault) onDirectPlay else onStart,
                        enabled = canStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(if (directPlayDefault) "Reproducir ahora (sin guardar)" else "Descargar en el teléfono", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = if (directPlayDefault) onStart else onDirectPlay,
                        enabled = canChoose && (!directPlayDefault || !insufficientSpace),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (directPlayDefault) "Descargar en el teléfono" else "Reproducir ahora (sin guardar)") }
                }
            }
        },
    ) { padding ->
        if (!ready) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.Start,
            ) {
                if (draft.error != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "No se pudo leer este torrent",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                draft.error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onRetry ?: onCancel,
                                    modifier = Modifier.weight(1f).height(44.dp),
                                ) {
                                    Text("Reintentar")
                                }
                                if (onRetry != null) {
                                    OutlinedButton(
                                        onClick = onCancel,
                                        modifier = Modifier.weight(1f).height(44.dp),
                                    ) {
                                        Text("Volver")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Buscando archivos", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        prefetchStatus(draft) ?: "Buscando peers…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item { DraftVideoPreview(draft) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(torrent.name ?: "Torrent", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${draft.selected.size} de ${torrent.files.size} archivos seleccionados · ${formatBytes(selectedSize)}" +
                            if (storageKnown) " · ${formatBytes(freeBytes)} libres" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Los videos se seleccionan automáticamente. Marca subtítulos compatibles si deseas incluirlos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    prefetchStatus(draft)?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    ) {
                        TextButton(onClick = onSelectAll) { Text("Todos") }
                        TextButton(onClick = onSelectNone) { Text("Ninguno") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            items(torrent.files, key = { it.index }) { file ->
                val isVideo = file.name.isVideoName() || file.path.isVideoName()
                FileRow(
                    file = file,
                    checked = file.index in draft.selected,
                    onToggle = { onToggle(file.index) },
                    onPlayFile = if (isVideo && onPlayFile != null) {
                        { onPlayFile(file.index) }
                    } else {
                        null
                    },
                    playEnabled = !draft.busy,
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: TorrentFile,
    checked: Boolean,
    onToggle: () -> Unit,
    onPlayFile: (() -> Unit)? = null,
    playEnabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(
                file.path.ifBlank { file.name },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                formatBytes(file.length),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onPlayFile != null) {
            IconButton(onClick = onPlayFile, enabled = playEnabled) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir")
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
