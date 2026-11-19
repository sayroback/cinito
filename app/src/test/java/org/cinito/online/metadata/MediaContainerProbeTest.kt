package org.cinito.online.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MediaContainerProbeTest {
    private class ArrayByteSource(private val data: ByteArray) : ProbeByteSource {
        override val length: Long get() = data.size.toLong()
        override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int {
            if (offset < 0 || offset >= data.size) return -1
            val n = minOf(size, buffer.size, data.size - offset.toInt())
            System.arraycopy(data, offset.toInt(), buffer, 0, n)
            return n
        }
        override fun close() = Unit
    }

    @Test
    fun probesMatroskaAudioAndSubtitleTracksWithLatinoBcp47AndName() {
        val latEntry = mkvTrackEntry(
            trackType = 2,
            codec = "A_EAC3",
            language = "spa",
            bcp47 = "es-419",
            name = "Español Latino 5.1",
        )
        val engEntry = mkvTrackEntry(
            trackType = 2,
            codec = "A_TRUEHD",
            language = "eng",
            bcp47 = "en",
            name = "English Atmos",
        )
        val subEntry = mkvTrackEntry(
            trackType = 17,
            codec = "S_TEXT/ASS",
            language = "spa",
            bcp47 = "es-419",
            name = "Forzados Latino",
        )
        val tracksBody = latEntry + engEntry + subEntry
        val tracksElement = ebmlElem(0x1654AE6BL, tracksBody)
        val header = ebmlElem(0x1A45DFA3L, byteArrayOf(0x01)) + tracksElement

        val result = MediaContainerProbe.probe(ArrayByteSource(header))
        assertNotNull(result)
        assertEquals("MKV", result?.containerFormat)
        assertTrue(result?.hasLatinoAudio == true)
        assertEquals(2, result?.audioTracks?.size)
        assertEquals(1, result?.subtitleTracks?.size)
        assertEquals("EAC3", result?.audioTracks?.get(0)?.codec)
        assertEquals(AudioLanguageTag.Latino, result?.audioTracks?.get(0)?.regionTag)
        assertEquals("Español Latino · Español Latino 5.1 · EAC3", result?.audioTracks?.get(0)?.displayLabel)
    }

    @Test
    fun probesMp4TailMoovSeekingPastMdatWithoutScanningPayload() {
        val ftyp = mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000".toByteArray(Charsets.US_ASCII))
        val mdatPayload = ByteArray(1024 * 128) // skipped directly via box size!
        val mdat = mp4Box("mdat", mdatPayload)

        val mdhdSpa = mp4MdhdBox("spa")
        val hdlrSoun = mp4HdlrBox("soun", "Español Latino")
        val elng = mp4ElngBox("es-419")
        val mdia = mp4Box("mdia", mdhdSpa + hdlrSoun + elng)
        val trak = mp4Box("trak", mdia)
        val moov = mp4Box("moov", trak)

        val fileBytes = ftyp + mdat + moov
        val result = MediaContainerProbe.probe(ArrayByteSource(fileBytes))
        assertNotNull(result)
        assertEquals("MP4", result?.containerFormat)
        assertEquals(false, result?.isFastStartMp4)
        assertEquals(1, result?.audioTracks?.size)
        assertEquals(AudioLanguageTag.Latino, result?.audioTracks?.first()?.regionTag)
        assertEquals("es-419", result?.audioTracks?.first()?.bcp47)
    }

    @Test
    fun probesMp4FastStartWhenMoovPrecedesMdat() {
        val ftyp = mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000".toByteArray(Charsets.US_ASCII))
        val mdat = mp4Box("mdat", ByteArray(4096))
        val mdhdSpa = mp4MdhdBox("spa")
        val hdlrSoun = mp4HdlrBox("soun", "Español Latino")
        val mdia = mp4Box("mdia", mdhdSpa + hdlrSoun)
        val trak = mp4Box("trak", mdia)
        val moov = mp4Box("moov", trak)

        val result = MediaContainerProbe.probe(ArrayByteSource(ftyp + moov + mdat))
        assertNotNull(result)
        assertEquals("MP4", result?.containerFormat)
        assertEquals(true, result?.isFastStartMp4)
    }

    @Test
    fun probesLargeMp4MoovOverTwoMegabytesViaStreamingTrakWalk() {
        val ftyp = mp4Box("ftyp", "isom\u0000\u0000\u0002\u0000".toByteArray(Charsets.US_ASCII))
        // Video trak with a 2.4 MiB sample table inside minf -> stbl (exceeds old 2 MiB moov cap!)
        val mdhdVid = mp4MdhdBox("und")
        val hdlrVid = mp4HdlrBox("vide", "VideoHandler")
        val stsdVid = mp4Box("stsd", ByteArray(8) + mp4Box("avc1", ByteArray(24)))
        val hugeStts = mp4Box("stts", ByteArray(2_400_000))
        val minfVid = mp4Box("minf", mp4Box("stbl", stsdVid + hugeStts))
        val trakVid = mp4Box("trak", mp4Box("mdia", mdhdVid + hdlrVid + minfVid))

        // Audio trak placed AFTER the 2.4 MiB video trak inside moov
        val mdhdLat = mp4MdhdBox("spa")
        val hdlrLat = mp4HdlrBox("soun", "Español Latino")
        val elngLat = mp4ElngBox("es-419")
        val stsdLat = mp4Box("stsd", ByteArray(8) + mp4Box("mp4a", ByteArray(24)))
        val minfLat = mp4Box("minf", mp4Box("stbl", stsdLat))
        val trakLat = mp4Box("trak", mp4Box("mdia", mdhdLat + hdlrLat + elngLat + minfLat))

        val moov = mp4Box("moov", trakVid + trakLat)
        val mdat = mp4Box("mdat", ByteArray(1024))

        val result = MediaContainerProbe.probe(ArrayByteSource(ftyp + mdat + moov))
        assertNotNull(result)
        assertEquals("MP4", result?.containerFormat)
        assertEquals(1, result?.audioTracks?.size)
        assertEquals(AudioLanguageTag.Latino, result?.audioTracks?.first()?.regionTag)
        assertEquals("MP4A", result?.audioTracks?.first()?.codec)
    }

    @Test
    fun probesMkvTracksAtTailViaSeekHeadBeyondOneMegabyte() {
        val trackLat = mkvTrackEntry(2, "A_EAC3", "spa", "es-419", "Latino")
        val tracksElement = ebmlElem(0x1654AE6BL, trackLat)
        val paddingVoid = ByteArray(1_200_000) // > 1 MiB Attachments/Cluster before Tracks

        val tracksSeekPos = paddingVoid.size + 64L // relative to Segment content start
        val seekEntry = ebmlElem(
            0x4DBBL,
            ebmlElem(0x53ABL, byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B)) +
                ebmlElem(0x53ACL, longToBigEndianMinimal(tracksSeekPos)),
        )
        val seekHead = ebmlElem(0x114D9B74L, seekEntry)
        val voidPad = ByteArray((tracksSeekPos - seekHead.size).toInt())
        val segmentBody = seekHead + voidPad + tracksElement
        val segment = ebmlElem(0x18538067L, segmentBody)
        val mkv = ebmlElem(0x1A45DFA3L, byteArrayOf(0x01)) + segment

        val result = MediaContainerProbe.probe(ArrayByteSource(mkv))
        assertNotNull(result)
        assertEquals("MKV", result?.containerFormat)
        assertEquals(1, result?.audioTracks?.size)
        assertEquals(AudioLanguageTag.Latino, result?.audioTracks?.first()?.regionTag)
    }

    private fun longToBigEndianMinimal(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        do {
            bytes.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        } while (v > 0L)
        return bytes.toByteArray()
    }

    @Test
    fun preservesUntaggedSingleAudioTrackWithoutFabricatingEnglish() {
        val untaggedAudio = mkvTrackEntry(
            trackType = 2,
            codec = "A_AAC",
            language = null,
            bcp47 = null,
            name = null,
        )
        val tracksElement = ebmlElem(0x1654AE6BL, untaggedAudio)
        val header = ebmlElem(0x1A45DFA3L, byteArrayOf(0x01)) + tracksElement

        val result = MediaContainerProbe.probe(ArrayByteSource(header))
        assertNotNull(result)
        assertEquals(1, result?.audioTrackCount)
        assertTrue(result?.isSingleAudioTrack == true)
        assertTrue(result?.isMultiAudioTrack == false)
        assertTrue(result?.hasUntaggedSingleAudioTrack == true)
        val track = result?.audioTracks?.first()
        assertEquals(null, track?.languageCode)
        assertEquals(false, track?.hasExplicitLanguage)
        assertEquals(AudioLanguageTag.Other, result?.primaryAudioTag)
        assertEquals("1 pista (única · sin idioma)", result?.compactAudioBadgeLabel())
    }

    @Test
    fun summarizesMultiAudioVsSingleEnglishWithLatinoSubtitles() {
        val engAudio = mkvTrackEntry(2, "A_AAC", "eng", "en", "English")
        val latSub = mkvTrackEntry(17, "S_TEXT/UTF8", "spa", "es-419", "Latino")
        val singleEngWithLatSub = MediaContainerProbe.probe(
            ArrayByteSource(ebmlElem(0x1A45DFA3L, byteArrayOf(0x01)) + ebmlElem(0x1654AE6BL, engAudio + latSub)),
        )
        assertNotNull(singleEngWithLatSub)
        assertTrue(singleEngWithLatSub?.isSingleAudioTrack == true)
        assertEquals(false, singleEngWithLatSub?.hasLatinoAudio)
        assertEquals(AudioLanguageTag.SubSpanish, singleEngWithLatSub?.primaryAudioTag)
        assertEquals("1 pista: Eng + Sub Lat", singleEngWithLatSub?.compactAudioBadgeLabel())

        val latAudio = mkvTrackEntry(2, "A_EAC3", "spa", "es-419", "Latino")
        val multi = MediaContainerProbe.probe(
            ArrayByteSource(ebmlElem(0x1A45DFA3L, byteArrayOf(0x01)) + ebmlElem(0x1654AE6BL, latAudio + engAudio + latSub)),
        )
        assertNotNull(multi)
        assertTrue(multi?.isMultiAudioTrack == true)
        assertEquals(AudioLanguageTag.Latino, multi?.primaryAudioTag)
        assertEquals("2 pistas: Lat · Eng", multi?.compactAudioBadgeLabel())
    }

    private fun mkvTrackEntry(
        trackType: Int,
        codec: String,
        language: String?,
        bcp47: String?,
        name: String?,
    ): ByteArray {
        val parts = mutableListOf(
            ebmlElem(0x83L, byteArrayOf(trackType.toByte())),
            ebmlElem(0x86L, codec.toByteArray(Charsets.UTF_8)),
        )
        if (!language.isNullOrEmpty()) parts += ebmlElem(0x22B59CL, language.toByteArray(Charsets.UTF_8))
        if (!bcp47.isNullOrEmpty()) parts += ebmlElem(0x22B59DL, bcp47.toByteArray(Charsets.UTF_8))
        if (!name.isNullOrEmpty()) parts += ebmlElem(0x536EL, name.toByteArray(Charsets.UTF_8))
        val body = parts.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return ebmlElem(0xAEL, body)
    }

    private fun ebmlElem(id: Long, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = when {
            id <= 0xFFL -> byteArrayOf(id.toByte())
            id <= 0xFFFFL -> byteArrayOf((id ushr 8).toByte(), id.toByte())
            id <= 0xFFFFFFL -> byteArrayOf((id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
            else -> byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())
        }
        out.write(idBytes)
        // 4-byte VINT size (0x10000000 | size)
        val sz = body.size
        out.write(
            byteArrayOf(
                (0x10 or ((sz ushr 24) and 0x0F)).toByte(),
                ((sz ushr 16) and 0xFF).toByte(),
                ((sz ushr 8) and 0xFF).toByte(),
                (sz and 0xFF).toByte(),
            ),
        )
        out.write(body)
        return out.toByteArray()
    }

    private fun mp4Box(type: String, body: ByteArray): ByteArray {
        val sz = 8 + body.size
        val hdr = byteArrayOf(
            ((sz ushr 24) and 0xFF).toByte(),
            ((sz ushr 16) and 0xFF).toByte(),
            ((sz ushr 8) and 0xFF).toByte(),
            (sz and 0xFF).toByte(),
        ) + type.toByteArray(Charsets.US_ASCII)
        return hdr + body
    }

    private fun mp4MdhdBox(lang3: String): ByteArray {
        val body = ByteArray(24) // version 0: 4B fullbox + 16B times/duration + 2B lang + 2B quality
        val c1 = (lang3[0].code - 0x60) and 0x1F
        val c2 = (lang3[1].code - 0x60) and 0x1F
        val c3 = (lang3[2].code - 0x60) and 0x1F
        val packed = (c1 shl 10) or (c2 shl 5) or c3
        body[20] = ((packed ushr 8) and 0xFF).toByte()
        body[21] = (packed and 0xFF).toByte()
        return mp4Box("mdhd", body)
    }

    private fun mp4HdlrBox(handler: String, name: String): ByteArray {
        val head = ByteArray(24)
        System.arraycopy(handler.toByteArray(Charsets.US_ASCII), 0, head, 8, 4)
        return mp4Box("hdlr", head + name.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
    }

    private fun mp4ElngBox(tag: String): ByteArray =
        mp4Box("elng", ByteArray(4) + tag.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
}
