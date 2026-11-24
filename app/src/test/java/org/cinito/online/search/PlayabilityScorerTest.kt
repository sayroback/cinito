package org.cinito.online.search

import org.cinito.online.cast.WebOsCastMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayabilityScorerTest {

    @Test
    fun parsesRuntimeStringsCleanly() {
        assertEquals(142, PlayabilityContext.parseRuntimeMinutes("2h 22m"))
        assertEquals(120, PlayabilityContext.parseRuntimeMinutes("2h"))
        assertEquals(48, PlayabilityContext.parseRuntimeMinutes("48 min"))
        assertEquals(95, PlayabilityContext.parseRuntimeMinutes("95"))
        assertNull(PlayabilityContext.parseRuntimeMinutes(null))
        assertNull(PlayabilityContext.parseRuntimeMinutes("unknown"))
    }

    @Test
    fun prefersStreamable1080pOverLowSeeder4kRemux() {
        val remux = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "Torrentio",
            title = "Dune.Part.Two.2024.2160p.BluRay.REMUX.HEVC.TrueHD.Atmos",
            magnetUri = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
            sizeBytes = 60L * 1024L * 1024L * 1024L,
            seeders = 10,
            peers = 25,
            category = SearchCategory.Movies,
        )
        val webDl = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "Torrentio",
            title = "Dune.Part.Two.2024.1080p.WEB-DL.DDP5.1.H264.Latino",
            magnetUri = "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
            sizeBytes = 4L * 1024L * 1024L * 1024L,
            seeders = 22,
            peers = 4,
            category = SearchCategory.Movies,
        )
        val ctx = PlayabilityContext(expectedDurationMinutes = 166)

        val remuxEval = PlayabilityScorer.evaluate(remux, ctx)
        val webDlEval = PlayabilityScorer.evaluate(webDl, ctx)

        assertEquals(PlayabilityVerdict.Risky, remuxEval.verdict)
        assertEquals(PlayabilityVerdict.Optimal, webDlEval.verdict)
        assertTrue(webDlEval.score > remuxEval.score)
        assertNotNull(remuxEval.estimatedBitrateMbps)
        assertTrue(remuxEval.estimatedBitrateMbps!! > 45.0)
    }

    @Test
    fun marksZeroLiveScrapedSeedersAsDeadEvenIfIndexerReportedSeeds() {
        val stale = SearchUtils.makeResult(
            providerId = "tpb",
            providerName = "ThePirateBay",
            title = "Old.Movie.2005.1080p.BluRay.x264",
            magnetUri = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sizeBytes = 2L * 1024L * 1024L * 1024L,
            seeders = 40,
            peers = 2,
            category = SearchCategory.Movies,
        )
        val liveDead = TrackerSwarmStats(seeders = 0, leechers = 3, completed = 500, updatedAtMs = 1000L)

        val assessed = PlayabilityScorer.evaluate(stale, PlayabilityContext(), scrape = liveDead)

        assertEquals(PlayabilityVerdict.Dead, assessed.verdict)
        assertTrue(assessed.isLiveScraped)
        assertEquals(0, assessed.effectiveSeeders)
    }

    @Test
    fun penalizesBrowserFragileCodecsWhenCastingToLgBrowser() {
        val hevc = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Show.S01E03.1080p.HEVC.x265.10bit.TrueHD",
            magnetUri = "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            sizeBytes = 1_500L * 1024L * 1024L,
            seeders = 25,
            peers = 2,
            category = SearchCategory.Series,
        )
        val normal = PlayabilityScorer.evaluate(
            hevc,
            PlayabilityContext(isCastingToTv = false, requestedSeason = 1, requestedEpisode = 3),
        )
        val lgBrowser = PlayabilityScorer.evaluate(
            hevc,
            PlayabilityContext(
                isCastingToTv = true,
                activeWebOsMode = WebOsCastMode.Browser,
                requestedSeason = 1,
                requestedEpisode = 3,
            ),
        )

        assertTrue(lgBrowser.score < normal.score)
        assertTrue(lgBrowser.reasons.any { it.contains("navegador TV", ignoreCase = true) })
    }

    @Test
    fun prioritizesInLibraryAndUsesProbeHeadroomAndAvailability() {
        val hash = "cccccccccccccccccccccccccccccccccccccccc"
        val item = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "YTS",
            title = "Movie.2024.1080p.WEB-DL",
            magnetUri = "magnet:?xt=urn:btih:$hash",
            sizeBytes = 3_600L * 1024L * 1024L, // ~4.4 Mbps for 110 min
            seeders = 15,
            peers = 5,
            category = SearchCategory.Movies,
        )

        val libEval = PlayabilityScorer.evaluate(
            item,
            PlayabilityContext(inLibraryHashes = setOf(hash)),
        )
        assertEquals(PlayabilityVerdict.Optimal, libEval.verdict)
        assertTrue(libEval.score >= 95)

        // Probe with fast measured speed -> high headroom (> 5x)
        val fastProbe = SwarmProbeResult(
            infoHash = hash,
            ttfmMs = 900L,
            measuredDownloadBps = 4_000_000L, // 32 Mbps
            activePeers = 12,
            connectedSeeders = 8,
            swarmAvailability = 4.0,
            isFastStartMp4 = true,
        )
        val optimalEval = PlayabilityScorer.evaluate(item, PlayabilityContext(), probe = fastProbe)
        assertEquals(PlayabilityVerdict.Optimal, optimalEval.verdict)
        assertNotNull(optimalEval.headroomRatio)
        assertTrue(optimalEval.headroomRatio!! >= 5.0)

        // Probe reporting incomplete swarm (< 1.0 availability) -> Risky
        val incompleteProbe = fastProbe.copy(swarmAvailability = 0.72)
        val incompleteEval = PlayabilityScorer.evaluate(item, PlayabilityContext(), probe = incompleteProbe)
        assertEquals(PlayabilityVerdict.Risky, incompleteEval.verdict)
    }

    @Test
    fun bestPlayabilitySortPushesDeadLatinoBelowLiveSources() {
        val deadLatino = SearchUtils.makeResult(
            providerId = "tpb",
            providerName = "TPB",
            title = "Serie.S01E01.1080p.Latino",
            magnetUri = "magnet:?xt=urn:btih:dddddddddddddddddddddddddddddddddddddddd",
            sizeBytes = 1_200L * 1024L * 1024L,
            seeders = 0,
            peers = 4,
            category = SearchCategory.Series,
        )
        val liveEnglish = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Serie.S01E01.1080p.WEB-DL",
            magnetUri = "magnet:?xt=urn:btih:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            sizeBytes = 1_200L * 1024L * 1024L,
            seeders = 35,
            peers = 5,
            category = SearchCategory.Series,
        )

        val (sorted, assessments) = SearchService.evaluatePlayabilityAndSort(
            results = listOf(deadLatino, liveEnglish),
            sortOrder = SearchSortOrder.BestPlayability,
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
        )

        assertEquals(listOf(liveEnglish.id, deadLatino.id), sorted.map { it.id })
        assertEquals(PlayabilityVerdict.Optimal, assessments[liveEnglish.id]?.verdict)
        assertEquals(PlayabilityVerdict.Dead, assessments[deadLatino.id]?.verdict)
    }

    @Test
    fun base32InfoHashMatchesCanonicalHexInLibraryScrapeAndProbeMaps() {
        // Base32 of 0123456789abcdef0123456789abcdef01234567
        val base32 = "aeaqcaibaeaqcaibaeaqcaibaeaqcaib"
        val (canonicalHex, _) = SearchUtils.decodeInfoHashBytes(base32)!!
        val item = SearchUtils.makeResult(
            providerId = "solidtorrents",
            providerName = "SolidTorrents",
            title = "Movie.2024.1080p.WEB-DL.Latino",
            magnetUri = "magnet:?xt=urn:btih:$base32",
            seeders = 1,
        )
        assertEquals(canonicalHex, item.normalizedInfoHash)

        val (_, assessments) = SearchService.evaluatePlayabilityAndSort(
            results = listOf(item),
            sortOrder = SearchSortOrder.BestPlayability,
            languageFilter = SearchLanguageFilter.PrioritizeLatino,
            playabilityContext = PlayabilityContext(inLibraryHashes = setOf(canonicalHex)),
            scrapeStatsByHash = mapOf(canonicalHex to TrackerSwarmStats(seeders = 42, leechers = 5)),
            probeResultsByHash = mapOf(
                canonicalHex to SwarmProbeResult(
                    infoHash = canonicalHex,
                    ttfmMs = 500L,
                    measuredDownloadBps = 2_000_000L,
                    activePeers = 12,
                ),
            ),
        )
        val eval = assessments[item.id]!!
        assertEquals(PlayabilityVerdict.Optimal, eval.verdict)
        assertEquals(42, eval.effectiveSeeders)
        assertNotNull(eval.probe)
        assertTrue(eval.reasons.any { it.contains("Ya en biblioteca") })
    }

    @Test
    fun nonVideoPacksArePenalizedBelowPlayableVideosAndHighSeedIgnoresLeecherSpike() {
        val videoRelease = SearchUtils.makeResult(
            providerId = "solidtorrents",
            providerName = "SolidTorrents",
            title = "Studio.Sample.2026.1080p.mp4",
            magnetUri = "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sizeBytes = 1_200_000_000L,
            seeders = 250,
            peers = 9_000,
        )
        val photoPack = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Model.Photoset.2026.Gallery.zip",
            magnetUri = "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            sizeBytes = 400_000_000L,
            seeders = 300,
            peers = 10,
        )

        val videoEval = PlayabilityScorer.evaluate(videoRelease)
        val packEval = PlayabilityScorer.evaluate(photoPack)

        assertEquals(PlayabilityVerdict.Optimal, videoEval.verdict)
        assertEquals(PlayabilityVerdict.Risky, packEval.verdict)
        assertTrue(videoEval.score > packEval.score)
        assertTrue(packEval.reasons.any { it.contains("Pack de imágenes", ignoreCase = true) })
    }

    @Test
    fun liveScrapedTorrentOutranksUnverifiedStaleSolidTorrentsCache() {
        val liveItem = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Movie.2025.1080p.WEB-DL.H264",
            magnetUri = "magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678",
            sizeBytes = 2L * 1024L * 1024L * 1024L,
            seeders = 191,
            peers = 12,
            category = SearchCategory.Movies,
        )
        val staleSolidTorrents = SearchUtils.makeResult(
            providerId = "solidtorrents",
            providerName = "SolidTorrents",
            title = "Movie.2025.1080p.WEB-DL.H264",
            magnetUri = "magnet:?xt=urn:btih:fedcba0987654321fedcba0987654321fedcba09",
            sizeBytes = 2L * 1024L * 1024L * 1024L,
            seeders = 304,
            peers = 12,
            category = SearchCategory.Movies,
        )
        val midSeedUnprobed = SearchUtils.makeResult(
            providerId = "yts",
            providerName = "YTS",
            title = "Movie.2025.1080p.WEB-DL.H264",
            magnetUri = "magnet:?xt=urn:btih:abcdef1234567890abcdef1234567890abcdef12",
            sizeBytes = 2L * 1024L * 1024L * 1024L,
            seeders = 15,
            peers = 4,
            category = SearchCategory.Movies,
        )

        val liveEval = PlayabilityScorer.evaluate(
            liveItem,
            PlayabilityContext(),
            scrape = TrackerSwarmStats(seeders = 191, leechers = 12),
        )
        val staleEval = PlayabilityScorer.evaluate(staleSolidTorrents, PlayabilityContext())
        val midSeedEval = PlayabilityScorer.evaluate(midSeedUnprobed, PlayabilityContext())

        assertTrue(liveEval.isLiveScraped)
        assertTrue(liveEval.score > staleEval.score)
        assertEquals(PlayabilityVerdict.Good, midSeedEval.verdict)
    }

    @Test
    fun probeConfirmedDeadMarksDeadEvenWithTrackerSeedsUnlessPolicyBlocked() {
        val hash = "9999999999999999999999999999999999999999"
        val result = SearchUtils.makeResult(
            providerId = "knaben",
            providerName = "Knaben",
            title = "Movie.2025.1080p.WEB-DL",
            magnetUri = "magnet:?xt=urn:btih:$hash",
            sizeBytes = 2L * 1024L * 1024L * 1024L,
            seeders = 20,
            peers = 4,
            category = SearchCategory.Movies,
        )

        val deadProbe = SwarmProbeResult(
            infoHash = hash,
            ttfmMs = 0L,
            measuredDownloadBps = 0L,
            activePeers = 0,
            connectedSeeders = 0,
            isPolicyBlocked = false,
        )
        val deadEval = PlayabilityScorer.evaluate(result, PlayabilityContext(), probe = deadProbe)
        assertEquals(PlayabilityVerdict.Dead, deadEval.verdict)
        assertEquals(1, deadEval.score)

        val connectedHeaderOnlyProbe = deadProbe.copy(
            activePeers = 3,
            targetFileSizeBytes = 1_500_000_000L,
        )
        val headerEval = PlayabilityScorer.evaluate(result, PlayabilityContext(), probe = connectedHeaderOnlyProbe)
        assertTrue("Probe with connected peers/resolved file must not be marked Dead", headerEval.verdict != PlayabilityVerdict.Dead)

        val policyBlockedProbe = deadProbe.copy(isPolicyBlocked = true)
        val blockedEval = PlayabilityScorer.evaluate(result, PlayabilityContext(), probe = policyBlockedProbe)
        assertTrue(blockedEval.verdict != PlayabilityVerdict.Dead)
    }

    @Test
    fun estimateVideoRequiredMbpsComputesExpectedBitrateByCategory() {
        val twoGb = 2_000_000_000L
        val movieMbps = PlayabilityScorer.estimateVideoRequiredMbps(twoGb, SearchCategory.Movies)
        val animeMbps = PlayabilityScorer.estimateVideoRequiredMbps(twoGb, SearchCategory.Anime)
        assertNotNull(movieMbps)
        assertNotNull(animeMbps)
        assertTrue(animeMbps!! > movieMbps!!)
        assertNull(PlayabilityScorer.estimateVideoRequiredMbps(0L))
    }
}
