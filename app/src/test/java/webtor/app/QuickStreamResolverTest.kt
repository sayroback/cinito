package webtor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import webtor.core.TorrentFile

class QuickStreamResolverTest {

    @Test
    fun autoPlaysSingleVideoWithMatchingSubtitle() {
        val files = listOf(
            TorrentFile(0, "movie.mkv", "Movie/movie.mkv", 2_000_000L, 0.0, "video/x-matroska"),
            TorrentFile(1, "movie.es.srt", "Movie/movie.es.srt", 50_000L, 0.0, "application/x-subrip"),
            TorrentFile(2, "readme.txt", "Movie/readme.txt", 1_000L, 0.0, "text/plain"),
        )
        val plan = resolveQuickStreamSelection(files, QuickStreamTarget("Movie"))
        assertTrue(plan is QuickStreamPlan.AutoPlay)
        assertEquals(setOf(0, 1), (plan as QuickStreamPlan.AutoPlay).selectedIndices)
    }

    @Test
    fun autoPlaysExactEpisodeInSeasonPackWhenTargetSpecified() {
        val files = listOf(
            TorrentFile(0, "Show.S02E01.1080p.mkv", "Show/Show.S02E01.1080p.mkv", 1_000_000L, 0.0, "video/x-matroska"),
            TorrentFile(1, "Show.S02E02.1080p.mkv", "Show/Show.S02E02.1080p.mkv", 1_000_000L, 0.0, "video/x-matroska"),
            TorrentFile(2, "Show.S02E02.1080p.es.srt", "Show/Show.S02E02.1080p.es.srt", 20_000L, 0.0, "application/x-subrip"),
            TorrentFile(3, "Show.S02E03.1080p.mkv", "Show/Show.S02E03.1080p.mkv", 1_000_000L, 0.0, "video/x-matroska"),
        )

        val matched = resolveQuickStreamSelection(files, QuickStreamTarget("Show", season = 2, episode = 2))
        assertTrue(matched is QuickStreamPlan.AutoPlay)
        assertEquals(setOf(1, 2), (matched as QuickStreamPlan.AutoPlay).selectedIndices)

        val noTarget = resolveQuickStreamSelection(files, QuickStreamTarget("Show"))
        assertEquals(QuickStreamPlan.NeedsFileSelection, noTarget)
    }

    @Test
    fun findMatchingEpisodeFileIndexResolvesExistingLibraryEpisodes() {
        val entry = DownloadEntry(
            key = "k1",
            title = "Show S01",
            source = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            metadata = "",
            engineId = "e1",
            destination = "Direct Play",
            directPlay = true,
            files = listOf(
                SavedFile(0, "Show.S01E01.mkv", "Show.S01E01.mkv", 1000L),
                SavedFile(1, "Show.S01E02.mkv", "Show.S01E02.mkv", 1000L),
                SavedFile(2, "Show.S01E03.mkv", "Show.S01E03.mkv", 1000L),
            ),
            selected = setOf(0, 1, 2),
        )

        assertEquals(1, findMatchingEpisodeFileIndex(entry, season = 1, episode = 2))
        assertNull(findMatchingEpisodeFileIndex(entry, season = 1, episode = 9))
        assertNull(findMatchingEpisodeFileIndex(entry, season = null, episode = null))

        // Partial selection: only Episode 1 was previously selected, requesting Episode 2 must return null
        val partial = entry.copy(selected = setOf(0))
        assertEquals(0, findMatchingEpisodeFileIndex(partial, season = 1, episode = 1))
        assertNull(findMatchingEpisodeFileIndex(partial, season = 1, episode = 2))
    }
}
