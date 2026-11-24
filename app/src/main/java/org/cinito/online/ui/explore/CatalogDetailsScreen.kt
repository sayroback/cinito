package org.cinito.online.ui.explore

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import org.cinito.online.cast.CastSessionState
import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.ui.player.CastTopBarActionButton
import org.cinito.online.metadata.MediaEpisode
import org.cinito.online.metadata.MediaKind
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.search.PlayabilityContext
import org.cinito.online.search.PlayabilityVerdict
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchService
import org.cinito.online.search.SearchSortOrder
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.ui.library.PosterFallbackTile
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.CinemaCompactPill
import org.cinito.online.ui.theme.tabular
import webtor.app.DownloadEntry
import webtor.app.SavedFile
import webtor.app.isVideoName
import webtor.app.resumeFile
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogDetailsScreen(
    detail: CatalogDetailState,
    library: List<DownloadEntry>,
    isCastingToTv: Boolean = false,
    activeWebOsMode: WebOsCastMode? = null,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
    onBack: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (Int?) -> Unit,
    onLanguageFilterChange: (SearchLanguageFilter) -> Unit,
    onSortOrderChange: (SearchSortOrder) -> Unit,
    onToggleShowAll: () -> Unit,
    onRetrySearch: () -> Unit,
    onInspectResult: (TorrentSearchResult) -> Unit,
    onDiagnoseTop: (() -> Unit)? = null,
    onDiagnoseResult: ((TorrentSearchResult) -> Unit)? = null,
    onStartScan: ((Int) -> Unit)? = null,
    onPauseScan: (() -> Unit)? = null,
    onResumeScan: (() -> Unit)? = null,
    onStreamResult: (TorrentSearchResult, Int?, Int?, String) -> Unit,
    onChooseFilesResult: (TorrentSearchResult, String) -> Unit,
    onOpenLibraryEntry: (DownloadEntry) -> Unit,
    onPlayLibraryEntry: (DownloadEntry, Int?, Int?) -> Unit,
    onSearchMoreInTab: () -> Unit,
    onScrollChanged: (Int, Int) -> Unit,
) {
    BackHandler(onBack = onBack)

    val media = detail.media
    val listState = remember(detail.cacheKey) {
        LazyListState(detail.scrollIndex, detail.scrollOffset)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (idx, off) -> onScrollChanged(idx, off) }
    }

    val libraryHashes = remember(library) {
        library.map { it.key.lowercase(Locale.ROOT) }.toSet()
    }
    val playabilityContext = remember(
        media.runtime,
        media.type,
        detail.selectedSeason,
        detail.selectedEpisode,
        isCastingToTv,
        activeWebOsMode,
        libraryHashes,
    ) {
        PlayabilityContext(
            expectedDurationMinutes = PlayabilityContext.parseRuntimeMinutes(media.runtime),
            requestedSeason = if (media.type == "series") detail.selectedSeason else null,
            requestedEpisode = if (media.type == "series") detail.selectedEpisode else null,
            isCastingToTv = isCastingToTv,
            activeWebOsMode = activeWebOsMode,
            inLibraryHashes = libraryHashes,
        )
    }

    val (sortedResults, assessmentsById) = remember(
        detail.rawResults,
        detail.sortOrder,
        detail.languageFilter,
        playabilityContext,
        detail.swarmStatsByHash,
        detail.probeResultsByHash,
    ) {
        SearchService.evaluatePlayabilityAndSort(
            results = detail.rawResults,
            sortOrder = detail.sortOrder,
            languageFilter = detail.languageFilter,
            playabilityContext = playabilityContext,
            scrapeStatsByHash = detail.swarmStatsByHash,
            probeResultsByHash = detail.probeResultsByHash,
        )
    }
    val topRecommendedId = remember(sortedResults, assessmentsById, detail.sortOrder) {
        if (detail.sortOrder != SearchSortOrder.BestPlayability) null
        else sortedResults.firstOrNull()?.id?.takeIf { id ->
            val v = assessmentsById[id]?.verdict
            v == PlayabilityVerdict.Optimal || v == PlayabilityVerdict.Good
        }
    }
    val bestAuto = remember(
        detail.rawResults,
        detail.languageFilter,
        playabilityContext,
        detail.swarmStatsByHash,
        detail.probeResultsByHash,
    ) {
        pickBestAutoStreamResult(
            results = detail.rawResults,
            languageFilter = detail.languageFilter,
            playabilityContext = playabilityContext,
            scrapeStatsByHash = detail.swarmStatsByHash,
            probeResultsByHash = detail.probeResultsByHash,
            media = media,
        )
    }
    val matchingLibraryEntry = remember(media, library, detail.rawResults) {
        findMatchingLibraryEntry(media, library, detail.rawResults)
    }

    var synopsisExpanded by remember(detail.cacheKey) { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }

    val seasons = remember(media.episodes) {
        orderedSeriesSeasons(media.episodes)
    }
    val episodeCountBySeason = remember(media.episodes) {
        media.episodes.groupingBy { it.season }.eachCount()
    }
    val todayIso = remember {
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
    }
    val localEpisodeFiles = remember(matchingLibraryEntry) {
        matchingLibraryEntry?.files.orEmpty()
            .filter { it.name.isVideoName() || it.path.isVideoName() }
            .mapNotNull { file ->
                val parsed = MediaTitleParser.parse(file.path.ifBlank { file.name })
                val s = parsed.season
                val e = parsed.episode
                if (s != null && e != null) (s to e) to file else null
            }
            .toMap()
    }
    val resumeBadgeLabel = remember(matchingLibraryEntry) {
        matchingLibraryEntry?.resumeFile()?.let { file ->
            MediaTitleParser.parse(file.path.ifBlank { file.name }).episodeBadge
        }
    }
    val episodesForSeason = remember(media.episodes, detail.selectedSeason) {
        media.episodes.filter { it.season == detail.selectedSeason }.sortedBy { it.episode }
    }
    val episodesRowState = rememberLazyListState()
    LaunchedEffect(detail.selectedSeason, detail.selectedEpisode, episodesForSeason.size) {
        val targetEp = detail.selectedEpisode ?: return@LaunchedEffect
        val idx = episodesForSeason.indexOfFirst { it.episode == targetEp }
        if (idx >= 0) {
            episodesRowState.animateScrollToItem(idx)
        }
    }
    val activeEpisodeMeta = remember(episodesForSeason, detail.selectedEpisode) {
        detail.selectedEpisode?.let { ep -> episodesForSeason.find { it.episode == ep } }
    }
    val latestDisplayList = remember(sortedResults, assessmentsById, media, detail.showAllResults) {
        filterCatalogDetailResults(
            sortedResults = sortedResults,
            assessmentsById = assessmentsById,
            media = media,
            showAllResults = detail.showAllResults,
        )
    }
    val latestIds = remember(latestDisplayList) { latestDisplayList.map { it.id } }
    val latestById = remember(latestDisplayList) { latestDisplayList.associateBy { it.id } }
    var anchoredIds by remember(
        detail.cacheKey,
        detail.selectedSeason,
        detail.selectedEpisode,
        detail.sortOrder,
        detail.languageFilter,
        detail.showAllResults,
    ) { mutableStateOf(latestIds) }
    LaunchedEffect(latestIds, listState.firstVisibleItemIndex, listState.isScrollInProgress, detail.isSearching) {
        val atTop = listState.firstVisibleItemIndex <= 2 && !listState.isScrollInProgress
        val noProbesYet = detail.probeResultsByHash.isEmpty()
        val scanning = detail.swarmScan.status == SwarmScanStatus.Running
        // A single-card test must update in place; reorder only on demand, while scanning at top, or before any probe.
        if (anchoredIds.isEmpty() || detail.isSearching || noProbesYet || (scanning && atTop)) {
            anchoredIds = latestIds
        }
    }
    val displayList = remember(anchoredIds, latestById) { anchoredIds.mapNotNull { latestById[it] } }
    val hasPendingReorder = anchoredIds != latestIds

    val targetLabel = remember(media.type, detail.selectedSeason, detail.selectedEpisode, activeEpisodeMeta) {
        if (media.type != "series") {
            media.title
        } else if (detail.selectedEpisode != null) {
            val code = formatSeasonEpisodeCode(detail.selectedSeason, detail.selectedEpisode)
            val epTitle = activeEpisodeMeta?.title?.takeIf { it.isNotBlank() }
            if (epTitle != null) "${media.title} · $code ($epTitle)" else "${media.title} · $code"
        } else {
            "${media.title} · Pack / Serie completa"
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaColors.Background)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        item(key = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(296.dp),
            ) {
                val heroUrl = media.backdropUrl ?: media.posterUrl
                if (!heroUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = heroUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(CinemaColors.PrimaryMuted, CinemaColors.Background),
                                ),
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Black.copy(alpha = 0.52f),
                                    0.45f to Color.Black.copy(alpha = 0.28f),
                                    1.0f to CinemaColors.Background,
                                ),
                            ),
                        ),
                )

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(12.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.56f))
                        .align(Alignment.TopStart),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver al catálogo",
                        tint = Color.White,
                    )
                }

                if (onOpenCast != null) {
                    CastTopBarActionButton(
                        castState = castState,
                        onClick = onOpenCast,
                        overHeroScrim = true,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(12.dp)
                            .align(Alignment.TopEnd),
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                            .background(CinemaColors.SurfaceElevated),
                    ) {
                        if (!media.posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = media.posterUrl,
                                contentDescription = media.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            PosterFallbackTile(
                                title = media.title,
                                kind = if (media.type == "series") MediaKind.SERIES else MediaKind.MOVIE,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = media.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = CinemaColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!media.originalTitle.isNullOrBlank() && !media.originalTitle.equals(media.title, ignoreCase = true)) {
                            Text(
                                text = media.originalTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val infoBits = buildList {
                            add(if (media.type == "series") "Serie" else "Película")
                            media.year?.let { add(it.toString()) }
                            media.runtime?.let { add(it) }
                            media.rating?.let { add("★ $it") }
                        }.joinToString(" · ")
                        Text(
                            text = infoBits,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.Gold,
                        )
                        if (media.genres.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                media.genres.take(3).forEach { g ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = CinemaColors.SurfaceBright,
                                    ) {
                                        Text(
                                            text = g,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CinemaColors.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "overview_and_cta") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!media.description.isNullOrBlank()) {
                    Text(
                        text = media.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                        maxLines = if (synopsisExpanded) 16 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .animateContentSize()
                            .clickable { synopsisExpanded = !synopsisExpanded },
                    )
                }

                if (matchingLibraryEntry != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = CinemaColors.Secondary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CinemaColors.Secondary.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CinemaColors.Secondary,
                                    modifier = Modifier.size(18.dp),
                                )
                                val libText = buildString {
                                    append("Disponible en tu biblioteca (${matchingLibraryEntry.stateLabel()})")
                                    if (!resumeBadgeLabel.isNullOrBlank()) {
                                        append(" · ")
                                        append(resumeBadgeLabel)
                                    }
                                }
                                Text(
                                    text = libText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CinemaColors.TextPrimary,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        onPlayLibraryEntry(
                                            matchingLibraryEntry,
                                            if (media.type == "series") detail.selectedSeason else null,
                                            if (media.type == "series") detail.selectedEpisode else null,
                                        )
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (isCastingToTv) "Enviar a TV" else "Reproducir", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { onOpenLibraryEntry(matchingLibraryEntry) },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Ver ficha", maxLines = 1)
                                }
                            }
                        }
                    }
                }

                // Hero primary CTA
                val seasonArg = if (media.type == "series") detail.selectedSeason else null
                val epArg = if (media.type == "series") detail.selectedEpisode else null
                Button(
                    onClick = {
                        if (bestAuto != null) {
                            onStreamResult(bestAuto, seasonArg, epArg, targetLabel)
                        } else {
                            onSearchMoreInTab()
                        }
                    },
                    enabled = bestAuto != null || !detail.isSearching,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    if (bestAuto != null) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                isCastingToTv && media.type == "series" && detail.selectedEpisode != null ->
                                    "Enviar ${formatSeasonEpisodeCode(detail.selectedSeason, detail.selectedEpisode)} a TV"
                                isCastingToTv -> "Enviar a TV (mejor fuente)"
                                media.type == "series" && detail.selectedEpisode != null ->
                                    "Ver ${formatSeasonEpisodeCode(detail.selectedSeason, detail.selectedEpisode)} ahora"
                                else -> "Ver ahora (mejor fuente)"
                            },
                            maxLines = 1,
                        )
                    } else if (detail.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Buscando mejor fuente (${detail.completedProviders}/${detail.totalProviders})…",
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            maxLines = 1,
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Buscar manualmente en indexadores", maxLines = 1)
                    }
                }

                if (bestAuto != null) {
                    val bestSeeders = assessmentsById[bestAuto.id]?.effectiveSeeders ?: bestAuto.seeders
                    val bestSummary = listOfNotNull(
                        bestAuto.languageTag.shortLabel,
                        bestAuto.parsed.qualityBadges.take(2).joinToString(" · ").takeIf { it.isNotEmpty() },
                        bestAuto.sizeText,
                        "↑ $bestSeeders",
                    ).joinToString(" · ")
                    Text(
                        text = "Selección automática: $bestSummary",
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = CinemaColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (bestAuto != null) {
                        OutlinedButton(
                            onClick = { onChooseFilesResult(bestAuto, targetLabel) },
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Elegir archivos", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    OutlinedButton(
                        onClick = onSearchMoreInTab,
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Buscar más", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (media.type == "series") {
            item(key = "series_episodes") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Temporadas y episodios",
                            style = MaterialTheme.typography.titleLarge,
                            color = CinemaColors.TextPrimary,
                        )
                        if (detail.isLoadingDetails && media.episodes.isEmpty()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CinemaColors.Primary,
                            )
                        }
                    }

                    if (seasons.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(seasons, key = { it }) { seasonNum ->
                                CinemaCompactPill(
                                    label = formatSeasonChipLabel(seasonNum, episodeCountBySeason[seasonNum]),
                                    selected = detail.selectedSeason == seasonNum && detail.selectedEpisode != null,
                                    onClick = { onSelectSeason(seasonNum) },
                                )
                            }
                            item(key = "pack_chip") {
                                CinemaCompactPill(
                                    label = "Pack completo",
                                    selected = detail.selectedEpisode == null,
                                    accentBorderColor = CinemaColors.Secondary.copy(alpha = 0.7f),
                                    selectedContainerColor = CinemaColors.Secondary.copy(alpha = 0.18f),
                                    selectedTextColor = CinemaColors.Secondary,
                                    onClick = { onSelectEpisode(null) },
                                )
                            }
                        }

                        if (episodesForSeason.isNotEmpty()) {
                            LazyRow(
                                state = episodesRowState,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(episodesForSeason, key = { "${it.season}:${it.episode}" }) { ep ->
                                    EpisodeSelectorCard(
                                        episode = ep,
                                        selected = detail.selectedEpisode == ep.episode,
                                        localFile = localEpisodeFiles[ep.season to ep.episode],
                                        isFutureRelease = isEpisodeFutureRelease(ep.released, todayIso),
                                        onClick = { onSelectEpisode(ep.episode) },
                                    )
                                }
                            }
                        }
                    } else if (!detail.isLoadingDetails) {
                        Text(
                            text = "No se encontró guía de episodios; buscando packs y capítulos de la serie.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        item(key = "sources_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Fuentes disponibles",
                            style = MaterialTheme.typography.titleLarge,
                            color = CinemaColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val statusSub = when {
                            detail.isSearching ->
                                "Consultando ${detail.completedProviders}/${detail.totalProviders} motores · ${sortedResults.size} encontradas"
                            detail.isScrapingSwarm ->
                                "${displayList.size} recomendadas (${sortedResults.size} totales) · Verificando semillas…"
                            else ->
                                "${displayList.size} mostradas de ${sortedResults.size}" +
                                    if (media.type == "series") {
                                        " · " + (detail.selectedEpisode?.let { formatSeasonEpisodeCode(detail.selectedSeason, it) } ?: "Pack / Serie")
                                    } else {
                                        ""
                                    }
                        }
                        Text(
                            text = statusSub,
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasPendingReorder) {
                            TextButton(
                                onClick = { anchoredIds = latestIds },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = "Mover ↑",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CinemaColors.PrimarySoft,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (onStartScan != null && sortedResults.isNotEmpty() && !detail.isSearching) {
                            val scan = detail.swarmScan
                            if (scan.status == SwarmScanStatus.Idle || scan.status == SwarmScanStatus.Completed) {
                                if (scan.status == SwarmScanStatus.Completed) {
                                    Text(
                                        text = "✓${scan.ok} ✗${scan.dead}",
                                        style = MaterialTheme.typography.labelMedium.tabular(),
                                        color = CinemaColors.TextSecondary,
                                        maxLines = 1,
                                    )
                                }
                                if (scan.status == SwarmScanStatus.Idle) {
                                    listOf(3, 5).forEach { n ->
                                        TextButton(
                                            onClick = { onStartScan(n) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Text("▶ Probar $n", style = MaterialTheme.typography.labelMedium, color = CinemaColors.Secondary, maxLines = 1)
                                        }
                                    }
                                }
                            } else {
                                val running = scan.status == SwarmScanStatus.Running
                                TextButton(
                                    onClick = { (if (running) onPauseScan else onResumeScan)?.invoke() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = if (running) "⏸ ${scan.done}/${scan.total}" else "▶ Siguientes · ${scan.done}/${scan.total} ✓${scan.ok}",
                                        style = MaterialTheme.typography.labelMedium.tabular(),
                                        color = if (running) CinemaColors.Gold else CinemaColors.Secondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        } else if (onStartScan == null && onDiagnoseTop != null && sortedResults.isNotEmpty() && !detail.isSearching) {
                            val isProbing = detail.probingHashes.isNotEmpty()
                            TextButton(
                                enabled = !isProbing,
                                onClick = onDiagnoseTop,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = if (isProbing) "Probando…" else "Test Top 3",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isProbing) CinemaColors.Gold else CinemaColors.Secondary,
                                    maxLines = 1,
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { sortExpanded = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Ordenar fuentes",
                                    tint = CinemaColors.TextPrimary,
                                )
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
                                                fontWeight = if (order == detail.sortOrder) FontWeight.Bold else FontWeight.Normal,
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
                        IconButton(onClick = onRetrySearch) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Actualizar fuentes",
                                tint = CinemaColors.TextPrimary,
                            )
                        }
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(SearchLanguageFilter.entries) { filter ->
                        CinemaCompactPill(
                            label = filter.label,
                            selected = detail.languageFilter == filter,
                            accentBorderColor = CinemaColors.Secondary.copy(alpha = 0.7f),
                            selectedContainerColor = CinemaColors.Secondary.copy(alpha = 0.18f),
                            selectedTextColor = CinemaColors.Secondary,
                            onClick = { onLanguageFilterChange(filter) },
                        )
                    }
                }

                if (detail.isSearching) {
                    LinearProgressIndicator(
                        progress = {
                            if (detail.totalProviders > 0) {
                                detail.completedProviders.toFloat() / detail.totalProviders.toFloat()
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        color = CinemaColors.Primary,
                        trackColor = CinemaColors.SurfaceBright,
                    )
                }

                if (!detail.isSearching && detail.failures.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${detail.failures.size} proveedor(es) sin respuesta: ${detail.failures.joinToString { it.providerName }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.Gold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetrySearch) { Text("Reintentar") }
                    }
                }
            }
        }

        if (!detail.isSearching && sortedResults.isEmpty()) {
            item(key = "empty_sources") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = CinemaColors.Surface,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "No se encontraron fuentes con \"${detail.languageFilter.label}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CinemaColors.TextSecondary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (detail.languageFilter != SearchLanguageFilter.AllLanguages) {
                                OutlinedButton(onClick = { onLanguageFilterChange(SearchLanguageFilter.AllLanguages) }) {
                                    Text("Ver todos los idiomas")
                                }
                            }
                            if (media.type == "series" && detail.selectedEpisode != null) {
                                OutlinedButton(onClick = { onSelectEpisode(null) }) {
                                    Text("Buscar pack completo")
                                }
                            }
                            OutlinedButton(onClick = onSearchMoreInTab) {
                                Text("Editar búsqueda")
                            }
                            OutlinedButton(onClick = onRetrySearch) {
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }
        }

        val seasonArg = if (media.type == "series") detail.selectedSeason else null
        val epArg = if (media.type == "series") detail.selectedEpisode else null
        items(displayList, key = { it.id }) { res ->
            val canInspect = res.description == null && !res.sourceRecordId.isNullOrBlank()
            val inLibrary = isResultInLibrary(res, library)
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                TorrentReleaseCard(
                    result = res,
                    inLibrary = inLibrary,
                    isEnriching = res.id in detail.enrichingIds,
                    assessment = assessmentsById[res.id],
                    isTopRecommended = res.id == topRecommendedId,
                    isProbingSwarm = res.normalizedInfoHash in detail.probingHashes,
                    isCastingToTv = isCastingToTv,
                    showCleanTitle = false,
                    onInspectDetails = if (canInspect) ({ onInspectResult(res) }) else null,
                    onDiagnoseSwarm = onDiagnoseResult?.let { fn -> { fn(res) } },
                    onStream = { onStreamResult(res, seasonArg, epArg, targetLabel) },
                    onChooseFiles = { onChooseFilesResult(res, targetLabel) },
                )
            }
        }

        if (sortedResults.size > displayList.size || detail.showAllResults) {
            item(key = "show_more_sources") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(
                        onClick = onToggleShowAll,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (detail.showAllResults) {
                                "Ver solo recomendadas con semillas"
                            } else {
                                "Ver las ${sortedResults.size - displayList.size} fuentes restantes"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeSelectorCard(
    episode: MediaEpisode,
    selected: Boolean,
    localFile: SavedFile? = null,
    isFutureRelease: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(192.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) CinemaColors.PrimaryMuted.copy(alpha = 0.45f) else CinemaColors.Surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) CinemaColors.Primary else CinemaColors.HairlineBorder,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(CinemaColors.SurfaceElevated),
            ) {
                if (!episode.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.thumbnailUrl,
                        contentDescription = episode.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = CinemaColors.TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) CinemaColors.Primary else Color.Black.copy(alpha = 0.72f),
                ) {
                    Text(
                        text = "E%02d".format(episode.episode),
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (localFile != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        shape = CircleShape,
                        color = Color(0xCC090A0F),
                        border = BorderStroke(1.dp, CinemaColors.Secondary.copy(alpha = 0.8f)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Disponible en biblioteca",
                            tint = CinemaColors.Secondary,
                            modifier = Modifier.padding(3.dp).size(12.dp),
                        )
                    }
                } else if (isFutureRelease) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.76f),
                        border = BorderStroke(1.dp, CinemaColors.Gold.copy(alpha = 0.6f)),
                    ) {
                        Text(
                            text = "Próx.",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaColors.Gold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                if (localFile != null && localFile.playbackDurationMs > 0L && localFile.playbackPositionMs > 0L) {
                    val frac = (localFile.playbackPositionMs.toFloat() / localFile.playbackDurationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = CinemaColors.Primary,
                        trackColor = Color.Black.copy(alpha = 0.55f),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextPrimary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitleText = episode.overview?.takeIf { it.isNotBlank() }
                    ?: episode.released?.trim()?.take(10)?.takeIf { it.isNotBlank() }?.let { "Emisión: $it" }
                Text(
                    text = subtitleText ?: " ",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = if (isFutureRelease && episode.overview.isNullOrBlank()) CinemaColors.Gold else CinemaColors.TextSecondary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
