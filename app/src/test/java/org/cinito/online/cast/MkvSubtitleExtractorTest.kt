package org.cinito.online.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream

class MkvSubtitleExtractorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractsUtf8AndAssSubtitlesInSinglePassAndSkipsVideoPayloads() {
        val videoPayload = ByteArray(256 * 1024) { 0xAB.toByte() }
        val mkvBytes = buildSyntheticMkv(
            timecodeScale = 1_000_000L,
            tracks = listOf(
                trackEntry(trackNumber = 1, trackType = 1, codecId = "V_MPEGH/ISO/HEVC"),
                trackEntry(trackNumber = 2, trackType = 17, codecId = "S_TEXT/UTF8", defaultDurationNs = 2_000_000_000L),
                trackEntry(trackNumber = 3, trackType = 17, codecId = "S_TEXT/ASS"),
            ),
            clusters = listOf(
                cluster(
                    timecode = 1_000L,
                    children = listOf(
                        // Video SimpleBlock (128 KiB) must be skipped cleanly
                        ebmlElement(0xA3L, mkvBlockPayload(trackNumber = 1, relTimecode = 0, data = videoPayload)),
                        // S_TEXT/UTF8 via BlockGroup + BlockDuration (1500ms -> 4000ms)
                        blockGroup(
                            trackNumber = 2,
                            relTimecode = 500,
                            durationTicks = 2_500L,
                            data = "Hola mundo".toByteArray(Charsets.UTF_8),
                        ),
                        // S_TEXT/ASS via BlockGroup + BlockDuration (2000ms -> 5000ms)
                        blockGroup(
                            trackNumber = 3,
                            relTimecode = 1_000,
                            durationTicks = 3_000L,
                            data = "1,0,Default,,0,0,0,,{\\an8\\i1}Hola, ASS\\NMundo{\\i0}".toByteArray(Charsets.UTF_8),
                        ),
                        // S_TEXT/UTF8 via SimpleBlock using Track DefaultDuration (6000ms -> 8000ms)
                        ebmlElement(
                            0xA3L,
                            mkvBlockPayload(
                                trackNumber = 2,
                                relTimecode = 5_000,
                                data = "Segunda línea".toByteArray(Charsets.UTF_8),
                            ),
                        ),
                    ),
                ),
            ),
        )

        var skippedBytes = 0L
        var readBytes = 0L
        val trackingStream = object : FilterInputStream(ByteArrayInputStream(mkvBytes)) {
            override fun read(): Int {
                val r = super.read()
                if (r >= 0) readBytes++
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) readBytes += n
                return n
            }

            override fun skip(n: Long): Long {
                val s = super.skip(n)
                if (s > 0) skippedBytes += s
                return s
            }
        }

        val extracted = MkvSubtitleExtractor.extractWebVttFromStream(
            input = trackingStream,
            targetTracks = mapOf(
                2 to "S_TEXT/UTF8",
                3 to "S_TEXT/ASS",
            ),
        )

        assertEquals(setOf(2, 3), extracted.keys)
        assertFalse(1 in extracted)

        val srtVtt = extracted.getValue(2)
        assertTrue(srtVtt.startsWith("WEBVTT\n\n"))
        assertTrue(srtVtt.contains("00:00:01.500 --> 00:00:04.000\nHola mundo"))
        assertTrue(srtVtt.contains("00:00:06.000 --> 00:00:08.000\nSegunda línea"))

        val assVtt = extracted.getValue(3)
        assertTrue(assVtt.startsWith("WEBVTT\n\n"))
        assertTrue(assVtt.contains("00:00:02.000 --> 00:00:05.000\nHola, ASS\nMundo"))
        assertFalse(assVtt.contains("{\\an8"))
        assertFalse(assVtt.contains("Default"))

        // Verify the 256 KiB video payload was skipped via buffer/skip instead of reading the whole payload
        assertTrue("Expected video payload to be skipped, skippedBytes=$skippedBytes", skippedBytes >= 128 * 1024)
        assertTrue("Expected readBytes ($readBytes) well below videoPayload (${videoPayload.size})", readBytes < 128 * 1024)
    }

    @Test
    fun supportsFallbackDurationCustomTimecodeScaleWebVttSsaAndUnknownClusterSize() {
        val mkvBytes = buildSyntheticMkv(
            timecodeScale = 500_000L, // 1 tick = 0.5 ms
            tracks = listOf(
                trackEntry(trackNumber = 1, trackType = 1, codecId = "V_VP9"),
                trackEntry(trackNumber = 2, trackType = 17, codecId = "S_TEXT/UTF8"), // no DefaultDuration -> 3500ms fallback
                trackEntry(trackNumber = 3, trackType = 17, codecId = "S_TEXT/SSA"),
                trackEntry(trackNumber = 4, trackType = 17, codecId = "S_TEXT/WEBVTT"),
            ),
            clusters = listOf(
                cluster(
                    timecode = 2_000L, // 1000 ms
                    unknownSize = true,
                    children = listOf(
                        ebmlElement(0xA3L, mkvBlockPayload(trackNumber = 1, relTimecode = 0, data = ByteArray(1024))),
                        ebmlElement(
                            0xA3L,
                            mkvBlockPayload(trackNumber = 2, relTimecode = 2_000, data = "Fallback cue".toByteArray(Charsets.UTF_8)),
                        ),
                        blockGroup(
                            trackNumber = 3,
                            relTimecode = 4_000, // 3000 ms
                            durationTicks = 3_000L, // 1500 ms
                            data = "0,0,Default,,0,0,0,,{\\b1}SSA\\nLine{\\b0}".toByteArray(Charsets.UTF_8),
                        ),
                    ),
                ),
                cluster(
                    timecode = 8_000L, // 4000 ms
                    children = listOf(
                        blockGroup(
                            trackNumber = 4,
                            relTimecode = 0, // 4000 ms
                            durationTicks = 4_000L, // 2000 ms
                            data = "Direct WebVTT cue".toByteArray(Charsets.UTF_8),
                        ),
                    ),
                ),
            ),
        )

        val extracted = MkvSubtitleExtractor.extractWebVttFromStream(
            input = ByteArrayInputStream(mkvBytes),
            targetTracks = mapOf(
                2 to "S_TEXT/UTF8",
                3 to "S_TEXT/SSA",
                4 to "S_TEXT/WEBVTT",
            ),
        )

        assertTrue(extracted.getValue(2).contains("00:00:02.000 --> 00:00:05.500\nFallback cue"))
        assertTrue(extracted.getValue(3).contains("00:00:03.000 --> 00:00:04.500\nSSA\nLine"))
        assertTrue(extracted.getValue(4).contains("00:00:04.000 --> 00:00:06.000\nDirect WebVTT cue"))
    }

    @Test
    fun extractFromSourceCachesDeterministicVttFilesUnderCacheDirCastSubs() {
        val mkvBytes = buildSyntheticMkv(
            timecodeScale = 1_000_000L,
            tracks = listOf(
                trackEntry(trackNumber = 2, trackType = 17, codecId = "S_TEXT/UTF8"),
            ),
            clusters = listOf(
                cluster(
                    timecode = 0L,
                    children = listOf(
                        blockGroup(
                            trackNumber = 2,
                            relTimecode = 1_000,
                            durationTicks = 2_000L,
                            data = "Cached cue".toByteArray(Charsets.UTF_8),
                        ),
                    ),
                ),
            ),
        )
        val mkvFile = tempFolder.newFile("sample.mkv").apply { writeBytes(mkvBytes) }
        val cacheDir = tempFolder.newFolder("app_cache")
        val specs = listOf(
            EmbeddedSubtitleSpec(
                trackNumber = 2,
                label = "Español",
                language = "es",
                rawCodecId = "S_TEXT/UTF8",
                isTextBased = true,
            ),
        )

        val first = MkvSubtitleExtractor.extractFromSource(
            context = null,
            sourceUri = mkvFile.toURI().toString(),
            targetTracks = specs,
            cacheDir = cacheDir,
        )
        val cachedFile = first[2]
        assertNotNull(cachedFile)
        cachedFile!!
        assertTrue(cachedFile.isFile)
        assertEquals(File(cacheDir, "cast_subs").canonicalPath, cachedFile.parentFile?.canonicalPath)
        assertTrue(cachedFile.name.endsWith(".vtt"))
        val content = cachedFile.readText(Charsets.UTF_8)
        assertTrue(content.startsWith("WEBVTT\n\n"))
        assertTrue(content.contains("00:00:01.000 --> 00:00:03.000\nCached cue"))

        // Overwrite source MKV with same-length garbage; second call must hit the deterministic cached .vtt file
        mkvFile.writeBytes(ByteArray(mkvBytes.size))
        val second = MkvSubtitleExtractor.extractFromSource(
            context = null,
            sourceUri = mkvFile.toURI().toString(),
            targetTracks = specs,
            cacheDir = cacheDir,
        )
        assertEquals(cachedFile.absolutePath, second[2]?.absolutePath)
        assertEquals(content, second[2]?.readText(Charsets.UTF_8))
    }

    private fun buildSyntheticMkv(
        timecodeScale: Long,
        tracks: List<ByteArray>,
        clusters: List<ByteArray>,
    ): ByteArray {
        val ebmlHeader = ebmlElement(
            0x1A45DFA3L,
            concat(
                ebmlUint(0x4286L, 1L),
                ebmlString(0x4282L, "matroska"),
            ),
        )
        val info = ebmlElement(0x1549A966L, ebmlUint(0x2AD7B1L, timecodeScale))
        val tracksElem = ebmlElement(0x1654AE6BL, concat(*tracks.toTypedArray()))
        val segmentBody = concat(info, tracksElem, *clusters.toTypedArray())
        val segment = ebmlElement(0x18538067L, segmentBody)
        return concat(ebmlHeader, segment)
    }

    private fun trackEntry(
        trackNumber: Int,
        trackType: Int,
        codecId: String,
        defaultDurationNs: Long? = null,
    ): ByteArray {
        val parts = mutableListOf(
            ebmlUint(0xD7L, trackNumber.toLong()),
            ebmlUint(0x83L, trackType.toLong()),
            ebmlString(0x86L, codecId),
        )
        if (defaultDurationNs != null) {
            parts += ebmlUint(0x23E383L, defaultDurationNs)
        }
        return ebmlElement(0xAEL, concat(*parts.toTypedArray()))
    }

    private fun cluster(
        timecode: Long,
        unknownSize: Boolean = false,
        children: List<ByteArray>,
    ): ByteArray {
        val body = concat(ebmlUint(0xE7L, timecode), *children.toTypedArray())
        return if (unknownSize) ebmlElementUnknownSize(0x1F43B675L, body) else ebmlElement(0x1F43B675L, body)
    }

    private fun blockGroup(
        trackNumber: Int,
        relTimecode: Int,
        durationTicks: Long,
        data: ByteArray,
    ): ByteArray = ebmlElement(
        0xA0L,
        concat(
            ebmlElement(0xA1L, mkvBlockPayload(trackNumber, relTimecode, data = data)),
            ebmlUint(0x9BL, durationTicks),
        ),
    )

    private fun mkvBlockPayload(
        trackNumber: Int,
        relTimecode: Int,
        flags: Int = 0,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVintSize(trackNumber.toLong()))
        out.write((relTimecode ushr 8) and 0xFF)
        out.write(relTimecode and 0xFF)
        out.write(flags and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun ebmlUint(id: Long, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        if (value == 0L) {
            out.write(0)
        } else {
            val bytes = mutableListOf<Byte>()
            var v = value
            while (v > 0L) {
                bytes.add(0, (v and 0xFFL).toByte())
                v = v ushr 8
            }
            bytes.forEach { out.write(it.toInt() and 0xFF) }
        }
        return ebmlElement(id, out.toByteArray())
    }

    private fun ebmlString(id: Long, text: String): ByteArray =
        ebmlElement(id, text.toByteArray(Charsets.UTF_8))

    private fun ebmlElement(id: Long, payload: ByteArray): ByteArray =
        concat(encodeId(id), encodeVintSize(payload.size.toLong()), payload)

    private fun ebmlElementUnknownSize(id: Long, payload: ByteArray): ByteArray =
        concat(encodeId(id), byteArrayOf(0xFF.toByte()), payload)

    private fun encodeId(id: Long): ByteArray {
        val len = when {
            id <= 0xFFL -> 1
            id <= 0xFFFFL -> 2
            id <= 0xFFFFFFL -> 3
            else -> 4
        }
        return ByteArray(len) { idx ->
            ((id ushr ((len - 1 - idx) * 8)) and 0xFFL).toByte()
        }
    }

    private fun encodeVintSize(size: Long): ByteArray {
        return when {
            size < 0x7FL -> byteArrayOf((0x80L or size).toByte())
            size < 0x3FFFL -> byteArrayOf(
                (0x40L or (size ushr 8)).toByte(),
                (size and 0xFFL).toByte(),
            )
            size < 0x1FFFFFL -> byteArrayOf(
                (0x20L or (size ushr 16)).toByte(),
                ((size ushr 8) and 0xFFL).toByte(),
                (size and 0xFFL).toByte(),
            )
            else -> byteArrayOf(
                (0x10L or (size ushr 24)).toByte(),
                ((size ushr 16) and 0xFFL).toByte(),
                ((size ushr 8) and 0xFFL).toByte(),
                (size and 0xFFL).toByte(),
            )
        }
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        arrays.forEach { out.write(it) }
        return out.toByteArray()
    }
}
