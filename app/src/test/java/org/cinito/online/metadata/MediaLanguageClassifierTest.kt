package org.cinito.online.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLanguageClassifierTest {
    @Test
    fun distinguishesLatinoCastellanoGenericAndSubtitled() {
        val lat = MediaLanguageClassifier.classify("Dune.Part.Two.2024.1080p.WEB-DL.ENG.LATINO.H265.MKV")
        assertEquals(AudioLanguageTag.Latino, lat.tag)

        val latDesc = MediaLanguageClassifier.classify(
            title = "Spider-Man.No.Way.Home.2021.1080p.Dual.mkv",
            description = "Audio 1: Español Latino AC3 5.1 | Audio 2: Inglés EAC3",
        )
        assertEquals(AudioLanguageTag.Latino, latDesc.tag)

        val cast = MediaLanguageClassifier.classify("Oppenheimer.2023.Bluray.Castellano.1080p.mkv")
        assertEquals(AudioLanguageTag.Castellano, cast.tag)

        val vose = MediaLanguageClassifier.classify("Poor.Things.2023.1080p.VOSE.Sub.Esp.mkv")
        assertEquals(AudioLanguageTag.SubSpanish, vose.tag)

        val generic = MediaLanguageClassifier.classify("Intensamente.2015.Dual.Español.1080p.mkv")
        assertEquals(AudioLanguageTag.SpanishGeneric, generic.tag)

        val eng = MediaLanguageClassifier.classify("Dune.Part.Two.2024.1080p.WEBRip.x265-YTS.mp4")
        assertEquals(AudioLanguageTag.Other, eng.tag)
        assertNull(eng.matchedEvidence)

        val torrentioMx = MediaLanguageClassifier.classify(
            title = "Wildwood.2026.1080p.CAMRip.mp4",
            description = "Fuente: Cinecalidad · Dual Audio / 🇲🇽",
        )
        assertEquals(AudioLanguageTag.Latino, torrentioMx.tag)

        val torrentioBothFlags = MediaLanguageClassifier.classify(
            title = "Pelicula.2024.1080p.SPANiSH.mkv",
            description = "Fuente: TorrentGalaxy · 🇪🇸 / 🇲🇽",
        )
        assertEquals(AudioLanguageTag.Latino, torrentioBothFlags.tag)

        val torrentioEs = MediaLanguageClassifier.classify(
            title = "Pelicula.2024.1080p.mkv",
            description = "Fuente: Wolfmax4K · 🇪🇸",
        )
        assertEquals(AudioLanguageTag.Castellano, torrentioEs.tag)

        val portugueseDual = MediaLanguageClassifier.classify(
            title = "Homem-Aranha - Sem Volta Para Casa 2022 2160p BluRay DUAL",
            description = "Fuente: Torrentio · Dual Audio / 🇬🇧 / 🇵🇹",
            providerLanguageField = "Dual Audio / 🇬🇧 / 🇵🇹",
        )
        assertEquals(AudioLanguageTag.Other, portugueseDual.tag)

        val galaxyRgSubOnly = MediaLanguageClassifier.classify(
            title = "Inside.Out.2.2024.1080p.WEBRip.1400MB.DD5.1.x264-GalaxyRG",
            description = "Included subtitles = English, English, Spanish\n--Audio\nLanguage = English\nSubtitles: Spanish",
            providerLanguageField = "English",
        )
        assertEquals(AudioLanguageTag.SubSpanish, galaxyRgSubOnly.tag)

        val subLatinoOnly = MediaLanguageClassifier.classify(
            title = "Oppenheimer.2023.1080p.WEB-DL.Sub.Latino.mkv",
        )
        assertEquals(AudioLanguageTag.SubSpanish, subLatinoOnly.tag)
    }

    @Test
    fun classifiesTrackMetadataFromContainerHeaders() {
        assertEquals(
            AudioLanguageTag.Latino,
            MediaLanguageClassifier.classifyTrackMetadata("spa", "es-419", "Audio 1"),
        )
        assertEquals(
            AudioLanguageTag.Latino,
            MediaLanguageClassifier.classifyTrackMetadata("spa", null, "Español Latino AC3 5.1"),
        )
        assertEquals(
            AudioLanguageTag.Castellano,
            MediaLanguageClassifier.classifyTrackMetadata("spa", null, "Castellano DTS"),
        )
        assertEquals(
            AudioLanguageTag.SpanishGeneric,
            MediaLanguageClassifier.classifyTrackMetadata("spa", null, "Spanish"),
        )
    }

    @Test
    fun rejectsSubtitleFalsePositivesAcrossMultiLineLongListsFlagsAndCastCredit() {
        val longSubList = MediaLanguageClassifier.classify(
            title = "Severance.S02E01.1080p.WEB-DL.DDP5.1.H264",
            description = "Audio: English DD+ 5.1\nSubtitles: English, French, German, Italian, Portuguese, Spanish (Latino)",
        )
        assertEquals(AudioLanguageTag.SubSpanish, longSubList.tag)

        val multiLineSubSection = MediaLanguageClassifier.classify(
            title = "The.Last.of.Us.S01E01.2160p.WEB-DL",
            description = "Audio:\n- English Atmos\n\nSubtitles:\n- English\n- Latino 🇲🇽\n- Spanish",
        )
        assertEquals(AudioLanguageTag.SubSpanish, multiLineSubSection.tag)

        val flagInSubContext = MediaLanguageClassifier.classify(
            title = "Anora.2024.1080p.WEB-DL.Subbed",
            description = "Fuente: Torrentio · 🇲🇽",
            providerLanguageField = "🇲🇽",
        )
        assertEquals(AudioLanguageTag.SubSpanish, flagInSubContext.tag)

        val castCreditInDescription = MediaLanguageClassifier.classify(
            title = "F1.2025.1080p.WEB-DL.x264",
            description = "Cast: Brad Pitt, Javier Bardem, Damson Idris",
        )
        assertEquals(AudioLanguageTag.Other, castCreditInDescription.tag)

        val subtitleFieldOnly = MediaLanguageClassifier.classify(
            title = "Dune.Part.Two.2024.1080p.WEBRip",
            subtitleLanguageField = "Spanish",
        )
        assertEquals(AudioLanguageTag.SubSpanish, subtitleFieldOnly.tag)

        val bothAudioAndSubtitleFields = MediaLanguageClassifier.classify(
            title = "Duna.2024.1080p.WEBRip",
            providerLanguageField = "Spanish",
            subtitleLanguageField = "Spanish",
        )
        assertEquals(AudioLanguageTag.SpanishGeneric, bothAudioAndSubtitleFields.tag)

        val audioSectionAfterSubSection = MediaLanguageClassifier.classify(
            title = "Inception.2010.1080p.BluRay",
            description = "Subtitles: English, Spanish\n--Audio\nLanguage: Español Latino",
        )
        assertEquals(AudioLanguageTag.Latino, audioSectionAfterSubSection.tag)
    }
}
