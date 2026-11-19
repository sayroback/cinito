package org.cinito.online.ui.library

import org.cinito.online.metadata.MediaEpisode
import org.cinito.online.metadata.MediaKind
import org.cinito.online.metadata.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import webtor.app.DownloadEntry
import webtor.app.SavedFile

class LibraryGalleryModelsTest {
    @Test
    fun groupsMultiEpisodeSeriesTorrentBySeasonAndMergesMetadata() {
        val f1 = SavedFile(index = 0, name = "Breaking.Bad.S01E02.1080p.mkv", path = "Season 1/Breaking.Bad.S01E02.1080p.mkv", length = 1_500_000_000L, verifiedBytes = 1_500_000_000L)
        val f2 = SavedFile(index = 1, name = "Breaking.Bad.S01E01.1080p.mkv", path = "Season 1/Breaking.Bad.S01E01.1080p.mkv", length = 1_400_000_000L, verifiedBytes = 1_400_000_000L)
        val f3 = SavedFile(index = 2, name = "Breaking.Bad.S02E01.1080p.mkv", path = "Season 2/Breaking.Bad.S02E01.1080p.mkv", length = 1_600_000_000L, verifiedBytes = 0L)
        val sub = SavedFile(index = 3, name = "Breaking.Bad.S01E01.es.srt", path = "Season 1/Breaking.Bad.S01E01.es.srt", length = 42_000L, verifiedBytes = 42_000L)

        val entry = DownloadEntry(
            key = "k1",
            title = "Breaking.Bad.Complete.1080p.BluRay.x265",
            source = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            metadata = "meta",
            engineId = null,
            destination = "Downloads/CiniTo",
            files = listOf(f1, f2, f3, sub),
            selected = setOf(0, 1, 2, 3),
            directPlay = true,
        )

        val meta = MediaMetadata(
            id = "tt0903747",
            type = "series",
            title = "Breaking Bad",
            year = 2008,
            posterUrl = "https://example.org/bb.jpg",
            episodes = listOf(
                MediaEpisode(id = "e1", season = 1, episode = 1, title = "Pilot"),
                MediaEpisode(id = "e2", season = 1, episode = 2, title = "Cat's in the Bag..."),
            ),
        )

        val card = LibraryGalleryBuilder.buildCardModel(entry, meta)
        assertTrue(card.isSeriesLayout)
        assertEquals("Breaking Bad", card.displayTitle)
        assertEquals(2008, card.year)
        assertEquals("https://example.org/bb.jpg", card.posterUrl)
        assertEquals(2, card.episodesBySeason.size)
        // Episode 1 sorted before Episode 2 inside Season 1
        assertEquals("Pilot", card.episodesBySeason[1]?.get(0)?.displayTitle)
        assertEquals("Cat's in the Bag...", card.episodesBySeason[1]?.get(1)?.displayTitle)
        assertEquals(1, card.episodesBySeason[2]?.size)
        assertEquals(1, card.subtitleFiles.size)
        assertEquals(MediaKind.SERIES, card.kind)
    }

    @Test
    fun librarySeriesFallsBackToParsedEpisodeTitleAndTracksResumeSeason() {
        val f1 = SavedFile(index = 0, name = "Series.Name.S01E01.El.Comienzo.1080p.mkv", path = "Series.Name.S01E01.El.Comienzo.1080p.mkv", length = 1_000_000L)
        val f2 = SavedFile(
            index = 1,
            name = "Series.Name.S03E04.La.Venganza.1080p.mkv",
            path = "Series.Name.S03E04.La.Venganza.1080p.mkv",
            length = 1_000_000L,
            playbackPositionMs = 450_000L,
            playbackDurationMs = 2_400_000L,
        )
        val f3 = SavedFile(
            index = 2,
            name = "Series.Name.S01E02.Pasado.1080p.mkv",
            path = "Series.Name.S01E02.Pasado.1080p.mkv",
            length = 1_000_000L,
            playbackPositionMs = 2_280_000L,
            playbackDurationMs = 2_400_000L,
        )

        val entry = DownloadEntry(
            key = "k2",
            title = "Series.Name.Complete.1080p",
            source = "magnet:?xt=urn:btih:abcdef0123456789abcdef0123456789abcdef01",
            metadata = "meta",
            engineId = null,
            destination = "Downloads/CiniTo",
            files = listOf(f1, f2, f3),
            selected = setOf(0, 1, 2),
            directPlay = true,
        )

        val card = LibraryGalleryBuilder.buildCardModel(entry, metadata = null)
        assertEquals("El Comienzo", card.episodesBySeason[1]?.get(0)?.displayTitle)
        assertEquals(true, card.episodesBySeason[1]?.get(1)?.isWatched)
        assertEquals(3, card.primaryEpisode?.season)
        assertEquals(4, card.primaryEpisode?.episode)
        assertEquals("La Venganza", card.primaryEpisode?.displayTitle)
    }
}
