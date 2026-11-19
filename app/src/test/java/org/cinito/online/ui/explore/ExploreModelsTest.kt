package org.cinito.online.ui.explore

import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.CatalogGenreRegistry
import org.cinito.online.metadata.MediaEpisode
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import webtor.app.DownloadEntry
import webtor.app.SavedFile

class ExploreModelsTest {

    @Test
    fun buildCatalogSourceQueryFormatsMovieAndSeriesTargetsCorrectly() {
        val movie = MediaMetadata(
            id = "tt15239678",
            type = "movie",
            title = "Duna: Parte Dos",
            originalTitle = "Dune: Part Two",
            year = 2024,
        )
        val movieQuery = buildCatalogSourceQuery(movie, season = null, episode = null)
        assertEquals("Duna: Parte Dos 2024", movieQuery.query)
        assertEquals("Dune: Part Two 2024", movieQuery.alternateQuery)
        assertEquals("tt15239678", movieQuery.imdbId)
        assertEquals(SearchCategory.Movies, movieQuery.category)

        val sameTitleMovie = movie.copy(title = "Inception", originalTitle = "Inception", year = 2010)
        assertNull(buildCatalogSourceQuery(sameTitleMovie, null, null).alternateQuery)

        val series = MediaMetadata(
            id = "tt0903747",
            type = "series",
            title = "Breaking Bad",
            originalTitle = "Breaking Bad",
            episodes = listOf(MediaEpisode("ep1", season = 2, episode = 5, title = "Breakage")),
        )
        val epQuery = buildCatalogSourceQuery(series, season = 2, episode = 5)
        assertEquals("Breaking Bad S02E05", epQuery.query)
        assertEquals(2, epQuery.season)
        assertEquals(5, epQuery.episode)
        assertEquals(SearchCategory.Series, epQuery.category)

        val packQuery = buildCatalogSourceQuery(series, season = 2, episode = null)
        assertEquals("Breaking Bad S02", packQuery.query)
        assertEquals(2, packQuery.season)
        assertNull(packQuery.episode)
    }

    @Test
    fun extractGenresAndFilterCatalogInterleavesAndFiltersByGenre() {
        val movies = listOf(
            MediaMetadata("tt1", "movie", "Movie 1", genres = listOf("Acción", "Drama")),
            MediaMetadata("tt2", "movie", "Movie 2", genres = listOf("Comedia")),
        )
        val series = listOf(
            MediaMetadata("tt3", "series", "Series 1", genres = listOf("Drama", "Sci-Fi")),
        )

        val genres = extractAvailableGenres(movies, series, DiscoverCategoryTab.All)
        assertEquals("Drama", genres.first())
        assertTrue("Acción" in genres)

        val allItems = filterCatalogItems(movies, series, DiscoverCategoryTab.All, selectedGenre = null)
        assertEquals(listOf("tt1", "tt3", "tt2"), allItems.map { it.id })

        val dramaOnly = filterCatalogItems(movies, series, DiscoverCategoryTab.All, selectedGenre = "drama")
        assertEquals(listOf("tt1", "tt3"), dramaOnly.map { it.id })

        val seriesOnly = filterCatalogItems(movies, series, DiscoverCategoryTab.Series, selectedGenre = null)
        assertEquals(listOf("tt3"), seriesOnly.map { it.id })
    }

    @Test
    fun findMatchingLibraryEntryMatchesByHashOrNormalizedTitleAndYear() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val entry = DownloadEntry(
            key = hash,
            title = "Dune.Part.Two.2024.1080p.WEB-DL.Latino",
            source = "magnet:?xt=urn:btih:$hash",
            metadata = "",
            engineId = null,
            destination = "Direct Play",
            files = listOf(SavedFile(0, "Dune.Part.Two.2024.mkv", "Dune.Part.Two.2024.mkv", 2_000_000L)),
            selected = setOf(0),
        )
        val movie = MediaMetadata(
            id = "tt15239678",
            type = "movie",
            title = "Duna: Parte Dos",
            originalTitle = "Dune: Part Two",
            year = 2024,
        )

        assertNotNull(findMatchingLibraryEntry(movie, listOf(entry)))
        assertNull(findMatchingLibraryEntry(movie.copy(year = 2021), listOf(entry)))

        val hashResult = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "YTS",
            title = "Different Display Title",
            magnetUri = SearchUtils.buildMagnet(hash, "Different"),
            seeders = 20,
        )
        assertTrue(isResultInLibrary(hashResult, listOf(entry)))
        assertNotNull(
            findMatchingLibraryEntry(
                MediaMetadata("tt99", "movie", "Unrelated Title"),
                listOf(entry),
                listOf(hashResult),
            ),
        )
        assertFalse(
            isResultInLibrary(
                hashResult.copy(
                    infoHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    magnetUri = SearchUtils.buildMagnet("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "Other"),
                ),
                listOf(entry),
            ),
        )
    }

    @Test
    fun pickBestAutoStreamResultPrefersHealthyLatino1080pOverHugeOrDeadTorrents() {
        val deadLatino = SearchUtils.makeResult(
            providerId = "torrentio",
            providerName = "Torrentio",
            title = "Dune Part Two 2024 1080p Latino",
            magnetUri = SearchUtils.buildMagnet("1111111111111111111111111111111111111111", "a"),
            sizeBytes = 2_000_000_000L,
            seeders = 0,
            providerLanguageField = "🇲🇽",
        )
        val hugeRemuxEnglish = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Dune Part Two 2024 2160p REMUX",
            magnetUri = SearchUtils.buildMagnet("2222222222222222222222222222222222222222", "b"),
            sizeBytes = 65_000_000_000L,
            seeders = 500,
        )
        val healthyLatino1080 = SearchUtils.makeResult(
            providerId = "torrentio",
            providerName = "Torrentio · Cinecalidad",
            title = "Dune Part Two 2024 1080p Dual Latino",
            magnetUri = SearchUtils.buildMagnet("3333333333333333333333333333333333333333", "c"),
            sizeBytes = 2_500_000_000L,
            seeders = 45,
            providerLanguageField = "🇲🇽",
        )

        assertEquals(AudioLanguageTag.Latino, healthyLatino1080.languageTag)
        val picked = pickBestAutoStreamResult(
            results = listOf(deadLatino, hugeRemuxEnglish, healthyLatino1080),
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
        )
        assertEquals(healthyLatino1080.id, picked?.id)
    }

    @Test
    fun catalogLibraryIndexMatchesNormalizedTitleAndRespectsYear() {
        val index = buildCatalogLibraryIndex(
            listOf(
                "Dune.Part.Two.2024.1080p.WEB-DL.Latino",
                "Breaking.Bad.S01E01.720p",
            ),
        )
        assertTrue(index.contains(MediaMetadata("tt1", "movie", "Duna: Parte Dos", originalTitle = "Dune: Part Two", year = 2024)))
        assertFalse(index.contains(MediaMetadata("tt1", "movie", "Duna", originalTitle = "Dune: Part Two", year = 2021)))
        assertTrue(index.contains(MediaMetadata("tt2", "series", "Breaking Bad", year = 2008)))
    }

    @Test
    fun pickBestAutoStreamResultPenalizesCamAndLowSeederTorrentsOverHealthyHd() {
        val lowSeedCamLatino = SearchUtils.makeResult(
            providerId = "torrentio",
            providerName = "Torrentio · Cinecalidad",
            title = "Movie 2026 1080p HDCAM Lat",
            magnetUri = SearchUtils.buildMagnet("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "cam"),
            sizeBytes = 1_400_000_000L,
            seeders = 1,
            providerLanguageField = "🇲🇽",
        )
        val healthyWebDlLatino = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Movie 2026 1080p WEB-DL Dual Latino",
            magnetUri = SearchUtils.buildMagnet("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "web"),
            sizeBytes = 2_400_000_000L,
            seeders = 42,
        )
        val healthyEnglishHd = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "YTS",
            title = "Movie 2026 1080p WEB-DL",
            magnetUri = SearchUtils.buildMagnet("cccccccccccccccccccccccccccccccccccccccc", "eng"),
            sizeBytes = 2_100_000_000L,
            seeders = 120,
        )

        val pickedWithWebDl = pickBestAutoStreamResult(
            results = listOf(lowSeedCamLatino, healthyWebDlLatino, healthyEnglishHd),
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
        )
        assertEquals(healthyWebDlLatino.id, pickedWithWebDl?.id)

        val pickedWithoutLatinoWebDl = pickBestAutoStreamResult(
            results = listOf(lowSeedCamLatino, healthyEnglishHd),
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
        )
        assertEquals(healthyEnglishHd.id, pickedWithoutLatinoWebDl?.id)
    }

    @Test
    fun buildCatalogSourceQueryTargetsAnimeCategoryWhenAnimeGenrePresent() {
        val anime = MediaMetadata(
            id = "tt2560140",
            type = "series",
            title = "Attack on Titan",
            genres = listOf("Anime", "Acción"),
        )
        val query = buildCatalogSourceQuery(anime, season = 1, episode = 1)
        assertEquals(SearchCategory.Anime, query.category)
    }

    @Test
    fun selectDailyHighlightsRotatesOrderAcrossDaysAndRespectsMediaArtwork() {
        val movies = (1..5).map { i ->
            MediaMetadata("m$i", "movie", "Movie $i", posterUrl = "https://img/m$i.jpg")
        }
        val series = (1..4).map { i ->
            MediaMetadata("s$i", "series", "Series $i", posterUrl = "https://img/s$i.jpg")
        }
        val withoutArtwork = MediaMetadata("no_art", "movie", "No Art", posterUrl = null, backdropUrl = null)

        val day1 = selectDailyHighlights(listOf(withoutArtwork) + movies, series, dayIso = "2026-11-17", limit = 6)
        val day2 = selectDailyHighlights(listOf(withoutArtwork) + movies, series, dayIso = "2026-11-18", limit = 6)

        assertEquals(6, day1.size)
        assertEquals(6, day2.size)
        assertFalse(day1.any { it.id == "no_art" })
        assertTrue(day1.first().id != day2.first().id)
        assertEquals(day1.size, day1.map { "${it.type}:${it.id}" }.toSet().size)
    }

    @Test
    fun appendCatalogDeduplicatingPreservesOrderAndHasMoreForActiveTabMatchesCategory() {
        val existing = listOf(
            MediaMetadata("tt1", "movie", "Movie 1"),
            MediaMetadata("tt2", "series", "Series 1"),
        )
        val incoming = listOf(
            MediaMetadata("tt1", "movie", "Movie 1 Duplicate"),
            MediaMetadata("tt3", "movie", "Movie 2"),
            MediaMetadata("tt1", "series", "Series Sharing Numeric Id"),
        )
        val (merged, added) = appendCatalogDeduplicating(existing, incoming)
        assertEquals(listOf("movie:tt3", "series:tt1"), added.map { "${it.type}:${it.id}" })
        assertEquals(4, merged.size)

        val uiState = DiscoverUiState(
            categoryTab = DiscoverCategoryTab.All,
            hasMoreMovies = false,
            hasMoreSeries = true,
        )
        assertTrue(uiState.hasMoreForActiveTab)
        assertFalse(uiState.copy(categoryTab = DiscoverCategoryTab.Movies).hasMoreForActiveTab)
        assertTrue(uiState.copy(categoryTab = DiscoverCategoryTab.Series).hasMoreForActiveTab)

        assertEquals("act", CatalogGenreRegistry.findById("Acción")?.id)
        assertEquals("Sci-Fi", CatalogGenreRegistry.findById("scf")?.cinemetaName)
    }

    @Test
    fun orderedSeriesSeasonsAndInitialTargetDeprioritizeSpecialsAndFutureEpisodes() {
        val episodes = listOf(
            MediaEpisode("s0e1", season = 0, episode = 1, title = "Recap Special", released = "2024-01-01"),
            MediaEpisode("s1e1", season = 1, episode = 1, title = "Ep 1 Future", released = "2026-12-20"),
            MediaEpisode("s1e2", season = 1, episode = 2, title = "Ep 2", released = "2026-11-01"),
            MediaEpisode("s2e1", season = 2, episode = 1, title = "S2 Premiere", released = "2026-10-01"),
        )
        assertEquals(listOf(1, 2, 0), orderedSeriesSeasons(episodes))
        assertEquals("Especiales", formatSeasonChipLabel(0))
        assertEquals("Temporada 2 · 8", formatSeasonChipLabel(2, 8))
        assertTrue(isEpisodeFutureRelease("2026-12-20T00:00:00.000Z", "2026-11-17"))
        assertFalse(isEpisodeFutureRelease("2026-11-01", "2026-11-17"))
        assertFalse(isEpisodeFutureRelease(null, "2026-11-17"))

        // Starts on Season 1 (not Season 0 Specials), picking Ep 2 because Ep 1 is unreleased
        val (initialSeason, initialEp) = pickInitialSeriesTarget(episodes, todayIso = "2026-11-17")
        assertEquals(1, initialSeason)
        assertEquals(2, initialEp)

        // Specials-only series still falls back cleanly to Season 0
        val specialsOnly = listOf(MediaEpisode("sp", season = 0, episode = 1, title = "Special"))
        assertEquals(0 to 1, pickInitialSeriesTarget(specialsOnly, todayIso = "2026-11-17"))
    }

    @Test
    fun isRelevantCatalogResultRejectsUnrelatedPrefixCollisionsAndFiltersDeadSources() {
        val yourName = MediaMetadata(
            id = "tt5311514",
            type = "movie",
            title = "Your Name",
            originalTitle = "Kimi no na wa",
            year = 2016,
        )
        val validKimi = SearchUtils.makeResult(
            providerId = "limetorrents",
            providerName = "LimeTorrents",
            title = "Kimi no na wa 2016 1080p 1080p-dual-lat mp4",
            magnetUri = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
            seeders = 139,
        )
        val validYourName = SearchUtils.makeResult(
            providerId = "torrentio",
            providerName = "Torrentio",
            title = "Your Name 2016 2160p HMAX WEB-DL",
            magnetUri = "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
            seeders = 8,
        )
        val deadKimi = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Kimi no Na wa 2016 BDRip 720p Latino",
            magnetUri = "magnet:?xt=urn:btih:3333333333333333333333333333333333333333",
            seeders = 0,
        )
        val wrongCallMe = SearchUtils.makeResult(
            providerId = "solidtorrents",
            providerName = "SolidTorrents",
            title = "Call Me by Your Name [1080p][Latino]",
            magnetUri = "magnet:?xt=urn:btih:4444444444444444444444444444444444444444",
            seeders = 15,
        )
        val wrongTellMy = SearchUtils.makeResult(
            providerId = "solidtorrents",
            providerName = "SolidTorrents",
            title = "Tell my Your Name [BRS][Latino]",
            magnetUri = "magnet:?xt=urn:btih:5555555555555555555555555555555555555555",
            seeders = 2,
        )

        assertTrue(isRelevantCatalogResult(validKimi, yourName))
        assertTrue(isRelevantCatalogResult(validYourName, yourName))
        assertFalse(isRelevantCatalogResult(wrongCallMe, yourName))
        assertFalse(isRelevantCatalogResult(wrongTellMy, yourName))

        val all = listOf(validKimi, validYourName, deadKimi, wrongCallMe, wrongTellMy)
        val defaultFiltered = filterCatalogDetailResults(
            sortedResults = all,
            assessmentsById = emptyMap(),
            media = yourName,
            showAllResults = false,
        )
        assertEquals(listOf(validKimi.id, validYourName.id), defaultFiltered.map { it.id })

        val showAll = filterCatalogDetailResults(
            sortedResults = all,
            assessmentsById = emptyMap(),
            media = yourName,
            showAllResults = true,
        )
        assertEquals(5, showAll.size)

        val genreA = dailyFeaturedGenre("2026-11-17")
        val genreB = dailyFeaturedGenre("2026-11-18")
        assertTrue(genreA.id != genreB.id)
    }
}
