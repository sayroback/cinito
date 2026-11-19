package org.cinito.online.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.cinito.online.cast.CastSessionState
import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.ui.player.CastTopBarActionButton
import org.cinito.online.search.PlayabilityContext
import org.cinito.online.search.PlayabilityVerdict
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchService
import org.cinito.online.search.SearchSortOrder
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.search.TorznabConfig
import org.cinito.online.ui.explore.SearchUiState
import org.cinito.online.ui.explore.TorrentReleaseCard
import org.cinito.online.ui.explore.isResultInLibrary
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.CinemaCompactPill
import org.cinito.online.ui.theme.CinemaCompactSearchField
import org.cinito.online.ui.theme.tabular
import webtor.app.DownloadEntry
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    recentQueries: List<String>,
    enabledProviderIds: Set<String>,
    torznabConfigs: List<TorznabConfig>,
    library: List<DownloadEntry>,
    isCastingToTv: Boolean = false,
    activeWebOsMode: WebOsCastMode? = null,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onClearSearch: () -> Unit,
    onCategoryChange: (SearchCategory) -> Unit,
    onSortOrderChange: (SearchSortOrder) -> Unit,
    onLanguageFilterChange: (SearchLanguageFilter) -> Unit,
    onRetrySearch: () -> Unit,
    onInspectResult: (TorrentSearchResult) -> Unit,
    onDiagnoseTop: (() -> Unit)? = null,
    onDiagnoseResult: ((TorrentSearchResult) -> Unit)? = null,
    onStreamResult: (TorrentSearchResult, Int?, Int?) -> Unit,
    onChooseFilesResult: (TorrentSearchResult) -> Unit,
    onRemoveRecentQuery: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
    onReturnToCatalog: () -> Unit,
    onOpenMagnetSheet: () -> Unit,
    onScrollChanged: (Int, Int) -> Unit,
) {
    BackHandler(enabled = state.originCatalogTitle != null, onBack = onReturnToCatalog)

    var sortExpanded by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val libraryHashes = remember(library) {
        library.map { it.key.lowercase(Locale.ROOT) }.toSet()
    }
    val playabilityContext = remember(
        state.activeSeasonHint,
        state.activeEpisodeHint,
        isCastingToTv,
        activeWebOsMode,
        libraryHashes,
    ) {
        PlayabilityContext(
            requestedSeason = state.activeSeasonHint,
            requestedEpisode = state.activeEpisodeHint,
            isCastingToTv = isCastingToTv,
            activeWebOsMode = activeWebOsMode,
            inLibraryHashes = libraryHashes,
        )
    }

    val (sortedResults, assessmentsById) = remember(
        state.rawResults,
        state.sortOrder,
        state.languageFilter,
        playabilityContext,
        state.swarmStatsByHash,
        state.probeResultsByHash,
    ) {
        SearchService.evaluatePlayabilityAndSort(
            results = state.rawResults,
            sortOrder = state.sortOrder,
            languageFilter = state.languageFilter,
            playabilityContext = playabilityContext,
            scrapeStatsByHash = state.swarmStatsByHash,
            probeResultsByHash = state.probeResultsByHash,
        )
    }
    val topRecommendedId = remember(sortedResults, assessmentsById, state.sortOrder) {
        if (state.sortOrder != SearchSortOrder.BestPlayability) null
        else sortedResults.firstOrNull()?.id?.takeIf { id ->
            val v = assessmentsById[id]?.verdict
            v == PlayabilityVerdict.Optimal || v == PlayabilityVerdict.Good
        }
    }

    val listState = remember(state.submittedQuery, state.category) {
        LazyListState(state.scrollIndex, state.scrollOffset)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (idx, off) -> onScrollChanged(idx, off) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaColors.Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Buscar",
                    style = MaterialTheme.typography.headlineLarge,
                    color = CinemaColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Motores HTTPS + DoH · ${enabledProviderIds.size + torznabConfigs.size} activos",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = CinemaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.submittedQuery.isNotBlank()) {
                IconButton(onClick = onRetrySearch) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualizar búsqueda", tint = CinemaColors.TextPrimary)
                }
            }
            Box {
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Ordenar", tint = CinemaColors.TextPrimary)
                }
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                ) {
                    SearchSortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = order.label,
                                    fontWeight = if (order == state.sortOrder) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSortOrderChange(order)
                                sortExpanded = false
                            },
                        )
                    }
                }
            }
            if (onOpenCast != null) {
                CastTopBarActionButton(
                    castState = castState,
                    onClick = onOpenCast,
                    compact = true,
                )
            }
            IconButton(onClick = onOpenMagnetSheet) {
                Icon(Icons.Default.Link, contentDescription = "Pegar magnet", tint = CinemaColors.Primary)
            }
        }

        state.originCatalogTitle?.let { originTitle ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = CinemaColors.PrimaryMuted.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, CinemaColors.Primary.copy(alpha = 0.4f)),
                onClick = onReturnToCatalog,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = CinemaColors.Primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Volver a ficha: $originTitle",
                        style = MaterialTheme.typography.labelLarge,
                        color = CinemaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        CinemaCompactSearchField(
            query = state.query,
            submittedQuery = state.submittedQuery,
            placeholder = "Película, serie, anime o año…",
            submitContentDescription = "Buscar ahora",
            modifier = Modifier.padding(horizontal = 16.dp),
            onQueryChange = onQueryChange,
            onSubmit = {
                keyboard?.hide()
                focusManager.clearFocus()
                onSubmitSearch(null)
            },
            onClear = {
                keyboard?.hide()
                focusManager.clearFocus()
                onClearSearch()
            },
        )

        LazyRow(
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(SearchCategory.entries) { cat ->
                CinemaCompactPill(
                    label = cat.label,
                    selected = state.category == cat,
                    onClick = { onCategoryChange(cat) },
                )
            }
        }

        LazyRow(
            modifier = Modifier.padding(bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(SearchLanguageFilter.entries) { filter ->
                CinemaCompactPill(
                    label = filter.label,
                    selected = state.languageFilter == filter,
                    accentBorderColor = CinemaColors.Secondary.copy(alpha = 0.7f),
                    selectedContainerColor = CinemaColors.Secondary.copy(alpha = 0.18f),
                    selectedTextColor = CinemaColors.Secondary,
                    onClick = { onLanguageFilterChange(filter) },
                )
            }
        }

        val ctx = state.resolvedContext
        if (state.submittedQuery.isNotBlank() && (ctx?.originalTitle != null || ctx?.imdbId != null)) {
            val parts = buildList {
                ctx.originalTitle
                    ?.takeIf { !it.equals(state.submittedQuery, ignoreCase = true) }
                    ?.let { add("Título original: \"$it\"") }
                ctx.year?.let { add(it.toString()) }
                ctx.imdbId?.let { add("IMDb $it") }
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = "Búsqueda bilingüe · ${parts.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaColors.Primary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                )
            }
        }

        if (state.isSearching) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                LinearProgressIndicator(
                    progress = {
                        if (state.totalProviders > 0) {
                            state.completedProviders.toFloat() / state.totalProviders.toFloat()
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = CinemaColors.Primary,
                    trackColor = CinemaColors.SurfaceBright,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Consultando proveedores (${state.completedProviders}/${state.totalProviders})… ${sortedResults.size} encontrados",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = CinemaColors.TextSecondary,
                )
            }
        }

        if (state.submittedQuery.isBlank()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (recentQueries.isNotEmpty()) {
                    item(key = "recent_queries") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = CinemaColors.TextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Búsquedas recientes",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CinemaColors.TextPrimary,
                                    )
                                }
                                TextButton(onClick = onClearRecentQueries) {
                                    Text("Limpiar")
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                recentQueries.forEach { recent ->
                                    InputChip(
                                        selected = false,
                                        onClick = {
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                            onSubmitSearch(recent)
                                        },
                                        label = { Text(recent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { onRemoveRecentQuery(recent) },
                                                modifier = Modifier.size(18.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Quitar",
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "empty_info") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = CinemaColors.Surface,
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = CinemaColors.Primary,
                                modifier = Modifier.size(40.dp),
                            )
                            Text("Búsqueda directa multi-proveedor", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Consulta en paralelo Torrentio (Cinecalidad+), Knaben, ThePirateBay, SolidTorrents, LimeTorrents, BitSearch, YTS, Nyaa o tus indexadores Torznab sin WebViews ni anuncios.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CinemaColors.TextSecondary,
                            )
                            OutlinedButton(
                                onClick = onOpenMagnetSheet,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pegar enlace magnet o .torrent")
                            }
                        }
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.isSearching && state.failures.isNotEmpty()) {
                item(key = "failures_note") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${state.failures.size} proveedor(es) no respondieron: ${state.failures.joinToString { it.providerName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.Gold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetrySearch) { Text("Reintentar") }
                    }
                }
            }
            if (!state.isSearching && sortedResults.isEmpty()) {
                item(key = "no_results") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CinemaColors.Surface,
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val altNote = state.resolvedContext?.originalTitle
                                ?.takeIf { !it.equals(state.submittedQuery, ignoreCase = true) }
                                ?.let { " (ni como \"$it\")" }
                                .orEmpty()
                            Text(
                                text = "Sin resultados para \"${state.submittedQuery}\"$altNote en ${state.category.label} con \"${state.languageFilter.label}\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CinemaColors.TextSecondary,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (state.languageFilter != SearchLanguageFilter.AllLanguages) {
                                    OutlinedButton(onClick = { onLanguageFilterChange(SearchLanguageFilter.AllLanguages) }) {
                                        Text("Ver todos los idiomas")
                                    }
                                }
                                if (state.category != SearchCategory.All) {
                                    OutlinedButton(onClick = { onCategoryChange(SearchCategory.All) }) {
                                        Text("Buscar en Todo")
                                    }
                                }
                                OutlinedButton(onClick = onRetrySearch) {
                                    Text("Reintentar")
                                }
                            }
                        }
                    }
                }
            }
            if (onDiagnoseTop != null && sortedResults.isNotEmpty() && !state.isSearching) {
                item(key = "diagnose_top_row") {
                    val isProbing = state.probingHashes.isNotEmpty()
                    val probedVisibleCount = sortedResults.count { item ->
                        val h = item.normalizedInfoHash
                        h != null && h in state.probeResultsByHash
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (state.isScrapingSwarm) {
                                "Verificando trackers en vivo…"
                            } else {
                                "${sortedResults.size} opciones · $probedVisibleCount probadas"
                            },
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = CinemaColors.TextSecondary,
                        )
                        TextButton(
                            enabled = !isProbing,
                            onClick = onDiagnoseTop,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = if (isProbing) "Probando swarm…" else "Diagnosticar Top 3",
                                color = if (isProbing) CinemaColors.Gold else CinemaColors.Secondary,
                            )
                        }
                    }
                }
            }
            items(sortedResults, key = { it.id }) { res ->
                val canInspect = res.description == null && !res.sourceRecordId.isNullOrBlank()
                val inLibrary = isResultInLibrary(res, library)
                TorrentReleaseCard(
                    result = res,
                    inLibrary = inLibrary,
                    isEnriching = res.id in state.enrichingIds,
                    assessment = assessmentsById[res.id],
                    isTopRecommended = res.id == topRecommendedId,
                    isProbingSwarm = res.normalizedInfoHash in state.probingHashes,
                    isCastingToTv = isCastingToTv,
                    onInspectDetails = if (canInspect) ({ onInspectResult(res) }) else null,
                    onDiagnoseSwarm = onDiagnoseResult?.let { fn -> { fn(res) } },
                    onStream = { onStreamResult(res, state.activeSeasonHint, state.activeEpisodeHint) },
                    onChooseFiles = { onChooseFilesResult(res) },
                )
            }
        }
    }
}
