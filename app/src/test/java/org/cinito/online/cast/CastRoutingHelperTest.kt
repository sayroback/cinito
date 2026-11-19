package org.cinito.online.cast

import kotlinx.coroutines.test.runTest
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.metadata.ProbeByteSource
import org.cinito.online.metadata.ProbeTrackKind
import org.cinito.online.metadata.ProbedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CastRoutingHelperTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun buildCastAudioTracksMapsOrderedAudioOnly() {
        val probe = ContainerProbeResult(
            containerFormat = "matroska",
            tracks = listOf(
                ProbedTrack(ProbeTrackKind.Video, "und", null, "HEVC", "H.265", AudioLanguageTag.Other),
                ProbedTrack(ProbeTrackKind.Audio, "eng", "en", "English 5.1", "AC3", AudioLanguageTag.Other),
                ProbedTrack(ProbeTrackKind.Audio, "spa", "es-419", "Latino", "EAC3", AudioLanguageTag.Latino),
                ProbedTrack(ProbeTrackKind.Subtitle, "spa", "es-419", "Latino", "SRT", AudioLanguageTag.SubSpanish),
            ),
        )

        val result = CastRoutingHelper.buildCastAudioTracks(probe)
        assertEquals(2, result.size)
        assertEquals(0, result[0].index)
        assertEquals("en", result[0].language)
        assertEquals("AC3", result[0].codec)
        assertEquals(1, result[1].index)
        assertEquals("es-419", result[1].language)
        assertEquals(AudioLanguageTag.Latino, result[1].regionTag)
    }

    @Test
    fun pickPreferredAudioIndexPrefersLatinoForSpanishWhenPrioritized() {
        val tracks = listOf(
            CastAudioTrack(index = 0, name = "English", language = "en", regionTag = AudioLanguageTag.Other),
            CastAudioTrack(index = 1, name = "Castellano", language = "es-ES", regionTag = AudioLanguageTag.Castellano),
            CastAudioTrack(index = 2, name = "Español Latino", language = "es-419", regionTag = AudioLanguageTag.Latino),
        )

        val picked = CastRoutingHelper.pickPreferredAudioIndex(
            tracks = tracks,
            preferredLanguage = "es",
            prioritizeLatino = true,
        )
        assertEquals(2, picked)
    }

    @Test
    fun pickPreferredAudioIndexMatchesIsoThreeAndTwoLetterCodes() {
        val tracks = listOf(
            CastAudioTrack(index = 0, name = "English", language = "eng"),
            CastAudioTrack(index = 1, name = "Japanese", language = "jpn"),
        )

        assertEquals(1, CastRoutingHelper.pickPreferredAudioIndex(tracks, preferredLanguage = "ja"))
        assertEquals(0, CastRoutingHelper.pickPreferredAudioIndex(tracks, preferredLanguage = "en"))
        assertNull(CastRoutingHelper.pickPreferredAudioIndex(tracks, preferredLanguage = "fr"))
    }

    @Test
    fun extractFileIndexFromStreamUriParsesEngineStreamPath() {
        assertEquals(
            3,
            CastRoutingHelper.extractFileIndexFromStreamUri("http://127.0.0.1:41234/stream/abcd1234/3"),
        )
        assertEquals(
            12,
            CastRoutingHelper.extractFileIndexFromStreamUri("http://127.0.0.1:41234/stream/abcd/12?foo=1"),
        )
        assertNull(CastRoutingHelper.extractFileIndexFromStreamUri("content://media/external/video/42"))
    }

    @Test
    fun resolveEmbeddedSubtitleLabelsReadsProbedSubtitlesByHashAndFileIndex() = runTest {
        val probe = ContainerProbeResult(
            containerFormat = "matroska",
            tracks = listOf(
                ProbedTrack(ProbeTrackKind.Video, "und", null, null, "H.265", AudioLanguageTag.Other),
                ProbedTrack(ProbeTrackKind.Audio, "eng", "en", "English", "AC3", AudioLanguageTag.Other),
                ProbedTrack(ProbeTrackKind.Subtitle, "spa", "es-419", "Latino", "SRT", AudioLanguageTag.SubSpanish),
                ProbedTrack(ProbeTrackKind.Subtitle, "eng", "en", null, "ASS", AudioLanguageTag.Other),
            ),
        )
        val dummySource = object : ProbeByteSource {
            override val length: Long = 1024L
            override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int = -1
            override fun close() = Unit
        }
        val repo = AudioTrackProbeRepository(
            cacheDir = tempFolder.newFolder("cast_routing_probes"),
            sourceOpener = { _, _, _, _ -> dummySource },
            probeRunner = { probe },
            magnetDriver = null,
        )
        val hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        repo.probeFile(
            entryKey = null,
            infoHash = hash,
            fileIndex = 2,
            fileName = "Movie.2025.mkv",
            fileLength = 1024L,
            engineId = null,
            contentUri = "content://media/2",
        )

        val labels = CastRoutingHelper.resolveEmbeddedSubtitleLabels(repo, hash.uppercase(), 2, "Movie.2025.mkv")
        assertEquals(2, labels.size)
        assertEquals("Español Latino · Latino · SRT", labels[0])
        assertEquals("Inglés · ASS", labels[1])
        assertEquals(
            emptyList<String>(),
            CastRoutingHelper.resolveEmbeddedSubtitleLabels(repo, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 0, "Other.mkv"),
        )
    }
}
