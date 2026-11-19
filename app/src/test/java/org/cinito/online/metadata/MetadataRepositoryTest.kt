package org.cinito.online.metadata

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.cinito.online.network.DohProvider
import org.cinito.online.network.HardenedNetworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MetadataRepositoryTest {

    @Test
    fun parsesAndCachesCinemetaCatalogAndDetails() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "metas": [
                    {
                      "id": "tt15239678",
                      "type": "movie",
                      "name": "Dune: Part Two",
                      "releaseInfo": "2024",
                      "poster": "https://images.example.org/dune2.jpg",
                      "imdbRating": "8.6"
                    }
                  ]
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "meta": {
                    "id": "tt15239678",
                    "type": "movie",
                    "name": "Dune: Part Two",
                    "releaseInfo": "2024",
                    "poster": "https://images.example.org/dune2.jpg",
                    "background": "https://images.example.org/dune2-bg.jpg",
                    "description": "Paul Atreides unites with the Fremen.",
                    "imdbRating": "8.6",
                    "runtime": "166 min",
                    "genres": ["Sci-Fi", "Adventure"]
                  }
                }
                """.trimIndent()
            )
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-test").toFile()
        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
            )

            val catalog = repo.getTopCatalog("movie")
            assertEquals(1, catalog.size)
            assertEquals("Dune: Part Two", catalog.first().title)
            assertEquals(2024, catalog.first().year)

            // Second call should hit disk cache without making another HTTP request
            val cachedCatalog = repo.getTopCatalog("movie")
            assertEquals(1, cachedCatalog.size)

            val details = repo.getDetails("movie", "tt15239678")
            assertNotNull(details)
            assertEquals("166 min", details?.runtime)
            assertEquals(listOf("Sci-Fi", "Adventure"), details?.genres)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun respectsLookupDisabledPrivacyToggle() = runTest {
        val server = MockWebServer()
        server.start()
        val cacheDir = Files.createTempDirectory("meta-privacy").toFile()
        try {
            val base = server.url("").toString().trimEnd('/')
            val repo = MetadataRepository(
                network = HardenedNetworkClient(DohProvider.System),
                cacheDir = cacheDir,
                baseCinemetaUrl = base,
                baseTvMazeUrl = base,
            )
            repo.lookupEnabled = false

            val res = repo.resolveForParsedTitle(MediaTitleParser.parse("Inception.2010.1080p"))
            assertNull(res)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun fetchesJustWatchMxCatalogInSpanishWithOriginalTitle() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "popularTitles": {
                      "edges": [
                        {
                          "node": {
                            "id": "tm123",
                            "objectType": "MOVIE",
                            "content": {
                              "title": "Duna: Parte Dos",
                              "originalTitle": "Dune: Part Two",
                              "originalReleaseYear": 2024,
                              "shortDescription": "Paul Atreides se une a los Fremen en Arrakis.",
                              "runtime": 166,
                              "genres": [{"translation": "Ciencia ficción"}],
                              "scoring": {"imdbScore": 8.5},
                              "externalIds": {"imdbId": "tt15239678"},
                              "posterUrl": "/poster/123/s592/duna.jpg",
                              "backdrops": [{"backdropUrl": "/backdrop/456/s1440/duna.jpg"}]
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-jw-mx").toFile()
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

            val items = repo.getTopCatalog("movie")
            assertEquals(1, items.size)
            val first = items.first()
            assertEquals("tt15239678", first.id)
            assertEquals("Duna: Parte Dos", first.title)
            assertEquals("Dune: Part Two", first.originalTitle)
            assertEquals(2024, first.year)
            assertEquals("166 min", first.runtime)
            assertEquals("8.5", first.rating)
            assertEquals(listOf("Ciencia ficción"), first.genres)
            assertEquals("https://images.justwatch.com/poster/123/s592/duna.jpg", first.posterUrl)
            assertEquals("https://images.justwatch.com/backdrop/456/s1440/duna.jpg", first.backdropUrl)

            val recorded = server.takeRequest()
            assert(recorded.body.readUtf8().contains(""""country":"MX""""))
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun prefersTmdbMxTrendingWhenApiKeyConfigured() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "results": [
                    {
                      "id": 693134,
                      "title": "Duna: Parte Dos",
                      "original_title": "Dune: Part Two",
                      "release_date": "2024-02-27",
                      "overview": "Sinopsis oficial de México.",
                      "poster_path": "/p.jpg",
                      "backdrop_path": "/b.jpg",
                      "vote_average": 8.4
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-tmdb-mx").toFile()
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
                tmdbApiKey = "test-tmdb-key"
            }

            val items = repo.getTopCatalog("movie")
            assertEquals(1, items.size)
            val first = items.first()
            assertEquals("tmdb:693134", first.id)
            assertEquals("Duna: Parte Dos", first.title)
            assertEquals("Dune: Part Two", first.originalTitle)
            assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", first.posterUrl)

            val recorded = server.takeRequest()
            assert(recorded.path?.contains("language=es-MX") == true)
            assert(recorded.path?.contains("region=MX") == true)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun resolveSearchQueryStripsColonAndResolvesOriginalTitleAndImdb() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "popularTitles": {
                      "edges": [
                        {
                          "node": {
                            "id": "tm999",
                            "objectType": "MOVIE",
                            "content": {
                              "title": "Wildwood: El bosque salvaje",
                              "originalTitle": "Wildwood",
                              "originalReleaseYear": 2026,
                              "externalIds": {"imdbId": "tt2056785"}
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-resolve").toFile()
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

            val resolved = repo.resolveSearchQuery("Wildwood: El bosque salvaje", "movie")
            assertNotNull(resolved)
            assertEquals("tt2056785", resolved?.id)
            assertEquals("Wildwood", resolved?.originalTitle)
            assertEquals(2026, resolved?.year)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun enrichCatalogMediaDetailsResolvesImdbAndEpisodesForJustWatchSeries() = runTest {
        val server = MockWebServer()
        // First response: Cinemeta search for original title -> resolves tt0903747
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "metas": [
                    {
                      "id": "tt0903747",
                      "type": "series",
                      "name": "Breaking Bad",
                      "releaseInfo": "2008"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        // Second response: Cinemeta meta details -> full episode list
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
                      {"id": "tt0903747:1:1", "season": 1, "episode": 1, "title": "Piloto"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-enrich").toFile()
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
                catalogRegion = CatalogRegion.GlobalEnglish
            }

            val rawJustWatch = MediaMetadata(
                id = "jw:ts123",
                type = "series",
                title = "Breaking Bad",
                originalTitle = "Breaking Bad",
                year = 2008,
                description = "Sinopsis en español",
            )
            val enriched = repo.enrichCatalogMediaDetails(rawJustWatch)
            assertEquals("tt0903747", enriched.id)
            assertEquals("49 min", enriched.runtime)
            assertEquals(1, enriched.episodes.size)
            assertEquals("Sinopsis en español", enriched.description)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun searchCatalogAllCombinesAndDeduplicatesMoviesAndSeries() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"metas": [{"id": "tt111", "type": "movie", "name": "Dune"}]}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"metas": [{"id": "tt222", "type": "series", "name": "Dune: Prophecy"}]}
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-search-all").toFile()
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
            val combined = repo.searchCatalogAll("Dune")
            assertEquals(2, combined.size)
            assertEquals(setOf("tt111", "tt222"), combined.map { it.id }.toSet())
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun getCatalogPagePaginatesJustWatchWithSortGenresAndDailyInvalidation() = runTest {
        val server = MockWebServer()
        // Page 1 (day 1)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "popularTitles": {
                      "totalCount": 120,
                      "pageInfo": {"endCursor": "cursor_1", "hasNextPage": true},
                      "edges": [
                        {
                          "node": {
                            "id": "tm1",
                            "objectType": "MOVIE",
                            "content": {
                              "title": "Película Uno",
                              "originalTitle": "Movie One",
                              "originalReleaseYear": 2026,
                              "externalIds": {"imdbId": "tt1000001"}
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        // Page 2 (cursor_1)
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "popularTitles": {
                      "totalCount": 120,
                      "pageInfo": {"endCursor": "cursor_2", "hasNextPage": false},
                      "edges": [
                        {
                          "node": {
                            "id": "tm2",
                            "objectType": "MOVIE",
                            "content": {
                              "title": "Película Dos",
                              "originalTitle": "Movie Two",
                              "originalReleaseYear": 2025,
                              "externalIds": {"imdbId": "tt1000002"}
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-jw-paging").toFile()
        var currentDay = "2026-11-17"
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

            val genre = CatalogGenreRegistry.findById("act")!!
            val p1 = repo.getCatalogPage(type = "movie", feed = CatalogFeed.TrendingToday, genre = genre, cursor = null)
            assertEquals(1, p1.items.size)
            assertEquals("jw:cursor_1", p1.nextCursor)
            assertEquals(true, p1.hasMore)
            assertEquals(120, p1.totalCount)

            val req1 = server.takeRequest().body.readUtf8()
            assert(req1.contains(""""sortBy":"TRENDING"""") || req1.contains("TRENDING"))
            assert(req1.contains(""""act""""))

            // Same day -> page 0 comes from disk cache without hitting network
            val p1Cached = repo.getCatalogPage(type = "movie", feed = CatalogFeed.TrendingToday, genre = genre, cursor = null)
            assertEquals("Película Uno", p1Cached.items.first().title)
            assertEquals(1, server.requestCount)

            // Fetch page 2 using cursor_1
            val p2 = repo.getCatalogPage(type = "movie", feed = CatalogFeed.TrendingToday, genre = genre, cursor = p1.nextCursor)
            assertEquals(1, p2.items.size)
            assertEquals("Película Dos", p2.items.first().title)
            assertEquals(false, p2.hasMore)
            val req2 = server.takeRequest().body.readUtf8()
            assert(req2.contains(""""after":"cursor_1""""))

            // Network error on cursor != null must throw rather than corrupting cursor with Cinemeta fallback
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
            }
            val threwOnPageError = runCatching {
                repo.getCatalogPage(type = "movie", feed = CatalogFeed.Popular, genre = genre, cursor = "jw:broken_cursor")
            }.isFailure
            assertTrue("Expected cursor pagination failure to throw", threwOnPageError)

            // Advance calendar day -> cache invalidates automatically and returns day 2 content
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "data": {
                        "popularTitles": {
                          "totalCount": 125,
                          "pageInfo": {"endCursor": "cursor_day2", "hasNextPage": true},
                          "edges": [
                            {
                              "node": {
                                "id": "tm3",
                                "objectType": "MOVIE",
                                "content": {
                                  "title": "Película Día 2",
                                  "originalTitle": "Movie Day 2",
                                  "originalReleaseYear": 2026,
                                  "externalIds": {"imdbId": "tt1000003"}
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            )
            currentDay = "2026-11-18"
            val p1NextDay = repo.getCatalogPage(type = "movie", feed = CatalogFeed.TrendingToday, genre = genre, cursor = null)
            assertEquals("Película Día 2", p1NextDay.items.first().title)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun getCatalogPagePaginatesCinemetaSkipAndGenreAndTmdbDaily() = runTest {
        val server = MockWebServer()
        // Cinemeta page 1 (50 items so hasMore = true)
        val cmItems1 = (1..50).joinToString(",") { i ->
            """{"id":"tt2000$i","type":"movie","name":"CM Movie $i","releaseInfo":"2026"}"""
        }
        server.enqueue(MockResponse().setBody("""{"metas":[$cmItems1]}"""))
        // Cinemeta page 2 (10 items -> hasMore = false)
        server.enqueue(
            MockResponse().setBody(
                """{"metas":[{"id":"tt200099","type":"movie","name":"CM Movie 51","releaseInfo":"2025"}]}""",
            ),
        )
        // TMDB daily trending page 1
        server.enqueue(
            MockResponse().setBody(
                """{"page":1,"total_pages":5,"total_results":100,"results":[{"id":701,"title":"TMDB Hoy","original_title":"TMDB Today","release_date":"2026-11-01","poster_path":"/a.jpg"}]}""",
            ),
        )
        server.start()
        val cacheDir = Files.createTempDirectory("meta-cm-tmdb").toFile()
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
                catalogRegion = CatalogRegion.GlobalEnglish
            }

            val action = CatalogGenreRegistry.findById("act")!!
            val cm1 = repo.getCatalogPage("movie", CatalogFeed.Popular, genre = action, cursor = null)
            assertEquals(50, cm1.items.size)
            assertEquals(true, cm1.hasMore)
            assertEquals("cm:50", cm1.nextCursor)
            val cmReq1 = server.takeRequest().path.orEmpty()
            assert(cmReq1.contains("/catalog/movie/top/genre=Action.json"))

            val cm2 = repo.getCatalogPage("movie", CatalogFeed.Popular, genre = action, cursor = cm1.nextCursor)
            assertEquals(1, cm2.items.size)
            assertEquals(false, cm2.hasMore)
            val cmReq2 = server.takeRequest().path.orEmpty()
            assert(cmReq2.contains("genre=Action") && cmReq2.contains("skip=50"))

            repo.catalogRegion = CatalogRegion.MexicoLatino
            repo.tmdbApiKey = "tmdb-key"
            val tmdb1 = repo.getCatalogPage("movie", CatalogFeed.TrendingToday, genre = null, cursor = null)
            assertEquals("TMDB Hoy", tmdb1.items.first().title)
            assertEquals(true, tmdb1.hasMore)
            assertEquals("tmdb:2", tmdb1.nextCursor)
            val tmdbReq = server.takeRequest().path.orEmpty()
            assert(tmdbReq.contains("/trending/movie/day"))
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun enrichCatalogMediaDetailsLocalizesSeriesEpisodesFromTmdbAndFallsBackToTvMaze() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/meta/series/tt1000999.json") -> MockResponse().setBody(
                        """
                        {
                          "meta": {
                            "id": "tt1000999",
                            "type": "series",
                            "name": "Dark Matter",
                            "runtime": "50 min",
                            "videos": [
                              {"id": "e1", "season": 1, "episode": 1, "title": "Are You Happy in Your Life?", "released": "2024-05-08"}
                            ]
                          }
                        }
                        """.trimIndent(),
                    )
                    path.contains("/find/tt1000999") -> MockResponse().setBody(
                        """{"tv_results":[{"id": 196322, "name": "Materia Oscura"}]}""",
                    )
                    path.contains("/tv/196322/season/1") -> MockResponse().setBody(
                        """
                        {
                          "episodes": [
                            {
                              "episode_number": 1,
                              "name": "¿Eres feliz con tu vida?",
                              "overview": "Resumen en español del episodio uno.",
                              "air_date": "2024-05-08",
                              "still_path": "/still1.jpg"
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                    path.contains("/tv/196322?") || path.contains("/tv/196322/") -> MockResponse().setBody(
                        """
                        {
                          "name": "Materia Oscura",
                          "original_name": "Dark Matter",
                          "seasons": [{"season_number": 1, "episode_count": 1}]
                        }
                        """.trimIndent(),
                    )
                    path.contains("/meta/series/tt2000888.json") -> MockResponse().setBody(
                        """{"meta": {"id": "tt2000888", "type": "series", "name": "Regional Show"}}""",
                    )
                    path.contains("/lookup/shows?imdb=tt2000888") -> MockResponse().setBody(
                        """{"id": 9001, "name": "Regional Show"}""",
                    )
                    path.contains("/shows/9001/episodes") -> MockResponse().setBody(
                        """
                        [
                          {
                            "id": 1,
                            "season": 1,
                            "number": 1,
                            "name": "Capítulo Uno TVmaze",
                            "airdate": "2025-02-01",
                            "summary": "<p>Sinopsis desde TVmaze.</p>",
                            "image": {"medium": "https://tvmaze.invalid/ep1.jpg"}
                          }
                        ]
                        """.trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        val cacheDir = Files.createTempDirectory("meta-series-episodes").toFile()
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
                tmdbApiKey = "tmdb-test-key"
            }

            // Case 1: Cinemeta has English episode -> TMDB localizes title + overview in Spanish
            val localized = repo.enrichCatalogMediaDetails(
                MediaMetadata(id = "tt1000999", type = "series", title = "Materia Oscura", originalTitle = "Dark Matter"),
            )
            assertEquals(1, localized.episodes.size)
            assertEquals("¿Eres feliz con tu vida?", localized.episodes.first().title)
            assertEquals("Resumen en español del episodio uno.", localized.episodes.first().overview)

            // Case 2: Cinemeta lacks videos -> falls back to TVmaze episodes automatically
            repo.tmdbApiKey = null
            val fallback = repo.enrichCatalogMediaDetails(
                MediaMetadata(id = "tt2000888", type = "series", title = "Regional Show"),
            )
            assertEquals(1, fallback.episodes.size)
            assertEquals("Capítulo Uno TVmaze", fallback.episodes.first().title)
            assertEquals("Sinopsis desde TVmaze.", fallback.episodes.first().overview)
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }
}
