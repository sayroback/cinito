package org.cinito.online.ui.explore

import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cinito.online.metadata.CatalogFeed
import org.cinito.online.metadata.CatalogGenreRegistry
import org.cinito.online.metadata.CatalogPage
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.metadata.MetadataRepository
import org.cinito.online.search.PlayabilityContext
import org.cinito.online.search.PlayabilityVerdict
import org.cinito.online.search.ProbePriority
import org.cinito.online.search.ProbeProfile
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchService
import org.cinito.online.search.SearchSortOrder
import org.cinito.online.search.SearchTitleResolution
import org.cinito.online.search.SearchUtils
import org.cinito.online.search.SwarmDiagnosticsCoordinator
import org.cinito.online.search.SwarmProbeResult
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.search.TorznabConfig
import org.cinito.online.search.UdpTrackerScraper
import org.cinito.online.settings.AppPreferences
import org.cinito.online.settings.PlayabilityDiagnosticsMode
import java.util.Locale

/**
 * Coordinates all state, caching, and network searches for the Discover catalog,
 * Catalog Details, and multi-provider Torrent Search screens inside ViewModel scope
 * so opening a player/file sheet or switching tabs never discards search state.
 */
class ExploreCoordinator(
    private val scope: CoroutineScope,
    private val metadataRepository: MetadataRepository,
    private val searchService: SearchService,
    private val preferencesFlow: StateFlow<AppPreferences>,
    private val torznabFlow: StateFlow<List<TorznabConfig>>,
    private val onRecordRecentSearch: (String) -> Unit,
    private val todayProvider: () -> String = { java.time.LocalDate.now().toString() },
    private val clockProvider: () -> Long = { System.currentTimeMillis() },
    private val udpScraper: UdpTrackerScraper = UdpTrackerScraper(),
    private val swarmDiagnostics: SwarmDiagnosticsCoordinator? = null,
    private val canAutoActiveProbe: () -> Boolean = { false },
) {
    val supportsActiveSwarmProbe: Boolean
        get() = swarmDiagnostics?.isAvailable == true

    private val _discover = MutableStateFlow(DiscoverUiState())
    val discover: StateFlow<DiscoverUiState> = _discover.asStateFlow()

    private val _search = MutableStateFlow(
        SearchUiState(languageFilter = preferencesFlow.value.searchLanguageFilter),
    )
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var catalogLoadJob: Job? = null
    private var catalogLoadMoreJob: Job? = null
    private var catalogSearchJob: Job? = null
    private var detailMetaJob: Job? = null
    private var detailSearchJob: Job? = null
    private var detailScrapeJob: Job? = null
    private var detailProbeJob: Job? = null
    private var detailProbeToken: Long = 0L
    private var detailScanJob: Job? = null
    private var detailScanToken: Long = 0L
    private var searchTabJob: Job? = null
    private var searchScrapeJob: Job? = null
    private var searchProbeJob: Job? = null
    private var searchProbeToken: Long = 0L
    private var searchScanJob: Job? = null
    private var searchScanToken: Long = 0L

    init {
        scope.launch {
            preferencesFlow
                .map { it.hideAdultContent }
                .distinctUntilChanged()
                .collect { hideAdult ->
                    if (hideAdult) {
                        var resetAdultCategory = false
                        _search.update { cur ->
                            val wasAdultCategory = cur.category == SearchCategory.Adult
                            if (wasAdultCategory) resetAdultCategory = true
                            val filtered = cur.rawResults.filterNot {
                                it.category == SearchCategory.Adult ||
                                    SearchUtils.isAdultContent(it.title, it.description)
                            }
                            if (!wasAdultCategory && filtered.size == cur.rawResults.size) {
                                cur
                            } else {
                                cur.copy(
                                    category = if (wasAdultCategory) SearchCategory.All else cur.category,
                                    rawResults = filtered,
                                    queriedScopes = if (wasAdultCategory) emptySet() else cur.queriedScopes,
                                    isSearching = if (wasAdultCategory) false else cur.isSearching,
                                    isScrapingSwarm = if (wasAdultCategory) false else cur.isScrapingSwarm,
                                    probingHashes = if (wasAdultCategory) emptySet() else cur.probingHashes,
                                    swarmScan = if (wasAdultCategory) SwarmScanState() else cur.swarmScan,
                                )
                            }
                        }
                        if (resetAdultCategory) {
                            searchTabJob?.cancel()
                            searchScrapeJob?.cancel()
                            searchProbeJob?.cancel()
                            searchScanToken++
                            searchScanJob?.cancel()
                        }
                    }
                }
        }
    }

    fun cancelProbeAndClaimOwnership(result: TorrentSearchResult) {
        val hash = result.normalizedInfoHash ?: return
        pauseDetailScan()
        pauseSearchScan()
        swarmDiagnostics?.claimExternalOwnership(hash)
        if (_discover.value.activeDetail?.probingHashes?.contains(hash) == true) {
            detailProbeToken++
            detailProbeJob?.cancel()
            _discover.update { cur ->
                val active = cur.activeDetail ?: return@update cur
                cur.copy(activeDetail = active.copy(probingHashes = active.probingHashes - hash))
            }
        }
        if (hash in _search.value.probingHashes) {
            searchProbeToken++
            searchProbeJob?.cancel()
            _search.update { cur -> cur.copy(probingHashes = cur.probingHashes - hash) }
        }
    }

    fun loadDiscover(force: Boolean = false) {
        if (!preferencesFlow.value.metadataLookupEnabled) return
        val cur = _discover.value
        val today = todayProvider()
        val nowMs = clockProvider()
        val ttlFresh = cur.loadedAtMs > 0L && (nowMs - cur.loadedAtMs) < DISCOVER_IN_MEMORY_TTL_MS
        val sameDayReady = cur.loadedDay == today && ttlFresh && (cur.movies.isNotEmpty() || cur.series.isNotEmpty())
        if (!force && sameDayReady) return
        val dayChanged = cur.loadedDay != null && (cur.loadedDay != today || !ttlFresh)
        if (catalogLoadJob?.isActive == true && !force && !dayChanged) return

        val targetFeed = cur.selectedFeed
        val targetGenreId = cur.selectedGenre
        val targetGenre = CatalogGenreRegistry.findById(targetGenreId)
        val hasExisting = cur.movies.isNotEmpty() || cur.series.isNotEmpty()

        catalogLoadMoreJob?.cancel()
        catalogLoadJob?.cancel()
        catalogLoadJob = scope.launch {
            _discover.update {
                it.copy(
                    isLoading = !hasExisting || force,
                    isLoadingMore = false,
                    loadMoreError = false,
                )
            }
            coroutineScope {
                val moviesDef = async {
                    runCatchingNonCancellation {
                        metadataRepository.getCatalogPage(
                            type = "movie",
                            feed = targetFeed,
                            genre = targetGenre,
                            cursor = null,
                            forceRefresh = force,
                        )
                    }.getOrDefault(CatalogPage(emptyList()))
                }
                val seriesDef = async {
                    runCatchingNonCancellation {
                        metadataRepository.getCatalogPage(
                            type = "series",
                            feed = targetFeed,
                            genre = targetGenre,
                            cursor = null,
                            forceRefresh = force,
                        )
                    }.getOrDefault(CatalogPage(emptyList()))
                }
                val shouldFetchRails = targetGenre == null && targetFeed == CatalogFeed.TrendingToday
                val dailyGenre = if (shouldFetchRails) dailyFeaturedGenre(today) else null
                val newMoviesDef: Deferred<List<MediaMetadata>>? = if (shouldFetchRails) {
                    async {
                        runCatchingNonCancellation {
                            metadataRepository.getCatalogPage("movie", CatalogFeed.NewReleases, null, null, forceRefresh = force).items
                        }.getOrDefault(emptyList())
                    }
                } else null
                val newSeriesDef: Deferred<List<MediaMetadata>>? = if (shouldFetchRails) {
                    async {
                        runCatchingNonCancellation {
                            metadataRepository.getCatalogPage("series", CatalogFeed.NewReleases, null, null, forceRefresh = force).items
                        }.getOrDefault(emptyList())
                    }
                } else null
                val airingSeriesDef: Deferred<List<MediaMetadata>>? = if (shouldFetchRails) {
                    async {
                        runCatchingNonCancellation {
                            metadataRepository.getAiringSeriesSchedule(today, forceRefresh = force)
                        }.getOrDefault(emptyList())
                    }
                } else null
                val dailyGenreMoviesDef: Deferred<List<MediaMetadata>>? = if (dailyGenre != null) {
                    async {
                        runCatchingNonCancellation {
                            metadataRepository.getCatalogPage("movie", CatalogFeed.TrendingToday, dailyGenre, null, forceRefresh = force).items
                        }.getOrDefault(emptyList())
                    }
                } else null
                val dailyGenreSeriesDef: Deferred<List<MediaMetadata>>? = if (dailyGenre != null) {
                    async {
                        runCatchingNonCancellation {
                            metadataRepository.getCatalogPage("series", CatalogFeed.TrendingToday, dailyGenre, null, forceRefresh = force).items
                        }.getOrDefault(emptyList())
                    }
                } else null
                val topMoviesDef: Deferred<List<MediaMetadata>>? = if (shouldFetchRails) {
                    async {
                        runCatchingNonCancellation {
                            metadataRepository.getCatalogPage("movie", CatalogFeed.TopRated, null, null, forceRefresh = force).items
                        }.getOrDefault(emptyList())
                    }
                } else null

                val moviesPage = moviesDef.await()
                val seriesPage = seriesDef.await()
                val newRail = if (shouldFetchRails) {
                    interleaveCatalog(newMoviesDef?.await().orEmpty(), newSeriesDef?.await().orEmpty())
                        .distinctBy { "${it.type}:${it.id}" }
                        .take(14)
                } else {
                    emptyList()
                }
                val airingRail = if (shouldFetchRails) {
                    airingSeriesDef?.await().orEmpty().take(14)
                } else {
                    emptyList()
                }
                val dailyGenreRail = if (dailyGenre != null) {
                    interleaveCatalog(dailyGenreMoviesDef?.await().orEmpty(), dailyGenreSeriesDef?.await().orEmpty())
                        .distinctBy { "${it.type}:${it.id}" }
                        .take(14)
                } else {
                    emptyList()
                }
                val topRail = if (shouldFetchRails) {
                    topMoviesDef?.await().orEmpty()
                        .distinctBy { "${it.type}:${it.id}" }
                        .take(14)
                } else {
                    emptyList()
                }

                coroutineContext.ensureActive()

                val (_, dedupMovies) = appendCatalogDeduplicating(emptyList(), moviesPage.items)
                val (_, dedupSeries) = appendCatalogDeduplicating(emptyList(), seriesPage.items)
                val combinedFeed = interleaveCatalog(dedupMovies, dedupSeries)
                val highlights = selectDailyHighlights(
                    movies = dedupMovies,
                    series = dedupSeries,
                    dayIso = today,
                    newReleases = newRail,
                    topRated = topRail,
                )
                val nextLoadedDay = if (dedupMovies.isNotEmpty() || dedupSeries.isNotEmpty()) today else null

                _discover.update { state ->
                    if (state.selectedFeed != targetFeed || state.selectedGenre != targetGenreId) {
                        state
                    } else {
                        state.copy(
                            movies = dedupMovies,
                            series = dedupSeries,
                            feedItems = combinedFeed,
                            highlightItems = highlights,
                            newReleasesRail = if (shouldFetchRails) newRail else state.newReleasesRail,
                            airingSeriesRail = if (shouldFetchRails) airingRail else state.airingSeriesRail,
                            dailyGenreId = if (shouldFetchRails) dailyGenre?.id else state.dailyGenreId,
                            dailyGenreLabel = if (shouldFetchRails) dailyGenre?.label else state.dailyGenreLabel,
                            dailyGenreRail = if (shouldFetchRails) dailyGenreRail else state.dailyGenreRail,
                            topRatedRail = if (shouldFetchRails) topRail else state.topRatedRail,
                            moviesCursor = moviesPage.nextCursor,
                            seriesCursor = seriesPage.nextCursor,
                            hasMoreMovies = moviesPage.hasMore,
                            hasMoreSeries = seriesPage.hasMore,
                            moviesTotalCount = moviesPage.totalCount,
                            seriesTotalCount = seriesPage.totalCount,
                            loadedDay = nextLoadedDay,
                            loadedAtMs = if (nextLoadedDay != null) nowMs else state.loadedAtMs,
                            isLoading = false,
                            loadMoreError = false,
                        )
                    }
                }
            }
        }
    }

    fun loadMoreDiscover() {
        if (!preferencesFlow.value.metadataLookupEnabled) return
        val cur = _discover.value
        if (cur.isLoading || cur.isLoadingMore || cur.submittedCatalogQuery.isNotBlank()) return
        if (catalogLoadJob?.isActive == true || catalogLoadMoreJob?.isActive == true) return

        val fetchMovies = (cur.categoryTab == DiscoverCategoryTab.All || cur.categoryTab == DiscoverCategoryTab.Movies) &&
            cur.hasMoreMovies && !cur.moviesCursor.isNullOrBlank()
        val fetchSeries = (cur.categoryTab == DiscoverCategoryTab.All || cur.categoryTab == DiscoverCategoryTab.Series) &&
            cur.hasMoreSeries && !cur.seriesCursor.isNullOrBlank()
        if (!fetchMovies && !fetchSeries) return

        val targetFeed = cur.selectedFeed
        val targetGenreId = cur.selectedGenre
        val targetGenre = CatalogGenreRegistry.findById(targetGenreId)
        val moviesCursor = cur.moviesCursor
        val seriesCursor = cur.seriesCursor

        catalogLoadMoreJob = scope.launch {
            _discover.update { it.copy(isLoadingMore = true, loadMoreError = false) }
            val result = runCatchingNonCancellation {
                coroutineScope {
                    val mDef = if (fetchMovies) {
                        async {
                            metadataRepository.getCatalogPage("movie", targetFeed, targetGenre, moviesCursor)
                        }
                    } else null
                    val sDef = if (fetchSeries) {
                        async {
                            metadataRepository.getCatalogPage("series", targetFeed, targetGenre, seriesCursor)
                        }
                    } else null
                    mDef?.await() to sDef?.await()
                }
            }
            coroutineContext.ensureActive()
            result.onSuccess { (mPage, sPage) ->
                _discover.update { state ->
                    if (state.selectedFeed != targetFeed || state.selectedGenre != targetGenreId) {
                        state.copy(isLoadingMore = false)
                    } else {
                        val (allMovies, addedMovies) = if (mPage != null) {
                            appendCatalogDeduplicating(state.movies, mPage.items)
                        } else {
                            state.movies to emptyList()
                        }
                        val (allSeries, addedSeries) = if (sPage != null) {
                            appendCatalogDeduplicating(state.series, sPage.items)
                        } else {
                            state.series to emptyList()
                        }
                        val (nextFeedItems, _) = appendCatalogDeduplicating(
                            state.feedItems,
                            interleaveCatalog(addedMovies, addedSeries),
                        )
                        state.copy(
                            movies = allMovies,
                            series = allSeries,
                            feedItems = nextFeedItems,
                            moviesCursor = mPage?.nextCursor ?: state.moviesCursor,
                            seriesCursor = sPage?.nextCursor ?: state.seriesCursor,
                            hasMoreMovies = mPage?.hasMore ?: state.hasMoreMovies,
                            hasMoreSeries = sPage?.hasMore ?: state.hasMoreSeries,
                            moviesTotalCount = mPage?.totalCount ?: state.moviesTotalCount,
                            seriesTotalCount = sPage?.totalCount ?: state.seriesTotalCount,
                            isLoadingMore = false,
                            loadMoreError = false,
                        )
                    }
                }
            }.onFailure {
                _discover.update { state ->
                    if (state.selectedFeed != targetFeed || state.selectedGenre != targetGenreId) {
                        state.copy(isLoadingMore = false)
                    } else {
                        state.copy(isLoadingMore = false, loadMoreError = true)
                    }
                }
            }
        }
    }

    fun resetDiscoverCache() {
        catalogLoadJob?.cancel()
        catalogLoadMoreJob?.cancel()
        detailMetaJob?.cancel()
        detailSearchJob?.cancel()
        catalogSearchJob?.cancel()
        _discover.value = DiscoverUiState()
    }

    fun clearMetadataCacheAndReload(reloadDiscover: Boolean) {
        scope.launch {
            runCatchingNonCancellation { metadataRepository.clearCache() }
            coroutineContext.ensureActive()
            resetDiscoverCache()
            if (reloadDiscover) {
                loadDiscover(force = true)
            }
        }
    }

    fun selectDiscoverTab(tab: DiscoverCategoryTab) {
        _discover.update { cur ->
            if (cur.categoryTab == tab) return
            cur.copy(
                categoryTab = tab,
                gridScrollIndex = 0,
                gridScrollOffset = 0,
                loadMoreError = false,
            )
        }
    }

    fun selectDiscoverFeed(feed: CatalogFeed) {
        val cur = _discover.value
        if (cur.selectedFeed == feed) return
        catalogLoadJob?.cancel()
        catalogLoadMoreJob?.cancel()
        _discover.update {
            it.copy(
                selectedFeed = feed,
                movies = emptyList(),
                series = emptyList(),
                feedItems = emptyList(),
                moviesCursor = null,
                seriesCursor = null,
                hasMoreMovies = false,
                hasMoreSeries = false,
                isLoading = true,
                isLoadingMore = false,
                loadMoreError = false,
                gridScrollIndex = 0,
                gridScrollOffset = 0,
            )
        }
        loadDiscover(force = false)
    }

    fun selectDiscoverGenre(genre: String?) {
        val cur = _discover.value
        val next = if (cur.selectedGenre.equals(genre, ignoreCase = true)) null else genre
        catalogLoadJob?.cancel()
        catalogLoadMoreJob?.cancel()
        _discover.update {
            it.copy(
                selectedGenre = next,
                movies = emptyList(),
                series = emptyList(),
                feedItems = emptyList(),
                moviesCursor = null,
                seriesCursor = null,
                hasMoreMovies = false,
                hasMoreSeries = false,
                isLoading = true,
                isLoadingMore = false,
                loadMoreError = false,
                gridScrollIndex = 0,
                gridScrollOffset = 0,
            )
        }
        loadDiscover(force = false)
    }

    fun onCatalogQueryChange(query: String) {
        _discover.update { it.copy(catalogQuery = query) }
        if (query.isBlank()) {
            clearCatalogSearch()
        }
    }

    fun submitCatalogSearch(overrideQuery: String? = null) {
        val raw = overrideQuery ?: _discover.value.catalogQuery
        val clean = raw.trim()
        if (clean.isEmpty()) {
            clearCatalogSearch()
            return
        }
        catalogSearchJob?.cancel()
        _discover.update {
            it.copy(
                catalogQuery = raw,
                submittedCatalogQuery = clean,
                isCatalogSearching = true,
                gridScrollIndex = 0,
                gridScrollOffset = 0,
            )
        }
        catalogSearchJob = scope.launch {
            val found = runCatchingNonCancellation {
                when (_discover.value.categoryTab) {
                    DiscoverCategoryTab.Movies -> metadataRepository.searchCatalog(clean, "movie")
                    DiscoverCategoryTab.Series -> metadataRepository.searchCatalog(clean, "series")
                    DiscoverCategoryTab.All -> metadataRepository.searchCatalogAll(clean)
                }
            }.getOrDefault(emptyList())
            coroutineContext.ensureActive()
            _discover.update { cur ->
                if (cur.submittedCatalogQuery != clean) cur
                else cur.copy(catalogSearchResults = found, isCatalogSearching = false)
            }
        }
    }

    fun clearCatalogSearch() {
        catalogSearchJob?.cancel()
        _discover.update {
            it.copy(
                catalogQuery = "",
                submittedCatalogQuery = "",
                isCatalogSearching = false,
                catalogSearchResults = emptyList(),
                gridScrollIndex = 0,
                gridScrollOffset = 0,
            )
        }
    }

    fun saveDiscoverGridScroll(index: Int, offset: Int) {
        val cur = _discover.value
        if (cur.gridScrollIndex == index && cur.gridScrollOffset == offset) return
        _discover.update { it.copy(gridScrollIndex = index, gridScrollOffset = offset) }
    }

    fun openCatalogMedia(media: MediaMetadata) {
        detailMetaJob?.cancel()
        detailSearchJob?.cancel()
        detailScrapeJob?.cancel()
        detailProbeJob?.cancel()
        detailScanToken++
        detailScanJob?.cancel()

        val cacheKey = "${media.type}:${media.id}"
        val cached = _discover.value.detailCache[cacheKey]
        if (cached != null) {
            _discover.update { it.copy(activeDetail = cached) }
            val needsEpisodeOrImdb = !cached.media.id.startsWith("tt") ||
                (cached.media.type == "series" && cached.media.episodes.isEmpty()) ||
                cached.media.runtime == null
            if (needsEpisodeOrImdb) {
                launchDetailMetadataEnrichment(cacheKey, cached.media)
            }
            if (cached.rawResults.isEmpty() && cached.queriedFilters.isEmpty()) {
                startDetailSourceSearch(force = false, mergeExisting = false)
            }
            return
        }

        val (initialSeason, initialEpisode) = if (media.type == "series") {
            pickInitialSeriesTarget(media.episodes, todayProvider())
        } else {
            1 to null
        }
        val freshDetail = CatalogDetailState(
            cacheKey = cacheKey,
            media = media,
            isLoadingDetails = !media.id.startsWith("tt") ||
                (media.type == "series" && media.episodes.isEmpty()) ||
                media.runtime == null,
            selectedSeason = initialSeason,
            selectedEpisode = initialEpisode,
            languageFilter = preferencesFlow.value.searchLanguageFilter,
        )
        _discover.update { it.copy(activeDetail = freshDetail) }

        if (freshDetail.isLoadingDetails) {
            launchDetailMetadataEnrichment(cacheKey, media, startSearchAfter = true)
        } else {
            startDetailSourceSearch(force = false, mergeExisting = false)
        }
    }

    private fun launchDetailMetadataEnrichment(
        cacheKey: String,
        baseMedia: MediaMetadata,
        startSearchAfter: Boolean = false,
    ) {
        detailMetaJob?.cancel()
        detailMetaJob = scope.launch {
            val enriched = runCatchingNonCancellation { metadataRepository.enrichCatalogMediaDetails(baseMedia) }
                .getOrDefault(baseMedia)
            coroutineContext.ensureActive()
            var gainedEpisodesTransition = false
            var shouldSearch = startSearchAfter
            updateActiveDetailIfMatch(cacheKey) { detail ->
                val gainedImdb = !detail.media.id.startsWith("tt") && enriched.id.startsWith("tt")
                val gainedEpisodes = detail.media.episodes.isEmpty() && enriched.episodes.isNotEmpty()
                val seasons = orderedSeriesSeasons(enriched.episodes)
                val today = todayProvider()
                val validSelectedSeason = detail.selectedSeason in seasons &&
                    (detail.selectedSeason > 0 || seasons.none { it > 0 })
                val nextSeason = if (validSelectedSeason) {
                    detail.selectedSeason
                } else {
                    seasons.firstOrNull() ?: 1
                }
                val nextEpisode = if (enriched.type == "series") {
                    detail.selectedEpisode
                        ?: preferredEpisodeInSeason(enriched.episodes, nextSeason, today)?.episode
                } else {
                    null
                }
                gainedEpisodesTransition = gainedEpisodes && detail.selectedEpisode == null && nextEpisode != null
                if (gainedImdb || gainedEpisodesTransition) {
                    shouldSearch = true
                }
                val updatedSnapshots = if (gainedEpisodesTransition && detail.rawResults.isNotEmpty()) {
                    detail.episodeSnapshots + (
                        detail.episodeSnapshotKey(nextSeason, null) to EpisodeSourcesSnapshot(
                            rawResults = detail.rawResults,
                            queriedFilters = detail.queriedFilters,
                            completedProviders = detail.completedProviders,
                            totalProviders = detail.totalProviders,
                            failures = detail.failures,
                            isFinished = !detail.isSearching,
                        )
                    )
                } else {
                    detail.episodeSnapshots
                }
                detail.copy(
                    media = enriched,
                    isLoadingDetails = false,
                    selectedSeason = nextSeason,
                    selectedEpisode = nextEpisode,
                    rawResults = if (gainedEpisodesTransition) emptyList() else detail.rawResults,
                    isSearching = if (shouldSearch) true else detail.isSearching,
                    episodeSnapshots = updatedSnapshots,
                )
            }
            if (shouldSearch) {
                startDetailSourceSearch(
                    force = !startSearchAfter,
                    mergeExisting = !startSearchAfter && !gainedEpisodesTransition,
                )
            }
        }
    }

    fun closeCatalogMedia() {
        detailMetaJob?.cancel()
        detailSearchJob?.cancel()
        detailScrapeJob?.cancel()
        detailProbeJob?.cancel()
        detailScanToken++
        detailScanJob?.cancel()
        _discover.update { cur ->
            val active = cur.activeDetail ?: return@update cur
            val snapshotKey = active.episodeSnapshotKey()
            val updatedSnapshots = active.episodeSnapshots + (
                snapshotKey to EpisodeSourcesSnapshot(
                    rawResults = active.rawResults,
                    queriedFilters = active.queriedFilters,
                    completedProviders = active.completedProviders,
                    totalProviders = active.totalProviders,
                    failures = active.failures,
                    swarmStatsByHash = active.swarmStatsByHash,
                    probeResultsByHash = active.probeResultsByHash,
                    isFinished = !active.isSearching,
                )
            )
            val saved = active.copy(
                isSearching = false,
                isScrapingSwarm = false,
                probingHashes = emptySet(),
                isLoadingDetails = false,
                scrollIndex = 0,
                scrollOffset = 0,
                swarmScan = SwarmScanState(),
                episodeSnapshots = updatedSnapshots,
            )
            val nextCache = cur.detailCache.toMutableMap().apply {
                put(saved.cacheKey, saved)
                put("${saved.media.type}:${saved.media.id}", saved)
            }
            cur.copy(activeDetail = null, detailCache = nextCache)
        }
    }

    fun selectDetailSeason(season: Int) {
        val current = _discover.value.activeDetail ?: return
        if (current.selectedSeason == season && current.selectedEpisode != null) return
        val firstEpisode = preferredEpisodeInSeason(current.media.episodes, season, todayProvider())?.episode
        switchDetailEpisodeInternal(season = season, episode = firstEpisode)
    }

    fun selectDetailEpisode(episode: Int?) {
        val current = _discover.value.activeDetail ?: return
        if (current.selectedEpisode == episode) return
        switchDetailEpisodeInternal(season = current.selectedSeason, episode = episode)
    }

    private fun switchDetailEpisodeInternal(season: Int, episode: Int?) {
        detailSearchJob?.cancel()
        detailScrapeJob?.cancel()
        detailProbeJob?.cancel()
        detailScanToken++
        detailScanJob?.cancel()
        var cachedHit = false
        _discover.update { cur ->
            val active = cur.activeDetail ?: return@update cur
            val prevKey = active.episodeSnapshotKey()
            val nextSnapshots = active.episodeSnapshots + (
                prevKey to EpisodeSourcesSnapshot(
                    rawResults = active.rawResults,
                    queriedFilters = active.queriedFilters,
                    completedProviders = active.completedProviders,
                    totalProviders = active.totalProviders,
                    failures = active.failures,
                    swarmStatsByHash = active.swarmStatsByHash,
                    probeResultsByHash = active.probeResultsByHash,
                    isFinished = !active.isSearching,
                )
            )
            val targetKey = active.episodeSnapshotKey(season, episode)
            val restored = nextSnapshots[targetKey]
            cachedHit = restored != null && (restored.isFinished || restored.rawResults.isNotEmpty())
            val nextDetail = active.copy(
                selectedSeason = season,
                selectedEpisode = episode,
                rawResults = restored?.rawResults.orEmpty(),
                queriedFilters = restored?.queriedFilters.orEmpty(),
                completedProviders = restored?.completedProviders ?: 0,
                totalProviders = restored?.totalProviders ?: 0,
                failures = restored?.failures.orEmpty(),
                swarmStatsByHash = restored?.swarmStatsByHash.orEmpty(),
                probeResultsByHash = restored?.probeResultsByHash.orEmpty(),
                probingHashes = emptySet(),
                isScrapingSwarm = false,
                isSearching = false,
                showAllResults = false,
                swarmScan = SwarmScanState(),
                episodeSnapshots = nextSnapshots,
            )
            cur.copy(activeDetail = nextDetail)
        }
        if (!cachedHit) {
            startDetailSourceSearch(force = false, mergeExisting = false)
        }
    }

    fun setDetailLanguageFilter(filter: SearchLanguageFilter) {
        val active = _discover.value.activeDetail ?: return
        if (active.languageFilter == filter) return
        _discover.update { cur ->
            val d = cur.activeDetail ?: return@update cur
            cur.copy(activeDetail = d.copy(languageFilter = filter))
        }
        if (filter !in active.queriedFilters) {
            startDetailSourceSearch(force = false, mergeExisting = true)
        }
    }

    fun setDetailSortOrder(order: SearchSortOrder) {
        _discover.update { cur ->
            val d = cur.activeDetail ?: return@update cur
            cur.copy(activeDetail = d.copy(sortOrder = order))
        }
    }

    fun toggleDetailShowAll() {
        _discover.update { cur ->
            val d = cur.activeDetail ?: return@update cur
            cur.copy(activeDetail = d.copy(showAllResults = !d.showAllResults))
        }
    }

    fun retryDetailSearch() {
        startDetailSourceSearch(force = true, mergeExisting = false)
    }

    fun saveDetailScroll(index: Int, offset: Int) {
        val active = _discover.value.activeDetail ?: return
        if (active.scrollIndex == index && active.scrollOffset == offset) return
        _discover.update { cur ->
            val d = cur.activeDetail ?: return@update cur
            cur.copy(activeDetail = d.copy(scrollIndex = index, scrollOffset = offset))
        }
    }

    fun enrichDetailResult(result: TorrentSearchResult) {
        val cacheKey = _discover.value.activeDetail?.cacheKey ?: return
        updateActiveDetailIfMatch(cacheKey) { it.copy(enrichingIds = it.enrichingIds + result.id) }
        scope.launch {
            val enriched = searchService.enrichResultDetails(result, torznabFlow.value)
            updateActiveDetailIfMatch(cacheKey) { d ->
                d.copy(
                    rawResults = d.rawResults.map { item -> if (item.id == result.id) enriched else item },
                    enrichingIds = d.enrichingIds - result.id,
                )
            }
        }
    }

    private fun startDetailSourceSearch(force: Boolean, mergeExisting: Boolean) {
        val detail = _discover.value.activeDetail ?: return
        if (!force && detail.languageFilter in detail.queriedFilters) return

        val cacheKey = detail.cacheKey
        val season = detail.selectedSeason
        val episode = detail.selectedEpisode
        val lang = detail.languageFilter
        val seedResults = if (mergeExisting) detail.rawResults else emptyList()
        val srcQuery = buildCatalogSourceQuery(detail.media, season, episode)
        val prefs = preferencesFlow.value
        val torznab = torznabFlow.value

        detailSearchJob?.cancel()
        detailScrapeJob?.cancel()
        detailProbeJob?.cancel()
        detailScanToken++
        detailScanJob?.cancel()
        updateActiveDetailIfMatch(cacheKey) { d ->
            d.copy(
                rawResults = seedResults,
                isSearching = true,
                isScrapingSwarm = false,
                probingHashes = emptySet(),
                swarmScan = SwarmScanState(),
                failures = emptyList(),
                completedProviders = 0,
                totalProviders = 0,
                queriedFilters = if (force && !mergeExisting) emptySet() else d.queriedFilters,
            )
        }

        detailSearchJob = scope.launch {
            searchService.searchStreaming(
                query = srcQuery.query,
                category = srcQuery.category,
                enabledProviderIds = prefs.enabledProviderIds,
                torznabConfigs = torznab,
                sortOrder = SearchSortOrder.SeedersDesc,
                languageFilter = lang,
                alternateQuery = srcQuery.alternateQuery,
                imdbId = srcQuery.imdbId,
                seasonHint = srcQuery.season,
                episodeHint = srcQuery.episode,
                skipTitleResolution = srcQuery.imdbId != null && srcQuery.alternateQuery != null,
                disabledAdultProviderIds = prefs.disabledAdultProviderIds,
            ).collect { batch ->
                updateActiveDetailIfMatch(cacheKey) { d ->
                    if (d.selectedSeason != season || d.selectedEpisode != episode) {
                        return@updateActiveDetailIfMatch d
                    }
                    val baseForEnrich = if (mergeExisting) d.rawResults else d.rawResults.filter { existing ->
                        batch.results.any { it.id == existing.id }
                    }
                    val merged = mergePreservingEnriched(
                        seed = if (mergeExisting) d.rawResults else emptyList(),
                        incoming = batch.results,
                        enrichedSource = baseForEnrich,
                    )
                    val nextQueried = if (batch.isFinished) d.queriedFilters + lang else d.queriedFilters
                    val updated = d.copy(
                        rawResults = merged,
                        completedProviders = batch.completedProviders,
                        totalProviders = batch.totalProviders,
                        failures = batch.failures,
                        isSearching = !batch.isFinished,
                        queriedFilters = nextQueried,
                    )
                    val snapKey = updated.episodeSnapshotKey()
                    updated.copy(
                        episodeSnapshots = updated.episodeSnapshots + (
                            snapKey to EpisodeSourcesSnapshot(
                                rawResults = updated.rawResults,
                                queriedFilters = updated.queriedFilters,
                                completedProviders = updated.completedProviders,
                                totalProviders = updated.totalProviders,
                                failures = updated.failures,
                                swarmStatsByHash = updated.swarmStatsByHash,
                                probeResultsByHash = updated.probeResultsByHash,
                                isFinished = !updated.isSearching,
                            )
                        ),
                    )
                }
                if (batch.isFinished && batch.results.isNotEmpty()) {
                    launchDetailSwarmScrape(cacheKey, season, episode)
                }
            }
        }
    }

    private fun launchDetailSwarmScrape(cacheKey: String, season: Int, episode: Int?) {
        val mode = preferencesFlow.value.playabilityDiagnosticsMode
        if (mode == PlayabilityDiagnosticsMode.LocalOnly) return
        detailScrapeJob?.cancel()
        detailScrapeJob = scope.launch {
            val snapshot = _discover.value.activeDetail
                ?.takeIf { it.cacheKey == cacheKey && it.selectedSeason == season && it.selectedEpisode == episode }
                ?.rawResults
                .orEmpty()
            if (snapshot.isEmpty()) return@launch
            updateActiveDetailIfMatch(cacheKey) { it.copy(isScrapingSwarm = true) }
            try {
                val scrapeCandidates = snapshot.sortedByDescending { it.seeders }
                val stats = runCatchingNonCancellation { udpScraper.scrapeBatch(scrapeCandidates, maxItems = 40) }
                    .getOrDefault(emptyMap())
                var stillMatches = false
                updateActiveDetailIfMatch(cacheKey) { d ->
                    if (d.selectedSeason != season || d.selectedEpisode != episode) {
                        return@updateActiveDetailIfMatch d.copy(isScrapingSwarm = false)
                    }
                    stillMatches = true
                    d.copy(
                        swarmStatsByHash = d.swarmStatsByHash + stats,
                        isScrapingSwarm = false,
                    )
                }
                if (stillMatches &&
                    preferencesFlow.value.playabilityDiagnosticsMode == PlayabilityDiagnosticsMode.AutoTop3OnWifi &&
                    canAutoActiveProbe()
                ) {
                    diagnoseDetailTop(maxCount = 3)
                }
            } catch (ce: CancellationException) {
                updateActiveDetailIfMatch(cacheKey) { it.copy(isScrapingSwarm = false) }
                throw ce
            }
        }
    }

    private fun updateActiveDetailIfMatch(
        cacheKey: String,
        transform: (CatalogDetailState) -> CatalogDetailState,
    ) {
        _discover.update { cur ->
            val active = cur.activeDetail
            if (active != null && active.cacheKey == cacheKey) {
                val next = transform(active)
                val nextCache = cur.detailCache.toMutableMap().apply {
                    put(next.cacheKey, next)
                    put("${next.media.type}:${next.media.id}", next)
                }
                cur.copy(activeDetail = next, detailCache = nextCache)
            } else {
                val cached = cur.detailCache[cacheKey] ?: return@update cur
                val next = transform(cached)
                cur.copy(detailCache = cur.detailCache + (cacheKey to next))
            }
        }
    }

    /**
     * Transfers the active catalog item's search context and already-resolved torrents into
     * SearchScreen so the user starts with instant results and can return to the catalog card.
     */
    fun openSearchFromCatalogDetail() {
        val detail = _discover.value.activeDetail ?: return
        val srcQuery = buildCatalogSourceQuery(detail.media, detail.selectedSeason, detail.selectedEpisode)
        val seedResolution = if (srcQuery.alternateQuery != null || srcQuery.imdbId != null) {
            SearchTitleResolution(
                displayTitle = detail.media.title,
                originalTitle = detail.media.originalTitle,
                year = detail.media.year,
                imdbId = srcQuery.imdbId,
                mediaType = detail.media.type,
            )
        } else {
            null
        }
        val alreadyFinished = !detail.isSearching && detail.languageFilter in detail.queriedFilters
        detailScrapeJob?.cancel()
        detailProbeJob?.cancel()
        detailScanToken++
        detailScanJob?.cancel()
        searchTabJob?.cancel()
        searchScrapeJob?.cancel()
        searchProbeJob?.cancel()
        searchScanToken++
        searchScanJob?.cancel()
        _search.update { cur ->
            val base = cur.copy(
                query = srcQuery.query,
                submittedQuery = srcQuery.query,
                activeAlternateQuery = srcQuery.alternateQuery,
                activeImdbId = srcQuery.imdbId,
                activeSeasonHint = srcQuery.season,
                activeEpisodeHint = srcQuery.episode,
                category = srcQuery.category,
                sortOrder = detail.sortOrder,
                languageFilter = detail.languageFilter,
                rawResults = detail.rawResults,
                resolvedContext = seedResolution,
                completedProviders = detail.completedProviders,
                totalProviders = detail.totalProviders,
                failures = detail.failures,
                swarmStatsByHash = detail.swarmStatsByHash,
                probeResultsByHash = detail.probeResultsByHash,
                probingHashes = emptySet(),
                isScrapingSwarm = false,
                isSearching = false,
                originCatalogTitle = detail.media.title,
                scrollIndex = 0,
                scrollOffset = 0,
                swarmScan = SwarmScanState(),
            )
            if (alreadyFinished) {
                base.copy(queriedScopes = setOf(base.scopeKey()))
            } else {
                base.copy(queriedScopes = emptySet())
            }
        }
        if (!alreadyFinished || detail.rawResults.isEmpty()) {
            detailSearchJob?.cancel()
            if (detail.isSearching) {
                updateActiveDetailIfMatch(detail.cacheKey) { cur ->
                    cur.copy(isSearching = false)
                }
            }
            runSearchTabQuery(force = true, mergeExisting = detail.rawResults.isNotEmpty())
        }
    }

    fun returnFromSearchToCatalog(): Boolean {
        if (_search.value.originCatalogTitle == null) return false
        _search.update { it.copy(originCatalogTitle = null) }
        return _discover.value.activeDetail != null
    }

    fun searchDirectFromDiscover(query: String, category: SearchCategory) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val effectiveAdultOnly = isEffectiveAdultOnly(
            prefs = preferencesFlow.value,
            category = category,
            originCatalogTitle = null,
        )
        if (!effectiveAdultOnly && category != SearchCategory.Adult) {
            onRecordRecentSearch(clean)
        }
        searchTabJob?.cancel()
        searchScrapeJob?.cancel()
        searchProbeJob?.cancel()
        searchScanToken++
        searchScanJob?.cancel()
        _search.update {
            it.copy(
                query = clean,
                submittedQuery = clean,
                activeAlternateQuery = null,
                activeImdbId = null,
                activeSeasonHint = null,
                activeEpisodeHint = null,
                category = category,
                rawResults = emptyList(),
                queriedScopes = emptySet(),
                resolvedContext = null,
                failures = emptyList(),
                completedProviders = 0,
                totalProviders = 0,
                swarmStatsByHash = emptyMap(),
                probeResultsByHash = emptyMap(),
                probingHashes = emptySet(),
                isScrapingSwarm = false,
                originCatalogTitle = null,
                scrollIndex = 0,
                scrollOffset = 0,
                swarmScan = SwarmScanState(),
            )
        }
        runSearchTabQuery(force = true, mergeExisting = false)
    }

    fun onSearchQueryChange(value: String) {
        _search.update { it.copy(query = value) }
    }

    fun submitSearch(overrideQuery: String? = null) {
        val raw = overrideQuery ?: _search.value.query
        val clean = raw.trim()
        if (clean.isEmpty()) {
            clearSearch()
            return
        }
        val curCategory = _search.value.category
        val effectiveAdultOnly = isEffectiveAdultOnly(
            prefs = preferencesFlow.value,
            category = curCategory,
            originCatalogTitle = null,
        )
        if (!effectiveAdultOnly && curCategory != SearchCategory.Adult) {
            onRecordRecentSearch(clean)
        }
        searchTabJob?.cancel()
        searchScrapeJob?.cancel()
        searchProbeJob?.cancel()
        searchScanToken++
        searchScanJob?.cancel()
        _search.update {
            it.copy(
                query = raw,
                submittedQuery = clean,
                activeAlternateQuery = null,
                activeImdbId = null,
                activeSeasonHint = null,
                activeEpisodeHint = null,
                originCatalogTitle = null,
                rawResults = emptyList(),
                queriedScopes = emptySet(),
                resolvedContext = null,
                failures = emptyList(),
                completedProviders = 0,
                totalProviders = 0,
                swarmStatsByHash = emptyMap(),
                probeResultsByHash = emptyMap(),
                probingHashes = emptySet(),
                isScrapingSwarm = false,
                scrollIndex = 0,
                scrollOffset = 0,
                swarmScan = SwarmScanState(),
            )
        }
        runSearchTabQuery(force = true, mergeExisting = false)
    }

    fun clearSearch() {
        searchTabJob?.cancel()
        searchScrapeJob?.cancel()
        searchProbeJob?.cancel()
        searchScanToken++
        searchScanJob?.cancel()
        _search.update {
            it.copy(
                query = "",
                submittedQuery = "",
                activeAlternateQuery = null,
                activeImdbId = null,
                activeSeasonHint = null,
                activeEpisodeHint = null,
                originCatalogTitle = null,
                rawResults = emptyList(),
                queriedScopes = emptySet(),
                resolvedContext = null,
                failures = emptyList(),
                completedProviders = 0,
                totalProviders = 0,
                swarmStatsByHash = emptyMap(),
                probeResultsByHash = emptyMap(),
                probingHashes = emptySet(),
                isScrapingSwarm = false,
                isSearching = false,
                scrollIndex = 0,
                scrollOffset = 0,
                swarmScan = SwarmScanState(),
            )
        }
    }

    fun setSearchCategory(category: SearchCategory) {
        val cur = _search.value
        if (cur.category == category) return
        searchTabJob?.cancel()
        searchScrapeJob?.cancel()
        searchProbeJob?.cancel()
        searchScanToken++
        searchScanJob?.cancel()
        _search.update {
            it.copy(
                category = category,
                rawResults = emptyList(),
                queriedScopes = emptySet(),
                failures = emptyList(),
                swarmStatsByHash = emptyMap(),
                probeResultsByHash = emptyMap(),
                probingHashes = emptySet(),
                isScrapingSwarm = false,
                scrollIndex = 0,
                scrollOffset = 0,
                swarmScan = SwarmScanState(),
            )
        }
        if (cur.submittedQuery.isNotBlank()) {
            runSearchTabQuery(force = true, mergeExisting = false)
        }
    }

    fun setSearchSortOrder(order: SearchSortOrder) {
        _search.update { it.copy(sortOrder = order) }
    }

    fun setHideDeadSearchResults(hide: Boolean) {
        _search.update { it.copy(hideDeadResults = hide) }
    }

    fun setSearchLanguageFilter(filter: SearchLanguageFilter) {
        val cur = _search.value
        if (cur.languageFilter == filter) return
        _search.update { it.copy(languageFilter = filter) }
        val targetScope = _search.value.scopeKey(filter)
        if (cur.submittedQuery.isNotBlank() && targetScope !in cur.queriedScopes) {
            runSearchTabQuery(force = false, mergeExisting = true)
        }
    }

    fun retrySearch() {
        if (_search.value.submittedQuery.isBlank()) return
        runSearchTabQuery(force = true, mergeExisting = false)
    }

    fun saveSearchScroll(index: Int, offset: Int) {
        val cur = _search.value
        if (cur.scrollIndex == index && cur.scrollOffset == offset) return
        _search.update { it.copy(scrollIndex = index, scrollOffset = offset) }
    }

    fun enrichSearchResult(result: TorrentSearchResult) {
        _search.update { it.copy(enrichingIds = it.enrichingIds + result.id) }
        scope.launch {
            val enriched = searchService.enrichResultDetails(result, torznabFlow.value)
            _search.update { cur ->
                cur.copy(
                    rawResults = cur.rawResults.map { item -> if (item.id == result.id) enriched else item },
                    enrichingIds = cur.enrichingIds - result.id,
                )
            }
        }
    }

    private fun isEffectiveAdultOnly(
        prefs: AppPreferences,
        category: SearchCategory,
        originCatalogTitle: String?,
    ): Boolean {
        val applyAdultOnlyPref = prefs.adultOnlySearch &&
            (category == SearchCategory.All || category == SearchCategory.Adult) &&
            originCatalogTitle == null
        return !prefs.hideAdultContent && (applyAdultOnlyPref || category == SearchCategory.Adult)
    }

    private fun runSearchTabQuery(force: Boolean, mergeExisting: Boolean) {
        val snap = _search.value
        if (snap.submittedQuery.isBlank()) return
        val prefs = preferencesFlow.value
        val effectiveAdultOnly = isEffectiveAdultOnly(
            prefs = prefs,
            category = snap.category,
            originCatalogTitle = snap.originCatalogTitle,
        )
        val scopeKey = snap.scopeKey(
            broadGeneralSearch = prefs.broadGeneralSearchEnabled,
            hideAdultContent = prefs.hideAdultContent,
            adultOnly = effectiveAdultOnly,
        )
        if (!force && scopeKey in snap.queriedScopes) return

        val seedResults = if (mergeExisting) snap.rawResults else emptyList()
        val torznab = torznabFlow.value

        searchTabJob?.cancel()
        searchScrapeJob?.cancel()
        searchProbeJob?.cancel()
        searchScanToken++
        searchScanJob?.cancel()
        _search.update {
            it.copy(
                rawResults = seedResults,
                isSearching = true,
                isScrapingSwarm = false,
                probingHashes = emptySet(),
                swarmScan = SwarmScanState(),
                failures = emptyList(),
                completedProviders = 0,
                totalProviders = 0,
            )
        }

        searchTabJob = scope.launch {
            searchService.searchStreaming(
                query = snap.submittedQuery,
                category = snap.category,
                enabledProviderIds = prefs.enabledProviderIds,
                torznabConfigs = torznab,
                sortOrder = SearchSortOrder.SeedersDesc,
                languageFilter = snap.languageFilter,
                alternateQuery = snap.activeAlternateQuery,
                imdbId = snap.activeImdbId,
                seasonHint = snap.activeSeasonHint,
                episodeHint = snap.activeEpisodeHint,
                skipTitleResolution = snap.activeImdbId != null && snap.activeAlternateQuery != null,
                broadGeneralSearch = prefs.broadGeneralSearchEnabled,
                hideAdultContent = prefs.hideAdultContent,
                adultOnly = effectiveAdultOnly,
                disabledAdultProviderIds = prefs.disabledAdultProviderIds,
            ).collect { batch ->
                _search.update { cur ->
                    if (cur.scopeKey(
                            broadGeneralSearch = prefs.broadGeneralSearchEnabled,
                            hideAdultContent = prefs.hideAdultContent,
                            adultOnly = effectiveAdultOnly,
                        ) != scopeKey
                    ) return@update cur
                    val baseForEnrich = if (mergeExisting) cur.rawResults else cur.rawResults.filter { existing ->
                        batch.results.any { it.id == existing.id }
                    }
                    val merged = mergePreservingEnriched(
                        seed = if (mergeExisting) cur.rawResults else emptyList(),
                        incoming = batch.results,
                        enrichedSource = baseForEnrich,
                    )
                    cur.copy(
                        rawResults = merged,
                        resolvedContext = batch.resolvedContext ?: cur.resolvedContext,
                        completedProviders = batch.completedProviders,
                        totalProviders = batch.totalProviders,
                        failures = batch.failures,
                        isSearching = !batch.isFinished,
                        queriedScopes = if (batch.isFinished) cur.queriedScopes + scopeKey else cur.queriedScopes,
                    )
                }
                if (batch.isFinished && batch.results.isNotEmpty()) {
                    launchSearchSwarmScrape(scopeKey)
                }
            }
        }
    }

    fun diagnoseDetailTop(maxCount: Int = 3) {
        val prober = swarmDiagnostics ?: return
        val detail = _discover.value.activeDetail ?: return
        val context = PlayabilityContext(
            expectedDurationMinutes = PlayabilityContext.parseRuntimeMinutes(detail.media.runtime),
            requestedSeason = if (detail.media.type == "series") detail.selectedSeason else null,
            requestedEpisode = if (detail.media.type == "series") detail.selectedEpisode else null,
        )
        val candidates = selectTopProbeCandidates(
            results = detail.rawResults,
            languageFilter = detail.languageFilter,
            context = context,
            scrapeStatsByHash = detail.swarmStatsByHash,
            probeResultsByHash = detail.probeResultsByHash,
            maxCount = maxCount,
        )
        if (candidates.isEmpty()) return
        launchDetailProbe(
            prober = prober,
            cacheKey = detail.cacheKey,
            season = detail.selectedSeason,
            episode = detail.selectedEpisode,
            targets = candidates,
            priority = ProbePriority.BACKGROUND_SCAN,
            profile = ProbeProfile.BACKGROUND_SCAN,
        )
    }

    fun diagnoseDetailCandidate(result: TorrentSearchResult) {
        val prober = swarmDiagnostics ?: return
        val detail = _discover.value.activeDetail ?: return
        launchDetailProbe(
            prober = prober,
            cacheKey = detail.cacheKey,
            season = detail.selectedSeason,
            episode = detail.selectedEpisode,
            targets = listOf(result),
            forceRefresh = true,
            priority = ProbePriority.USER_CLICK,
            profile = ProbeProfile.USER_CLICK,
        )
    }

    fun diagnoseSearchTop(maxCount: Int = 3) {
        val prober = swarmDiagnostics ?: return
        val snap = _search.value
        val context = PlayabilityContext(
            requestedSeason = snap.activeSeasonHint,
            requestedEpisode = snap.activeEpisodeHint,
        )
        val candidates = selectTopProbeCandidates(
            results = snap.rawResults,
            languageFilter = snap.languageFilter,
            context = context,
            scrapeStatsByHash = snap.swarmStatsByHash,
            probeResultsByHash = snap.probeResultsByHash,
            maxCount = maxCount,
        )
        if (candidates.isEmpty()) return
        launchSearchProbe(
            prober = prober,
            scopeKey = snap.scopeKey(),
            season = snap.activeSeasonHint,
            episode = snap.activeEpisodeHint,
            targets = candidates,
            priority = ProbePriority.BACKGROUND_SCAN,
            profile = ProbeProfile.BACKGROUND_SCAN,
        )
    }

    fun diagnoseSearchCandidate(result: TorrentSearchResult) {
        val prober = swarmDiagnostics ?: return
        val snap = _search.value
        launchSearchProbe(
            prober = prober,
            scopeKey = snap.scopeKey(),
            season = snap.activeSeasonHint,
            episode = snap.activeEpisodeHint,
            targets = listOf(result),
            forceRefresh = true,
            priority = ProbePriority.USER_CLICK,
            profile = ProbeProfile.USER_CLICK,
        )
    }

    fun startProgressiveSearchScan(batchSize: Int = lastScanBatchSize, ignoreCheckpoint: Boolean = false) {
        lastScanBatchSize = batchSize.coerceIn(1, 10)
        val runBatch = lastScanBatchSize
        android.util.Log.i("ScanCoord", "search scan start batch=$runBatch ignoreCheckpoint=$ignoreCheckpoint")
        val prober = swarmDiagnostics ?: return
        if (!prober.isAvailable) return
        val snap = _search.value
        val scopeKey = snap.scopeKey()
        val context = PlayabilityContext(
            requestedSeason = snap.activeSeasonHint,
            requestedEpisode = snap.activeEpisodeHint,
        )
        val allCandidates = buildProgressiveScanCandidates(
            results = snap.rawResults,
            languageFilter = snap.languageFilter,
            context = context,
            scrapeStatsByHash = snap.swarmStatsByHash,
            probeResultsByHash = snap.probeResultsByHash,
        )
        val existingProbes = allCandidates.mapNotNull { cand ->
            cand.normalizedInfoHash?.let { snap.probeResultsByHash[it] }
        }.filterNot { it.isPolicyBlocked }
        val initialTested = existingProbes.size
        val initialLive = existingProbes.count { it.isVerifiedLive }
        val initialDead = existingProbes.count { it.isFailed }
        val untested = allCandidates.filter { cand ->
            val hash = cand.normalizedInfoHash ?: return@filter false
            val existing = snap.probeResultsByHash[hash]
            existing == null || existing.isPolicyBlocked
        }
        if (allCandidates.isEmpty()) {
            _search.update { it.copy(swarmScan = SwarmScanState()) }
            return
        }
        if (untested.isEmpty()) {
            _search.update {
                it.copy(
                    swarmScan = SwarmScanState(
                        status = SwarmScanStatus.Completed,
                        testedCount = initialTested,
                        totalCount = allCandidates.size,
                        verifiedLiveCount = initialLive,
                        deadCount = initialDead,
                    ),
                )
            }
            return
        }

        val token = ++searchScanToken
        searchScanJob?.cancel()
        _search.update {
            if (it.scopeKey() != scopeKey) it
            else it.copy(
                swarmScan = SwarmScanState(
                    status = SwarmScanStatus.Running,
                    testedCount = initialTested,
                    totalCount = allCandidates.size,
                    verifiedLiveCount = initialLive,
                    deadCount = initialDead,
                ),
            )
        }

        val untestedHashes = untested.mapNotNull { it.normalizedInfoHash }.toSet()
        searchScanJob = scope.launch {
            var tested = initialTested
            var live = initialLive
            var dead = initialDead
            var paused = false
            var runTested = 0
            try {
                for (candidate in untested) {
                    coroutineContext.ensureActive()
                    val curSnap = _search.value
                    if (curSnap.scopeKey() != scopeKey) return@launch
                    val hash = candidate.normalizedInfoHash ?: continue
                    val alreadyProbed = curSnap.probeResultsByHash[hash]
                    if (alreadyProbed != null && !alreadyProbed.isPolicyBlocked) continue

                    _search.update { cur ->
                        if (cur.scopeKey() != scopeKey) cur
                        else cur.copy(
                            probingHashes = cur.probingHashes + hash,
                            swarmScan = cur.swarmScan.copy(
                                status = SwarmScanStatus.Running,
                                currentHash = hash,
                            ),
                        )
                    }

                    val probed = runCatchingNonCancellation {
                        prober.probeSingle(
                            result = candidate,
                            season = snap.activeSeasonHint,
                            episode = snap.activeEpisodeHint,
                            forceRefresh = false,
                            priority = ProbePriority.BACKGROUND_SCAN,
                            profile = ProbeProfile.BACKGROUND_SCAN,
                        )
                    }.getOrNull()
                    if (probed == null) {
                        android.util.Log.w("ScanCoord", "scan probe returned null hash=${hash.take(8)}")
                    }

                    if (probed?.isPolicyBlocked == true) {
                        _search.update { cur ->
                            if (cur.scopeKey() != scopeKey) cur.copy(probingHashes = cur.probingHashes - hash)
                            else cur.copy(
                                probingHashes = cur.probingHashes - hash,
                                swarmScan = cur.swarmScan.copy(
                                    status = SwarmScanStatus.Paused,
                                    currentHash = null,
                                ),
                            )
                        }
                        paused = true
                        break
                    }

                    tested++
                    val isLive = probed?.isVerifiedLive == true
                    if (isLive) live++ else dead++
                    runTested++
                    val hitCheckpoint = runTested >= runBatch && tested < allCandidates.size

                    _search.update { cur ->
                        if (cur.scopeKey() != scopeKey) {
                            cur.copy(probingHashes = cur.probingHashes - hash)
                        } else {
                            val nextMap = if (probed != null) cur.probeResultsByHash + (probed.infoHash to probed) else cur.probeResultsByHash
                            cur.copy(
                                probeResultsByHash = nextMap,
                                probingHashes = cur.probingHashes - hash,
                                swarmScan = SwarmScanState(
                                    status = if (hitCheckpoint) SwarmScanStatus.Paused else SwarmScanStatus.Running,
                                    testedCount = tested,
                                    totalCount = allCandidates.size,
                                    verifiedLiveCount = live,
                                    deadCount = dead,
                                    currentHash = null,
                                ),
                            )
                        }
                    }

                    if (hitCheckpoint) {
                        android.util.Log.i("ScanCoord", "scan checkpoint tested=$tested live=$live dead=$dead")
                        paused = true
                        break
                    }
                }
                android.util.Log.i("ScanCoord", "scan loop end paused=$paused tested=$tested live=$live dead=$dead")

                if (!paused && searchScanToken == token && _search.value.scopeKey() == scopeKey) {
                    _search.update { cur ->
                        cur.copy(
                            swarmScan = SwarmScanState(
                                status = SwarmScanStatus.Completed,
                                testedCount = tested,
                                totalCount = allCandidates.size,
                                verifiedLiveCount = live,
                                deadCount = dead,
                                currentHash = null,
                            ),
                        )
                    }
                }
            } finally {
                if (searchScanToken == token) {
                    _search.update { cur ->
                        cur.copy(
                            probingHashes = cur.probingHashes - untestedHashes,
                            swarmScan = if (cur.swarmScan.currentHash != null) cur.swarmScan.copy(currentHash = null) else cur.swarmScan,
                        )
                    }
                }
            }
        }
    }

    fun pauseSearchScan() {
        searchScanToken++
        searchScanJob?.cancel()
        searchScanJob = null
        _search.update { cur ->
            if (cur.swarmScan.status == SwarmScanStatus.Running) {
                cur.copy(swarmScan = cur.swarmScan.copy(status = SwarmScanStatus.Paused, currentHash = null))
            } else {
                cur
            }
        }
    }

    fun resumeSearchScan() {
        if (_search.value.swarmScan.status == SwarmScanStatus.Paused) {
            startProgressiveSearchScan(batchSize = lastScanBatchSize, ignoreCheckpoint = false)
        }
    }

    fun startProgressiveDetailScan(batchSize: Int = lastScanBatchSize, ignoreCheckpoint: Boolean = false) {
        lastScanBatchSize = batchSize.coerceIn(1, 10)
        val runBatch = lastScanBatchSize
        val prober = swarmDiagnostics ?: return
        if (!prober.isAvailable) return
        val detail = _discover.value.activeDetail ?: return
        val cacheKey = detail.cacheKey
        val season = detail.selectedSeason
        val episode = detail.selectedEpisode
        val sArg = if (detail.media.type == "series") season else null
        val eArg = if (detail.media.type == "series") episode else null
        val context = PlayabilityContext(
            expectedDurationMinutes = PlayabilityContext.parseRuntimeMinutes(detail.media.runtime),
            requestedSeason = sArg,
            requestedEpisode = eArg,
        )
        val allCandidates = buildProgressiveScanCandidates(
            results = detail.rawResults,
            languageFilter = detail.languageFilter,
            context = context,
            scrapeStatsByHash = detail.swarmStatsByHash,
            probeResultsByHash = detail.probeResultsByHash,
        )
        val existingProbes = allCandidates.mapNotNull { cand ->
            cand.normalizedInfoHash?.let { detail.probeResultsByHash[it] }
        }.filterNot { it.isPolicyBlocked }
        val initialTested = existingProbes.size
        val initialLive = existingProbes.count { it.isVerifiedLive }
        val initialDead = existingProbes.count { it.isFailed }
        val untested = allCandidates.filter { cand ->
            val hash = cand.normalizedInfoHash ?: return@filter false
            val existing = detail.probeResultsByHash[hash]
            existing == null || existing.isPolicyBlocked
        }
        if (allCandidates.isEmpty()) {
            updateActiveDetailIfMatch(cacheKey) { d ->
                if (d.selectedSeason != season || d.selectedEpisode != episode) d
                else d.copy(swarmScan = SwarmScanState())
            }
            return
        }
        if (untested.isEmpty()) {
            updateActiveDetailIfMatch(cacheKey) { d ->
                if (d.selectedSeason != season || d.selectedEpisode != episode) d
                else d.copy(
                    swarmScan = SwarmScanState(
                        status = SwarmScanStatus.Completed,
                        testedCount = initialTested,
                        totalCount = allCandidates.size,
                        verifiedLiveCount = initialLive,
                        deadCount = initialDead,
                    ),
                )
            }
            return
        }

        val token = ++detailScanToken
        detailScanJob?.cancel()
        updateActiveDetailIfMatch(cacheKey) { d ->
            if (d.selectedSeason != season || d.selectedEpisode != episode) d
            else d.copy(
                swarmScan = SwarmScanState(
                    status = SwarmScanStatus.Running,
                    testedCount = initialTested,
                    totalCount = allCandidates.size,
                    verifiedLiveCount = initialLive,
                    deadCount = initialDead,
                ),
            )
        }

        val untestedHashes = untested.mapNotNull { it.normalizedInfoHash }.toSet()
        detailScanJob = scope.launch {
            var tested = initialTested
            var live = initialLive
            var dead = initialDead
            var paused = false
            var runTested = 0
            try {
                for (candidate in untested) {
                    coroutineContext.ensureActive()
                    val curDetail = _discover.value.activeDetail
                    if (curDetail == null || curDetail.cacheKey != cacheKey || curDetail.selectedSeason != season || curDetail.selectedEpisode != episode) {
                        return@launch
                    }
                    val hash = candidate.normalizedInfoHash ?: continue
                    val alreadyProbed = curDetail.probeResultsByHash[hash]
                    if (alreadyProbed != null && !alreadyProbed.isPolicyBlocked) continue

                    updateActiveDetailIfMatch(cacheKey) { d ->
                        if (d.selectedSeason != season || d.selectedEpisode != episode) d
                        else d.copy(
                            probingHashes = d.probingHashes + hash,
                            swarmScan = d.swarmScan.copy(
                                status = SwarmScanStatus.Running,
                                currentHash = hash,
                            ),
                        )
                    }

                    val probed = runCatchingNonCancellation {
                        prober.probeSingle(
                            result = candidate,
                            season = sArg,
                            episode = eArg,
                            forceRefresh = false,
                            priority = ProbePriority.BACKGROUND_SCAN,
                            profile = ProbeProfile.BACKGROUND_SCAN,
                        )
                    }.getOrNull()
                    if (probed == null) {
                        android.util.Log.w("ScanCoord", "scan probe returned null hash=${hash.take(8)}")
                    }

                    if (probed?.isPolicyBlocked == true) {
                        updateActiveDetailIfMatch(cacheKey) { d ->
                            if (d.selectedSeason != season || d.selectedEpisode != episode) {
                                d.copy(probingHashes = d.probingHashes - hash)
                            } else {
                                d.copy(
                                    probingHashes = d.probingHashes - hash,
                                    swarmScan = d.swarmScan.copy(
                                        status = SwarmScanStatus.Paused,
                                        currentHash = null,
                                    ),
                                )
                            }
                        }
                        paused = true
                        break
                    }

                    tested++
                    val isLive = probed?.isVerifiedLive == true
                    if (isLive) live++ else dead++
                    runTested++
                    val hitCheckpoint = runTested >= runBatch && tested < allCandidates.size

                    updateActiveDetailIfMatch(cacheKey) { d ->
                        if (d.selectedSeason != season || d.selectedEpisode != episode) {
                            d.copy(probingHashes = d.probingHashes - hash)
                        } else {
                            val nextMap = if (probed != null) d.probeResultsByHash + (probed.infoHash to probed) else d.probeResultsByHash
                            d.copy(
                                probeResultsByHash = nextMap,
                                probingHashes = d.probingHashes - hash,
                                swarmScan = SwarmScanState(
                                    status = if (hitCheckpoint) SwarmScanStatus.Paused else SwarmScanStatus.Running,
                                    testedCount = tested,
                                    totalCount = allCandidates.size,
                                    verifiedLiveCount = live,
                                    deadCount = dead,
                                    currentHash = null,
                                ),
                            )
                        }
                    }

                    if (hitCheckpoint) {
                        android.util.Log.i("ScanCoord", "scan checkpoint tested=$tested live=$live dead=$dead")
                        paused = true
                        break
                    }
                }
                android.util.Log.i("ScanCoord", "scan loop end paused=$paused tested=$tested live=$live dead=$dead")

                val curDetail = _discover.value.activeDetail
                if (!paused &&
                    detailScanToken == token &&
                    curDetail != null &&
                    curDetail.cacheKey == cacheKey &&
                    curDetail.selectedSeason == season &&
                    curDetail.selectedEpisode == episode
                ) {
                    updateActiveDetailIfMatch(cacheKey) { d ->
                        d.copy(
                            swarmScan = SwarmScanState(
                                status = SwarmScanStatus.Completed,
                                testedCount = tested,
                                totalCount = allCandidates.size,
                                verifiedLiveCount = live,
                                deadCount = dead,
                                currentHash = null,
                            ),
                        )
                    }
                }
            } finally {
                if (detailScanToken == token) {
                    updateActiveDetailIfMatch(cacheKey) { d ->
                        d.copy(
                            probingHashes = d.probingHashes - untestedHashes,
                            swarmScan = if (d.swarmScan.currentHash != null) d.swarmScan.copy(currentHash = null) else d.swarmScan,
                        )
                    }
                }
            }
        }
    }

    fun pauseDetailScan() {
        detailScanToken++
        detailScanJob?.cancel()
        detailScanJob = null
        val cacheKey = _discover.value.activeDetail?.cacheKey ?: return
        updateActiveDetailIfMatch(cacheKey) { d ->
            if (d.swarmScan.status == SwarmScanStatus.Running) {
                d.copy(swarmScan = d.swarmScan.copy(status = SwarmScanStatus.Paused, currentHash = null))
            } else {
                d
            }
        }
    }

    fun resumeDetailScan() {
        if (_discover.value.activeDetail?.swarmScan?.status == SwarmScanStatus.Paused) {
            startProgressiveDetailScan(batchSize = lastScanBatchSize, ignoreCheckpoint = false)
        }
    }

    fun measurePreparedSwarm(engineId: String, magnetOrHash: String?) {
        val prober = swarmDiagnostics ?: return
        if (!prober.isAvailable) return
        val raw = magnetOrHash?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val hash = SearchUtils.extractInfoHash(raw)?.let {
            SearchUtils.decodeInfoHashBytes(it)?.first ?: it.lowercase(Locale.ROOT)
        } ?: SearchUtils.decodeInfoHashBytes(raw)?.first ?: return
        val magnet = if (raw.startsWith("magnet:", ignoreCase = true)) raw else SearchUtils.buildMagnet(hash, "Swarm")
        val synthetic = SearchUtils.makeResult(
            providerId = "prepared",
            providerName = "Prepared",
            title = engineId,
            magnetUri = magnet,
        )
        scope.launch {
            _search.update { cur ->
                if (cur.rawResults.any { it.normalizedInfoHash == hash }) {
                    cur.copy(probingHashes = cur.probingHashes + hash)
                } else {
                    cur
                }
            }
            _discover.value.activeDetail?.let { detail ->
                if (detail.rawResults.any { it.normalizedInfoHash == hash }) {
                    updateActiveDetailIfMatch(detail.cacheKey) { d ->
                        d.copy(probingHashes = d.probingHashes + hash)
                    }
                }
            }
            val probed = try {
                runCatchingNonCancellation {
                    prober.probeSingle(
                        result = synthetic,
                        forceRefresh = true,
                        priority = ProbePriority.USER_CLICK,
                        profile = ProbeProfile.USER_CLICK,
                    )
                }.getOrNull()
            } finally {
                _search.update { cur -> cur.copy(probingHashes = cur.probingHashes - hash) }
                _discover.value.activeDetail?.let { detail ->
                    updateActiveDetailIfMatch(detail.cacheKey) { d ->
                        d.copy(probingHashes = d.probingHashes - hash)
                    }
                }
            }
            if (probed != null) {
                _search.update { cur ->
                    cur.copy(probeResultsByHash = cur.probeResultsByHash + (probed.infoHash to probed))
                }
                _discover.value.activeDetail?.let { detail ->
                    updateActiveDetailIfMatch(detail.cacheKey) { d ->
                        d.copy(probeResultsByHash = d.probeResultsByHash + (probed.infoHash to probed))
                    }
                }
            }
        }
    }

    private fun buildProgressiveScanCandidates(
        results: List<TorrentSearchResult>,
        languageFilter: SearchLanguageFilter,
        context: PlayabilityContext,
        scrapeStatsByHash: Map<String, org.cinito.online.search.TrackerSwarmStats>,
        probeResultsByHash: Map<String, SwarmProbeResult>,
    ): List<TorrentSearchResult> {
        val (sorted, _) = SearchService.evaluatePlayabilityAndSort(
            results = results,
            sortOrder = SearchSortOrder.BestPlayability,
            languageFilter = languageFilter,
            playabilityContext = context,
            scrapeStatsByHash = scrapeStatsByHash,
            probeResultsByHash = probeResultsByHash,
        )
        return sorted
            .filter { it.normalizedInfoHash != null }
            .distinctBy { it.normalizedInfoHash }
    }

    private fun selectTopProbeCandidates(
        results: List<TorrentSearchResult>,
        languageFilter: SearchLanguageFilter,
        context: PlayabilityContext,
        scrapeStatsByHash: Map<String, org.cinito.online.search.TrackerSwarmStats>,
        probeResultsByHash: Map<String, SwarmProbeResult>,
        maxCount: Int,
    ): List<TorrentSearchResult> {
        val (sorted, assessments) = SearchService.evaluatePlayabilityAndSort(
            results = results,
            sortOrder = SearchSortOrder.BestPlayability,
            languageFilter = languageFilter,
            playabilityContext = context,
            scrapeStatsByHash = scrapeStatsByHash,
            probeResultsByHash = probeResultsByHash,
        )
        val viable = sorted.filter { item ->
            val hash = item.normalizedInfoHash ?: return@filter false
            hash !in probeResultsByHash && assessments[item.id]?.verdict != PlayabilityVerdict.Dead
        }
        return viable.ifEmpty {
            sorted.filter { it.normalizedInfoHash != null && it.normalizedInfoHash !in probeResultsByHash }
        }.distinctBy { it.normalizedInfoHash }.take(maxCount)
    }

    private fun launchDetailProbe(
        prober: SwarmDiagnosticsCoordinator,
        cacheKey: String,
        season: Int,
        episode: Int?,
        targets: List<TorrentSearchResult>,
        forceRefresh: Boolean = false,
        priority: ProbePriority = ProbePriority.USER_CLICK,
        profile: ProbeProfile = ProbeProfile.USER_CLICK,
    ) {
        val hashes = targets.mapNotNull { it.normalizedInfoHash }.toSet()
        if (hashes.isEmpty()) return
        val token = ++detailProbeToken
        detailProbeJob?.cancel()
        detailProbeJob = scope.launch {
            val sArg = if (_discover.value.activeDetail?.media?.type == "series") season else null
            val eArg = if (_discover.value.activeDetail?.media?.type == "series") episode else null
            try {
                for (candidate in targets) {
                    val hash = candidate.normalizedInfoHash ?: continue
                    updateActiveDetailIfMatch(cacheKey) { d ->
                        if (d.selectedSeason != season || d.selectedEpisode != episode) d
                        else d.copy(probingHashes = d.probingHashes + hash)
                    }
                    val probed = runCatchingNonCancellation {
                        prober.probeSingle(
                            result = candidate,
                            season = sArg,
                            episode = eArg,
                            forceRefresh = forceRefresh,
                            priority = priority,
                            profile = profile,
                        )
                    }.getOrNull()
                    updateActiveDetailIfMatch(cacheKey) { d ->
                        if (d.selectedSeason != season || d.selectedEpisode != episode) {
                            d.copy(probingHashes = d.probingHashes - hash)
                        } else {
                            val nextMap = if (probed != null) d.probeResultsByHash + (probed.infoHash to probed) else d.probeResultsByHash
                            d.copy(
                                probeResultsByHash = nextMap,
                                probingHashes = d.probingHashes - hash,
                            )
                        }
                    }
                }
            } finally {
                if (detailProbeToken == token) {
                    updateActiveDetailIfMatch(cacheKey) { d ->
                        d.copy(probingHashes = d.probingHashes - hashes)
                    }
                }
            }
        }
    }

    private fun launchSearchProbe(
        prober: SwarmDiagnosticsCoordinator,
        scopeKey: String,
        season: Int?,
        episode: Int?,
        targets: List<TorrentSearchResult>,
        forceRefresh: Boolean = false,
        priority: ProbePriority = ProbePriority.USER_CLICK,
        profile: ProbeProfile = ProbeProfile.USER_CLICK,
    ) {
        val hashes = targets.mapNotNull { it.normalizedInfoHash }.toSet()
        if (hashes.isEmpty()) return
        val token = ++searchProbeToken
        searchProbeJob?.cancel()
        searchProbeJob = scope.launch {
            try {
                for (candidate in targets) {
                    val hash = candidate.normalizedInfoHash ?: continue
                    _search.update { cur ->
                        if (cur.scopeKey() != scopeKey) cur else cur.copy(probingHashes = cur.probingHashes + hash)
                    }
                    val probed = runCatchingNonCancellation {
                        prober.probeSingle(
                            result = candidate,
                            season = season,
                            episode = episode,
                            forceRefresh = forceRefresh,
                            priority = priority,
                            profile = profile,
                        )
                    }.getOrNull()
                    _search.update { cur ->
                        if (cur.scopeKey() != scopeKey) {
                            cur.copy(probingHashes = cur.probingHashes - hash)
                        } else {
                            val nextMap = if (probed != null) cur.probeResultsByHash + (probed.infoHash to probed) else cur.probeResultsByHash
                            cur.copy(
                                probeResultsByHash = nextMap,
                                probingHashes = cur.probingHashes - hash,
                            )
                        }
                    }
                }
            } finally {
                if (searchProbeToken == token) {
                    _search.update { cur -> cur.copy(probingHashes = cur.probingHashes - hashes) }
                }
            }
        }
    }

    private fun launchSearchSwarmScrape(scopeKey: String) {
        val mode = preferencesFlow.value.playabilityDiagnosticsMode
        if (mode == PlayabilityDiagnosticsMode.LocalOnly) return
        searchScrapeJob?.cancel()
        searchScrapeJob = scope.launch {
            val snapshot = _search.value.takeIf { it.scopeKey() == scopeKey }?.rawResults.orEmpty()
            if (snapshot.isEmpty()) return@launch
            _search.update { if (it.scopeKey() == scopeKey) it.copy(isScrapingSwarm = true) else it }
            try {
                val scrapeCandidates = snapshot.sortedByDescending { it.seeders }
                val stats = runCatchingNonCancellation { udpScraper.scrapeBatch(scrapeCandidates, maxItems = 40) }
                    .getOrDefault(emptyMap())
                var stillMatches = false
                _search.update { cur ->
                    if (cur.scopeKey() != scopeKey) {
                        cur.copy(isScrapingSwarm = false)
                    } else {
                        stillMatches = true
                        cur.copy(
                            swarmStatsByHash = cur.swarmStatsByHash + stats,
                            isScrapingSwarm = false,
                        )
                    }
                }
                if (stillMatches &&
                    preferencesFlow.value.playabilityDiagnosticsMode == PlayabilityDiagnosticsMode.AutoTop3OnWifi &&
                    canAutoActiveProbe()
                ) {
                    diagnoseSearchTop(maxCount = 3)
                }
            } catch (ce: CancellationException) {
                _search.update { it.copy(isScrapingSwarm = false) }
                throw ce
            }
        }
    }

    private fun mergePreservingEnriched(
        seed: List<TorrentSearchResult>,
        incoming: List<TorrentSearchResult>,
        enrichedSource: List<TorrentSearchResult> = seed,
    ): List<TorrentSearchResult> {
        val enrichedById = enrichedSource.filter { it.description != null }.associateBy { it.id }
        val merged = if (seed.isEmpty()) incoming else SearchService.deduplicateResults(seed + incoming)
        if (enrichedById.isEmpty()) return merged
        return merged.map { item ->
            val prev = enrichedById[item.id]
            if (prev?.description != null && item.description == null) {
                item.copy(
                    description = prev.description,
                    languageTag = prev.languageTag,
                    languageEvidence = prev.languageEvidence,
                    imdbId = item.imdbId ?: prev.imdbId,
                )
            } else {
                item
            }
        }
    }

    private suspend inline fun <T> runCatchingNonCancellation(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private var lastScanBatchSize = 5

    companion object {
        private const val DISCOVER_IN_MEMORY_TTL_MS = 4L * 3600L * 1000L
        private const val PROGRESSIVE_SCAN_CHECKPOINT = 5
    }
}
