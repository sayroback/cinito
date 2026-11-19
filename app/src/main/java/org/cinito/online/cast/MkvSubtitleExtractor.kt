package org.cinito.online.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Pure-Kotlin single-pass streaming EBML/Matroska subtitle extractor.
 *
 * Parses Matroska `Segment` -> `Info`, `Tracks`, and `Cluster` sequentially while calling
 * [EbmlStreamReader.skipExact] on all video/audio payloads so large MKV files never load
 * media frames into memory.
 */
object MkvSubtitleExtractor {
    private const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L
    private const val DEFAULT_SUBTITLE_DURATION_MS = 3_500L
    private const val MAX_SUBTITLE_BLOCK_BYTES = 1024 * 1024
    private const val MAX_STRING_ELEMENT_BYTES = 16 * 1024
    private const val CACHE_SUBDIR = "cast_subs"

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_DEFAULT_DURATION = 0x23E383L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    private val SEGMENT_LEVEL_IDS = setOf(
        0x114D9B74L, // SeekHead
        ID_INFO,
        ID_TRACKS,
        ID_CLUSTER,
        0x1C53BB6BL, // Cues
        0x1941A469L, // Attachments
        0x1043A770L, // Chapters
        0x1254C367L, // Tags
    )

    private val ASS_TAG_REGEX = Regex("""\{\\[^}\r\n]*\}""")
    private val MULTI_NEWLINE_REGEX = Regex("""\n{2,}""")

    private data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    /**
     * Extracts WebVTT content for the requested [targetTracks] (`trackNumber -> rawCodecId`)
     * from a Matroska [input] stream in a single sequential pass.
     */
    fun extractWebVttFromStream(
        input: InputStream,
        targetTracks: Map<Int, String>,
    ): Map<Int, String> {
        if (targetTracks.isEmpty()) return emptyMap()

        val reader = EbmlStreamReader(input)
        var segmentEnd = Long.MAX_VALUE
        var foundSegment = false

        while (true) {
            val id = reader.readEbmlId() ?: break
            val (size, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            if (id == ID_SEGMENT) {
                foundSegment = true
                segmentEnd = if (size >= 0L) reader.position + size else Long.MAX_VALUE
                break
            }
            if (size < 0L) break
            if (!reader.skipExact(size)) break
        }
        if (!foundSegment) return emptyMap()

        var timecodeScaleNs = DEFAULT_TIMECODE_SCALE_NS
        val streamCodecs = mutableMapOf<Int, String>()
        val defaultDurationsMs = mutableMapOf<Int, Long>()
        val cuesByTrack = mutableMapOf<Int, MutableList<SubtitleCue>>()

        fun resolvedCodec(trackNumber: Int): String? {
            val fromStream = streamCodecs[trackNumber]
            val fromCaller = targetTracks[trackNumber]
            return normalizeCodecId(fromStream) ?: normalizeCodecId(fromCaller)
        }

        while (reader.position < segmentEnd) {
            val elemId = reader.readEbmlId() ?: break
            val (elemSize, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            val elemEnd = if (elemSize >= 0L) {
                minOf(segmentEnd, reader.position + elemSize)
            } else {
                segmentEnd
            }

            when (elemId) {
                ID_INFO -> {
                    if (elemSize >= 0L) {
                        timecodeScaleNs = parseInfo(reader, elemEnd, timecodeScaleNs)
                    } else {
                        break
                    }
                }
                ID_TRACKS -> {
                    if (elemSize >= 0L) {
                        parseTracks(reader, elemEnd, streamCodecs, defaultDurationsMs)
                    } else {
                        break
                    }
                }
                ID_CLUSTER -> {
                    parseCluster(
                        reader = reader,
                        clusterEnd = elemEnd,
                        unknownClusterSize = elemSize < 0L,
                        timecodeScaleNs = timecodeScaleNs,
                        targetTrackNumbers = targetTracks.keys,
                        defaultDurationsMs = defaultDurationsMs,
                        codecResolver = ::resolvedCodec,
                        cuesByTrack = cuesByTrack,
                    )
                }
                else -> {
                    if (elemSize < 0L) break
                    if (!reader.skipExact(elemEnd - reader.position)) break
                }
            }

            if (elemSize >= 0L && reader.position < elemEnd) {
                if (!reader.skipExact(elemEnd - reader.position)) break
            }
        }

        val out = LinkedHashMap<Int, String>()
        for (trackNumber in targetTracks.keys) {
            val cues = cuesByTrack[trackNumber]
            if (cues.isNullOrEmpty()) continue
            val sorted = cues.sortedWith(compareBy({ it.startMs }, { it.endMs }))
            out[trackNumber] = buildWebVtt(sorted)
        }
        return out
    }

    /**
     * Extracts text-based subtitle tracks from [sourceUri] into deterministic cached `.vtt` files
     * under `cacheDir/cast_subs/`. Returns `trackNumber -> cachedFile`.
     */
    fun extractFromSource(
        context: Context?,
        sourceUri: String,
        targetTracks: Map<Int, String>,
        cacheDir: File? = context?.cacheDir,
    ): Map<Int, File> {
        val supportedTargets = targetTracks.filterValues { normalizeCodecId(it) != null || it.isBlank() }
        if (supportedTargets.isEmpty()) return emptyMap()
        val rootCacheDir = cacheDir ?: context?.cacheDir ?: return emptyMap()
        val subCacheDir = File(rootCacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val sourceSize = resolveSourceSize(context, sourceUri)

        val result = LinkedHashMap<Int, File>()
        val missingTracks = LinkedHashMap<Int, String>()
        val cacheFiles = HashMap<Int, File>()

        for ((trackNum, codecId) in supportedTargets) {
            val key = sha256Hex("$sourceUri|$sourceSize|$trackNum")
            val vttFile = File(subCacheDir, "$key.vtt")
            cacheFiles[trackNum] = vttFile
            if (vttFile.isFile && vttFile.length() > 0L) {
                result[trackNum] = vttFile
            } else {
                missingTracks[trackNum] = codecId
            }
        }

        if (missingTracks.isEmpty()) return result

        val extracted = openSourceStream(context, sourceUri)?.use { stream ->
            extractWebVttFromStream(stream, missingTracks)
        }.orEmpty()

        for ((trackNum, vttContent) in extracted) {
            val destFile = cacheFiles[trackNum] ?: continue
            writeVttAtomically(subCacheDir, destFile, vttContent)
            if (destFile.isFile && destFile.length() > 0L) {
                result[trackNum] = destFile
            }
        }
        return result
    }

    /** Convenience overload accepting probed [EmbeddedSubtitleSpec] items. */
    fun extractFromSource(
        context: Context?,
        sourceUri: String,
        targetTracks: List<EmbeddedSubtitleSpec>,
        cacheDir: File? = context?.cacheDir,
    ): Map<Int, File> {
        val trackMap = LinkedHashMap<Int, String>()
        for (spec in targetTracks) {
            if (spec.isTextBased && spec.trackNumber > 0) {
                trackMap[spec.trackNumber] = spec.rawCodecId.orEmpty()
            }
        }
        return extractFromSource(context, sourceUri, trackMap, cacheDir)
    }

    private fun parseInfo(
        reader: EbmlStreamReader,
        infoEnd: Long,
        currentScaleNs: Long,
    ): Long {
        var scaleNs = currentScaleNs
        while (reader.position < infoEnd) {
            val id = reader.readEbmlId() ?: break
            val (size, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            if (size < 0L) break
            val childEnd = minOf(infoEnd, reader.position + size)
            if (id == ID_TIMECODE_SCALE && size in 1L..8L) {
                val parsed = reader.readUnsignedInt(size.toInt())
                if (parsed != null && parsed > 0L) {
                    scaleNs = parsed
                }
            } else {
                if (!reader.skipExact(childEnd - reader.position)) break
            }
            if (reader.position < childEnd && !reader.skipExact(childEnd - reader.position)) break
        }
        return scaleNs
    }

    private fun parseTracks(
        reader: EbmlStreamReader,
        tracksEnd: Long,
        streamCodecs: MutableMap<Int, String>,
        defaultDurationsMs: MutableMap<Int, Long>,
    ) {
        while (reader.position < tracksEnd) {
            val id = reader.readEbmlId() ?: break
            val (size, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            if (size < 0L) break
            val childEnd = minOf(tracksEnd, reader.position + size)
            if (id == ID_TRACK_ENTRY) {
                parseTrackEntry(reader, childEnd, streamCodecs, defaultDurationsMs)
            } else {
                if (!reader.skipExact(childEnd - reader.position)) break
            }
            if (reader.position < childEnd && !reader.skipExact(childEnd - reader.position)) break
        }
    }

    private fun parseTrackEntry(
        reader: EbmlStreamReader,
        entryEnd: Long,
        streamCodecs: MutableMap<Int, String>,
        defaultDurationsMs: MutableMap<Int, Long>,
    ) {
        var trackNumber: Int? = null
        var codecId: String? = null
        var defaultDurationNs: Long? = null

        while (reader.position < entryEnd) {
            val id = reader.readEbmlId() ?: break
            val (size, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            if (size < 0L) break
            val childEnd = minOf(entryEnd, reader.position + size)
            when (id) {
                ID_TRACK_NUMBER -> if (size in 1L..8L) {
                    trackNumber = reader.readUnsignedInt(size.toInt())?.toInt()?.takeIf { it > 0 }
                }
                ID_CODEC_ID -> if (size in 1L..MAX_STRING_ELEMENT_BYTES.toLong()) {
                    val bytes = reader.readBytes(size.toInt()) ?: break
                    codecId = String(bytes, Charsets.UTF_8).trim { it == '\u0000' || it <= ' ' }
                }
                ID_DEFAULT_DURATION -> if (size in 1L..8L) {
                    defaultDurationNs = reader.readUnsignedInt(size.toInt())?.takeIf { it > 0L }
                }
                else -> if (!reader.skipExact(childEnd - reader.position)) break
            }
            if (reader.position < childEnd && !reader.skipExact(childEnd - reader.position)) break
        }

        val num = trackNumber ?: return
        if (!codecId.isNullOrBlank()) {
            streamCodecs[num] = codecId
        }
        if (defaultDurationNs != null) {
            val ms = (defaultDurationNs / 1_000_000L).coerceAtLeast(1L)
            defaultDurationsMs[num] = ms
        }
    }

    private fun parseCluster(
        reader: EbmlStreamReader,
        clusterEnd: Long,
        unknownClusterSize: Boolean,
        timecodeScaleNs: Long,
        targetTrackNumbers: Set<Int>,
        defaultDurationsMs: Map<Int, Long>,
        codecResolver: (Int) -> String?,
        cuesByTrack: MutableMap<Int, MutableList<SubtitleCue>>,
    ) {
        var clusterTimecode = 0L

        while (reader.position < clusterEnd) {
            val (id, idBytes) = reader.readEbmlIdWithBytes() ?: break
            if (unknownClusterSize && id in SEGMENT_LEVEL_IDS) {
                reader.unread(idBytes)
                break
            }
            val (size, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            if (size < 0L) break
            val childEnd = minOf(clusterEnd, reader.position + size)

            when (id) {
                ID_CLUSTER_TIMECODE -> if (size in 1L..8L) {
                    clusterTimecode = reader.readUnsignedInt(size.toInt()) ?: clusterTimecode
                }
                ID_SIMPLE_BLOCK -> {
                    parseBlock(
                        reader = reader,
                        blockSize = childEnd - reader.position,
                        clusterTimecode = clusterTimecode,
                        timecodeScaleNs = timecodeScaleNs,
                        targetTrackNumbers = targetTrackNumbers,
                        codecResolver = codecResolver,
                    )?.let { (trackNum, startMs, payload) ->
                        val codec = codecResolver(trackNum)
                        if (codec != null) {
                            val durMs = defaultDurationsMs[trackNum] ?: DEFAULT_SUBTITLE_DURATION_MS
                            convertPayloadToCue(codec, startMs, durMs, payload)?.let { cue ->
                                cuesByTrack.getOrPut(trackNum) { mutableListOf() }.add(cue)
                            }
                        }
                    }
                }
                ID_BLOCK_GROUP -> {
                    parseBlockGroup(
                        reader = reader,
                        groupEnd = childEnd,
                        clusterTimecode = clusterTimecode,
                        timecodeScaleNs = timecodeScaleNs,
                        targetTrackNumbers = targetTrackNumbers,
                        defaultDurationsMs = defaultDurationsMs,
                        codecResolver = codecResolver,
                        cuesByTrack = cuesByTrack,
                    )
                }
                else -> {
                    if (!reader.skipExact(childEnd - reader.position)) break
                }
            }

            if (reader.position < childEnd && !reader.skipExact(childEnd - reader.position)) break
        }
    }

    private fun parseBlockGroup(
        reader: EbmlStreamReader,
        groupEnd: Long,
        clusterTimecode: Long,
        timecodeScaleNs: Long,
        targetTrackNumbers: Set<Int>,
        defaultDurationsMs: Map<Int, Long>,
        codecResolver: (Int) -> String?,
        cuesByTrack: MutableMap<Int, MutableList<SubtitleCue>>,
    ) {
        var blockTrackNum: Int? = null
        var blockStartMs = 0L
        var blockPayload: ByteArray? = null
        var blockDurationTicks: Long? = null

        while (reader.position < groupEnd) {
            val id = reader.readEbmlId() ?: break
            val (size, _) = reader.readEbmlVintSizeOrUnknown() ?: break
            if (size < 0L) break
            val childEnd = minOf(groupEnd, reader.position + size)

            when (id) {
                ID_BLOCK -> {
                    parseBlock(
                        reader = reader,
                        blockSize = childEnd - reader.position,
                        clusterTimecode = clusterTimecode,
                        timecodeScaleNs = timecodeScaleNs,
                        targetTrackNumbers = targetTrackNumbers,
                        codecResolver = codecResolver,
                    )?.let { (trackNum, startMs, payload) ->
                        blockTrackNum = trackNum
                        blockStartMs = startMs
                        blockPayload = payload
                    }
                }
                ID_BLOCK_DURATION -> if (size in 1L..8L) {
                    blockDurationTicks = reader.readUnsignedInt(size.toInt())
                }
                else -> if (!reader.skipExact(childEnd - reader.position)) break
            }

            if (reader.position < childEnd && !reader.skipExact(childEnd - reader.position)) break
        }

        val trackNum = blockTrackNum ?: return
        val payload = blockPayload ?: return
        val codec = codecResolver(trackNum) ?: return
        val durationMs = blockDurationTicks
            ?.let { ticksToMs(it, timecodeScaleNs) }
            ?.takeIf { it > 0L }
            ?: defaultDurationsMs[trackNum]
            ?: DEFAULT_SUBTITLE_DURATION_MS

        convertPayloadToCue(codec, blockStartMs, durationMs, payload)?.let { cue ->
            cuesByTrack.getOrPut(trackNum) { mutableListOf() }.add(cue)
        }
    }

    private data class ParsedBlock(
        val trackNumber: Int,
        val startMs: Long,
        val payload: ByteArray,
    )

    private fun parseBlock(
        reader: EbmlStreamReader,
        blockSize: Long,
        clusterTimecode: Long,
        timecodeScaleNs: Long,
        targetTrackNumbers: Set<Int>,
        codecResolver: (Int) -> String?,
    ): ParsedBlock? {
        if (blockSize < 4L) {
            reader.skipExact(blockSize)
            return null
        }
        val (trackNumberLong, vintLen) = reader.readEbmlVintValue() ?: return null
        val trackNumber = trackNumberLong.toInt()
        val relTimecode = reader.readInt16BE() ?: return null
        val flags = reader.readByte()
        if (flags < 0) return null

        val headerBytes = vintLen + 3L
        val payloadSize = blockSize - headerBytes
        if (payloadSize <= 0L) return null

        if (trackNumber !in targetTrackNumbers ||
            codecResolver(trackNumber) == null ||
            payloadSize > MAX_SUBTITLE_BLOCK_BYTES
        ) {
            reader.skipExact(payloadSize)
            return null
        }

        val payload = reader.readBytes(payloadSize.toInt()) ?: return null
        val startMs = ticksToMs(clusterTimecode + relTimecode, timecodeScaleNs).coerceAtLeast(0L)
        return ParsedBlock(trackNumber, startMs, payload)
    }

    private fun convertPayloadToCue(
        normalizedCodec: String,
        startMs: Long,
        durationMs: Long,
        payload: ByteArray,
    ): SubtitleCue? {
        val rawText = String(payload, Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimEnd('\u0000')
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val cleanText = when (normalizedCodec) {
            "S_TEXT/UTF8" -> {
                ASS_TAG_REGEX.replace(rawText, "")
                    .replace(MULTI_NEWLINE_REGEX, "\n")
                    .trim()
            }
            "S_TEXT/ASS", "S_TEXT/SSA" -> {
                val parts = rawText.split(',', limit = 9)
                if (parts.size < 9) return null
                val dialogText = parts[8]
                ASS_TAG_REGEX.replace(dialogText, "")
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .replace("\\h", " ")
                    .lines()
                    .joinToString("\n") { it.trim() }
                    .replace(MULTI_NEWLINE_REGEX, "\n")
                    .trim()
            }
            "S_TEXT/WEBVTT" -> {
                rawText.replace(MULTI_NEWLINE_REGEX, "\n").trim()
            }
            else -> return null
        }

        if (cleanText.isEmpty()) return null
        val safeDuration = durationMs.takeIf { it > 0L } ?: DEFAULT_SUBTITLE_DURATION_MS
        return SubtitleCue(
            startMs = startMs.coerceAtLeast(0L),
            endMs = startMs.coerceAtLeast(0L) + safeDuration,
            text = cleanText,
        )
    }

    private fun buildWebVtt(cues: List<SubtitleCue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEachIndexed { idx, cue ->
            if (idx > 0) append("\n\n")
            append(formatVttTimestamp(cue.startMs))
            append(" --> ")
            append(formatVttTimestamp(cue.endMs))
            append('\n')
            append(cue.text)
        }
        append('\n')
    }

    private fun formatVttTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    private fun ticksToMs(ticks: Long, timecodeScaleNs: Long): Long {
        if (timecodeScaleNs == DEFAULT_TIMECODE_SCALE_NS) return ticks
        return (ticks * timecodeScaleNs) / 1_000_000L
    }

    private fun normalizeCodecId(codecId: String?): String? {
        val upper = codecId?.trim()?.uppercase(Locale.ROOT) ?: return null
        return when {
            upper == "S_TEXT/UTF8" || upper == "SRT" || upper == "UTF8" || upper == "SUBRIP" -> "S_TEXT/UTF8"
            upper == "S_TEXT/ASS" || upper == "ASS" -> "S_TEXT/ASS"
            upper == "S_TEXT/SSA" || upper == "SSA" -> "S_TEXT/SSA"
            upper == "S_TEXT/WEBVTT" || upper == "WEBVTT" || upper == "VTT" -> "S_TEXT/WEBVTT"
            else -> null
        }
    }

    private fun openSourceStream(context: Context?, sourceUri: String): InputStream? {
        val parsed = runCatching { URI(sourceUri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        return when (scheme) {
            null, "" -> {
                val f = File(sourceUri)
                if (f.isFile) FileInputStream(f) else null
            }
            "file" -> {
                val f = runCatching { File(parsed) }.getOrNull()
                if (f != null && f.isFile) FileInputStream(f)
                else context?.contentResolver?.openInputStream(Uri.parse(sourceUri))
            }
            "content" -> {
                context?.contentResolver?.openInputStream(Uri.parse(sourceUri))
            }
            "http" -> {
                if (parsed.host != "127.0.0.1" && parsed.host != "localhost") return null
                runCatching {
                    val conn = (URL(sourceUri).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4_000
                        readTimeout = 10_000
                        requestMethod = "GET"
                    }
                    if (conn.responseCode in 200..299) conn.inputStream else {
                        conn.disconnect()
                        null
                    }
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun resolveSourceSize(context: Context?, sourceUri: String): Long {
        val parsed = runCatching { URI(sourceUri) }.getOrNull()
        return when (parsed?.scheme?.lowercase(Locale.ROOT)) {
            null, "" -> File(sourceUri).takeIf { it.isFile }?.length() ?: -1L
            "file" -> runCatching { File(parsed).takeIf { it.isFile }?.length() }.getOrNull() ?: -1L
            "content" -> runCatching {
                context?.contentResolver?.query(Uri.parse(sourceUri), arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            }.getOrNull() ?: -1L
            else -> -1L
        }
    }

    private fun writeVttAtomically(dir: File, dest: File, content: String) {
        val tmp = File(dir, "${dest.name}.${System.nanoTime()}.tmp")
        runCatching {
            tmp.writeText(content, Charsets.UTF_8)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                dest.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure {
            runCatching { tmp.delete() }
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class EbmlStreamReader(private val raw: InputStream) {
        private val buf = ByteArray(64 * 1024)
        private val skipScratch = ByteArray(64 * 1024)
        private val pushback = ByteArray(16)
        private var pushbackLen = 0
        private var pos = 0
        private var limit = 0

        var position: Long = 0L
            private set

        fun unread(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            // Store in reverse-stack order so readByte pops from pushbackLen - 1
            for (i in bytes.size - 1 downTo 0) {
                if (pushbackLen < pushback.size) {
                    pushback[pushbackLen++] = bytes[i]
                    position--
                }
            }
        }

        fun readByte(): Int {
            if (pushbackLen > 0) {
                position++
                return pushback[--pushbackLen].toInt() and 0xFF
            }
            if (pos >= limit) {
                if (!fillBuffer()) return -1
            }
            position++
            return buf[pos++].toInt() and 0xFF
        }

        fun readBytes(count: Int): ByteArray? {
            if (count < 0) return null
            if (count == 0) return ByteArray(0)
            val out = ByteArray(count)
            var offset = 0
            while (offset < count && pushbackLen > 0) {
                out[offset++] = pushback[--pushbackLen]
                position++
            }
            while (offset < count) {
                val avail = limit - pos
                if (avail > 0) {
                    val take = minOf(avail, count - offset)
                    System.arraycopy(buf, pos, out, offset, take)
                    pos += take
                    offset += take
                    position += take
                } else {
                    if (!fillBuffer()) return null
                }
            }
            return out
        }

        fun skipExact(count: Long): Boolean {
            if (count <= 0L) return true
            var remaining = count
            while (remaining > 0L && pushbackLen > 0) {
                pushbackLen--
                remaining--
                position++
            }
            val avail = limit - pos
            if (avail > 0) {
                val take = minOf(avail.toLong(), remaining).toInt()
                pos += take
                remaining -= take
                position += take
            }
            while (remaining > 0L) {
                val skipped = raw.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                    position += skipped
                    continue
                }
                val toRead = minOf(skipScratch.size.toLong(), remaining).toInt()
                val n = raw.read(skipScratch, 0, toRead)
                if (n < 0) return false
                remaining -= n
                position += n
            }
            return true
        }

        fun readEbmlId(): Long? = readEbmlIdWithBytes()?.first

        fun readEbmlIdWithBytes(): Pair<Long, ByteArray>? {
            val b0 = readByte()
            if (b0 < 0) return null
            val len = when {
                (b0 and 0x80) != 0 -> 1
                (b0 and 0x40) != 0 -> 2
                (b0 and 0x20) != 0 -> 3
                (b0 and 0x10) != 0 -> 4
                else -> return null
            }
            val rawBytes = ByteArray(len)
            rawBytes[0] = b0.toByte()
            var value = b0.toLong()
            for (i in 1 until len) {
                val b = readByte()
                if (b < 0) return null
                rawBytes[i] = b.toByte()
                value = (value shl 8) or b.toLong()
            }
            return value to rawBytes
        }

        fun readEbmlVintSizeOrUnknown(): Pair<Long, Int>? = readEbmlVint(allowUnknown = true)

        fun readEbmlVintValue(): Pair<Long, Int>? = readEbmlVint(allowUnknown = false)

        private fun readEbmlVint(allowUnknown: Boolean): Pair<Long, Int>? {
            val b0 = readByte()
            if (b0 < 0 || b0 == 0) return null
            var mask = 0x80
            var len = 1
            while (len <= 8 && (b0 and mask) == 0) {
                mask = mask ushr 1
                len++
            }
            if (len > 8) return null
            val firstBits = b0 and mask.inv()
            var value = firstBits.toLong()
            var allOnes = (firstBits == mask - 1)
            for (i in 1 until len) {
                val b = readByte()
                if (b < 0) return null
                if (b != 0xFF) allOnes = false
                value = (value shl 8) or b.toLong()
            }
            return (if (allowUnknown && allOnes) -1L else value) to len
        }

        fun readUnsignedInt(byteLen: Int): Long? {
            if (byteLen !in 0..8) return null
            var v = 0L
            for (i in 0 until byteLen) {
                val b = readByte()
                if (b < 0) return null
                v = (v shl 8) or b.toLong()
            }
            return v
        }

        fun readInt16BE(): Int? {
            val hi = readByte()
            val lo = readByte()
            if (hi < 0 || lo < 0) return null
            return ((hi shl 8) or lo).toShort().toInt()
        }

        private fun fillBuffer(): Boolean {
            pos = 0
            limit = 0
            val n = raw.read(buf, 0, buf.size)
            if (n <= 0) return false
            limit = n
            return true
        }
    }
}
