package org.cinito.online.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.cinito.online.BuildConfig
import org.cinito.online.metadata.CatalogRegion
import org.cinito.online.network.DohProvider
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchProviderInfo
import org.cinito.online.search.TorznabConfig
import org.cinito.online.settings.AppPreferences
import org.cinito.online.settings.PlayabilityDiagnosticsMode
import org.cinito.online.settings.SubtitleEdgeStyle
import org.cinito.online.settings.SubtitleStyleConfig
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.TransferNetworkPolicy
import webtor.app.UiState
import webtor.app.formatBytes

private val LANGUAGE_OPTIONS = listOf(
    "es" to "Español",
    "en" to "Inglés",
    "ja" to "Japonés",
    "fr" to "Francés",
    "pt" to "Portugués",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CineSettingsScreen(
    prefs: AppPreferences,
    engineState: UiState,
    builtinProviders: List<SearchProviderInfo>,
    torznabConfigs: List<TorznabConfig>,
    onPreferredAudioLang: (String) -> Unit,
    onPreferredSubtitleLang: (String) -> Unit,
    onSubtitlesDefault: (Boolean) -> Unit,
    onSubtitleStyle: (SubtitleStyleConfig) -> Unit,
    onUseExternalPlayer: (Boolean) -> Unit,
    onDohProvider: (DohProvider) -> Unit,
    onMetadataLookup: (Boolean) -> Unit,
    onCatalogRegion: (CatalogRegion) -> Unit,
    onSearchLanguageFilter: (SearchLanguageFilter) -> Unit,
    onPlayabilityDiagnosticsMode: (PlayabilityDiagnosticsMode) -> Unit = {},
    onSaveTmdbApiKey: (String) -> Unit,
    onClearMetadataCache: () -> Unit,
    onShowRawFilenames: (Boolean) -> Unit,
    onToggleProvider: (String, Boolean) -> Unit,
    onAddTorznab: (String, String, String, Set<SearchCategory>) -> Unit,
    onRemoveTorznab: (String) -> Unit,
    onPickFolder: () -> Unit,
    onDirectPlayDefault: (Boolean) -> Unit,
    onAllowMobileData: (Boolean) -> Unit = {},
    onNetworkPolicy: (TransferNetworkPolicy) -> Unit,
    onCleanupEngineCache: () -> Unit,
    onOpenAdvancedEngineSettings: () -> Unit,
) {
    var showTorznabDialog by remember { mutableStateOf(false) }
    var showTmdbDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaColors.Background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = "Ajustes",
                    style = MaterialTheme.typography.headlineLarge,
                    color = CinemaColors.TextPrimary,
                )
                Text(
                    text = "Sin publicidad · Sin telemetría · Código abierto GPLv3",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.Secondary,
                )
            }
        }

        item(key = "network_protection") {
            SettingsCard("Red y protección de datos móviles") {
                RowSwitch(
                    title = "Permitir uso con datos móviles",
                    subtitle = if (prefs.allowMobileData) {
                        "Activado: la app puede descargar, reproducir y buscar usando tu red móvil."
                    } else {
                        "Desactivado (Solo Wi-Fi): si se desconecta el Wi-Fi se bloquean descargas, streaming, búsquedas y carátulas para no consumir tus datos."
                    },
                    checked = prefs.allowMobileData,
                    onCheckedChange = onAllowMobileData,
                )
                SettingsDivider()
                Text("Política de red", style = MaterialTheme.typography.bodyMedium, color = CinemaColors.TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TransferNetworkPolicy.WIFI_ONLY to "Solo Wi-Fi",
                        TransferNetworkPolicy.UNMETERED to "Sin datos medidos",
                        TransferNetworkPolicy.ANY to "Wi-Fi y datos",
                    ).forEach { (policy, label) ->
                        FilterChip(
                            selected = engineState.networkPolicy == policy,
                            onClick = { onNetworkPolicy(policy) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        item(key = "player") {
            SettingsCard("Reproductor y subtítulos") {
                Text(
                    text = "Idioma preferido de audio",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LANGUAGE_OPTIONS.forEach { (code, label) ->
                        FilterChip(
                            selected = prefs.preferredAudioLanguage == code,
                            onClick = { onPreferredAudioLang(code) },
                            label = { Text(label) },
                        )
                    }
                }
                SettingsDivider()
                RowSwitch(
                    title = "Activar subtítulos automáticamente",
                    subtitle = "Selecciona la pista acorde a tu idioma preferido si está disponible.",
                    checked = prefs.subtitlesEnabledByDefault,
                    onCheckedChange = onSubtitlesDefault,
                )
                if (prefs.subtitlesEnabledByDefault) {
                    Text(
                        text = "Idioma preferido de subtítulos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LANGUAGE_OPTIONS.forEach { (code, label) ->
                            FilterChip(
                                selected = prefs.preferredSubtitleLanguage == code,
                                onClick = { onPreferredSubtitleLang(code) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tamaño de subtítulos", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${prefs.subtitleStyle.fontSizeSp.toInt()} sp",
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = CinemaColors.Primary,
                    )
                }
                Slider(
                    value = prefs.subtitleStyle.fontSizeSp,
                    onValueChange = { size ->
                        onSubtitleStyle(prefs.subtitleStyle.copy(fontSizeSp = size))
                    },
                    valueRange = 14f..32f,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubtitleEdgeStyle.entries.forEach { edge ->
                        FilterChip(
                            selected = prefs.subtitleStyle.edgeStyle == edge,
                            onClick = { onSubtitleStyle(prefs.subtitleStyle.copy(edgeStyle = edge)) },
                            label = { Text(edge.label) },
                        )
                    }
                }
                SettingsDivider()
                RowSwitch(
                    title = "Usar reproductor externo (VLC / MPV)",
                    subtitle = "Desactivado usa el Cinema Player integrado con audio/subtítulos internos.",
                    checked = prefs.useExternalPlayer,
                    onCheckedChange = onUseExternalPlayer,
                )
            }
        }

        item(key = "privacy") {
            SettingsCard("Catálogo, Idioma y Privacidad") {
                Text(
                    text = "Región del catálogo (Descubrir)",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatalogRegion.entries.forEach { region ->
                        FilterChip(
                            selected = prefs.catalogRegion == region,
                            onClick = { onCatalogRegion(region) },
                            label = { Text(region.label) },
                        )
                    }
                }
                SettingsDivider()
                Text(
                    text = "Filtro de idioma predeterminado en búsquedas",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchLanguageFilter.entries.forEach { langFilter ->
                        FilterChip(
                            selected = prefs.searchLanguageFilter == langFilter,
                            onClick = { onSearchLanguageFilter(langFilter) },
                            label = { Text(langFilter.label) },
                        )
                    }
                }
                SettingsDivider()
                Text(
                    text = "Diagnóstico de mejor opción para reproducir",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayabilityDiagnosticsMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.playabilityDiagnosticsMode == mode,
                            onClick = { onPlayabilityDiagnosticsMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    text = prefs.playabilityDiagnosticsMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
                SettingsDivider()
                Text(
                    text = "DNS-over-HTTPS (DoH) para búsquedas y pósters",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DohProvider.entries.forEach { doh ->
                        FilterChip(
                            selected = prefs.dohProvider == doh,
                            onClick = { onDohProvider(doh) },
                            label = { Text(doh.displayName) },
                        )
                    }
                }
                SettingsDivider()
                RowSwitch(
                    title = "Descargar pósters y guías de episodios",
                    subtitle = "Usa JustWatch MX / Cinemeta / TVmaze sin cuentas ni rastreadores. Desactívalo para biblioteca 100% offline.",
                    checked = prefs.metadataLookupEnabled,
                    onCheckedChange = onMetadataLookup,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Clave API de TMDB (es-MX, opcional)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (prefs.hasTmdbApiKey) {
                                "Configurada cifrada con Android Keystore"
                            } else {
                                "Opcional: mejora sinopsis y tendencias en español latino"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                    OutlinedButton(onClick = { showTmdbDialog = true }) {
                        Text(if (prefs.hasTmdbApiKey) "Cambiar" else "Configurar")
                    }
                }
                OutlinedButton(
                    onClick = onClearMetadataCache,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Limpiar caché local de pósters y catálogo")
                }
                SettingsDivider()
                RowSwitch(
                    title = "Mostrar nombres originales del torrent",
                    subtitle = "Muestra los nombres de archivo sin limpiar junto al título del episodio o película.",
                    checked = prefs.showRawFilenames,
                    onCheckedChange = onShowRawFilenames,
                )
            }
        }

        item(key = "providers") {
            SettingsCard("Motores de búsqueda y Torznab") {
                builtinProviders.forEachIndexed { idx, info ->
                    if (idx > 0) SettingsDivider()
                    RowSwitch(
                        title = info.name,
                        subtitle = info.url,
                        checked = info.id in prefs.enabledProviderIds,
                        onCheckedChange = { checked -> onToggleProvider(info.id, checked) },
                    )
                }
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = CinemaColors.Secondary)
                        Column {
                            Text("Servidores Torznab (Keystore)", style = MaterialTheme.typography.titleMedium)
                            Text("Claves cifradas con AES-256-GCM", style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
                        }
                    }
                    OutlinedButton(onClick = { showTorznabDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Añadir")
                    }
                }
                torznabConfigs.forEach { cfg ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cfg.name, style = MaterialTheme.typography.bodyMedium)
                            Text(cfg.url, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
                        }
                        IconButton(onClick = { onRemoveTorznab(cfg.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar Torznab", tint = CinemaColors.Danger)
                        }
                    }
                }
            }
        }

        item(key = "engine") {
            SettingsCard("Motor BitTorrent y almacenamiento") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Carpeta de descargas", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${engineState.storageFolderName} · ${formatBytes(engineState.freeBytes.coerceAtLeast(0L))} libres",
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.TextSecondary,
                        )
                    }
                    OutlinedButton(onClick = onPickFolder) { Text("Cambiar") }
                }
                SettingsDivider()
                RowSwitch(
                    title = "Direct Play por defecto al abrir torrent",
                    subtitle = "Transmite en memoria RAM sin guardar el archivo completo salvo que elijas Descargar.",
                    checked = engineState.directPlayDefault,
                    onCheckedChange = onDirectPlayDefault,
                )
                SettingsDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onCleanupEngineCache,
                        modifier = Modifier.weight(1f),
                    ) { Text("Limpiar caché motor") }
                    Button(
                        onClick = onOpenAdvancedEngineSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Avanzado")
                    }
                }
                SettingsDivider()
                Text(
                    text = "CiniTo v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Engine Rust + Media3 + NextLib FFmpeg",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = CinemaColors.TextSecondary,
                )
            }
        }
    }

    if (showTorznabDialog) {
        AddTorznabDialog(
            onDismiss = { showTorznabDialog = false },
            onSave = { name, url, apiKey, cats ->
                onAddTorznab(name, url, apiKey, cats)
                showTorznabDialog = false
            },
        )
    }

    if (showTmdbDialog) {
        TmdbApiKeyDialog(
            hasExistingKey = prefs.hasTmdbApiKey,
            onDismiss = { showTmdbDialog = false },
            onSave = { key ->
                onSaveTmdbApiKey(key)
                showTmdbDialog = false
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = CinemaColors.Primary,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CinemaColors.Surface,
            border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = CinemaColors.HairlineBorder)
}

@Composable
private fun RowSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = CinemaColors.TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = CinemaColors.TextSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AddTorznabDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, Set<SearchCategory>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CinemaColors.SurfaceElevated,
        title = { Text("Agregar servidor Torznab") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Conecta Prowlarr o Jackett por HTTPS (o localhost). La clave se guarda cifrada con Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre (ej. Mi Prowlarr)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL HTTPS (ej. https://prowlarr.mi-red.org/1/api)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name,
                        url,
                        apiKey,
                        setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime),
                    )
                },
                enabled = name.isNotBlank() && url.isNotBlank() && apiKey.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun TmdbApiKeyDialog(
    hasExistingKey: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CinemaColors.SurfaceElevated,
        title = { Text("Clave API de TMDB (es-MX)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Opcional: mejora tendencias, pósters y sinopsis en español de México. Se guarda cifrada con Android Keystore. Déjala vacía para quitarla y usar JustWatch MX.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (hasExistingKey) "Nueva API Key (o vacía para borrar)" else "TMDB API Key v3") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(apiKey.trim()) }) {
                Text(if (apiKey.isBlank() && hasExistingKey) "Quitar clave" else "Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
