package org.cinito.online.search

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.network.DohProvider
import org.cinito.online.network.HardenedNetworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchServiceTest {
    private class FakeProvider(
        override val info: SearchProviderInfo,
        private val action: suspend () -> List<TorrentSearchResult>,
    ) : TorrentSearchProvider {
        override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> = action()
    }

    @Test
    fun aggregatesDeduplicatesAndSurvivesSingleProviderFailure() = runTest {
        val sharedHash = "0123456789abcdef0123456789abcdef01234567"
        val r1 = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "YTS",
            title = "Inception 2010 1080p BluRay",
            magnetUri = SearchUtils.buildMagnet(sharedHash, "Inception"),
            seeders = 40,
            posterUrl = "https://example.org/poster.jpg",
        )
        val r2 = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Inception.2010.1080p.BluRay",
            magnetUri = SearchUtils.buildMagnet(sharedHash, "Inception"),
            seeders = 180,
            imdbId = "tt1375666",
        )
        val r3 = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Inception 2010 2160p UHD",
            magnetUri = SearchUtils.buildMagnet("ffffffffffffffffffffffffffffffffffffffff", "Inception 4K"),
            seeders = 90,
        )

        val pFast = FakeProvider(
            SearchProviderInfo("yts", "YTS", "https://yts.bz", setOf(SearchCategory.Movies)),
        ) {
            delay(10)
            listOf(r1)
        }
        val pBroken = FakeProvider(
            SearchProviderInfo("broken", "Broken", "https://broken.invalid", setOf(SearchCategory.Movies)),
        ) {
            error("HTTP 503")
        }
        val pMore = FakeProvider(
            SearchProviderInfo("knaben", "Knaben", "https://knaben.org", setOf(SearchCategory.Movies)),
        ) {
            delay(30)
            listOf(r2, r3)
        }

        val service = SearchService(listOf(pFast, pBroken, pMore))
        val emissions = service.searchStreaming(
            query = "Inception",
            category = SearchCategory.Movies,
            enabledProviderIds = setOf("yts", "broken", "knaben"),
            torznabConfigs = emptyList(),
        ).toList()

        val finalBatch = emissions.last()
        assertTrue(finalBatch.isFinished)
        assertEquals(3, finalBatch.totalProviders)
        assertEquals(1, finalBatch.failures.size)
        assertEquals("broken", finalBatch.failures.first().providerId)
        assertEquals(2, finalBatch.results.size)
        // Higher seeders result merged and kept posterUrl from r1 + imdbId from r2
        val merged = finalBatch.results.first()
        assertEquals(180, merged.seeders)
        assertEquals("https://example.org/poster.jpg", merged.posterUrl)
        assertEquals("tt1375666", merged.imdbId)
    }

    @Test
    fun torznabConfigStorePersistsWithoutExposingInvalidInput() {
        val map = mutableMapOf<String, String>()
        val store = TorznabConfigStore(object : SecretStorage {
            override fun saveString(key: String, value: String) { map[key] = value }
            override fun readString(key: String): String? = map[key]
        })
        val added = store.addOrUpdate("Mi Prowlarr", "indexer.local:9696", "secret-key", setOf(SearchCategory.Movies))
        assertEquals("https://indexer.local:9696", added?.url)
        assertEquals(1, store.configs.value.size)

        val reloaded = TorznabConfigStore(object : SecretStorage {
            override fun saveString(key: String, value: String) { map[key] = value }
            override fun readString(key: String): String? = map[key]
        })
        assertEquals("secret-key", reloaded.configs.value.first().apiKey)
    }

    @Test
    fun buildsBilingualLatinoQueriesFiltersOnlyLatinoAndRanksLatinoFirst() = runTest {
        val prioQueries = SearchService.buildQueriesForProvider(
            primary = "Duna: Parte Dos",
            alternate = "Dune: Part Two",
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            supportsNonEnglish = true,
        )
        assertEquals(
            listOf("Dune: Part Two Latino", "Dune: Part Two", "Duna: Parte Dos Latino"),
            prioQueries,
        )

        val colonFallbackQueries = SearchService.buildQueriesForProvider(
            primary = "Wildwood: El bosque salvaje",
            alternate = null,
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            supportsNonEnglish = true,
        )
        assertEquals(
            listOf("Wildwood Latino", "Wildwood", "Wildwood: El bosque salvaje Latino"),
            colonFallbackQueries,
        )

        val onlyLatinoQueries = SearchService.buildQueriesForProvider(
            primary = "Duna",
            alternate = "Dune",
            languageFilter = SearchLanguageFilter.OnlyLatino,
            supportsNonEnglish = true,
        )
        assertEquals(listOf("Dune Latino", "Duna Latino", "Dune Dual"), onlyLatinoQueries)

        val englishOnlyProviderQueries = SearchService.buildQueriesForProvider(
            primary = "Duna",
            alternate = "Dune",
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            supportsNonEnglish = false,
        )
        assertEquals(listOf("Dune"), englishOnlyProviderQueries)

        val rEnglish = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "YTS",
            title = "Dune Part Two 2024 1080p",
            magnetUri = SearchUtils.buildMagnet("1111111111111111111111111111111111111111", "Dune"),
            seeders = 900,
        )
        val rCastellano = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Dune Parte Dos 2024 Castellano 1080p",
            magnetUri = SearchUtils.buildMagnet("2222222222222222222222222222222222222222", "Dune Cast"),
            seeders = 300,
        )
        val rLatino = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Duna Parte Dos 2024 1080p Dual Audio Latino",
            magnetUri = SearchUtils.buildMagnet("3333333333333333333333333333333333333333", "Dune Lat"),
            seeders = 45,
        )

        val prioritized = SearchService.applyLanguageFilterAndSort(
            listOf(rEnglish, rCastellano, rLatino),
            SearchSortOrder.SeedersDesc,
            SearchLanguageFilter.PrioritizeLatino,
        )
        assertEquals(AudioLanguageTag.Latino, prioritized.first().languageTag)

        val onlyLatino = SearchService.applyLanguageFilterAndSort(
            listOf(rEnglish, rCastellano, rLatino),
            SearchSortOrder.SeedersDesc,
            SearchLanguageFilter.OnlyLatino,
        )
        assertEquals(1, onlyLatino.size)
        assertEquals(AudioLanguageTag.Latino, onlyLatino.first().languageTag)

        // When filtering OnlyLatino, providers with supportsNonEnglishQueries=false are not queried
        var ytsQueried = false
        val ytsProvider = FakeProvider(
            SearchProviderInfo(
                id = "yts",
                name = "YTS",
                url = "https://yts.bz",
                categories = setOf(SearchCategory.Movies),
                supportsNonEnglishQueries = false,
            ),
        ) {
            ytsQueried = true
            listOf(rEnglish)
        }
        val knabenProvider = FakeProvider(
            SearchProviderInfo(
                id = "knaben",
                name = "Knaben",
                url = "https://knaben.org",
                categories = setOf(SearchCategory.Movies),
                supportsNonEnglishQueries = true,
            ),
        ) {
            listOf(rLatino)
        }
        val service = SearchService(listOf(ytsProvider, knabenProvider))
        val batch = service.searchStreaming(
            query = "Duna",
            category = SearchCategory.Movies,
            enabledProviderIds = setOf("yts", "knaben"),
            torznabConfigs = emptyList(),
            languageFilter = SearchLanguageFilter.OnlyLatino,
        ).toList().last()
        assertFalse(ytsQueried)
        assertEquals(1, batch.results.size)
    }

    @Test
    fun thePirateBayParsesAndEnrichesAmbiguousDualViaNfo() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "id": "777",
                    "name": "Duna Parte Dos 2024 1080p Dual Español",
                    "info_hash": "4444444444444444444444444444444444444444",
                    "leechers": "12",
                    "seeders": "64",
                    "size": "2147483648",
                    "category": "207",
                    "added": "1710000000",
                    "imdb": "tt15239678"
                  }
                ]
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": 777,
                  "name": "Duna Parte Dos 2024 1080p Dual Español",
                  "descr": "Audio 1: Español Latino AC3 5.1\nAudio 2: Inglés AAC",
                  "language": "Spanish",
                  "textlanguage": "Spanish",
                  "imdb": "tt15239678"
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val provider = ThePirateBayProvider(HardenedNetworkClient(DohProvider.System), apiBaseUrl = base)
            val hits = provider.search("Duna Latino", SearchCategory.Movies)
            assertEquals(1, hits.size)
            val initial = hits.first()
            assertEquals(AudioLanguageTag.SpanishGeneric, initial.languageTag)
            assertEquals("777", initial.sourceRecordId)
            assertEquals(64, initial.seeders)

            val enriched = provider.enrichResult(initial)
            assertEquals(AudioLanguageTag.Latino, enriched.languageTag)
            assertTrue(enriched.description?.contains("Español Latino") == true)
            assertEquals("tt15239678", enriched.imdbId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun bitSearchParsesResultCardsAndLanguageTag() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                <html><body>
                  <div class="space-y-4">
                    <div class="torrent-result-card">
                      <div>
                        <div>
                          <h3><a href="/torrent/duna-latino">Duna Parte Dos 2024 1080p Audio Latino</a></h3>
                          <div class="torrent-result-meta">
                            <span><i></i><span>Movie</span></span>
                            <span><i class="fas fa-download"></i><span>3.2 GB</span></span>
                          </div>
                          <div class="torrent-result-meta">
                            <span class="text-green-600"><i class="fas fa-arrow-up"></i><span>58</span><span>seeders</span></span>
                            <span class="text-red-600"><i class="fas fa-arrow-down"></i><span>11</span><span>leechers</span></span>
                          </div>
                        </div>
                        <div>
                          <a href="magnet:?xt=urn:btih:5555555555555555555555555555555555555555&dn=Duna">Magnet</a>
                        </div>
                      </div>
                    </div>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val provider = BitSearchProvider(HardenedNetworkClient(DohProvider.System), baseUrl = base)
            val hits = provider.search("Duna Latino", SearchCategory.Movies)
            assertEquals(1, hits.size)
            val hit = hits.first()
            assertEquals("Duna Parte Dos 2024 1080p Audio Latino", hit.title)
            assertEquals(AudioLanguageTag.Latino, hit.languageTag)
            assertEquals(58, hit.seeders)
            assertEquals(11, hit.peers)
            assertEquals("3.2 GB", hit.sizeText)
            assertEquals("5555555555555555555555555555555555555555", hit.infoHash)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun torrentioProviderParsesCinecalidadLatinoAndCastellanoFlags() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "streams": [
                    {
                      "name": "Torrentio\nCAM",
                      "title": "Wildwood.2026.1080p.CAMRip.LAT.DUB.1XBET.mp4\n👤 5 💾 3.56 GB ⚙️ Cinecalidad\n🇲🇽",
                      "infoHash": "7d6090d46fb99f2db33f173bf7b4d2eddd843420"
                    },
                    {
                      "name": "Torrentio\n1080p",
                      "title": "Pelicula.2024.1080p.HDRip\n👤 14 💾 2.10 GB ⚙️ Wolfmax4K\n🇪🇸",
                      "infoHash": "6666666666666666666666666666666666666666",
                      "fileIdx": 0
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val provider = TorrentioProvider(HardenedNetworkClient(DohProvider.System), baseUrl = base)
            val hits = provider.searchByImdb("tt2056785", SearchCategory.Movies, mediaTypeHint = "movie")
            assertEquals(2, hits.size)

            val lat = hits[0]
            assertEquals("Torrentio · Cinecalidad", lat.providerName)
            assertEquals(AudioLanguageTag.Latino, lat.languageTag)
            assertEquals(5, lat.seeders)
            assertEquals("3.56 GB", lat.sizeText)
            assertEquals("tt2056785", lat.imdbId)
            assertEquals("7d6090d46fb99f2db33f173bf7b4d2eddd843420", lat.infoHash)

            val cast = hits[1]
            assertEquals("Torrentio · Wolfmax4K", cast.providerName)
            assertEquals(AudioLanguageTag.Castellano, cast.languageTag)

            val recorded = server.takeRequest()
            assertEquals("/language=latino/stream/movie/tt2056785.json", recorded.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun providersTreatSubtitleOnlyHintsAsSubSpanishNotAudioLatino() = runTest {
        val server = MockWebServer()
        // Response 1: TPB search hit
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "id": "888",
                    "name": "English.Movie.2025.1080p.WEBRip",
                    "info_hash": "8888888888888888888888888888888888888888",
                    "leechers": "5",
                    "seeders": "40",
                    "size": "1500000000",
                    "category": "207",
                    "added": "1710000000",
                    "imdb": "tt9999999"
                  }
                ]
                """.trimIndent(),
            ),
        )
        // Response 2: TPB NFO details with subtitle-only Spanish (`textlanguage`), no audio `language`
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id": 888,
                  "name": "English.Movie.2025.1080p.WEBRip",
                  "descr": "Audio: English AAC 2.0",
                  "language": "",
                  "textlanguage": "Spanish",
                  "imdb": "tt9999999"
                }
                """.trimIndent(),
            ),
        )
        // Response 3: Torrentio stream marked Subbed with Mexico flag
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "streams": [
                    {
                      "name": "Torrentio\n1080p",
                      "title": "English.Movie.2025.1080p.WEB.Subbed.x264\n👤 22 💾 1.80 GB ⚙️ YTS\n🇲🇽",
                      "infoHash": "9999999999999999999999999999999999999999"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val client = HardenedNetworkClient(DohProvider.System)
            val tpb = ThePirateBayProvider(client, apiBaseUrl = base)
            val hit = tpb.search("English Movie", SearchCategory.Movies).first()
            val enriched = tpb.enrichResult(hit)
            assertEquals(AudioLanguageTag.SubSpanish, enriched.languageTag)

            val torrentio = TorrentioProvider(client, baseUrl = base)
            val streams = torrentio.searchByImdb("tt9999999", SearchCategory.Movies, mediaTypeHint = "movie")
            assertEquals(1, streams.size)
            assertEquals(AudioLanguageTag.SubSpanish, streams.first().languageTag)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun solidTorrentsProviderParsesJsonAndFiltersExecutableSpam() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "success": true,
                  "results": [
                    {
                      "id": "1",
                      "infohash": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                      "title": "Inside.Out.2.2024.1080p.WEB-DL.ENG.LATINO",
                      "size": 3221225472,
                      "category": 2,
                      "seeders": 202,
                      "leechers": 19
                    },
                    {
                      "id": "2",
                      "infohash": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                      "title": "coco latino - Downloader.exe",
                      "size": 1024000,
                      "category": 1,
                      "seeders": 0,
                      "leechers": 0
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val provider = SolidTorrentsProvider(HardenedNetworkClient(DohProvider.System), baseUrl = base)
            val hits = provider.search("Inside Out 2 Latino", SearchCategory.Movies)
            assertEquals(1, hits.size)
            val hit = hits.first()
            assertEquals(AudioLanguageTag.Latino, hit.languageTag)
            assertEquals(202, hit.seeders)
            assertEquals(19, hit.peers)
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", hit.infoHash)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun limeTorrentsProviderIgnoresSponsoredTableAndExtractsMagnetHash() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                <html><body>
                  <table>
                    <tr><th>Sponsored Links</th></tr>
                    <tr>
                      <td class="tdleft"><div class="tt-name"><a href="/leet2/?search=1">Sponsored Fast Download</a></div></td>
                    </tr>
                  </table>
                  <table class="table2">
                    <tr><th>Torrent Name</th><th>Added</th><th>Size</th><th>Seed</th><th>Leech</th></tr>
                    <tr>
                      <td class="tdleft">
                        <div class="tt-name">
                          <a href="http://itorrents.net/torrent/42021E6763DDA0C3883D148F3CA9E42A1A66ACF0.torrent?title=Coco"></a>
                          <a href="/Coco-2017-Latino-torrent-1.html">Coco 2017 DVDRip Latino</a>
                        </div>
                      </td>
                      <td class="tdnormal">1 Year+ - in Movies</td>
                      <td class="tdnormal">1.68 GB</td>
                      <td class="tdseed">22</td>
                      <td class="tdleech">3</td>
                    </tr>
                  </table>
                </body></html>
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val provider = LimeTorrentsProvider(HardenedNetworkClient(DohProvider.System), baseUrl = base)
            val hits = provider.search("Coco Latino", SearchCategory.Movies)
            assertEquals(1, hits.size)
            val hit = hits.first()
            assertEquals("Coco 2017 DVDRip Latino", hit.title)
            assertEquals(AudioLanguageTag.Latino, hit.languageTag)
            assertEquals(22, hit.seeders)
            assertEquals(3, hit.peers)
            assertEquals("1.68 GB", hit.sizeText)
            assertEquals("42021e6763dda0c3883d148f3ca9e42a1a66acf0", hit.infoHash)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun searchServiceResolvesBilingualImdbContextAndRespectsMaxQueriesPerProvider() = runTest {
        val capturedTextQueries = mutableListOf<String>()
        var capturedImdb: String? = null

        val imdbProvider = object : TorrentSearchProvider {
            override val info = SearchProviderInfo(
                id = "torrentio",
                name = "Torrentio · Cinecalidad+",
                url = "https://torrentio.strem.fun",
                categories = setOf(SearchCategory.Movies, SearchCategory.Series),
                supportsImdbSearch = true,
            )
            override suspend fun search(query: String, category: SearchCategory) = emptyList<TorrentSearchResult>()
            override suspend fun searchByImdb(
                imdbId: String,
                category: SearchCategory,
                season: Int?,
                episode: Int?,
                mediaTypeHint: String?,
            ): List<TorrentSearchResult> {
                capturedImdb = imdbId
                return listOf(
                    SearchUtils.makeResult(
                        providerId = info.id,
                        providerName = "Torrentio · Cinecalidad",
                        title = "Wildwood.2026.1080p.LAT.DUB.mp4",
                        magnetUri = SearchUtils.buildMagnet("7d6090d46fb99f2db33f173bf7b4d2eddd843420", "Wildwood"),
                        seeders = 5,
                        imdbId = imdbId,
                        description = "Fuente: Cinecalidad · 🇲🇽",
                    ),
                )
            }
        }

        val cappedProvider = object : TorrentSearchProvider {
            override val info = SearchProviderInfo(
                id = "bitsearch",
                name = "BitSearch",
                url = "https://bitsearch.to",
                categories = setOf(SearchCategory.Movies),
                maxQueriesPerSearch = 1,
            )
            override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
                capturedTextQueries += query
                return emptyList()
            }
        }

        val service = SearchService(
            builtinProviders = listOf(imdbProvider, cappedProvider),
            titleResolver = { _, _ ->
                SearchTitleResolution(
                    displayTitle = "Wildwood: El bosque salvaje",
                    originalTitle = "Wildwood",
                    year = 2026,
                    imdbId = "tt2056785",
                    mediaType = "movie",
                )
            },
        )

        val finalBatch = service.searchStreaming(
            query = "Wildwood: El bosque salvaje",
            category = SearchCategory.Movies,
            enabledProviderIds = setOf("torrentio", "bitsearch"),
            torznabConfigs = emptyList(),
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
        ).toList().last()

        assertEquals("tt2056785", capturedImdb)
        assertEquals("Wildwood", finalBatch.resolvedContext?.originalTitle)
        assertEquals(listOf("Wildwood Latino"), capturedTextQueries)
        assertEquals(1, finalBatch.results.size)
        assertEquals(AudioLanguageTag.Latino, finalBatch.results.first().languageTag)
    }

    @Test
    fun searchStreamingHonorsExplicitSeasonEpisodeHintsAndSkipTitleResolution() = runTest {
        var resolverCalled = false
        var capturedSeason: Int? = null
        var capturedEpisode: Int? = null

        val imdbProvider = object : TorrentSearchProvider {
            override val info = SearchProviderInfo(
                id = "torrentio",
                name = "Torrentio",
                url = "https://torrentio.strem.fun",
                categories = setOf(SearchCategory.Series),
                supportsImdbSearch = true,
            )
            override suspend fun search(query: String, category: SearchCategory) = emptyList<TorrentSearchResult>()
            override suspend fun searchByImdb(
                imdbId: String,
                category: SearchCategory,
                season: Int?,
                episode: Int?,
                mediaTypeHint: String?,
            ): List<TorrentSearchResult> {
                capturedSeason = season
                capturedEpisode = episode
                return emptyList()
            }
        }

        val service = SearchService(
            builtinProviders = listOf(imdbProvider),
            titleResolver = { _, _ ->
                resolverCalled = true
                null
            },
        )

        service.searchStreaming(
            query = "Breaking Bad",
            category = SearchCategory.Series,
            enabledProviderIds = setOf("torrentio"),
            torznabConfigs = emptyList(),
            imdbId = "tt0903747",
            seasonHint = 3,
            episodeHint = 7,
            skipTitleResolution = true,
        ).toList()

        assertFalse(resolverCalled)
        assertEquals(3, capturedSeason)
        assertEquals(7, capturedEpisode)
    }

    @Test
    fun buildQueriesIncludesYearlessFallbackAndYtsSupportsImdbWithoutBlockingTextFallback() = kotlinx.coroutines.runBlocking {
        val catalogMovieQueries = SearchService.buildQueriesForProvider(
            primary = "Duna: Parte Dos 2024",
            alternate = "Dune: Part Two 2024",
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            supportsNonEnglish = true,
            maxQueries = 3,
        )
        assertTrue(
            "Expected yearless fallback without colon in $catalogMovieQueries",
            catalogMovieQueries.any { !it.contains("2024") && !it.contains(":") },
        )

        val localizedQueries = SearchService.buildQueriesForProvider(
            primary = "Intensamente 2 2024",
            alternate = "Inside Out 2 2024",
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            supportsNonEnglish = true,
            maxQueries = 3,
        )
        assertTrue(
            "Expected Spanish yearless Latino query within maxQueries=3: $localizedQueries",
            localizedQueries.contains("Intensamente 2 Latino"),
        )

        val colonOnlyQueries = SearchService.buildQueriesForProvider(
            primary = "Wildwood: El bosque salvaje 2026",
            alternate = null,
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            supportsNonEnglish = true,
            maxQueries = 3,
        )
        assertTrue(
            "Expected colon-stripped Latino query: $colonOnlyQueries",
            colonOnlyQueries.contains("Wildwood Latino"),
        )

        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "data": {
                    "movies": [
                      {
                        "title_long": "Dune: Part Two (2024)",
                        "imdb_code": "tt15239678",
                        "torrents": [
                          {
                            "hash": "1234567890abcdef1234567890abcdef12345678",
                            "quality": "1080p",
                            "type": "web",
                            "video_codec": "x264",
                            "size": "2.4 GB",
                            "size_bytes": 2576980377,
                            "seeds": 120,
                            "peers": 15
                          }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val yts = YtsProvider(HardenedNetworkClient(DohProvider.System), apiBaseUrl = base)
            val service = SearchService(listOf(yts))
            val batch = service.searchStreaming(
                query = "Duna: Parte Dos 2024",
                category = SearchCategory.Movies,
                enabledProviderIds = setOf("yts"),
                torznabConfigs = emptyList(),
                imdbId = "tt15239678",
                skipTitleResolution = true,
            ).toList().last()

            assertEquals("Failures: ${batch.failures}", 1, batch.results.size)
            val req = server.takeRequest()
            assertTrue("Expected YTS IMDb query, got ${req.path}", req.path?.contains("query_term=tt15239678") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tpbSeriesUsesVideoCategoryToIncludeHdTvAndTorrentioSkipsPackWithNullEpisode() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "id": "101",
                    "name": "Breaking Bad S01 1080p BluRay",
                    "info_hash": "1111111111111111111111111111111111111111",
                    "seeders": "80",
                    "leechers": "5",
                    "size": "15000000000",
                    "category": "208"
                  },
                  {
                    "id": "102",
                    "name": "Breaking Bad El Camino 2019 Movie",
                    "info_hash": "2222222222222222222222222222222222222222",
                    "seeders": "50",
                    "leechers": "3",
                    "size": "4000000000",
                    "category": "207"
                  }
                ]
                """.trimIndent(),
            ),
        )
        server.start()
        try {
            val base = server.url("").toString().trimEnd('/')
            val tpb = ThePirateBayProvider(HardenedNetworkClient(DohProvider.System), apiBaseUrl = base)
            val seriesHits = tpb.search("Breaking Bad", SearchCategory.Series)
            val req = server.takeRequest()
            assertTrue("Expected cat=200 to include HD TV 208, got ${req.path}", req.path?.contains("cat=200") == true)
            assertEquals(1, seriesHits.size)
            assertEquals("101", seriesHits.first().sourceRecordId)

            val torrentio = TorrentioProvider(HardenedNetworkClient(DohProvider.System), baseUrl = base)
            val packHits = torrentio.searchByImdb(
                imdbId = "tt0903747",
                category = SearchCategory.Series,
                season = 1,
                episode = null,
                mediaTypeHint = "series",
            )
            assertTrue("Pack search without episode must not falsely return S01E01", packHits.isEmpty())
        } finally {
            server.shutdown()
        }
    }
}
