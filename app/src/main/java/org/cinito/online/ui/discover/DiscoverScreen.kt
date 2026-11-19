package org.cinito.online.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.cinito.online.cast.CastSessionState
import org.cinito.online.metadata.CatalogFeed
import org.cinito.online.metadata.CatalogGenreRegistry
import org.cinito.online.metadata.CatalogRegion
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.search.SearchCategory
import org.cinito.online.ui.explore.DiscoverCategoryTab
import org.cinito.online.ui.explore.DiscoverUiState
import org.cinito.online.ui.explore.buildCatalogLibraryIndex
import org.cinito.online.ui.player.CastTopBarActionButton
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.CinemaCompactPill
import org.cinito.online.ui.theme.CinemaCompactSearchField
import org.cinito.online.ui.theme.tabular
import webtor.app.DownloadEntry

@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    metadataEnabled: Boolean,
    catalogRegion: CatalogRegion,
    library: List<DownloadEntry>,
    onRefresh: () -> Unit,
    onSelectCategoryTab: (DiscoverCategoryTab) -> Unit,
    onSelectFeed: (CatalogFeed) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onCatalogQueryChange: (String) -> Unit,
    onSubmitCatalogSearch: () -> Unit,
    onClearCatalogSearch: () -> Unit,
    onOpenMedia: (MediaMetadata) -> Unit,
    onGridScrollChanged: (Int, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onFallbackDirectSearch: (String, SearchCategory) -> Unit,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    val isSearchMode = state.submittedCatalogQuery.isNotBlank()
    var searchExpanded by rememberSaveable {
        mutableStateOf(state.catalogQuery.isNotEmpty() || state.submittedCatalogQuery.isNotEmpty())
    }
    val showSearchBar = searchExpanded || state.catalogQuery.isNotEmpty() || isSearchMode

    BackHandler(enabled = showSearchBar) {
        keyboard?.hide()
        focusManager.clearFocus()
        searchExpanded = false
        if (state.catalogQuery.isNotEmpty() || isSearchMode) {
            onClearCatalogSearch()
        }
    }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded && state.submittedCatalogQuery.isBlank()) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val libraryIndex = remember(library) { buildCatalogLibraryIndex(library.map { it.title }) }
    val displayedItems = remember(
        isSearchMode,
        state.catalogSearchResults,
        state.feedItems,
        state.movies,
        state.series,
        state.categoryTab,
    ) {
        if (isSearchMode) {
            when (state.categoryTab) {
                DiscoverCategoryTab.All -> state.catalogSearchResults
                DiscoverCategoryTab.Movies -> state.catalogSearchResults.filter { it.type != "series" }
                DiscoverCategoryTab.Series -> state.catalogSearchResults.filter { it.type == "series" }
            }
        } else {
            when (state.categoryTab) {
                DiscoverCategoryTab.All -> state.feedItems
                DiscoverCategoryTab.Movies -> state.movies
                DiscoverCategoryTab.Series -> state.series
            }
        }
    }

    val gridState = remember {
        LazyGridState(
            firstVisibleItemIndex = state.gridScrollIndex,
            firstVisibleItemScrollOffset = state.gridScrollOffset,
        )
    }
    var lastFilterKey by remember {
        mutableStateOf(
            listOf<Any?>(state.categoryTab, state.selectedFeed, state.selectedGenre, state.submittedCatalogQuery),
        )
    }

    LaunchedEffect(
        state.categoryTab,
        state.selectedFeed,
        state.selectedGenre,
        state.submittedCatalogQuery,
    ) {
        val nextKey = listOf<Any?>(
            state.categoryTab,
            state.selectedFeed,
            state.selectedGenre,
            state.submittedCatalogQuery,
        )
        if (lastFilterKey != nextKey) {
            lastFilterKey = nextKey
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (idx, off) -> onGridScrollChanged(idx, off) }
    }

    LaunchedEffect(
        gridState,
        displayedItems.size,
        state.hasMoreForActiveTab,
        state.isLoading,
        state.isLoadingMore,
        state.loadMoreError,
        isSearchMode,
    ) {
        if (isSearchMode || state.isLoading || !state.hasMoreForActiveTab || state.isLoadingMore || state.loadMoreError) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalCount) ->
                if (totalCount > 0 && lastVisible >= totalCount - 6) {
                    onLoadMore()
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaColors.Background),
    ) {
        DiscoverHeaderBar(
            catalogRegion = catalogRegion,
            metadataEnabled = metadataEnabled,
            showSearchBar = showSearchBar,
            castState = castState,
            onOpenCast = onOpenCast,
            onToggleSearch = {
                if (showSearchBar) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    searchExpanded = false
                    if (state.catalogQuery.isNotEmpty() || isSearchMode) {
                        onClearCatalogSearch()
                    }
                } else {
                    searchExpanded = true
                }
            },
            onRefresh = onRefresh,
        )

        if (!metadataEnabled) {
            DisabledMetadataState(onOpenSettings = onOpenSettings)
            return@Column
        }

        AnimatedVisibility(
            visible = showSearchBar,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            CinemaCompactSearchField(
                query = state.catalogQuery,
                submittedQuery = state.submittedCatalogQuery,
                placeholder = "Buscar título en el catálogo…",
                focusRequester = searchFocusRequester,
                submitContentDescription = "Buscar en catálogo",
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                onQueryChange = onCatalogQueryChange,
                onSubmit = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onSubmitCatalogSearch()
                },
                onClear = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    onClearCatalogSearch()
                },
            )
        }

        DiscoverSegmentedTypeTabs(
            selectedTab = state.categoryTab,
            onSelectTab = onSelectCategoryTab,
        )

        if (!isSearchMode) {
            DiscoverFeedAndGenreStrip(
                selectedFeed = state.selectedFeed,
                selectedGenreId = state.selectedGenre,
                onSelectFeed = onSelectFeed,
                onSelectGenre = onSelectGenre,
            )
        }

        if (state.isCatalogSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = CinemaColors.Primary,
                trackColor = CinemaColors.SurfaceBright,
            )
        }

        if (!isSearchMode && state.isLoading && displayedItems.isEmpty()) {
            DiscoverSkeletonGrid()
            return@Column
        }

        if (!isSearchMode && displayedItems.isEmpty()) {
            val activeGenreLabel = remember(state.selectedGenre) {
                CatalogGenreRegistry.findById(state.selectedGenre)?.label ?: state.selectedGenre
            }
            EmptyCatalogState(
                genreLabel = activeGenreLabel,
                onClearGenre = { onSelectGenre(null) },
                onRetry = onRefresh,
            )
            return@Column
        }

        if (isSearchMode && !state.isCatalogSearching && displayedItems.isEmpty()) {
            val fallbackCategory = when (state.categoryTab) {
                DiscoverCategoryTab.All -> SearchCategory.All
                DiscoverCategoryTab.Movies -> SearchCategory.Movies
                DiscoverCategoryTab.Series -> SearchCategory.Series
            }
            EmptyCatalogSearchState(
                submittedQuery = state.submittedCatalogQuery,
                onFallbackDirectSearch = { onFallbackDirectSearch(state.submittedCatalogQuery, fallbackCategory) },
            )
            return@Column
        }

        val isHomeCuratedView = !isSearchMode &&
            state.categoryTab == DiscoverCategoryTab.All &&
            state.selectedFeed == CatalogFeed.TrendingToday &&
            state.selectedGenre == null

        val heroItems = remember(
            isSearchMode,
            isHomeCuratedView,
            state.highlightItems,
            state.selectedGenre,
            displayedItems,
        ) {
            when {
                isSearchMode || state.selectedGenre != null -> emptyList()
                isHomeCuratedView -> state.highlightItems.ifEmpty { listOfNotNull(displayedItems.firstOrNull()) }
                else -> listOfNotNull(displayedItems.firstOrNull())
            }
        }

        val gridItems = remember(heroItems, displayedItems) {
            if (heroItems.size == 1 && displayedItems.firstOrNull() == heroItems.first()) {
                displayedItems.drop(1)
            } else {
                displayedItems
            }
        }

        val showRails = isHomeCuratedView &&
            (
                state.newReleasesRail.isNotEmpty() ||
                    state.airingSeriesRail.isNotEmpty() ||
                    state.dailyGenreRail.isNotEmpty() ||
                    state.topRatedRail.isNotEmpty()
                )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isSearchMode) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search_summary") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Resultados para \"${state.submittedCatalogQuery}\" (${displayedItems.size})",
                                style = MaterialTheme.typography.titleMedium.tabular(),
                                color = CinemaColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    searchExpanded = false
                                    onClearCatalogSearch()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("Catálogo", color = CinemaColors.PrimarySoft)
                            }
                        }
                    }
                } else if (heroItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hero_pager") {
                        FeaturedHeroPager(
                            items = heroItems,
                            libraryIndex = libraryIndex,
                            onOpenMedia = onOpenMedia,
                        )
                    }
                }

                if (showRails) {
                    if (state.newReleasesRail.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "rail_new_releases") {
                            CatalogPosterRail(
                                title = "Estrenos",
                                items = state.newReleasesRail,
                                libraryIndex = libraryIndex,
                                showTypeTag = true,
                                onSeeAll = { onSelectFeed(CatalogFeed.NewReleases) },
                                onOpenMedia = onOpenMedia,
                            )
                        }
                    }
                    if (state.airingSeriesRail.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "rail_airing_series") {
                            CatalogPosterRail(
                                title = "Nuevos episodios hoy",
                                items = state.airingSeriesRail,
                                libraryIndex = libraryIndex,
                                showTypeTag = false,
                                onSeeAll = {
                                    onSelectCategoryTab(DiscoverCategoryTab.Series)
                                    onSelectFeed(CatalogFeed.NewReleases)
                                },
                                onOpenMedia = onOpenMedia,
                            )
                        }
                    }
                    if (state.dailyGenreRail.isNotEmpty() && !state.dailyGenreLabel.isNullOrBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "rail_daily_genre") {
                            CatalogPosterRail(
                                title = "Selección del día · ${state.dailyGenreLabel}",
                                items = state.dailyGenreRail,
                                libraryIndex = libraryIndex,
                                showTypeTag = true,
                                onSeeAll = { state.dailyGenreId?.let(onSelectGenre) },
                                onOpenMedia = onOpenMedia,
                            )
                        }
                    }
                    if (state.topRatedRail.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "rail_top_rated") {
                            CatalogPosterRail(
                                title = "Aclamadas",
                                items = state.topRatedRail,
                                libraryIndex = libraryIndex,
                                showTypeTag = true,
                                onSeeAll = { onSelectFeed(CatalogFeed.TopRated) },
                                onOpenMedia = onOpenMedia,
                            )
                        }
                    }
                }

                if (!isSearchMode && gridItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "section_header") {
                        val activeGenreLabel = CatalogGenreRegistry.findById(state.selectedGenre)?.label
                        val sectionTitle = buildList {
                            add(state.selectedFeed.label)
                            if (state.categoryTab != DiscoverCategoryTab.All) add(state.categoryTab.label)
                            if (!activeGenreLabel.isNullOrBlank()) add(activeGenreLabel)
                        }.joinToString(" · ")
                        val countText = state.totalCountForActiveTab?.takeIf { it > displayedItems.size }?.let { total ->
                            "${displayedItems.size} / $total"
                        } ?: "${displayedItems.size} títulos"
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = if (showRails) 4.dp else 0.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = sectionTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = CinemaColors.TextPrimary,
                            )
                            Text(
                                text = countText,
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = CinemaColors.TextTertiary,
                            )
                        }
                    }
                }

                items(gridItems, key = { "grid_${it.type}:${it.id}" }) { meta ->
                    val inLib = remember(meta.id, libraryIndex) { libraryIndex.contains(meta) }
                    CatalogPosterCard(
                        item = meta,
                        inLibrary = inLib,
                        showTypeTag = state.categoryTab == DiscoverCategoryTab.All || isSearchMode,
                        onClick = { onOpenMedia(meta) },
                    )
                }

                if (!isSearchMode) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "load_more_footer") {
                        DiscoverLoadMoreFooter(
                            isLoadingMore = state.isLoadingMore,
                            loadMoreError = state.loadMoreError,
                            hasMore = state.hasMoreForActiveTab,
                            loadedCount = displayedItems.size,
                            onRetry = onLoadMore,
                        )
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search_torrent_fallback") {
                        val fallbackCategory = when (state.categoryTab) {
                            DiscoverCategoryTab.All -> SearchCategory.All
                            DiscoverCategoryTab.Movies -> SearchCategory.Movies
                            DiscoverCategoryTab.Series -> SearchCategory.Series
                        }
                        OutlinedButton(
                            onClick = { onFallbackDirectSearch(state.submittedCatalogQuery, fallbackCategory) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(46.dp),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Buscar \"${state.submittedCatalogQuery}\" directo en torrents")
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(CinemaColors.Background, Color.Transparent))),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, CinemaColors.Background))),
            )
        }
    }
}

@Composable
private fun DiscoverHeaderBar(
    catalogRegion: CatalogRegion,
    metadataEnabled: Boolean,
    showSearchBar: Boolean,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
    onToggleSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Descubrir",
                style = MaterialTheme.typography.headlineLarge,
                color = CinemaColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = CinemaColors.SurfaceElevated,
                border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
            ) {
                Text(
                    text = if (catalogRegion == CatalogRegion.MexicoLatino) "MX" else "GLOBAL",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.5.sp),
                    color = CinemaColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (metadataEnabled) {
                IconButton(onClick = onToggleSearch) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (showSearchBar) CinemaColors.PrimaryMuted else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (showSearchBar) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (showSearchBar) "Cerrar búsqueda" else "Buscar en catálogo",
                            tint = if (showSearchBar) CinemaColors.PrimarySoft else CinemaColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Actualizar catálogo",
                        tint = CinemaColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (onOpenCast != null) {
                CastTopBarActionButton(
                    castState = castState,
                    onClick = onOpenCast,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun DiscoverSegmentedTypeTabs(
    selectedTab: DiscoverCategoryTab,
    onSelectTab: (DiscoverCategoryTab) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(10.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            DiscoverCategoryTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(role = Role.Tab) { onSelectTab(tab) }
                        .semantics { this.selected = selected },
                    shape = RoundedCornerShape(7.dp),
                    color = if (selected) CinemaColors.SurfaceBright else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, CinemaColors.HairlineBorderStrong) else null,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (selected) CinemaColors.TextPrimary else CinemaColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverFeedAndGenreStrip(
    selectedFeed: CatalogFeed,
    selectedGenreId: String?,
    onSelectFeed: (CatalogFeed) -> Unit,
    onSelectGenre: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(CatalogFeed.entries, key = { "feed_${it.name}" }) { feed ->
            val selected = selectedFeed == feed
            CinemaCompactPill(
                label = feed.label,
                selected = selected,
                accentBorderColor = CinemaColors.Primary,
                selectedContainerColor = CinemaColors.PrimaryMuted,
                selectedTextColor = Color.White,
                onClick = { onSelectFeed(feed) },
            )
        }
        item(key = "strip_divider") {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(1.dp)
                    .height(16.dp)
                    .background(CinemaColors.HairlineBorderStrong),
            )
        }
        items(CatalogGenreRegistry.genres, key = { "genre_${it.id}" }) { genre ->
            val selected = selectedGenreId.equals(genre.id, ignoreCase = true)
            CinemaCompactPill(
                label = genre.label,
                selected = selected,
                accentBorderColor = CinemaColors.HairlineBorderStrong,
                selectedContainerColor = CinemaColors.SurfaceBright,
                selectedTextColor = CinemaColors.TextPrimary,
                onClick = { onSelectGenre(if (selected) null else genre.id) },
            )
        }
    }
}

@Composable
private fun DisabledMetadataState(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CinemaColors.Surface,
            border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ExploreOff,
                    contentDescription = null,
                    tint = CinemaColors.TextSecondary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = "Metadatos desactivados",
                    style = MaterialTheme.typography.titleLarge,
                    color = CinemaColors.TextPrimary,
                )
                Text(
                    text = "Activaste el modo de privacidad estricta sin consultas a catálogos externos. Puedes buscar torrents manualmente o activar los pósters en Ajustes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextSecondary,
                )
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaColors.Primary, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Ir a Ajustes de Privacidad")
                }
            }
        }
    }
}

@Composable
private fun EmptyCatalogState(
    genreLabel: String?,
    onClearGenre: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (genreLabel != null) "No hay títulos en \"$genreLabel\"" else "No se pudo cargar el catálogo",
                style = MaterialTheme.typography.titleMedium,
                color = CinemaColors.TextPrimary,
            )
            if (genreLabel != null) {
                OutlinedButton(onClick = onClearGenre) { Text("Quitar filtro de género") }
            } else {
                OutlinedButton(onClick = onRetry) { Text("Reintentar") }
            }
        }
    }
}

@Composable
private fun EmptyCatalogSearchState(
    submittedQuery: String,
    onFallbackDirectSearch: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = CinemaColors.Surface,
            border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Sin coincidencias en el catálogo para \"$submittedQuery\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                )
                Text(
                    text = "Puedes buscar el título directamente en los motores de torrents.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
                Button(
                    onClick = onFallbackDirectSearch,
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaColors.Primary, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Buscar en motores torrent")
                }
            }
        }
    }
}
