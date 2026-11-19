package org.cinito.online.metadata

import android.content.Context
import android.net.Uri
import org.cinito.online.player.TrackLanguageFormatter
import webtor.app.EngineHost
import java.io.Closeable
import java.io.FileInputStream
import java.util.Locale

enum class ProbeTrackKind {
    Video,
    Audio,
    Subtitle,
}

data class ProbedTrack(
    val kind: ProbeTrackKind,
    val languageCode: String?,
    val bcp47: String?,
    val name: String?,
    val codec: String?,
    val regionTag: AudioLanguageTag,
    val trackNumber: Int? = null,
    val rawCodecId: String? = null,
) {
    val isTextSubtitle: Boolean
        get() {
            if (kind != ProbeTrackKind.Subtitle) return false
            val raw = rawCodecId?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (raw.startsWith("S_TEXT/") || raw == "S_VOBSUB" && false) return true
            if (raw.contains("PGS") || raw.contains("VOBSUB") || raw.contains("DVDSUB")) return false
            val c = codec?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return c in setOf("SRT", "ASS", "SSA", "WEBVTT", "VTT", "UTF8", "TX3G", "SUBRIP", "TEXT")
        }

    val hasExplicitLanguage: Boolean
        get() {
            val normLang = languageCode?.trim()?.lowercase(Locale.ROOT)
            val normBcp = bcp47?.trim()?.lowercase(Locale.ROOT)
            val hasLang = !normLang.isNullOrBlank() && normLang !in UNDETERMINED_LANG_CODES
            val hasBcp = !normBcp.isNullOrBlank() && normBcp !in UNDETERMINED_LANG_CODES
            return hasLang || hasBcp || regionTag != AudioLanguageTag.Other
        }

    val compactCodeLabel: String?
        get() = when (regionTag) {
            AudioLanguageTag.Latino -> "Lat"
            AudioLanguageTag.Castellano -> "Cast"
            AudioLanguageTag.SpanishGeneric -> "Esp"
            AudioLanguageTag.SubSpanish, AudioLanguageTag.Other -> {
                if (!hasExplicitLanguage) {
                    null
                } else {
                    compactIsoLabel(bcp47 ?: languageCode)
                }
            }
        }

    val displayLabel: String
        get() {
            val langLabel = when (regionTag) {
                AudioLanguageTag.Latino -> "Español Latino"
                AudioLanguageTag.Castellano -> "Castellano"
                AudioLanguageTag.SpanishGeneric -> "Español"
                AudioLanguageTag.SubSpanish, AudioLanguageTag.Other -> {
                    if (hasExplicitLanguage) TrackLanguageFormatter.formatLanguage(bcp47 ?: languageCode) else "Sin idioma etiquetado"
                }
            }
            return listOfNotNull(
                langLabel,
                name?.takeIf { it.isNotBlank() && !it.equals(langLabel, ignoreCase = true) },
                codec?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }

    companion object {
        private val UNDETERMINED_LANG_CODES = setOf("und", "unk", "zxx", "mis")

        private fun compactIsoLabel(raw: String?): String? {
            val code = raw?.trim()?.lowercase(Locale.ROOT)
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.takeIf { it.isNotBlank() && it !in UNDETERMINED_LANG_CODES }
                ?: return null
            return when (code) {
                "en", "eng" -> "Eng"
                "ja", "jpn" -> "Jpn"
                "fr", "fre", "fra" -> "Fra"
                "de", "ger", "deu" -> "Ger"
                "it", "ita" -> "Ita"
                "pt", "por" -> "Por"
                "ko", "kor" -> "Kor"
                "zh", "chi", "zho" -> "Chi"
                "ru", "rus" -> "Rus"
                "hi", "hin" -> "Hin"
                else -> code.take(3).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }
    }
}

data class ContainerProbeResult(
    val containerFormat: String,
    val tracks: List<ProbedTrack>,
    val isFastStartMp4: Boolean? = null,
) {
    val audioTracks: List<ProbedTrack> get() = tracks.filter { it.kind == ProbeTrackKind.Audio }
    val subtitleTracks: List<ProbedTrack> get() = tracks.filter { it.kind == ProbeTrackKind.Subtitle }
    val audioTrackCount: Int get() = audioTracks.size
    val isSingleAudioTrack: Boolean get() = audioTracks.size == 1
    val isMultiAudioTrack: Boolean get() = audioTracks.size > 1
    val hasUntaggedSingleAudioTrack: Boolean get() = isSingleAudioTrack && audioTracks.firstOrNull()?.hasExplicitLanguage == false
    val hasLatinoAudio: Boolean get() = audioTracks.any { it.regionTag == AudioLanguageTag.Latino }
    val hasAnySpanishAudio: Boolean
        get() = audioTracks.any {
            it.regionTag == AudioLanguageTag.Latino ||
                it.regionTag == AudioLanguageTag.SpanishGeneric ||
                it.regionTag == AudioLanguageTag.Castellano
        }
    val hasAnySpanishSubtitle: Boolean
        get() = subtitleTracks.any {
            it.regionTag == AudioLanguageTag.Latino ||
                it.regionTag == AudioLanguageTag.SpanishGeneric ||
                it.regionTag == AudioLanguageTag.Castellano ||
                it.regionTag == AudioLanguageTag.SubSpanish
        }

    val primaryAudioTag: AudioLanguageTag
        get() = when {
            audioTracks.any { it.regionTag == AudioLanguageTag.Latino } -> AudioLanguageTag.Latino
            audioTracks.any { it.regionTag == AudioLanguageTag.Castellano } -> AudioLanguageTag.Castellano
            audioTracks.any { it.regionTag == AudioLanguageTag.SpanishGeneric } -> AudioLanguageTag.SpanishGeneric
            hasAnySpanishSubtitle -> AudioLanguageTag.SubSpanish
            else -> AudioLanguageTag.Other
        }

    fun compactAudioBadgeLabel(): String {
        val count = audioTracks.size
        val subSuffix = when {
            hasAnySpanishAudio || !hasAnySpanishSubtitle -> ""
            subtitleTracks.any { it.regionTag == AudioLanguageTag.Latino } -> " + Sub Lat"
            subtitleTracks.any { it.regionTag == AudioLanguageTag.Castellano } -> " + Sub Cast"
            else -> " + Sub Esp"
        }
        if (count == 0) {
            return if (subSuffix.isNotEmpty()) "Sin audio$subSuffix" else "Sin pistas de audio"
        }
        if (count == 1) {
            val single = audioTracks.first()
            val code = single.compactCodeLabel
            return if (code == null) {
                if (subSuffix.isEmpty()) "1 pista (única · sin idioma)" else "1 pista (sin idioma)$subSuffix"
            } else {
                "1 pista: $code$subSuffix"
            }
        }
        val distinctCodes = audioTracks.mapNotNull { it.compactCodeLabel }.distinct()
        val langsSummary = when {
            distinctCodes.isEmpty() -> "(sin idioma)"
            distinctCodes.size <= 3 -> ": ${distinctCodes.joinToString(" · ")}"
            else -> ": ${distinctCodes.take(3).joinToString(" · ")} +${distinctCodes.size - 3}"
        }
        return "$count pistas$langsSummary$subSuffix"
    }
}

interface ProbeByteSource : Closeable {
    val length: Long
    fun readAt(offset: Long, buffer: ByteArray, size: Int = buffer.size): Int
}

class EnginePreviewByteSource(
    private val engine: EngineHost,
    engineId: String,
    fileIndex: Int,
    override val length: Long,
) : ProbeByteSource {
    private val handle: Long = engine.openPreview(engineId, fileIndex)

    val isOpen: Boolean get() = handle != 0L

    override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int {
        if (handle == 0L || offset < 0L || offset >= length) return -1
        val capped = minOf(size, buffer.size, 256 * 1024)
        return engine.readPreview(handle, offset, capped, buffer)
    }

    override fun close() {
        if (handle != 0L) {
            engine.closePreview(handle)
        }
    }
}

class ContentUriByteSource(
    context: Context,
    uri: Uri,
    override val length: Long,
) : ProbeByteSource {
    private val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
    private val fis = afd?.let { FileInputStream(it.fileDescriptor) }

    override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int {
        val stream = fis ?: return -1
        if (offset < 0L || offset >= length) return -1
        stream.channel.position(offset)
        val n = minOf(size, buffer.size)
        return stream.read(buffer, 0, n)
    }

    override fun close() {
        runCatching { fis?.close() }
        runCatching { afd?.close() }
    }
}

/**
 * Pure-Kotlin MKV (EBML Tracks) and MP4/M4V/MOV (ISO-BMFF moov/trak/mdia) header probe.
 * Reads only metadata atoms/elements (~100-512 KB) to reveal real audio/subtitle tracks before downloading.
 */
object MediaContainerProbe {
    private const val INITIAL_MKV_PROBE_BYTES = 64 * 1024 // 64 KiB covers 95%+ of MKV headers
    private const val MEDIUM_MKV_PROBE_BYTES = 256 * 1024 // 256 KiB fallback
    private const val MAX_HEADER_PROBE_BYTES = 1024 * 1024 // 1 MiB safety cap
    private const val MAX_CONTIGUOUS_MOOV_BYTES = 512 * 1024 // Read whole moov only when <= 512 KiB
    private const val MAX_TRAK_ATOM_BYTES = 2 * 1024 * 1024

    fun probe(source: ProbeByteSource): ContainerProbeResult? {
        if (source.length < 16L) return null
        val head = readExactUpTo(source, 0L, 4096) ?: return null
        if (head.size < 8) return null

        // EBML magic: 0x1A 0x45 0xDF 0xA3
        if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) {
            return probeMatroska(source)
        }

        // ISO-BMFF box check at offset 4 ("ftyp", "moov", "mdat", "free", "wide")
        val boxType = String(head, 4, 4, Charsets.US_ASCII)
        if (boxType in setOf("ftyp", "moov", "mdat", "free", "wide", "skip")) {
            return probeMp4(source)
        }

        return null
    }

    private fun probeMatroska(source: ProbeByteSource): ContainerProbeResult? {
        val windowSizes = intArrayOf(
            INITIAL_MKV_PROBE_BYTES,
            MEDIUM_MKV_PROBE_BYTES,
            MAX_HEADER_PROBE_BYTES,
        )
        var buf: ByteArray? = null
        var segmentContentStart = -1L
        var tracksSeekAbsOffset: Long? = null

        for (targetWindow in windowSizes) {
            val scanLen = minOf(source.length, targetWindow.toLong()).toInt()
            if (scanLen <= 0) return null
            if (buf != null && buf.size >= scanLen) continue
            buf = readRange(source, 0L, scanLen) ?: return null

            if (segmentContentStart < 0L) {
                segmentContentStart = findMatroskaSegmentContentStart(buf) ?: -1L
            }
            if (segmentContentStart >= 0L && segmentContentStart < buf.size) {
                val walk = walkMatroskaSegmentHeader(buf, segmentContentStart.toInt(), segmentContentStart)
                if (walk.tracks != null && walk.tracks.isNotEmpty()) {
                    return ContainerProbeResult("MKV", walk.tracks)
                }
                if (walk.tracksSeekAbsOffset != null) {
                    tracksSeekAbsOffset = walk.tracksSeekAbsOffset
                }
                if (tracksSeekAbsOffset != null || walk.hitCluster || walk.tracksBodyRangeEnd != null) {
                    // Either we know exact Tracks offset from SeekHead, hit Cluster, or need slightly larger window for Tracks body
                    if (walk.tracksBodyRangeEnd != null && walk.tracksBodyRangeEnd <= MAX_HEADER_PROBE_BYTES) {
                        val bigger = readRange(source, 0L, minOf(source.length, walk.tracksBodyRangeEnd.toLong()).toInt())
                        if (bigger != null) {
                            val secondWalk = walkMatroskaSegmentHeader(bigger, segmentContentStart.toInt(), segmentContentStart)
                            if (!secondWalk.tracks.isNullOrEmpty()) {
                                return ContainerProbeResult("MKV", secondWalk.tracks)
                            }
                        }
                    }
                    if (tracksSeekAbsOffset != null && tracksSeekAbsOffset + 8L < source.length) {
                        val remoteTracks = readMatroskaTracksAt(source, tracksSeekAbsOffset)
                        if (!remoteTracks.isNullOrEmpty()) {
                            return ContainerProbeResult("MKV", remoteTracks)
                        }
                    }
                    if (walk.hitCluster) break
                }
            }
            if (buf.size.toLong() >= source.length) break
        }

        val finalBuf = buf ?: return null
        tracksSeekAbsOffset?.let { absOff ->
            if (absOff + 8L < source.length) {
                readMatroskaTracksAt(source, absOff)?.takeIf { it.isNotEmpty() }?.let {
                    return ContainerProbeResult("MKV", it)
                }
            }
        }

        // Fallback byte scan: skip SeekID (0x53 0xAB <len> 0x16 0x54 0xAE 0x6B) false positives and require >= 1 TrackEntry.
        var searchFrom = 0
        while (true) {
            val tracksOffset = findMatroskaTracksOffset(finalBuf, searchFrom)
            if (tracksOffset < 0) break
            val vint = readEbmlVintSize(finalBuf, tracksOffset)
            if (vint != null) {
                val (tracksSize, contentStart) = vint
                val neededEnd = (contentStart + tracksSize.coerceAtMost((512 * 1024).toLong()).toInt())
                    .coerceAtMost(minOf(source.length, MAX_HEADER_PROBE_BYTES.toLong()).toInt())
                val parseBuf = if (neededEnd > finalBuf.size) readRange(source, 0L, neededEnd) ?: finalBuf else finalBuf
                val tracks = parseMatroskaTracksBody(parseBuf, contentStart, neededEnd.coerceAtMost(parseBuf.size))
                if (tracks.isNotEmpty()) {
                    return ContainerProbeResult("MKV", tracks)
                }
            }
            searchFrom = tracksOffset
        }
        return null
    }

    private data class MkvSegmentWalkResult(
        val tracks: List<ProbedTrack>? = null,
        val tracksBodyRangeEnd: Int? = null,
        val tracksSeekAbsOffset: Long? = null,
        val hitCluster: Boolean = false,
    )

    private fun walkMatroskaSegmentHeader(
        buf: ByteArray,
        segStart: Int,
        segContentStartLong: Long,
    ): MkvSegmentWalkResult {
        var cursor = segStart
        var seekTargetAbs: Long? = null
        var hops = 0
        while (cursor < buf.size - 2 && hops++ < 256) {
            val (elemId, afterId) = readEbmlId(buf, cursor) ?: break
            val (elemSize, bodyStart) = readEbmlVintSizeOrUnknown(buf, afterId) ?: break
            val hasKnownSize = elemSize >= 0L
            val bodyEnd = if (hasKnownSize) {
                val rawEnd = bodyStart.toLong() + elemSize
                if (rawEnd > Int.MAX_VALUE) Int.MAX_VALUE else rawEnd.toInt()
            } else {
                buf.size
            }
            when (elemId) {
                0x1654AE6BL -> { // Tracks
                    if (hasKnownSize && bodyEnd > buf.size && bodyEnd <= MAX_HEADER_PROBE_BYTES) {
                        return MkvSegmentWalkResult(tracksBodyRangeEnd = bodyEnd, tracksSeekAbsOffset = seekTargetAbs)
                    }
                    val tracks = parseMatroskaTracksBody(buf, bodyStart, bodyEnd.coerceAtMost(buf.size))
                    if (tracks.isNotEmpty()) {
                        return MkvSegmentWalkResult(tracks = tracks, tracksSeekAbsOffset = seekTargetAbs)
                    }
                }
                0x114D9B74L -> { // SeekHead
                    if (bodyStart < buf.size) {
                        seekTargetAbs = seekTargetAbs ?: parseSeekHeadForTracks(
                            buf = buf,
                            start = bodyStart,
                            end = bodyEnd.coerceAtMost(buf.size),
                            segContentStart = segContentStartLong,
                        )
                    }
                }
                0x1F43B675L -> { // Cluster
                    return MkvSegmentWalkResult(tracksSeekAbsOffset = seekTargetAbs, hitCluster = true)
                }
            }
            if (!hasKnownSize || bodyEnd <= bodyStart || bodyEnd >= buf.size) break
            cursor = bodyEnd
        }
        return MkvSegmentWalkResult(tracksSeekAbsOffset = seekTargetAbs)
    }

    private fun parseSeekHeadForTracks(
        buf: ByteArray,
        start: Int,
        end: Int,
        segContentStart: Long,
    ): Long? {
        var sCursor = start
        while (sCursor < end) {
            val (sId, sAfter) = readEbmlId(buf, sCursor) ?: break
            val (sSize, sBodyStart) = readEbmlVintSize(buf, sAfter) ?: break
            val sBodyEnd = (sBodyStart + sSize.toInt()).coerceAtMost(end)
            if (sBodyEnd <= sBodyStart) break
            if (sId == 0x4DBBL) { // Seek
                var targetId = 0L
                var targetPos = -1L
                var eCursor = sBodyStart
                while (eCursor < sBodyEnd) {
                    val (fId, fAfter) = readEbmlId(buf, eCursor) ?: break
                    val (fSize, fBodyStart) = readEbmlVintSize(buf, fAfter) ?: break
                    val fBodyEnd = (fBodyStart + fSize.toInt()).coerceAtMost(sBodyEnd)
                    if (fBodyEnd < fBodyStart) break
                    when (fId) {
                        0x53ABL -> targetId = readUnsignedInt(buf, fBodyStart, fBodyEnd)
                        0x53ACL -> targetPos = readUnsignedInt(buf, fBodyStart, fBodyEnd)
                    }
                    eCursor = fBodyEnd
                }
                if (targetId == 0x1654AE6BL && targetPos >= 0L) {
                    return segContentStart + targetPos
                }
            }
            sCursor = sBodyEnd
        }
        return null
    }

    private fun readMatroskaTracksAt(source: ProbeByteSource, absTracksOffset: Long): List<ProbedTrack>? {
        val hdr = readRange(source, absTracksOffset, 16) ?: return null
        if (hdr.size < 6 ||
            hdr[0] != 0x16.toByte() ||
            hdr[1] != 0x54.toByte() ||
            hdr[2] != 0xAE.toByte() ||
            hdr[3] != 0x6B.toByte()
        ) {
            return null
        }
        val (tracksSize, relStart) = readEbmlVintSize(hdr, 4) ?: return null
        val bodyLen = tracksSize.coerceAtMost((512 * 1024).toLong()).toInt()
        if (bodyLen <= 0) return null
        val bodyBuf = readRange(source, absTracksOffset + relStart, bodyLen) ?: return null
        return parseMatroskaTracksBody(bodyBuf, 0, bodyBuf.size)
    }

    private fun parseMatroskaTracksBody(buf: ByteArray, start: Int, end: Int): List<ProbedTrack> {
        val parsedTracks = mutableListOf<ProbedTrack>()
        var cursor = start
        while (cursor < end) {
            val (elemId, afterId) = readEbmlId(buf, cursor) ?: break
            val (elemSize, elemBodyStart) = readEbmlVintSize(buf, afterId) ?: break
            val elemEnd = (elemBodyStart + elemSize.toInt()).coerceAtMost(end)
            if (elemEnd <= elemBodyStart) break

            if (elemId == 0xAEL) { // TrackEntry
                parseMkvTrackEntry(buf, elemBodyStart, elemEnd)?.let { parsedTracks += it }
            }
            cursor = elemEnd
        }
        return parsedTracks
    }

    private fun findMatroskaSegmentContentStart(buf: ByteArray): Long? {
        var pos = 0
        while (pos <= buf.size - 8) {
            if (buf[pos] == 0x18.toByte() &&
                buf[pos + 1] == 0x53.toByte() &&
                buf[pos + 2] == 0x80.toByte() &&
                buf[pos + 3] == 0x67.toByte()
            ) {
                val (_, segDataStart) = readEbmlVintSizeOrUnknown(buf, pos + 4) ?: return null
                return segDataStart.toLong()
            }
            pos++
        }
        return null
    }

    private fun findMatroskaTracksOffset(buf: ByteArray, fromIndex: Int = 0): Int {
        var pos = fromIndex.coerceAtLeast(0)
        while (pos <= buf.size - 8) {
            if (buf[pos] == 0x16.toByte() &&
                buf[pos + 1] == 0x54.toByte() &&
                buf[pos + 2] == 0xAE.toByte() &&
                buf[pos + 3] == 0x6B.toByte()
            ) {
                val isSeekIdValue = pos >= 3 && buf[pos - 3] == 0x53.toByte() && buf[pos - 2] == 0xAB.toByte()
                if (!isSeekIdValue) {
                    return pos + 4
                }
            }
            pos++
        }
        return -1
    }

    private fun readEbmlVintSizeOrUnknown(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 8 && (first and mask) == 0) {
            mask = mask ushr 1
            len++
        }
        if (len > 8 || offset + len > buf.size) return null
        val firstBits = first and mask.inv()
        var value = firstBits.toLong()
        var allOnes = (firstBits == (mask - 1))
        for (i in 1 until len) {
            val b = buf[offset + i].toInt() and 0xFF
            if (b != 0xFF) allOnes = false
            value = (value shl 8) or b.toLong()
        }
        return (if (allOnes) -1L else value) to (offset + len)
    }

    private fun parseMkvTrackEntry(buf: ByteArray, start: Int, end: Int): ProbedTrack? {
        var trackNumber: Int? = null
        var trackType = 0L
        var codecId: String? = null
        var language: String? = null
        var bcp47: String? = null
        var name: String? = null

        var cursor = start
        while (cursor < end) {
            val (id, afterId) = readEbmlId(buf, cursor) ?: break
            val (size, bodyStart) = readEbmlVintSize(buf, afterId) ?: break
            val bodyEnd = (bodyStart + size.toInt()).coerceAtMost(end)
            if (bodyEnd < bodyStart) break

            when (id) {
                0xD7L -> trackNumber = readUnsignedInt(buf, bodyStart, bodyEnd).toInt().takeIf { it > 0 }
                0x83L -> trackType = readUnsignedInt(buf, bodyStart, bodyEnd)
                0x86L -> codecId = readUtf8Clean(buf, bodyStart, bodyEnd)
                0x22B59CL -> language = readUtf8Clean(buf, bodyStart, bodyEnd)
                0x22B59DL -> bcp47 = readUtf8Clean(buf, bodyStart, bodyEnd)
                0x536EL -> name = readUtf8Clean(buf, bodyStart, bodyEnd)
            }
            cursor = bodyEnd
        }

        val kind = when (trackType) {
            1L -> ProbeTrackKind.Video
            2L -> ProbeTrackKind.Audio
            17L -> ProbeTrackKind.Subtitle
            else -> return null
        }

        val cleanCodec = formatMkvCodec(codecId)
        val effectiveLang = language
        val region = if (kind == ProbeTrackKind.Video) {
            AudioLanguageTag.Other
        } else {
            MediaLanguageClassifier.classifyTrackMetadata(effectiveLang, bcp47, name)
        }

        return ProbedTrack(
            kind = kind,
            languageCode = effectiveLang,
            bcp47 = bcp47,
            name = name,
            codec = cleanCodec,
            regionTag = region,
            trackNumber = trackNumber,
            rawCodecId = codecId,
        )
    }

    private fun probeMp4(source: ProbeByteSource): ContainerProbeResult? {
        var offset = 0L
        val boxHeader = ByteArray(16)
        var moovBoxOffset = -1L
        var moovOffset = -1L
        var moovSize = 0L
        var firstMdatOffset = -1L

        var hops = 0
        while (offset + 8L <= source.length && hops++ < 64) {
            val n = readExact(source, offset, boxHeader, 8)
            if (n < 8) break
            var size = uint32BE(boxHeader, 0)
            val type = String(boxHeader, 4, 4, Charsets.US_ASCII)
            var headerBytes = 8L
            if (size == 1L && offset + 16L <= source.length) {
                if (readExact(source, offset + 8, boxHeader, 8) < 8) break
                size = int64BE(boxHeader, 0)
                headerBytes = 16L
            } else if (size == 0L) {
                size = source.length - offset
            }
            if (size < headerBytes) break

            if (type == "mdat" && firstMdatOffset < 0L) {
                firstMdatOffset = offset
            } else if (type == "moov") {
                moovBoxOffset = offset
                moovOffset = offset + headerBytes
                moovSize = (size - headerBytes).coerceAtMost(source.length - moovOffset)
                break
            }
            offset += size
        }

        if (moovOffset < 0L || moovSize <= 0L) return null
        val tracks = mutableListOf<ProbedTrack>()
        if (moovSize <= MAX_CONTIGUOUS_MOOV_BYTES) {
            val moovBytes = readRange(source, moovOffset, moovSize.toInt()) ?: return null
            parseMp4Container(moovBytes, 0, moovBytes.size, tracks)
        } else {
            parseMp4MoovStreaming(source, moovOffset, moovOffset + moovSize, tracks)
        }

        if (tracks.isEmpty()) return null
        val fastStart = firstMdatOffset < 0L || moovBoxOffset < firstMdatOffset
        return ContainerProbeResult("MP4", tracks, isFastStartMp4 = fastStart)
    }

    private fun parseMp4MoovStreaming(
        source: ProbeByteSource,
        moovBodyStart: Long,
        moovBodyEnd: Long,
        out: MutableList<ProbedTrack>,
    ) {
        var offset = moovBodyStart
        val boxHeader = ByteArray(16)
        var hops = 0
        while (offset + 8L <= moovBodyEnd && hops++ < 128) {
            if (readExact(source, offset, boxHeader, 8) < 8) break
            var size = uint32BE(boxHeader, 0)
            val type = String(boxHeader, 4, 4, Charsets.US_ASCII)
            var headerBytes = 8L
            if (size == 1L && offset + 16L <= moovBodyEnd) {
                if (readExact(source, offset + 8L, boxHeader, 8) < 8) break
                size = int64BE(boxHeader, 0)
                headerBytes = 16L
            } else if (size == 0L) {
                size = moovBodyEnd - offset
            }
            if (size < headerBytes || offset + size > moovBodyEnd) break

            if (type == "trak") {
                val trakBodySize = size - headerBytes
                if (trakBodySize in 8L..MAX_TRAK_ATOM_BYTES.toLong()) {
                    val trakBuf = readRange(source, offset + headerBytes, trakBodySize.toInt())
                    if (trakBuf != null) {
                        parseMp4Trak(trakBuf, 0, trakBuf.size)?.let { out += it }
                    }
                } else if (trakBodySize > MAX_TRAK_ATOM_BYTES) {
                    parseMp4TrakStreaming(source, offset + headerBytes, offset + size)?.let { out += it }
                }
            }
            offset += size
        }
    }

    private fun parseMp4TrakStreaming(
        source: ProbeByteSource,
        trakBodyStart: Long,
        trakBodyEnd: Long,
    ): ProbedTrack? {
        var mdiaStart = -1L
        var mdiaEnd = -1L
        var trakName: String? = null
        val hdr = ByteArray(16)
        var cursor = trakBodyStart
        var hops = 0
        while (cursor + 8L <= trakBodyEnd && hops++ < 64) {
            if (readExact(source, cursor, hdr, 8) < 8) break
            val size = uint32BE(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.US_ASCII)
            if (size < 8L || cursor + size > trakBodyEnd) break
            when (type) {
                "mdia" -> {
                    mdiaStart = cursor + 8L
                    mdiaEnd = cursor + size
                }
                "udta" -> {
                    val udtaLen = (size - 8L).coerceAtMost(65_536L).toInt()
                    if (udtaLen > 0) {
                        readRange(source, cursor + 8L, udtaLen)?.let {
                            trakName = findMp4NameBox(it, 0, it.size) ?: trakName
                        }
                    }
                }
            }
            cursor += size
        }
        if (mdiaStart < 0L || mdiaEnd <= mdiaStart) return null

        var language: String? = null
        var bcp47: String? = null
        var handlerType: String? = null
        var handlerName: String? = null
        var codecFourCc: String? = null

        var c = mdiaStart
        var mdiaHops = 0
        while (c + 8L <= mdiaEnd && mdiaHops++ < 64) {
            if (readExact(source, c, hdr, 8) < 8) break
            val size = uint32BE(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.US_ASCII)
            if (size < 8L || c + size > mdiaEnd) break
            val bodyLen = (size - 8L).toInt()
            when (type) {
                "mdhd" -> if (bodyLen in 16..256) {
                    readRange(source, c + 8L, bodyLen)?.let {
                        language = parseMp4MdhdLanguage(it, 0, it.size)
                    }
                }
                "hdlr" -> if (bodyLen in 24..4096) {
                    readRange(source, c + 8L, bodyLen)?.let {
                        val (hType, hName) = parseMp4Hdlr(it, 0, it.size)
                        handlerType = hType
                        handlerName = hName
                    }
                }
                "elng" -> if (bodyLen in 5..512) {
                    readRange(source, c + 8L, bodyLen)?.let {
                        bcp47 = readUtf8Clean(it, 4, it.size)
                    }
                }
                "minf" -> {
                    codecFourCc = findMp4StsdCodecStreaming(source, c + 8L, c + size)
                }
            }
            c += size
        }

        val kind = when (handlerType) {
            "vide" -> ProbeTrackKind.Video
            "soun" -> ProbeTrackKind.Audio
            "sbtl", "text", "subt" -> ProbeTrackKind.Subtitle
            else -> return null
        }
        val usefulName = trakName ?: handlerName?.takeIf {
            !it.equals("SoundHandler", true) && !it.equals("VideoHandler", true) && !it.equals("SubtitleHandler", true)
        }
        val region = if (kind == ProbeTrackKind.Video) {
            AudioLanguageTag.Other
        } else {
            MediaLanguageClassifier.classifyTrackMetadata(language, bcp47, usefulName)
        }
        return ProbedTrack(
            kind = kind,
            languageCode = language,
            bcp47 = bcp47,
            name = usefulName,
            codec = codecFourCc?.uppercase(Locale.ROOT),
            regionTag = region,
        )
    }

    private fun findMp4StsdCodecStreaming(source: ProbeByteSource, start: Long, end: Long): String? {
        val hdr = ByteArray(32)
        var c = start
        var hops = 0
        while (c + 8L <= end && hops++ < 64) {
            if (readExact(source, c, hdr, 8) < 8) break
            val size = uint32BE(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.US_ASCII)
            if (size < 8L || c + size > end) break
            if (type == "stbl") {
                return findMp4StsdCodecStreaming(source, c + 8L, c + size)
            }
            if (type == "stsd" && size >= 24L) {
                if (readExact(source, c, hdr, 24) >= 24) {
                    return String(hdr, 20, 4, Charsets.US_ASCII)
                }
            }
            c += size
        }
        return null
    }

    private fun parseMp4Container(buf: ByteArray, start: Int, end: Int, out: MutableList<ProbedTrack>) {
        var cursor = start
        while (cursor + 8 <= end) {
            val size = uint32BE(buf, cursor).toInt()
            val type = String(buf, cursor + 4, 4, Charsets.US_ASCII)
            if (size < 8 || cursor + size > end) break
            if (type == "trak") {
                parseMp4Trak(buf, cursor + 8, cursor + size)?.let { out += it }
            }
            cursor += size
        }
    }

    private fun parseMp4Trak(buf: ByteArray, start: Int, end: Int): ProbedTrack? {
        var mdiaRange: IntRange? = null
        var trakName: String? = null

        var cursor = start
        while (cursor + 8 <= end) {
            val size = uint32BE(buf, cursor).toInt()
            val type = String(buf, cursor + 4, 4, Charsets.US_ASCII)
            if (size < 8 || cursor + size > end) break
            when (type) {
                "mdia" -> mdiaRange = (cursor + 8)..(cursor + size)
                "udta" -> trakName = findMp4NameBox(buf, cursor + 8, cursor + size) ?: trakName
            }
            cursor += size
        }
        val range = mdiaRange ?: return null

        var language: String? = null
        var bcp47: String? = null
        var handlerType: String? = null
        var handlerName: String? = null
        var codecFourCc: String? = null

        var c = range.first
        while (c + 8 <= range.last) {
            val size = uint32BE(buf, c).toInt()
            val type = String(buf, c + 4, 4, Charsets.US_ASCII)
            if (size < 8 || c + size > range.last) break
            val bodyStart = c + 8
            val bodyEnd = c + size

            when (type) {
                "mdhd" -> language = parseMp4MdhdLanguage(buf, bodyStart, bodyEnd)
                "hdlr" -> {
                    val (hType, hName) = parseMp4Hdlr(buf, bodyStart, bodyEnd)
                    handlerType = hType
                    handlerName = hName
                }
                "elng" -> {
                    // FullBox (4 bytes version/flags) + null-terminated/UTF-8 BCP47 language tag
                    if (bodyStart + 4 < bodyEnd) {
                        bcp47 = readUtf8Clean(buf, bodyStart + 4, bodyEnd)
                    }
                }
                "minf" -> codecFourCc = findMp4StsdCodec(buf, bodyStart, bodyEnd)
            }
            c += size
        }

        val kind = when (handlerType) {
            "vide" -> ProbeTrackKind.Video
            "soun" -> ProbeTrackKind.Audio
            "sbtl", "text", "subt" -> ProbeTrackKind.Subtitle
            else -> return null
        }

        val usefulName = trakName ?: handlerName?.takeIf {
            !it.equals("SoundHandler", true) && !it.equals("VideoHandler", true) && !it.equals("SubtitleHandler", true)
        }
        val region = if (kind == ProbeTrackKind.Video) {
            AudioLanguageTag.Other
        } else {
            MediaLanguageClassifier.classifyTrackMetadata(language, bcp47, usefulName)
        }

        return ProbedTrack(
            kind = kind,
            languageCode = language,
            bcp47 = bcp47,
            name = usefulName,
            codec = codecFourCc?.uppercase(Locale.ROOT),
            regionTag = region,
        )
    }

    private fun parseMp4MdhdLanguage(buf: ByteArray, start: Int, end: Int): String? {
        if (start >= end) return null
        val version = buf[start].toInt() and 0xFF
        // ISO 14496-12: FullBox (4B) + creation/modification/timescale/duration = 20B (v0) or 32B (v1)
        val langOffset = if (version == 1) start + 32 else start + 20
        if (langOffset + 2 > end) return null
        val packed = ((buf[langOffset].toInt() and 0xFF) shl 8) or (buf[langOffset + 1].toInt() and 0xFF)
        if (packed == 0 || packed == 0x7FFF) return null
        val v1 = (packed ushr 10) and 0x1F
        val v2 = (packed ushr 5) and 0x1F
        val v3 = packed and 0x1F
        if (v1 !in 1..26 || v2 !in 1..26 || v3 !in 1..26) return null
        val code = String(charArrayOf((v1 + 0x60).toChar(), (v2 + 0x60).toChar(), (v3 + 0x60).toChar()))
        return if (code in setOf("und", "unk", "zxx", "mis")) null else code
    }

    private fun parseMp4Hdlr(buf: ByteArray, start: Int, end: Int): Pair<String?, String?> {
        if (start + 24 > end) return null to null
        val hType = String(buf, start + 8, 4, Charsets.US_ASCII)
        val rawName = readUtf8Clean(buf, start + 24, end)
        return hType to rawName?.takeIf { it.isNotBlank() }
    }

    private fun findMp4NameBox(buf: ByteArray, start: Int, end: Int): String? {
        var c = start
        while (c + 8 <= end) {
            val size = uint32BE(buf, c).toInt()
            val type = String(buf, c + 4, 4, Charsets.US_ASCII)
            if (size < 8 || c + size > end) break
            if (type == "name") {
                return readUtf8Clean(buf, c + 8, c + size)
            }
            c += size
        }
        return null
    }

    private fun findMp4StsdCodec(buf: ByteArray, start: Int, end: Int): String? {
        var c = start
        while (c + 8 <= end) {
            val size = uint32BE(buf, c).toInt()
            val type = String(buf, c + 4, 4, Charsets.US_ASCII)
            if (size < 8 || c + size > end) break
            if (type == "stbl") {
                return findMp4StsdCodec(buf, c + 8, c + size)
            }
            if (type == "stsd" && c + 24 <= c + size) {
                return String(buf, c + 20, 4, Charsets.US_ASCII)
            }
            c += size
        }
        return null
    }

    private fun formatMkvCodec(raw: String?): String? {
        val code = raw?.uppercase(Locale.ROOT) ?: return null
        return when {
            code.startsWith("A_EAC3") -> "EAC3"
            code.startsWith("A_AC3") -> "AC3"
            code.startsWith("A_AAC") -> "AAC"
            code.startsWith("A_DTS") -> "DTS"
            code.startsWith("A_TRUEHD") -> "TrueHD"
            code.startsWith("A_OPUS") -> "Opus"
            code.startsWith("A_FLAC") -> "FLAC"
            code.startsWith("S_TEXT/ASS") || code.startsWith("S_TEXT/SSA") -> "ASS"
            code.startsWith("S_TEXT/UTF8") -> "SRT"
            code.startsWith("S_HDMV/PGS") -> "PGS"
            else -> code.substringAfter('_').takeIf { it.isNotBlank() }
        }
    }

    private fun readEbmlId(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 4 && (first and mask) == 0) {
            mask = mask ushr 1
            len++
        }
        if (len > 4 || offset + len > buf.size) return null
        var value = first.toLong()
        for (i in 1 until len) {
            value = (value shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        }
        return value to (offset + len)
    }

    private fun readEbmlVintSize(buf: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 8 && (first and mask) == 0) {
            mask = mask ushr 1
            len++
        }
        if (len > 8 || offset + len > buf.size) return null
        var value = (first and (mask.inv())).toLong()
        for (i in 1 until len) {
            value = (value shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        }
        return value to (offset + len)
    }

    private fun readUnsignedInt(buf: ByteArray, start: Int, end: Int): Long {
        var v = 0L
        for (i in start until end) {
            v = (v shl 8) or (buf[i].toInt() and 0xFF).toLong()
        }
        return v
    }

    private fun readUtf8Clean(buf: ByteArray, start: Int, end: Int): String? {
        if (start >= end) return null
        return String(buf, start, end - start, Charsets.UTF_8)
            .trim { it == '\u0000' || it <= ' ' }
            .takeIf { it.isNotEmpty() }
    }

    private fun readExactUpTo(source: ProbeByteSource, offset: Long, count: Int): ByteArray? {
        val len = minOf(count.toLong(), source.length - offset).toInt()
        if (len <= 0) return null
        val buf = ByteArray(len)
        val n = readExact(source, offset, buf, len)
        return if (n > 0) buf.copyOf(n) else null
    }

    private fun readRange(source: ProbeByteSource, offset: Long, count: Int): ByteArray? {
        val targetLen = minOf(count.toLong(), source.length - offset).toInt()
        if (targetLen <= 0) return null
        val out = ByteArray(targetLen)
        val n = readExact(source, offset, out, targetLen)
        return if (n > 0) out.copyOf(n) else null
    }

    private fun readExact(source: ProbeByteSource, startOffset: Long, dest: ByteArray, count: Int): Int {
        var total = 0
        val scratch = ByteArray(minOf(65536, count))
        while (total < count) {
            val want = minOf(scratch.size, count - total)
            val n = source.readAt(startOffset + total, scratch, want)
            if (n <= 0) break
            System.arraycopy(scratch, 0, dest, total, n)
            total += n
        }
        return total
    }

    private fun uint32BE(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    private fun int64BE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        }
        return v
    }
}
