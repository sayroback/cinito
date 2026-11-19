package org.cinito.online.player

import org.cinito.online.settings.SubtitleStyleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import webtor.app.PlaybackItem

class CinemaPlayerModelsTest {
    @Test
    fun formatsLanguagesAndMatchesIsoVariants() {
        assertEquals("Español", TrackLanguageFormatter.formatLanguage("es"))
        assertEquals("Español", TrackLanguageFormatter.formatLanguage("spa"))
        assertEquals("Español Latino", TrackLanguageFormatter.formatLanguage("es-419"))
        assertEquals("Japonés", TrackLanguageFormatter.formatLanguage("jpn"))

        assertTrue(TrackLanguageFormatter.matchesLanguage("es", "spa"))
        assertTrue(TrackLanguageFormatter.matchesLanguage("es", "es-419"))
        assertTrue(TrackLanguageFormatter.matchesLanguage("en", "eng"))
        assertFalse(TrackLanguageFormatter.matchesLanguage("es", "jpn"))
    }

    @Test
    fun formatsCodecsChannelsAndSubtitleExtensions() {
        assertEquals("5.1", TrackLanguageFormatter.formatChannels(6))
        assertEquals("Estéreo", TrackLanguageFormatter.formatChannels(2))
        assertEquals("EAC3", TrackLanguageFormatter.formatAudioCodec("audio/eac3", null))
        assertEquals("TrueHD", TrackLanguageFormatter.formatAudioCodec("audio/true-hd", null))
        assertEquals("ASS/SSA", TrackLanguageFormatter.formatSubtitleCodec("text/x-ssa", null))
        assertEquals("text/x-ssa", TrackLanguageFormatter.subtitleMimeTypeForFile("Anime.E01.ass"))
        assertEquals("application/x-subrip", TrackLanguageFormatter.subtitleMimeTypeForFile("Movie.es.srt"))
        assertEquals("es", TrackLanguageFormatter.inferLanguageFromFilename("Dune.Part.Two.2024.Latino.srt"))
    }

    @Test
    fun formatsPlayerMediaTitleIntoCleanTitleAndEpisodeBadge() {
        val series = CinemaPlayerUiLogic.formatMediaTitle("Breaking.Bad.S01E02.1080p.WEB-DL.x264.mkv")
        assertEquals("Breaking Bad", series.primaryTitle)
        assertEquals("T01 · E02", series.episodeBadge)

        val rawMode = CinemaPlayerUiLogic.formatMediaTitle(
            rawTitle = "Breaking.Bad.S01E02.1080p.WEB-DL.x264.mkv",
            showRawFilename = true,
        )
        assertEquals("Breaking.Bad.S01E02.1080p.WEB-DL.x264.mkv", rawMode.primaryTitle)
        assertNull(rawMode.episodeBadge)

        val movie = CinemaPlayerUiLogic.formatMediaTitle("Dune.Part.Two.2024.2160p.DV.mkv")
        assertEquals("Dune Part Two", movie.primaryTitle)
        assertEquals("2024", movie.secondaryTag)
    }

    @Test
    fun formatsEpisodeRowPreservingEpisodeTitleAfterMarker() {
        val withSubtitle = CinemaPlayerUiLogic.formatEpisodeRow(
            rawFileName = "Severance.S02E01.Hello.Ms.Cobel.1080p.WEB-DL.mkv",
            index = 0,
            showRawFilename = false,
        )
        assertEquals("T02 · E01", withSubtitle.badge)
        assertEquals("Hello Ms Cobel", withSubtitle.title)
        assertTrue(withSubtitle.detail.orEmpty().contains("1080p"))

        val withoutSubtitle = CinemaPlayerUiLogic.formatEpisodeRow(
            rawFileName = "Breaking.Bad.S01E02.1080p.x264.mkv",
            index = 1,
            showRawFilename = false,
        )
        assertEquals("T01 · E02", withoutSubtitle.badge)
        assertEquals("Breaking Bad", withoutSubtitle.title)

        val raw = CinemaPlayerUiLogic.formatEpisodeRow(
            rawFileName = "Breaking.Bad.S01E02.1080p.x264.mkv",
            index = 1,
            showRawFilename = true,
        )
        assertEquals("#2", raw.badge)
        assertEquals("Breaking.Bad.S01E02.1080p.x264.mkv", raw.title)
    }

    @Test
    fun aspectViewportNeverOverflowsInPortraitOrUltrawideLandscape() {
        val ultrawideW = 2400f
        val ultrawideH = 1080f

        val box16x9 = CinemaPlayerUiLogic.computeAspectViewportSize(ultrawideW, ultrawideH, PlayerAspectMode.Ratio16x9)
        assertTrue(box16x9.width <= ultrawideW + 0.01f)
        assertEquals(ultrawideH, box16x9.height, 0.01f)
        assertEquals(1080f * (16f / 9f), box16x9.width, 0.5f)

        val box4x3 = CinemaPlayerUiLogic.computeAspectViewportSize(ultrawideW, ultrawideH, PlayerAspectMode.Ratio4x3)
        assertTrue(box4x3.width <= ultrawideW + 0.01f)
        assertEquals(ultrawideH, box4x3.height, 0.01f)
        assertEquals(1080f * (4f / 3f), box4x3.width, 0.5f)

        val portraitW = 1080f
        val portraitH = 2400f
        val portrait16x9 = CinemaPlayerUiLogic.computeAspectViewportSize(portraitW, portraitH, PlayerAspectMode.Ratio16x9)
        assertEquals(portraitW, portrait16x9.width, 0.01f)
        assertTrue(portrait16x9.height <= portraitH + 0.01f)
        assertEquals(1080f / (16f / 9f), portrait16x9.height, 0.5f)
    }

    @Test
    fun pipAspectRatioClampsExtremeDimensionsAndFallsBackSafely() {
        assertEquals(16 to 9, CinemaPlayerUiLogic.pipAspectRatio(0, 0))
        assertEquals(16 to 9, CinemaPlayerUiLogic.pipAspectRatio(1920, 1080).let {
            if (it.first * 9 == it.second * 16) 16 to 9 else it
        })
        val tooWide = CinemaPlayerUiLogic.pipAspectRatio(3000, 900)
        assertEquals(239 to 100, tooWide)

        val tooTall = CinemaPlayerUiLogic.pipAspectRatio(500, 2000)
        assertEquals(100 to 239, tooTall)

        val normal = CinemaPlayerUiLogic.pipAspectRatio(1440, 1080)
        assertEquals(1440 to 1080, normal)
    }

    @Test
    fun pipSubtitleSizeScalesCompactly() {
        assertEquals(10f, CinemaPlayerUiLogic.computePipSubtitleSizeSp(14f), 0.01f)
        assertEquals(12.4f, CinemaPlayerUiLogic.computePipSubtitleSizeSp(20f), 0.01f)
        assertEquals(15f, CinemaPlayerUiLogic.computePipSubtitleSizeSp(32f), 0.01f)
    }

    @Test
    fun formatsSpeedLabelsAndRemainingTime() {
        assertEquals("1x", CinemaPlayerUiLogic.formatSpeedLabel(1.0f))
        assertEquals("1.25x", CinemaPlayerUiLogic.formatSpeedLabel(1.25f))
        assertEquals("1.5x", CinemaPlayerUiLogic.formatSpeedLabel(1.5f))
        assertEquals("2x", CinemaPlayerUiLogic.formatSpeedLabel(2.0f))

        assertEquals("-10:00", CinemaPlayerUiLogic.formatRemainingTime(positionMs = 300_000L, durationMs = 900_000L))
        assertEquals("-0:00", CinemaPlayerUiLogic.formatRemainingTime(positionMs = 950_000L, durationMs = 900_000L))
    }

    @Test
    fun skipIntroOnlyAppearsWithinOpeningWindowOfLongEpisodes() {
        assertTrue(
            CinemaPlayerUiLogic.shouldShowSkipIntro(
                title = "Severance.S02E01.1080p.mkv",
                playlistSize = 8,
                positionMs = 45_000L,
                durationMs = 2_800_000L,
            ),
        )
        assertFalse(
            CinemaPlayerUiLogic.shouldShowSkipIntro(
                title = "Dune.Part.Two.2024.1080p.mkv",
                playlistSize = 1,
                positionMs = 45_000L,
                durationMs = 7_200_000L,
            ),
        )
        assertFalse(
            CinemaPlayerUiLogic.shouldShowSkipIntro(
                title = "Severance.S02E01.1080p.mkv",
                playlistSize = 8,
                positionMs = 400_000L,
                durationMs = 2_800_000L,
            ),
        )
    }

    @Test
    fun nextEpisodePromptSwitchesBetweenPreEndAndAutoCountdown() {
        val base = CinemaPlayerState(
            title = "Severance.S02E01.1080p.mkv",
            playlist = listOf(
                PlaybackItem("uri://1", "Severance.S02E01.1080p.mkv"),
                PlaybackItem("uri://2", "Severance.S02E02.1080p.mkv"),
            ),
            currentPlaylistIndex = 0,
            durationMs = 300_000L,
            positionMs = 100_000L,
        )

        assertEquals(
            NextEpisodePromptMode.Hidden,
            CinemaPlayerUiLogic.evaluateNextEpisodePrompt(base, dismissed = false),
        )
        assertEquals(
            NextEpisodePromptMode.PreEnd,
            CinemaPlayerUiLogic.evaluateNextEpisodePrompt(base.copy(positionMs = 285_000L), dismissed = false),
        )
        assertEquals(
            NextEpisodePromptMode.AutoCountdown,
            CinemaPlayerUiLogic.evaluateNextEpisodePrompt(base.copy(positionMs = 300_000L, isEnded = true), dismissed = false),
        )
        assertEquals(
            NextEpisodePromptMode.Hidden,
            CinemaPlayerUiLogic.evaluateNextEpisodePrompt(base.copy(positionMs = 300_000L, isEnded = true), dismissed = true),
        )
        assertEquals(
            NextEpisodePromptMode.Hidden,
            CinemaPlayerUiLogic.evaluateNextEpisodePrompt(base.copy(isEnded = true, isInPipMode = true), dismissed = false),
        )
        assertEquals(
            NextEpisodePromptMode.Hidden,
            CinemaPlayerUiLogic.evaluateNextEpisodePrompt(base.copy(isEnded = true, isLocked = true), dismissed = false),
        )
    }

    @Test
    fun p2pBufferingStatusDistinguishesConnectingFromDownloading() {
        val connecting = CinemaPlayerUiLogic.describeP2pBufferingStatus(downloadSpeedBytesPerSec = 0L, numPeers = 0)
        assertEquals("Conectando con peers…", connecting.headline)
        assertEquals("Buscando semillas disponibles", connecting.detail)

        val downloading = CinemaPlayerUiLogic.describeP2pBufferingStatus(downloadSpeedBytesPerSec = 2_097_152L, numPeers = 14)
        assertEquals("Cargando búfer de video…", downloading.headline)
        assertTrue(downloading.detail.contains("14 peers"))
        assertTrue(downloading.detail.contains("↓"))
    }

    @Test
    fun lateralDoubleTapAccumulatesSameSideAndResetsAcrossSidesOrTimeout() {
        val first = CinemaPlayerUiLogic.nextDoubleTapSeek(
            previousDeltaMs = 0L,
            previousSideIsLeft = null,
            lastTapAtMs = 0L,
            nowMs = 1_000L,
            isLeft = false,
        )
        assertEquals(10_000L, first)

        val second = CinemaPlayerUiLogic.nextDoubleTapSeek(
            previousDeltaMs = first,
            previousSideIsLeft = false,
            lastTapAtMs = 1_000L,
            nowMs = 1_450L,
            isLeft = false,
        )
        assertEquals(20_000L, second)

        val switchedSide = CinemaPlayerUiLogic.nextDoubleTapSeek(
            previousDeltaMs = second,
            previousSideIsLeft = false,
            lastTapAtMs = 1_450L,
            nowMs = 1_650L,
            isLeft = true,
        )
        assertEquals(-10_000L, switchedSide)

        val expired = CinemaPlayerUiLogic.nextDoubleTapSeek(
            previousDeltaMs = second,
            previousSideIsLeft = false,
            lastTapAtMs = 1_450L,
            nowMs = 2_600L,
            isLeft = false,
        )
        assertEquals(10_000L, expired)
    }

    @Test
    fun safeDragZoneRejectsSystemGestureEdges() {
        assertFalse(CinemaPlayerUiLogic.isInsideSafeDragZone(10f, 500f, 1080f, 1920f, edgeGuardPx = 48f))
        assertFalse(CinemaPlayerUiLogic.isInsideSafeDragZone(1060f, 500f, 1080f, 1920f, edgeGuardPx = 48f))
        assertFalse(CinemaPlayerUiLogic.isInsideSafeDragZone(500f, 20f, 1080f, 1920f, edgeGuardPx = 48f))
        assertFalse(CinemaPlayerUiLogic.isInsideSafeDragZone(500f, 1900f, 1080f, 1920f, edgeGuardPx = 48f))
        assertTrue(CinemaPlayerUiLogic.isInsideSafeDragZone(300f, 500f, 1080f, 1920f, edgeGuardPx = 48f))
    }

    @Test
    fun tempSpeedBoostAndRetryStateTransitionsPreserveUserSettings() {
        val base = CinemaPlayerState(playbackSpeed = 1.25f, isEnded = true, errorMessage = "Timeout", subtitleStyle = SubtitleStyleConfig())
        val boosted = base.withTemporarySpeedBoost(true)
        assertTrue(boosted.isTempSpeedBoost)
        assertEquals(2.0f, boosted.playbackSpeed, 0.001f)
        assertEquals(1.25f, boosted.speedBeforeBoost, 0.001f)

        val restored = boosted.withTemporarySpeedBoost(false)
        assertFalse(restored.isTempSpeedBoost)
        assertEquals(1.25f, restored.playbackSpeed, 0.001f)

        val retried = boosted.withRetryStarted()
        assertNull(retried.errorMessage)
        assertFalse(retried.isEnded)
        assertTrue(retried.isBuffering)
    }
}
