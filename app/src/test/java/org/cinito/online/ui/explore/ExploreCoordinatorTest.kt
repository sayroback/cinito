package org.cinito.online.ui.explore

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.cinito.online.metadata.CatalogRegion
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.metadata.MetadataRepository
import org.cinito.online.network.DohProvider
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.search.PlayabilityAssessment
import org.cinito.online.search.PlayabilityScorer
import org.cinito.online.search.PlayabilityVerdict
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchProviderInfo
import org.cinito.online.search.SearchService
import org.cinito.online.search.SearchTitleResolution
import org.cinito.online.search.SearchUtils
import org.cinito.online.search.TorrentSearchProvider
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.search.TorznabConfig
import org.cinito.online.settings.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ExploreCoordinatorTest {

    private class RecordingProvider(
        override val info: SearchProviderInfo,
        private val onSearch: suspend (String, SearchCategory) -> List<TorrentSearchResult> = { _, _ -> emptyList() },
        private val onImdbSearch: (String, Int?, Int?) -> List<TorrentSearchResult> = { _, _, _ -> emptyList() },
        private val onEnrich: (TorrentSearchResult) -> TorrentSearchResult = { it },
    ) : TorrentSearchProvider {
        var searchCalls = 0
        var imdbCalls = 0
        val recordedEpisodes = mutableListOf<Pair<Int?, Int?>>()

        override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
            searchCalls++
            return onSearch(query, category)
        }

        override suspend fun searchByImdb(
            imdbId: String,
            category: SearchCategory,
            season: Int?,
            episode: Int?,
            mediaTypeHint: String?,
        ): List<TorrentSearchResult> {
            imdbCalls++
            recordedEpisodes += (season to episode)
            return onImdbSearch(imdbId, season, episode)
        }

        override suspend fun enrichResult(result: TorrentSearchResult): TorrentSearchResult = onEnrich(result)
    }

    private suspend fun <T> awaitState(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        withTimeout(5_000) { flow.first(predicate) }

    @Test
    fun preservesCatalogDetailAndSearchStateAcrossNavigationAndCachesEpisodes() = runBlocking {
        val server = MockWebServer()
        // Cinemeta series details with 2 episodes
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "meta": {
                    "id": "tt0903747",
                    "type": "series",
                    "name": "Breaking Bad",
                    "releaseInfo": "2008",
                    "runtime": "49 min",
                    "genres": ["Drama", "Crimen"],
                    "videos": [
                      {"id": "tt0903747:1:1", "season": 1, "episode": 1, "title": "Pilot"},
                      {"id": "tt0903747:1:2", "season": 1, "episode": 2, "title": "Cat's in the Bag"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("explore-coord-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
            ).apply {
                catalogRegion = CatalogRegion.GlobalEnglish
            }

            val torrentioFake = RecordingProvider(
                info = SearchProviderInfo(
                    id = "torrentio",
                    name = "Torrentio",
                    url = "https://torrentio.invalid",
                    categories = setOf(SearchCategory.Movies, SearchCategory.Series),
                    supportsImdbSearch = true,
                ),
                onImdbSearch = { _, s, e ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "torrentio",
                            providerName = "Torrentio",
                            title = "Breaking Bad S0${s ?: 1}E0${e ?: 1} 1080p Latino",
                            magnetUri = SearchUtils.buildMagnet("111111111111111111111111111111111111111${e ?: 1}", "BB"),
                            seeders = 50 + (e ?: 0),
                            providerLanguageField = "🇲🇽",
                        ),
                    )
                },
            )
            val knabenFake = RecordingProvider(
                info = SearchProviderInfo(
                    id = "knaben",
                    name = "Knaben",
                    url = "https://knaben.invalid",
                    categories = setOf(SearchCategory.Movies, SearchCategory.Series),
                ),
                onSearch = { q, _ ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "$q 720p WEB-DL",
                            magnetUri = SearchUtils.buildMagnet("2222222222222222222222222222222222222222", "K"),
                            seeders = 15,
                            sourceRecordId = "rec-1",
                        ),
                    )
                },
                onEnrich = { it.copy(description = "Audio Español Latino NFO") },
            )

            val searchService = SearchService(listOf(torrentioFake, knabenFake))
            val prefs = MutableStateFlow(
                AppPreferences(
                    enabledProviderIds = setOf("torrentio", "knaben"),
                    searchLanguageFilter = SearchLanguageFilter.PrioritizeLatino,
                ),
            )
            val torznab = MutableStateFlow<List<TorznabConfig>>(emptyList())
            val recordedQueries = mutableListOf<String>()

            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = searchService,
                preferencesFlow = prefs,
                torznabFlow = torznab,
                onRecordRecentSearch = { recordedQueries += it },
            )

            // Open series from Discover catalog
            val seriesCard = MediaMetadata(
                id = "tt0903747",
                type = "series",
                title = "Breaking Bad",
                year = 2008,
            )
            coordinator.openCatalogMedia(seriesCard)
            val settled = awaitState(coordinator.discover) { state ->
                val d = state.activeDetail
                d != null && !d.isLoadingDetails && !d.isSearching &&
                    d.selectedEpisode == 1 && d.rawResults.isNotEmpty()
            }

            val detailEp1 = settled.activeDetail
            assertNotNull(detailEp1)
            assertEquals(1, detailEp1?.selectedSeason)
            assertEquals(1, detailEp1?.selectedEpisode)
            assertEquals(2, detailEp1?.media?.episodes?.size)
            assertTrue((detailEp1?.rawResults?.size ?: 0) >= 2)
            assertEquals(1 to 1, torrentioFake.recordedEpisodes.last())

            // Switch to episode 2 -> queries S01E02
            coordinator.selectDetailEpisode(2)
            awaitState(coordinator.discover) { state ->
                val d = state.activeDetail
                d != null && d.selectedEpisode == 2 && !d.isSearching && d.rawResults.isNotEmpty()
            }
            assertEquals(1 to 2, torrentioFake.recordedEpisodes.last())
            val callsBeforeBackToEp1 = torrentioFake.imdbCalls

            // Switch back to episode 1 -> MUST restore from episode snapshot cache without hitting network
            coordinator.selectDetailEpisode(1)
            assertEquals(callsBeforeBackToEp1, torrentioFake.imdbCalls)
            assertEquals(1, coordinator.discover.value.activeDetail?.selectedEpisode)

            // Enrich NFO and save scroll
            val knabenItem = coordinator.discover.value.activeDetail!!.rawResults.first { it.providerId == "knaben" }
            coordinator.enrichDetailResult(knabenItem)
            coordinator.saveDetailScroll(3, 120)
            awaitState(coordinator.discover) { state ->
                state.activeDetail?.rawResults?.firstOrNull { it.id == knabenItem.id }?.description != null
            }
            assertEquals(
                "Audio Español Latino NFO",
                coordinator.discover.value.activeDetail?.rawResults?.first { it.id == knabenItem.id }?.description,
            )

            // Jump from Catalog Details to Search tab ("Buscar más")
            coordinator.openSearchFromCatalogDetail()
            val searchAfterJump = awaitState(coordinator.search) { !it.isSearching && it.rawResults.isNotEmpty() }
            assertEquals("Breaking Bad", searchAfterJump.originCatalogTitle)
            assertEquals(callsBeforeBackToEp1, torrentioFake.imdbCalls) // Reused preloaded catalog results!

            // Switching language filter keeps existing results instead of wiping to empty
            coordinator.setSearchLanguageFilter(SearchLanguageFilter.OnlyLatino)
            assertTrue(coordinator.search.value.rawResults.isNotEmpty())
            awaitState(coordinator.search) { !it.isSearching }
            assertTrue(coordinator.search.value.rawResults.isNotEmpty())

            // Return from Search back to Catalog Details
            assertTrue(coordinator.returnFromSearchToCatalog())
            assertNull(coordinator.search.value.originCatalogTitle)
            assertNotNull(coordinator.discover.value.activeDetail)
            assertEquals(3, coordinator.discover.value.activeDetail?.scrollIndex)
            assertEquals(120, coordinator.discover.value.activeDetail?.scrollOffset)

            // Close and reopen same catalog card -> instant cache restore, zero extra network calls, opens cleanly at top
            val callsBeforeClose = torrentioFake.imdbCalls
            coordinator.closeCatalogMedia()
            assertNull(coordinator.discover.value.activeDetail)
            coordinator.openCatalogMedia(seriesCard)
            val reopened = coordinator.discover.value.activeDetail
            assertEquals(callsBeforeClose, torrentioFake.imdbCalls)
            assertNotNull(reopened)
            assertFalse(reopened!!.isSearching)
            assertEquals(0, reopened.scrollIndex)
            assertEquals(0, reopened.scrollOffset)

            // Manual Search tab search records query & preserves state
            coordinator.submitSearch("Inception")
            val searchAfterManual = awaitState(coordinator.search) {
                it.submittedQuery == "Inception" && !it.isSearching && it.rawResults.isNotEmpty()
            }
            assertEquals(listOf("Inception"), recordedQueries)
            assertTrue(searchAfterManual.rawResults.isNotEmpty())
        } finally {
            coordScope.cancel()
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun catalogDetailResolvesMissingImdbEvenWhenOriginalTitleIsPresent() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                MockResponse().setBody("""{"metas": [], "data": {"popularTitles": {"edges": []}}}""")
        }
        server.start()
        val cacheDir = Files.createTempDirectory("explore-imdb-resolve").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
                baseJustWatchUrl = base,
            ).apply {
                catalogRegion = CatalogRegion.MexicoLatino
            }

            var torrentioImdbCalled = false
            val torrentioFake = RecordingProvider(
                info = SearchProviderInfo(
                    id = "torrentio",
                    name = "Torrentio",
                    url = "https://torrentio.invalid",
                    categories = setOf(SearchCategory.Movies),
                    supportsImdbSearch = true,
                ),
                onImdbSearch = { imdb, _, _ ->
                    torrentioImdbCalled = true
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "torrentio",
                            providerName = "Torrentio · Cinecalidad",
                            title = "Duna Parte Dos 2024 1080p Latino",
                            magnetUri = SearchUtils.buildMagnet("4444444444444444444444444444444444444444", "Duna"),
                            seeders = 45,
                            imdbId = imdb,
                            providerLanguageField = "🇲🇽",
                        ),
                    )
                },
            )
            val searchService = SearchService(
                builtinProviders = listOf(torrentioFake),
                titleResolver = { _, _ ->
                    SearchTitleResolution(
                        displayTitle = "Duna: Parte Dos",
                        originalTitle = "Dune: Part Two",
                        year = 2024,
                        imdbId = "tt15239678",
                        mediaType = "movie",
                    )
                },
            )
            val prefs = MutableStateFlow(AppPreferences(enabledProviderIds = setOf("torrentio")))
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = searchService,
                preferencesFlow = prefs,
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
            )

            // Movie from catalog with runtime already set, alternate title present, but non-IMDb id (e.g. "jw:..." / "tmdb:...")
            // Even if enrichment couldn't resolve tt..., startDetailSourceSearch must NOT skip titleResolution!
            val movieWithoutImdb = MediaMetadata(
                id = "jw:tm555",
                type = "movie",
                title = "Duna: Parte Dos",
                originalTitle = "Dune: Part Two",
                year = 2024,
                runtime = "166 min",
            )
            coordinator.openCatalogMedia(movieWithoutImdb)

            val done = awaitState(coordinator.discover) { state ->
                val d = state.activeDetail
                d != null && !d.isLoadingDetails && !d.isSearching
            }
            assertTrue("Expected Torrentio IMDb provider to run via titleResolver", torrentioImdbCalled)
            assertTrue(done.activeDetail?.rawResults?.isNotEmpty() == true)
        } finally {
            coordScope.cancel()
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun discoverSupportsInfiniteScrollDeduplicationServerFeedsGenresAndDailyRefresh() = runBlocking {
        val server = MockWebServer()
        var currentDay = "2026-11-17"
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val isSeries = body.contains("SHOW")
                val isAfterCursor = body.contains(""""after":"c1"""")
                val isComedy = body.contains(""""cmy"""")
                val isDay2 = currentDay == "2026-11-18"

                val payload = when {
                    isDay2 && !isSeries -> """{"data":{"popularTitles":{"totalCount":40,"pageInfo":{"endCursor":"d2","hasNextPage":true},"edges":[{"node":{"id":"m_day2","objectType":"MOVIE","content":{"title":"Estreno Día 2","originalReleaseYear":2026,"externalIds":{"imdbId":"tt9900001"}}}}]}}}"""
                    isDay2 && isSeries -> """{"data":{"popularTitles":{"totalCount":40,"pageInfo":{"endCursor":"d2s","hasNextPage":true},"edges":[{"node":{"id":"s_day2","objectType":"SHOW","content":{"title":"Serie Día 2","originalReleaseYear":2026,"externalIds":{"imdbId":"tt9900002"}}}}]}}}"""
                    isComedy && !isSeries -> """{"data":{"popularTitles":{"totalCount":80,"pageInfo":{"endCursor":"cm1","hasNextPage":true},"edges":[{"node":{"id":"m_cmy","objectType":"MOVIE","content":{"title":"Comedia MX","originalReleaseYear":2025,"externalIds":{"imdbId":"tt8800001"}}}}]}}}"""
                    isComedy && isSeries -> """{"data":{"popularTitles":{"totalCount":80,"pageInfo":{"endCursor":"cms1","hasNextPage":true},"edges":[{"node":{"id":"s_cmy","objectType":"SHOW","content":{"title":"Serie Comedia MX","originalReleaseYear":2025,"externalIds":{"imdbId":"tt8800002"}}}}]}}}"""
                    isAfterCursor && !isSeries -> """{"data":{"popularTitles":{"totalCount":40,"pageInfo":{"endCursor":null,"hasNextPage":false},"edges":[{"node":{"id":"m1","objectType":"MOVIE","content":{"title":"Película Página 1","originalReleaseYear":2026,"externalIds":{"imdbId":"tt7700001"}}}},{"node":{"id":"m2","objectType":"MOVIE","content":{"title":"Película Página 2","originalReleaseYear":2026,"externalIds":{"imdbId":"tt7700002"}}}}]}}}"""
                    isAfterCursor && isSeries -> """{"data":{"popularTitles":{"totalCount":40,"pageInfo":{"endCursor":null,"hasNextPage":false},"edges":[{"node":{"id":"s2","objectType":"SHOW","content":{"title":"Serie Página 2","originalReleaseYear":2026,"externalIds":{"imdbId":"tt7700004"}}}}]}}}"""
                    !isSeries -> """{"data":{"popularTitles":{"totalCount":40,"pageInfo":{"endCursor":"c1","hasNextPage":true},"edges":[{"node":{"id":"m1","objectType":"MOVIE","content":{"title":"Película Página 1","originalReleaseYear":2026,"externalIds":{"imdbId":"tt7700001"}}}}]}}}"""
                    else -> """{"data":{"popularTitles":{"totalCount":40,"pageInfo":{"endCursor":"c1","hasNextPage":true},"edges":[{"node":{"id":"s1","objectType":"SHOW","content":{"title":"Serie Página 1","originalReleaseYear":2026,"externalIds":{"imdbId":"tt7700003"}}}}]}}}"""
                }
                return MockResponse().setBody(payload)
            }
        }
        server.start()
        val cacheDir = Files.createTempDirectory("explore-infinite-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
                baseJustWatchUrl = base,
                baseTmdbUrl = base,
                todayProvider = { currentDay },
            ).apply {
                catalogRegion = CatalogRegion.MexicoLatino
            }

            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(emptyList()),
                preferencesFlow = MutableStateFlow(AppPreferences()),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                todayProvider = { currentDay },
            )

            coordinator.loadDiscover()
            val page1 = awaitState(coordinator.discover) { !it.isLoading && it.movies.isNotEmpty() && it.series.isNotEmpty() }
            assertEquals(listOf("tt7700001"), page1.movies.map { it.id })
            assertEquals(listOf("tt7700003"), page1.series.map { it.id })
            assertTrue(page1.hasMoreMovies)
            assertTrue(page1.hasMoreSeries)

            // Load page 2 via infinite scroll -> deduplicates tt7700001 and appends tt7700002 & tt7700004
            coordinator.loadMoreDiscover()
            val page2 = awaitState(coordinator.discover) { !it.isLoadingMore && it.movies.size == 2 }
            assertEquals(listOf("tt7700001", "tt7700002"), page2.movies.map { it.id })
            assertEquals(listOf("tt7700003", "tt7700004"), page2.series.map { it.id })
            assertFalse(page2.hasMoreMovies)
            assertFalse(page2.hasMoreSeries)

            // Selecting a genre fetches server-backed catalog for that genre
            coordinator.selectDiscoverGenre("cmy")
            val comedyState = awaitState(coordinator.discover) {
                !it.isLoading && it.selectedGenre == "cmy" && it.movies.any { m -> m.id == "tt8800001" }
            }
            assertEquals("Comedia MX", comedyState.movies.first().title)

            // Clear genre back to all, advance day -> loadDiscover(force=false) detects new day and refreshes!
            coordinator.selectDiscoverGenre("cmy")
            awaitState(coordinator.discover) { !it.isLoading && it.selectedGenre == null }
            currentDay = "2026-11-18"
            coordinator.loadDiscover(force = false)
            val nextDayState = awaitState(coordinator.discover) {
                !it.isLoading && it.movies.any { m -> m.id == "tt9900001" }
            }
            assertEquals("Estreno Día 2", nextDayState.movies.first().title)
        } finally {
            coordScope.cancel()
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun paginationErrorPreservesHasMoreAndOpenSearchClearsDetailSearchingState() = runBlocking {
        val server = MockWebServer()
        var failPage2 = true
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val isSeries = body.contains("SHOW")
                val isAfterCursor = body.contains(""""after":"p1"""")
                if (isAfterCursor && failPage2) {
                    return MockResponse().setResponseCode(500).setBody("upstream error")
                }
                val payload = when {
                    isAfterCursor && !isSeries -> """{"data":{"popularTitles":{"totalCount":25,"pageInfo":{"endCursor":null,"hasNextPage":false},"edges":[{"node":{"id":"m2","objectType":"MOVIE","content":{"title":"Peli Dos","originalReleaseYear":2026,"externalIds":{"imdbId":"tt6100002"}}}}]}}}"""
                    isAfterCursor && isSeries -> """{"data":{"popularTitles":{"totalCount":12,"pageInfo":{"endCursor":null,"hasNextPage":false},"edges":[{"node":{"id":"s2","objectType":"SHOW","content":{"title":"Serie Dos","originalReleaseYear":2026,"externalIds":{"imdbId":"tt6200002"}}}}]}}}"""
                    !isSeries -> """{"data":{"popularTitles":{"totalCount":25,"pageInfo":{"endCursor":"p1","hasNextPage":true},"edges":[{"node":{"id":"m1","objectType":"MOVIE","content":{"title":"Peli Uno","originalReleaseYear":2026,"externalIds":{"imdbId":"tt6100001"}}}}]}}}"""
                    else -> """{"data":{"popularTitles":{"totalCount":12,"pageInfo":{"endCursor":"p1","hasNextPage":true},"edges":[{"node":{"id":"s1","objectType":"SHOW","content":{"title":"Serie Uno","originalReleaseYear":2026,"externalIds":{"imdbId":"tt6200001"}}}}]}}}"""
                }
                return MockResponse().setBody(payload)
            }
        }
        server.start()
        val cacheDir = Files.createTempDirectory("explore-paging-err").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
                baseJustWatchUrl = base,
                baseTmdbUrl = base,
            ).apply {
                catalogRegion = CatalogRegion.MexicoLatino
            }
            val slowProvider = RecordingProvider(
                info = SearchProviderInfo("yts", "YTS", "https://yts.invalid", setOf(SearchCategory.Movies), supportsImdbSearch = false),
                onSearch = { _, _ ->
                    kotlinx.coroutines.delay(5_000L)
                    emptyList()
                },
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(slowProvider)),
                preferencesFlow = MutableStateFlow(AppPreferences(enabledProviderIds = setOf("yts"))),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
            )

            coordinator.loadDiscover()
            val ready = awaitState(coordinator.discover) { !it.isLoading && it.movies.isNotEmpty() }
            assertEquals(37, ready.totalCountForActiveTab)
            coordinator.selectDiscoverTab(DiscoverCategoryTab.Movies)
            assertEquals(25, coordinator.discover.value.totalCountForActiveTab)
            coordinator.selectDiscoverTab(DiscoverCategoryTab.All)

            // Network failure on page 2 sets loadMoreError = true while keeping hasMoreMovies = true
            coordinator.loadMoreDiscover()
            val errState = awaitState(coordinator.discover) { !it.isLoadingMore && it.loadMoreError }
            assertTrue(errState.hasMoreMovies)
            assertTrue(errState.hasMoreForActiveTab)

            // Retry recovers cleanly
            failPage2 = false
            coordinator.loadMoreDiscover()
            val recovered = awaitState(coordinator.discover) { !it.isLoadingMore && it.movies.size == 2 }
            assertFalse(recovered.loadMoreError)
            assertEquals(listOf("tt6100001", "tt6100002"), recovered.movies.map { it.id })

            // Jumping to Search while detail search is active cancels detailSearchJob & clears activeDetail.isSearching
            val media = MediaMetadata("tt6100001", "movie", "Peli Uno", year = 2026)
            coordinator.openCatalogMedia(media)
            awaitState(coordinator.discover) { it.activeDetail?.isSearching == true }
            coordinator.openSearchFromCatalogDetail()
            assertFalse(coordinator.discover.value.activeDetail?.isSearching == true)
        } finally {
            coordScope.cancel()
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun seriesDetailSkipsSeasonZeroAndAllowsReturningFromPackToSameSeason() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.contains("/meta/series/tt5551234.json")) {
                    return MockResponse().setBody(
                        """
                        {
                          "meta": {
                            "id": "tt5551234",
                            "type": "series",
                            "name": "Serie Ejemplo",
                            "runtime": "45 min",
                            "videos": [
                              {"id": "sp1", "season": 0, "episode": 1, "title": "Detrás de cámaras"},
                              {"id": "s1e1", "season": 1, "episode": 1, "title": "Inicio", "released": "2025-01-10"},
                              {"id": "s2e1", "season": 2, "episode": 1, "title": "Regreso", "released": "2026-01-10"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                }
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        val cacheDir = Files.createTempDirectory("explore-series-ux").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
                baseJustWatchUrl = base,
            ).apply {
                catalogRegion = CatalogRegion.GlobalEnglish
            }
            val fakeProvider = RecordingProvider(
                info = SearchProviderInfo("torrentio", "Torrentio", "https://t.invalid", setOf(SearchCategory.Series), supportsImdbSearch = true),
                onImdbSearch = { imdb, s, e ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "torrentio",
                            providerName = "Torrentio",
                            title = "Serie.Ejemplo.S0${s ?: 1}E0${e ?: 1}.1080p.Latino",
                            magnetUri = SearchUtils.buildMagnet("5555555555555555555555555555555555555555", "Serie"),
                            seeders = 25,
                            imdbId = imdb,
                        ),
                    )
                },
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(fakeProvider)),
                preferencesFlow = MutableStateFlow(AppPreferences(enabledProviderIds = setOf("torrentio"))),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                todayProvider = { "2026-11-17" },
            )

            coordinator.openCatalogMedia(MediaMetadata("tt5551234", "series", "Serie Ejemplo"))
            val opened = awaitState(coordinator.discover) {
                val d = it.activeDetail
                d != null && !d.isLoadingDetails && !d.isSearching && d.media.episodes.isNotEmpty()
            }
            assertEquals(1, opened.activeDetail?.selectedSeason)
            assertEquals(1, opened.activeDetail?.selectedEpisode)

            // Enter Pack completo (episode = null) while on Season 1
            coordinator.selectDetailEpisode(null)
            assertEquals(null, coordinator.discover.value.activeDetail?.selectedEpisode)
            assertEquals(1, coordinator.discover.value.activeDetail?.selectedSeason)

            // Tapping Season 1 again must exit Pack mode and restore Season 1 Ep 1!
            coordinator.selectDetailSeason(1)
            val backFromPack = coordinator.discover.value.activeDetail
            assertEquals(1, backFromPack?.selectedSeason)
            assertEquals(1, backFromPack?.selectedEpisode)
        } finally {
            coordScope.cancel()
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun searchTabRunsLiveTrackerScrapeAfterResultsArrive() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-scrape-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val liveHash = "9999999999999999999999999999999999999999"
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val fakeProvider = RecordingProvider(
                info = SearchProviderInfo("knaben", "Knaben", "https://k.invalid", setOf(SearchCategory.Movies)),
                onSearch = { _, _ ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "Matrix.1999.1080p.WEB-DL",
                            magnetUri = "magnet:?xt=urn:btih:$liveHash",
                            seeders = 4,
                        ),
                    )
                },
            )
            val fakeScraper = object : org.cinito.online.search.UdpTrackerScraper() {
                override suspend fun scrapeBatch(
                    results: List<TorrentSearchResult>,
                    maxItems: Int,
                ): Map<String, org.cinito.online.search.TrackerSwarmStats> = mapOf(
                    liveHash to org.cinito.online.search.TrackerSwarmStats(
                        seeders = 64,
                        leechers = 5,
                        completed = 900,
                        updatedAtMs = 1_000L,
                    ),
                )
            }
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(fakeProvider)),
                preferencesFlow = MutableStateFlow(AppPreferences(enabledProviderIds = setOf("knaben"))),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                udpScraper = fakeScraper,
            )

            coordinator.submitSearch("Matrix")
            val settled = awaitState(coordinator.search) {
                !it.isSearching && !it.isScrapingSwarm && it.swarmStatsByHash.containsKey(liveHash)
            }
            assertEquals(64, settled.swarmStatsByHash[liveHash]?.seeders)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun diagnoseSearchTopUpdatesProbeResultsAndCleansUp() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-probe-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val probeHash = "7777777777777777777777777777777777777777"
        var removedCount = 0
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val fakeProvider = RecordingProvider(
                info = SearchProviderInfo("knaben", "Knaben", "https://k.invalid", setOf(SearchCategory.Movies)),
                onSearch = { _, _ ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "Inception.2010.1080p.WEB-DL.Latino",
                            magnetUri = "magnet:?xt=urn:btih:$probeHash",
                            seeders = 22,
                        ),
                    )
                },
            )
            val fakeTransport = webtor.core.EngineTransport { method, path, _ ->
                when {
                    method == "POST" && path == "/add" ->
                        webtor.core.EngineResponse(200, """{"id":"probe-top","infoHash":"$probeHash"}""")
                    method == "GET" && path == "/torrent/probe-top" ->
                        webtor.core.EngineResponse(
                            200,
                            """{"id":"probe-top","infoHash":"$probeHash","ready":true,"done":false,"paused":false,"progress":0.02,"downloadSpeed":2800000,"uploadSpeed":0,"numPeers":16,"length":1400000000,"downloaded":131072,"uploaded":0,"files":[{"index":0,"name":"Inception.1080p.mp4","path":"Inception.1080p.mp4","length":1400000000,"progress":0.02,"type":"video/mp4"}]}""",
                        )
                    method == "POST" && path == "/focus" ->
                        webtor.core.EngineResponse(200, """{"ok":true}""")
                    method == "POST" && path == "/remove" -> {
                        removedCount++
                        webtor.core.EngineResponse(200, """{"ok":true}""")
                    }
                    else -> error("Unexpected $method $path")
                }
            }
            val prober = org.cinito.online.search.SwarmDiagnosticsCoordinator(
                engineClient = webtor.core.EngineClient(fakeTransport),
                delayFn = {},
                postReadySampleWindowMs = 0L,
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(fakeProvider)),
                preferencesFlow = MutableStateFlow(AppPreferences(enabledProviderIds = setOf("knaben"))),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                swarmDiagnostics = prober,
            )

            coordinator.submitSearch("Inception")
            awaitState(coordinator.search) { !it.isSearching && it.rawResults.isNotEmpty() }

            coordinator.diagnoseSearchTop(maxCount = 3)
            val probedState = awaitState(coordinator.search) {
                it.probingHashes.isEmpty() && it.probeResultsByHash.containsKey(probeHash)
            }
            assertEquals(2_800_000L, probedState.probeResultsByHash[probeHash]?.measuredDownloadBps)
            assertEquals(16, probedState.probeResultsByHash[probeHash]?.activePeers)
            assertEquals(1, removedCount)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun localOnlyModeSkipsLiveTrackerScrapeAndAutoTop3RespectsWifiGuard() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-mode-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val h1 = "8888888888888888888888888888888888888888"
        var scrapeCalls = 0
        var addCalls = 0
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val fakeProvider = RecordingProvider(
                info = SearchProviderInfo("knaben", "Knaben", "https://k.invalid", setOf(SearchCategory.Movies)),
                onSearch = { _, _ ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "Dune.2021.1080p.WEB-DL.Latino",
                            magnetUri = "magnet:?xt=urn:btih:$h1",
                            seeders = 30,
                        ),
                    )
                },
            )
            val fakeScraper = object : org.cinito.online.search.UdpTrackerScraper() {
                override suspend fun scrapeBatch(
                    results: List<TorrentSearchResult>,
                    maxItems: Int,
                ): Map<String, org.cinito.online.search.TrackerSwarmStats> {
                    scrapeCalls++
                    return mapOf(h1 to org.cinito.online.search.TrackerSwarmStats(42, 4))
                }
            }
            val fakeTransport = webtor.core.EngineTransport { method, path, _ ->
                when {
                    method == "POST" && path == "/add" -> {
                        addCalls++
                        webtor.core.EngineResponse(200, """{"id":"auto-1","infoHash":"$h1"}""")
                    }
                    method == "GET" && path == "/torrent/auto-1" ->
                        webtor.core.EngineResponse(
                            200,
                            """{"id":"auto-1","infoHash":"$h1","ready":true,"done":false,"paused":false,"progress":0.02,"downloadSpeed":3200000,"uploadSpeed":0,"numPeers":15,"connectedSeeders":5,"swarmAvailability":5.0,"length":1400000000,"downloaded":131072,"uploaded":0,"files":[{"index":0,"name":"Dune.mp4","path":"Dune.mp4","length":1400000000,"progress":0.02,"type":"video/mp4"}]}""",
                        )
                    method == "POST" && path == "/focus" -> webtor.core.EngineResponse(200, """{"ok":true}""")
                    method == "POST" && path == "/remove" -> webtor.core.EngineResponse(200, """{"ok":true}""")
                    else -> error("Unexpected $method $path")
                }
            }
            val prober = org.cinito.online.search.SwarmDiagnosticsCoordinator(
                engineClient = webtor.core.EngineClient(fakeTransport),
                delayFn = {},
                postReadySampleWindowMs = 0L,
            )
            val prefsFlow = MutableStateFlow(
                AppPreferences(
                    enabledProviderIds = setOf("knaben"),
                    playabilityDiagnosticsMode = org.cinito.online.settings.PlayabilityDiagnosticsMode.LocalOnly,
                ),
            )
            var wifiAndBatteryOk = false
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(fakeProvider)),
                preferencesFlow = prefsFlow,
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                udpScraper = fakeScraper,
                swarmDiagnostics = prober,
                canAutoActiveProbe = { wifiAndBatteryOk },
            )

            // 1. LocalOnly -> skips scrape & active probe
            coordinator.submitSearch("Dune Local")
            awaitState(coordinator.search) { !it.isSearching && it.rawResults.isNotEmpty() }
            kotlinx.coroutines.delay(50L)
            assertEquals(0, scrapeCalls)
            assertEquals(0, addCalls)

            // 2. AutoTop3OnWifi on metered/cellular (guard false) -> scrapes, does NOT auto-probe
            prefsFlow.value = prefsFlow.value.copy(
                playabilityDiagnosticsMode = org.cinito.online.settings.PlayabilityDiagnosticsMode.AutoTop3OnWifi,
            )
            coordinator.submitSearch("Dune Cellular")
            awaitState(coordinator.search) {
                !it.isSearching && !it.isScrapingSwarm && it.swarmStatsByHash.containsKey(h1)
            }
            kotlinx.coroutines.delay(50L)
            assertEquals(1, scrapeCalls)
            assertEquals(0, addCalls)

            // 3. AutoTop3OnWifi on unmetered Wi-Fi + healthy battery -> scrapes AND auto-probes
            wifiAndBatteryOk = true
            coordinator.submitSearch("Dune Wifi")
            val autoProbed = awaitState(coordinator.search) {
                !it.isSearching && !it.isScrapingSwarm && it.probingHashes.isEmpty() && it.probeResultsByHash.containsKey(h1)
            }
            assertEquals(1, addCalls)
            assertEquals(5, autoProbed.probeResultsByHash[h1]?.connectedSeeders)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun reEnablingHideAdultContentResetsAdultCategoryAndFiltersAdultResults() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-hide-adult-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val adultProvider = RecordingProvider(
                info = SearchProviderInfo(
                    id = "piratebay",
                    name = "TPB",
                    url = "https://tpb.invalid",
                    categories = setOf(SearchCategory.All, SearchCategory.Adult),
                    supportsAdultContent = true,
                    hasStrictAdultCategory = true,
                ),
                onSearch = { _, _ ->
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "piratebay",
                            providerName = "TPB",
                            title = "Adult XXX Pack",
                            magnetUri = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            seeders = 10,
                            category = SearchCategory.Adult,
                        ),
                        SearchUtils.makeResult(
                            providerId = "piratebay",
                            providerName = "TPB",
                            title = "Clean Documentary 1080p",
                            magnetUri = "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            seeders = 15,
                            category = SearchCategory.Movies,
                        ),
                    )
                },
            )
            val prefsFlow = MutableStateFlow(
                AppPreferences(
                    enabledProviderIds = setOf("piratebay"),
                    hideAdultContent = false,
                ),
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(adultProvider)),
                preferencesFlow = prefsFlow,
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
            )

            coordinator.setSearchCategory(SearchCategory.Adult)
            coordinator.submitSearch("sample")
            val loaded = awaitState(coordinator.search) { !it.isSearching && it.rawResults.isNotEmpty() }
            assertEquals(SearchCategory.Adult, loaded.category)

            prefsFlow.value = prefsFlow.value.copy(hideAdultContent = true)
            val afterHide = awaitState(coordinator.search) {
                it.category == SearchCategory.All &&
                    it.rawResults.none { r -> r.category == SearchCategory.Adult || SearchUtils.isAdultContent(r.title, r.description) }
            }
            assertEquals(SearchCategory.All, afterHide.category)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun adultOnlySearchDoesNotOverrideExplicitCategoriesAndSkipsRecentHistory() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-adult-only-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recordedRecent = mutableListOf<String>()
        val capturedCategories = mutableListOf<SearchCategory>()
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val generalProvider = RecordingProvider(
                info = SearchProviderInfo(
                    id = "knaben",
                    name = "Knaben",
                    url = "https://k.invalid",
                    categories = setOf(SearchCategory.All, SearchCategory.Movies, SearchCategory.Series),
                ),
                onSearch = { _, cat ->
                    capturedCategories += cat
                    listOf(
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "Inception 2010 1080p",
                            magnetUri = "magnet:?xt=urn:btih:cccccccccccccccccccccccccccccccccccccccc",
                            seeders = 40,
                            category = SearchCategory.Movies,
                        ),
                    )
                },
            )
            val prefsFlow = MutableStateFlow(
                AppPreferences(
                    enabledProviderIds = setOf("knaben"),
                    hideAdultContent = false,
                    adultOnlySearch = true,
                ),
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(generalProvider)),
                preferencesFlow = prefsFlow,
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = { recordedRecent += it },
            )

            // 1. All + adultOnlySearch -> effectiveAdultOnly = true -> skips recent history
            coordinator.submitSearch("adult query")
            awaitState(coordinator.search) { !it.isSearching && it.submittedQuery == "adult query" }
            assertTrue(recordedRecent.isEmpty())

            // 2. Explicit Movies category -> adultOnlySearch does NOT force +18, records recent search
            coordinator.setSearchCategory(SearchCategory.Movies)
            coordinator.submitSearch("Inception")
            val moviesState = awaitState(coordinator.search) {
                !it.isSearching && it.submittedQuery == "Inception" && it.rawResults.isNotEmpty()
            }
            assertEquals(1, moviesState.rawResults.size)
            assertEquals(listOf("Inception"), recordedRecent)
            assertTrue(capturedCategories.contains(SearchCategory.Movies))

            // 3. Direct Adult category with adultOnlySearch = false -> skips recent history
            prefsFlow.value = prefsFlow.value.copy(adultOnlySearch = false)
            coordinator.searchDirectFromDiscover("secret adult", SearchCategory.Adult)
            awaitState(coordinator.search) { !it.isSearching && it.submittedQuery == "secret adult" }
            assertEquals(listOf("Inception"), recordedRecent)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun filterSearchPlayableResultsHidesDeadWhenEnabledAndShowsAllWhenDisabled() {
        val live = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Live Movie 1080p",
            magnetUri = SearchUtils.buildMagnet("1111111111111111111111111111111111111111", "Live"),
            seeders = 25,
        )
        val deadExplicit = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Dead Explicit 1080p",
            magnetUri = SearchUtils.buildMagnet("2222222222222222222222222222222222222222", "Dead1"),
            seeders = 0,
        )
        val deadFallback = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Dead Fallback 720p",
            magnetUri = SearchUtils.buildMagnet("3333333333333333333333333333333333333333", "Dead2"),
            seeders = 0,
        )
        val results = listOf(live, deadExplicit, deadFallback)
        val assessments: Map<String, PlayabilityAssessment> = mapOf(
            live.id to PlayabilityScorer.evaluate(live),
            deadExplicit.id to PlayabilityScorer.evaluate(deadExplicit).copy(verdict = PlayabilityVerdict.Dead),
            // deadFallback intentionally omitted to exercise PlayabilityScorer.evaluate(it) fallback
        )

        val hidden = filterSearchPlayableResults(
            results = results,
            assessments = assessments,
            hideDeadResults = true,
        )
        assertEquals(listOf(live), hidden.visible)
        assertEquals(2, hidden.hiddenDeadCount)

        val shown = filterSearchPlayableResults(
            results = results,
            assessments = assessments,
            hideDeadResults = false,
        )
        assertEquals(results, shown.visible)
        assertEquals(0, shown.hiddenDeadCount)
    }

    @Test
    fun setHideDeadSearchResultsUpdatesSearchState() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-hide-dead-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = MetadataRepository(
                    network = HardenedNetworkClient(DohProvider.System),
                    cacheDir = cacheDir,
                ),
                searchService = SearchService(emptyList()),
                preferencesFlow = MutableStateFlow(AppPreferences()),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
            )

            assertTrue(coordinator.search.value.hideDeadResults)

            coordinator.setHideDeadSearchResults(false)
            assertFalse(coordinator.search.value.hideDeadResults)

            coordinator.setHideDeadSearchResults(true)
            assertTrue(coordinator.search.value.hideDeadResults)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun progressiveSearchScanPausesAtFiveVerifiedItemsAndResumesRemaining() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-scan-checkpoint-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hashes = (1..7).map { idx -> idx.toString().repeat(40) }
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val fakeProvider = RecordingProvider(
                info = SearchProviderInfo("knaben", "Knaben", "https://k.invalid", setOf(SearchCategory.Movies)),
                onSearch = { _, _ ->
                    hashes.mapIndexed { idx, hash ->
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "Movie ${idx + 1} 1080p Latino",
                            magnetUri = "magnet:?xt=urn:btih:$hash",
                            seeders = 50 - idx,
                        )
                    }
                },
            )
            var nextId = 0
            val idToHash = java.util.concurrent.ConcurrentHashMap<String, String>()
            val fakeTransport = webtor.core.EngineTransport { method, path, body ->
                when {
                    method == "POST" && path == "/add" -> {
                        val hash = hashes.firstOrNull { body?.contains(it, ignoreCase = true) == true } ?: hashes[0]
                        val id = "scan-${++nextId}"
                        idToHash[id] = hash
                        webtor.core.EngineResponse(200, """{"id":"$id","infoHash":"$hash"}""")
                    }
                    method == "GET" && path.startsWith("/torrent/") -> {
                        val id = path.removePrefix("/torrent/")
                        val hash = idToHash[id] ?: hashes[0]
                        webtor.core.EngineResponse(
                            200,
                            """{"id":"$id","infoHash":"$hash","ready":true,"done":false,"paused":false,"progress":0.02,"downloadSpeed":2500000,"uploadSpeed":0,"numPeers":12,"connectedSeeders":4,"swarmAvailability":4.0,"length":1000000000,"downloaded":65536,"uploaded":0,"files":[{"index":0,"name":"Movie.mp4","path":"Movie.mp4","length":1000000000,"progress":0.02,"type":"video/mp4"}]}""",
                        )
                    }
                    method == "POST" && path == "/focus" -> webtor.core.EngineResponse(200, """{"ok":true}""")
                    method == "POST" && path == "/remove" -> webtor.core.EngineResponse(200, """{"ok":true}""")
                    else -> error("Unexpected $method $path")
                }
            }
            val prober = org.cinito.online.search.SwarmDiagnosticsCoordinator(
                engineClient = webtor.core.EngineClient(fakeTransport),
                delayFn = {},
                postReadySampleWindowMs = 0L,
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(fakeProvider)),
                preferencesFlow = MutableStateFlow(
                    AppPreferences(
                        enabledProviderIds = setOf("knaben"),
                        playabilityDiagnosticsMode = org.cinito.online.settings.PlayabilityDiagnosticsMode.LocalOnly,
                    ),
                ),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                swarmDiagnostics = prober,
            )

            coordinator.submitSearch("Checkpoint Movie")
            awaitState(coordinator.search) { !it.isSearching && it.rawResults.size == 7 }

            coordinator.startProgressiveSearchScan()
            val pausedState = awaitState(coordinator.search) {
                it.swarmScan.status == SwarmScanStatus.Paused
            }
            assertEquals(5, pausedState.swarmScan.verifiedLiveCount)
            assertEquals(5, pausedState.swarmScan.testedCount)
            assertEquals(7, pausedState.swarmScan.totalCount)
            assertEquals(0, pausedState.swarmScan.deadCount)
            assertNull(pausedState.swarmScan.currentHash)

            coordinator.resumeSearchScan()
            val completedState = awaitState(coordinator.search) {
                it.swarmScan.status == SwarmScanStatus.Completed
            }
            assertEquals(7, completedState.swarmScan.verifiedLiveCount)
            assertEquals(7, completedState.swarmScan.testedCount)
            assertEquals(7, completedState.swarmScan.totalCount)
            assertEquals(7, completedState.probeResultsByHash.size)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun pauseSearchScanStopsRunningScanAndResumeContinuesRemaining() = runBlocking {
        val cacheDir = Files.createTempDirectory("explore-scan-pause-test").toFile()
        val coordScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hashes = (1..4).map { idx -> (idx + 5).toString().repeat(40) }
        try {
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
            )
            val fakeProvider = RecordingProvider(
                info = SearchProviderInfo("knaben", "Knaben", "https://k.invalid", setOf(SearchCategory.Movies)),
                onSearch = { _, _ ->
                    hashes.mapIndexed { idx, hash ->
                        SearchUtils.makeResult(
                            providerId = "knaben",
                            providerName = "Knaben",
                            title = "Pauseable ${idx + 1} 1080p",
                            magnetUri = "magnet:?xt=urn:btih:$hash",
                            seeders = 40 - idx,
                        )
                    }
                },
            )
            val gateFirstDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            val allowContinue = kotlinx.coroutines.CompletableDeferred<Unit>()
            var addCounter = 0
            val idToHash = java.util.concurrent.ConcurrentHashMap<String, String>()
            val fakeTransport = webtor.core.EngineTransport { method, path, body ->
                when {
                    method == "POST" && path == "/add" -> {
                        val seq = ++addCounter
                        if (seq == 2) {
                            gateFirstDone.complete(Unit)
                            kotlinx.coroutines.runBlocking { allowContinue.await() }
                        }
                        val hash = hashes.firstOrNull { body?.contains(it, ignoreCase = true) == true } ?: hashes[0]
                        val id = "pause-$seq"
                        idToHash[id] = hash
                        webtor.core.EngineResponse(200, """{"id":"$id","infoHash":"$hash"}""")
                    }
                    method == "GET" && path.startsWith("/torrent/") -> {
                        val id = path.removePrefix("/torrent/")
                        val hash = idToHash[id] ?: hashes[0]
                        webtor.core.EngineResponse(
                            200,
                            """{"id":"$id","infoHash":"$hash","ready":true,"done":false,"paused":false,"progress":0.02,"downloadSpeed":2000000,"uploadSpeed":0,"numPeers":10,"connectedSeeders":3,"swarmAvailability":3.0,"length":1000000000,"downloaded":65536,"uploaded":0,"files":[{"index":0,"name":"Pauseable.mp4","path":"Pauseable.mp4","length":1000000000,"progress":0.02,"type":"video/mp4"}]}""",
                        )
                    }
                    method == "POST" && path == "/focus" -> webtor.core.EngineResponse(200, """{"ok":true}""")
                    method == "POST" && path == "/remove" -> webtor.core.EngineResponse(200, """{"ok":true}""")
                    else -> error("Unexpected $method $path")
                }
            }
            val prober = org.cinito.online.search.SwarmDiagnosticsCoordinator(
                engineClient = webtor.core.EngineClient(fakeTransport),
                delayFn = {},
                postReadySampleWindowMs = 0L,
            )
            val coordinator = ExploreCoordinator(
                scope = coordScope,
                metadataRepository = repo,
                searchService = SearchService(listOf(fakeProvider)),
                preferencesFlow = MutableStateFlow(
                    AppPreferences(
                        enabledProviderIds = setOf("knaben"),
                        playabilityDiagnosticsMode = org.cinito.online.settings.PlayabilityDiagnosticsMode.LocalOnly,
                    ),
                ),
                torznabFlow = MutableStateFlow(emptyList()),
                onRecordRecentSearch = {},
                swarmDiagnostics = prober,
            )

            coordinator.submitSearch("Pauseable")
            awaitState(coordinator.search) { !it.isSearching && it.rawResults.size == 4 }

            coordinator.startProgressiveSearchScan()
            withTimeout(5_000) { gateFirstDone.await() }
            val runningWithOne = awaitState(coordinator.search) {
                it.swarmScan.status == SwarmScanStatus.Running && it.swarmScan.testedCount >= 1
            }
            assertEquals(1, runningWithOne.swarmScan.testedCount)

            coordinator.pauseSearchScan()
            val paused = awaitState(coordinator.search) {
                it.swarmScan.status == SwarmScanStatus.Paused && it.swarmScan.currentHash == null
            }
            assertEquals(1, paused.swarmScan.testedCount)

            allowContinue.complete(Unit)
            coordinator.resumeSearchScan()
            val completed = awaitState(coordinator.search) {
                it.swarmScan.status == SwarmScanStatus.Completed
            }
            assertEquals(4, completed.swarmScan.testedCount)
            assertEquals(4, completed.swarmScan.verifiedLiveCount)
            assertEquals(4, completed.probeResultsByHash.size)
        } finally {
            coordScope.cancel()
            cacheDir.deleteRecursively()
        }
    }
}
