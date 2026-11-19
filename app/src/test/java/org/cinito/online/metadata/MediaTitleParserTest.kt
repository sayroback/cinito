package org.cinito.online.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTitleParserTest {

    @Test
    fun parsesMovieWithYearAndQualityBadges() {
        val parsed = MediaTitleParser.parse(
            "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv"
        )

        assertEquals("Dune Part Two", parsed.cleanTitle)
        assertEquals(2024, parsed.year)
        assertEquals(MediaKind.MOVIE, parsed.kind)
        assertEquals(null, parsed.season)
        assertEquals(null, parsed.episode)
        assertTrue(parsed.qualityBadges.contains("4K"))
        assertTrue(parsed.qualityBadges.contains("DV"))
        assertTrue(parsed.qualityBadges.contains("HDR"))
        assertTrue(parsed.qualityBadges.contains("HEVC"))
        assertTrue(parsed.qualityBadges.contains("Atmos"))
    }

    @Test
    fun parsesSeriesEpisodeAndSeason() {
        val parsed = MediaTitleParser.parse("Severance.S02E04.1080p.WEB.h264-ETHEL")

        assertEquals("Severance", parsed.cleanTitle)
        assertEquals(MediaKind.SERIES, parsed.kind)
        assertEquals(2, parsed.season)
        assertEquals(4, parsed.episode)
        assertEquals("T02 · E04", parsed.episodeBadge)
        assertTrue(parsed.qualityBadges.contains("1080p"))
    }

    @Test
    fun parsesAnimeWithGroupAndEpisode() {
        val parsed = MediaTitleParser.parse("[SubsPlease] Dandadan - 07 (1080p) [ABC123].mkv")

        assertEquals("Dandadan", parsed.cleanTitle)
        assertEquals(MediaKind.ANIME, parsed.kind)
        assertEquals(7, parsed.episode)
        assertEquals("Ep. 07", parsed.episodeBadge)
        assertTrue(parsed.qualityBadges.contains("1080p"))
    }

    @Test
    fun parsesSeasonPackAndLanguageHints() {
        val parsed = MediaTitleParser.parse("Breaking Bad Season 1 1080p BluRay x265 Dual Latino")

        assertEquals("Breaking Bad", parsed.cleanTitle)
        assertEquals(MediaKind.SERIES, parsed.kind)
        assertEquals(1, parsed.season)
        assertTrue(parsed.isSeasonPack)
        assertEquals("Temporada 1", parsed.episodeBadge)
        assertTrue(parsed.qualityBadges.contains("Dual"))
        assertTrue(parsed.qualityBadges.contains("Latino"))
    }

    @Test
    fun keepsDigitsInMovieTitleWithoutTreatingAsYear() {
        val parsed = MediaTitleParser.parse("Blade.Runner.2049.2017.1080p.BluRay.x264")

        assertEquals("Blade Runner 2049", parsed.cleanTitle)
        assertEquals(2017, parsed.year)
        assertEquals(MediaKind.MOVIE, parsed.kind)
    }

    @Test
    fun extractsEpisodeSubtitleWithoutReleaseNoise() {
        assertEquals(
            "Hello Ms Cobel",
            MediaTitleParser.extractEpisodeTitle("Severance.S02E01.Hello.Ms.Cobel.1080p.WEB-DL.mkv"),
        )
        assertEquals(
            null,
            MediaTitleParser.extractEpisodeTitle("Severance.S02E04.1080p.WEB.h264-ETHEL.mkv"),
        )
        assertEquals(
            null,
            MediaTitleParser.extractEpisodeTitle("[SubsPlease] Dandadan - 07 (1080p) [ABC123].mkv"),
        )
    }
}
