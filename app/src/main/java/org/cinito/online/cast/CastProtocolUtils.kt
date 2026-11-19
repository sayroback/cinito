package org.cinito.online.cast

import android.util.Log
import org.cinito.online.BuildConfig
import org.cinito.online.network.NetworkSafety
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class CastWireMessage(
    val protocolVersion: Int = 0,
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadType: Int = 0, // 0 = STRING, 1 = BINARY
    val payloadUtf8: String? = null,
)

object CastProtocolUtils {
    private val SRT_TIMESTAMP = Regex("""(\d{1,2}):(\d{2}:\d{2}),(\d{3})\s*-->\s*(\d{1,2}):(\d{2}:\d{2}),(\d{3})""")
    private val VTT_TIMESTAMP = Regex("""^(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{1,3})$""")
    private val ASS_OVERRIDE_TAG = Regex("""\{[^}\r\n]*\}""")
    private val VTT_INLINE_TAG = Regex("""</?(?:c|v)(?:\.[^>\s]*)?(?:\s+[^>]*)?>|<\d{1,2}:\d{2}(?::\d{2})?\.\d{3}>""")
    private val LG_WORD_REGEX = Regex("""\blg\b""")

    /** Decodes subtitle bytes using UTF-8/UTF-16 BOM, strict UTF-8 validation, or Windows-1252/ISO-8859-1 fallback. */
    fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        val utf8Decoded = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8Decoded != null) return utf8Decoded

        val fallbackCharset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        return String(bytes, fallbackCharset)
    }

    fun srtToWebVtt(rawSrt: String): String {
        val cleaned = rawSrt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val withoutTags = ASS_OVERRIDE_TAG.replace(cleaned, "")
        val converted = SRT_TIMESTAMP.replace(withoutTags) { m ->
            val h1 = m.groupValues[1].padStart(2, '0')
            val h2 = m.groupValues[4].padStart(2, '0')
            "$h1:${m.groupValues[2]}.${m.groupValues[3]} --> $h2:${m.groupValues[5]}.${m.groupValues[6]}"
        }
        val trimmed = converted.trimStart()
        return if (trimmed.startsWith("WEBVTT")) trimmed else "WEBVTT\n\n$trimmed\n"
    }

    /** Converts ASS/SSA dialogue events into clean WebVTT cues. */
    fun assToWebVtt(rawAss: String): String {
        val normalized = rawAss.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        var inEvents = false
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var columnCount = 10
        val cues = mutableListOf<String>()

        for (rawLine in normalized.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                if (cols.isNotEmpty()) {
                    columnCount = cols.size
                    cols.indexOf("start").takeIf { it >= 0 }?.let { startIdx = it }
                    cols.indexOf("end").takeIf { it >= 0 }?.let { endIdx = it }
                    cols.indexOf("text").takeIf { it >= 0 }?.let { textIdx = it }
                }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val body = line.substringAfter(':').trimStart()
                val parts = body.split(',', limit = columnCount.coerceAtLeast(textIdx + 1))
                if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue
                val startVtt = assTimestampToVtt(parts[startIdx].trim()) ?: continue
                val endVtt = assTimestampToVtt(parts[endIdx].trim()) ?: continue
                val cleanedText = ASS_OVERRIDE_TAG.replace(parts[textIdx], "")
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .replace("\\h", " ")
                    .lines()
                    .joinToString("\n") { it.trim() }
                    .trim()
                if (cleanedText.isEmpty()) continue
                cues += "$startVtt --> $endVtt\n$cleanedText"
            }
        }

        return if (cues.isEmpty()) {
            "WEBVTT\n\n"
        } else {
            "WEBVTT\n\n${cues.joinToString("\n\n")}\n"
        }
    }

    /** Converts WebVTT cues into standard SubRip (.srt) format for DLNA/legacy TV renderers. */
    fun vttToSrt(rawVtt: String): String {
        val normalized = rawVtt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("""\n\s*\n"""))
        val srtCues = mutableListOf<String>()
        var cueNum = 1

        for (rawBlock in blocks) {
            val lines = rawBlock.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }
            if (lines.isEmpty()) continue
            val first = lines.first().trim()
            if (first.startsWith("WEBVTT", ignoreCase = true) ||
                first.startsWith("NOTE", ignoreCase = true) ||
                first.startsWith("STYLE", ignoreCase = true) ||
                first.startsWith("REGION", ignoreCase = true)
            ) {
                continue
            }

            val timingIdx = lines.indexOfFirst { "-->" in it }
            if (timingIdx < 0) continue

            val timingLine = lines[timingIdx]
            val arrowParts = timingLine.split("-->", limit = 2)
            if (arrowParts.size != 2) continue
            val startToken = arrowParts[0].trim().split(Regex("""\s+"""), limit = 2).firstOrNull().orEmpty()
            val endToken = arrowParts[1].trim().split(Regex("""\s+"""), limit = 2).firstOrNull().orEmpty()
            val startSrt = vttTimestampToSrt(startToken) ?: continue
            val endSrt = vttTimestampToSrt(endToken) ?: continue

            val text = lines.drop(timingIdx + 1)
                .joinToString("\n") { VTT_INLINE_TAG.replace(it, "") }
                .trim()
            if (text.isEmpty()) continue

            srtCues += "${cueNum++}\n$startSrt --> $endSrt\n$text"
        }

        return if (srtCues.isEmpty()) "" else "${srtCues.joinToString("\n\n")}\n"
    }

    private fun assTimestampToVtt(ts: String): String? {
        val parts = ts.split(':')
        if (parts.size != 3) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        val secParts = parts[2].trim().split('.', ',')
        if (secParts.size != 2) return null
        val s = secParts[0].toIntOrNull() ?: return null
        val fracRaw = secParts[1].filter { it.isDigit() }
        if (fracRaw.isEmpty()) return null
        val ms = fracRaw.take(3).padEnd(3, '0')
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%s", h, m, s, ms)
    }

    private fun vttTimestampToSrt(ts: String): String? {
        val match = VTT_TIMESTAMP.matchEntire(ts.trim()) ?: return null
        val h = (match.groupValues[1].ifEmpty { "0" }).padStart(2, '0')
        val m = match.groupValues[2]
        val s = match.groupValues[3]
        val ms = match.groupValues[4].padEnd(3, '0')
        return "$h:$m:$s,$ms"
    }

    /** Preserves real container extensions on cast HTTP URLs because LG/DLNA renderers inspect URL suffixes. */
    fun mediaExtensionFor(title: String, mimeType: String): String {
        val ext = title.trim().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mp3", "flac")) {
            return if (ext == "m4v") ".mp4" else ".$ext"
        }
        val mime = mimeType.lowercase(Locale.ROOT)
        return when {
            "matroska" in mime || "mkv" in mime -> ".mkv"
            "webm" in mime -> ".webm"
            "mp2t" in mime -> ".ts"
            "msvideo" in mime || "avi" in mime -> ".avi"
            "quicktime" in mime -> ".mov"
            "mpeg" in mime && "audio" in mime -> ".mp3"
            "flac" in mime -> ".flac"
            else -> ".mp4"
        }
    }

    fun parseHttpRangeHeader(header: String?, totalLength: Long): LongRange? {
        if (header.isNullOrBlank() || totalLength <= 0L) return null
        val spec = header.trim().removePrefix("bytes=").takeIf { it != header.trim() } ?: return null
        if (spec.contains(',')) return null
        val parts = spec.split('-', limit = 2)
        if (parts.size != 2) return null
        val startStr = parts[0].trim()
        val endStr = parts[1].trim()

        return if (startStr.isEmpty()) {
            val suffix = endStr.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val start = (totalLength - suffix).coerceAtLeast(0L)
            start until totalLength
        } else {
            val start = startStr.toLongOrNull()?.takeIf { it >= 0L && it < totalLength } ?: return null
            val end = if (endStr.isEmpty()) {
                totalLength - 1L
            } else {
                endStr.toLongOrNull()?.coerceAtLeast(start)?.coerceAtMost(totalLength - 1L) ?: return null
            }
            start..end
        }
    }

    fun escapeXml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    fun dlnaDidlMetadataXml(
        title: String,
        mediaUrl: String,
        mimeType: String,
        subtitleUrl: String? = null,
    ): String {
        val escTitle = escapeXml(title)
        val escUrl = escapeXml(mediaUrl)
        val escMime = escapeXml(mimeType)
        val subtitleTags = if (!subtitleUrl.isNullOrBlank()) {
            val escSub = escapeXml(subtitleUrl)
            """
            <pv:subtitleFileUri>$escSub</pv:subtitleFileUri>
            <sec:CaptionInfoEx sec:type="srt">$escSub</sec:CaptionInfoEx>
            """.trimIndent()
        } else {
            ""
        }
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/" xmlns:pv="http://www.pv.com/pvns/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>$escTitle</dc:title>
                <upnp:class>object.item.videoItem.movie</upnp:class>
                <res protocolInfo="http-get:*:$escMime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">$escUrl</res>
                $subtitleTags
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    fun formatDlnaTime(positionMs: Long): String {
        val totalSec = (positionMs.coerceAtLeast(0L) / 1000L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
    }

    fun parseDlnaTimeToMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim().substringBefore('.')
        val parts = clean.split(':')
        if (parts.size != 3) return 0L
        val h = parts[0].toLongOrNull() ?: return 0L
        val m = parts[1].toLongOrNull() ?: return 0L
        val s = parts[2].toLongOrNull() ?: return 0L
        return ((h * 3600L) + (m * 60L) + s) * 1000L
    }

    fun parseUpnpFaultMessage(xml: String): String? {
        if (xml.isBlank()) return null
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return null
        val desc = doc.selectFirst("errorDescription")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("faultstring")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val code = doc.selectFirst("errorCode")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
        return if (code != null) "$desc ($code)" else desc
    }

    fun parseDlnaVolume(xml: String): Float? {
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return null
        val current = doc.selectFirst("CurrentVolume")?.ownText()?.trim()?.toFloatOrNull() ?: return null
        return (current / 100f).coerceIn(0f, 1f)
    }

    fun parseDlnaMute(xml: String): Boolean? {
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull() ?: return null
        val current = doc.selectFirst("CurrentMute")?.ownText()?.trim() ?: return null
        return current == "1" || current.equals("true", ignoreCase = true)
    }

    fun parseSsdpLocationUrl(packetText: String): String? {
        for (line in packetText.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("LOCATION:", ignoreCase = true)) {
                val url = trimmed.substringAfter(':').trim()
                val uri = runCatching { URI(url) }.getOrNull() ?: return null
                val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
                val host = uri.host ?: return null
                if ((scheme == "http" || scheme == "https") && NetworkSafety.isPrivateLanHost(host)) {
                    return url
                }
                return null
            }
        }
        return null
    }

    fun parseUpnpDeviceDescription(xml: String, locationUrl: String): CastDevice? {
        if (xml.isBlank()) return null
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val friendlyName = doc.selectFirst("device > friendlyName")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val manufacturer = doc.selectFirst("device > manufacturer")?.ownText()?.trim().orEmpty()
        val model = doc.selectFirst("device > modelName")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
        val udn = doc.selectFirst("device > udn")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: locationUrl
        val deviceType = doc.selectFirst("device > deviceType")?.ownText().orEmpty()

        val locationUri = runCatching { URI(locationUrl) }.getOrNull() ?: return null
        val locationHost = locationUri.host?.takeIf { NetworkSafety.isPrivateLanHost(it) } ?: return null
        val candidateBaseUri = doc.selectFirst("URLBase")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?.takeIf { uri ->
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val h = uri.host
                (scheme == "http" || scheme == "https") &&
                    h != null &&
                    h.equals(locationHost, ignoreCase = true) &&
                    NetworkSafety.isPrivateLanHost(h)
            }
        val baseUri = candidateBaseUri ?: locationUri
        val host = locationHost
        val port = if (baseUri.port > 0) baseUri.port else if (baseUri.scheme.equals("https", ignoreCase = true)) 443 else 80

        var avTransportUrl: String? = null
        var renderingControlUrl: String? = null
        var hasWebOsService = false
        var hasDialService = deviceType.contains("dial-multiscreen-org", ignoreCase = true) ||
            deviceType.contains(":dial:", ignoreCase = true)
        for (service in doc.select("service")) {
            val serviceType = service.selectFirst("serviceType")?.ownText().orEmpty()
            if (serviceType.contains("webos-second-screen", ignoreCase = true) ||
                serviceType.contains("lge-com", ignoreCase = true)
            ) {
                hasWebOsService = true
            }
            if (serviceType.contains("dial-multiscreen-org", ignoreCase = true) ||
                serviceType.contains(":dial:", ignoreCase = true)
            ) {
                hasDialService = true
            }
            val controlPath = service.selectFirst("controlURL")?.ownText()?.trim().orEmpty()
            if (controlPath.isEmpty()) continue
            val resolved = runCatching { baseUri.resolve(controlPath).toString() }.getOrNull() ?: continue
            val resolvedHost = runCatching { URI(resolved).host }.getOrNull() ?: continue
            if (!resolvedHost.equals(host, ignoreCase = true) || !NetworkSafety.isPrivateLanHost(resolvedHost)) continue

            when {
                serviceType.contains("AVTransport", ignoreCase = true) -> avTransportUrl = resolved
                serviceType.contains("RenderingControl", ignoreCase = true) -> renderingControlUrl = resolved
            }
        }

        val vendor = inferVendor(
            manufacturer = manufacturer,
            friendlyName = friendlyName,
            model = model.orEmpty(),
            hasWebOsService = hasWebOsService,
        )
        val isLgWebOs = vendor == CastVendor.LG || hasWebOsService
        val isGoogleDial = hasDialService && vendor == CastVendor.Google
        if (avTransportUrl == null && !isLgWebOs && !isGoogleDial) return null

        val protocol = when {
            isLgWebOs -> CastProtocol.WebOs
            isGoogleDial -> CastProtocol.GoogleCast
            else -> CastProtocol.Dlna
        }
        val idPrefix = when (protocol) {
            CastProtocol.WebOs -> "webos"
            CastProtocol.GoogleCast -> "gcast"
            CastProtocol.Dlna -> "dlna"
        }
        val resolvedPort = if (protocol == CastProtocol.GoogleCast) 8009 else port
        return CastDevice(
            id = "$idPrefix:$udn",
            name = friendlyName,
            model = model,
            host = host,
            port = resolvedPort,
            protocol = protocol,
            vendor = vendor,
            avTransportControlUrl = avTransportUrl,
            renderingControlUrl = renderingControlUrl,
            ssapPort = if (isLgWebOs) 3000 else null,
        )
    }

    /** Merges DLNA and SSAP discoveries on the same physical TV into one unified CastDevice entry. */
    fun mergeDiscoveredDevice(existing: List<CastDevice>, incoming: CastDevice): List<CastDevice> {
        val matchIdx = existing.indexOfFirst { prev ->
            prev.id == incoming.id ||
                (prev.host == incoming.host &&
                    ((prev.vendor == CastVendor.LG && incoming.vendor == CastVendor.LG) ||
                        prev.protocol == incoming.protocol ||
                        (prev.protocol == CastProtocol.WebOs && incoming.protocol == CastProtocol.Dlna &&
                            (prev.vendor == CastVendor.LG || incoming.vendor == CastVendor.LG)) ||
                        (incoming.protocol == CastProtocol.WebOs && prev.protocol == CastProtocol.Dlna &&
                            (prev.vendor == CastVendor.LG || incoming.vendor == CastVendor.LG))))
        }
        val next = existing.toMutableList()
        if (matchIdx < 0) {
            next += incoming
        } else {
            val prev = existing[matchIdx]
            val mergedVendor = if (incoming.vendor != CastVendor.Generic) incoming.vendor else prev.vendor
            val mergedProtocol = when {
                mergedVendor == CastVendor.LG || prev.protocol == CastProtocol.WebOs || incoming.protocol == CastProtocol.WebOs ->
                    CastProtocol.WebOs
                else -> incoming.protocol
            }
            val mergedSsapPort = incoming.ssapPort ?: prev.ssapPort
            val mergedPort = when {
                mergedProtocol == CastProtocol.WebOs -> when {
                    incoming.protocol == CastProtocol.WebOs -> incoming.ssapPort ?: incoming.port
                    prev.protocol == CastProtocol.WebOs -> prev.ssapPort ?: prev.port
                    else -> mergedSsapPort ?: prev.port
                }
                else -> incoming.port
            }
            val incomingIsSynthetic = incoming.name == "LG webOS TV (${incoming.host})" ||
                incoming.name == "Chromecast / Google TV (${incoming.host})"
            val mergedName = if (incomingIsSynthetic && prev.name.isNotBlank()) {
                prev.name
            } else {
                incoming.name.ifBlank { prev.name }
            }
            next[matchIdx] = prev.copy(
                id = if (mergedProtocol == CastProtocol.WebOs && incoming.protocol == CastProtocol.WebOs) incoming.id else prev.id,
                name = mergedName,
                model = incoming.model ?: prev.model,
                port = mergedPort,
                protocol = mergedProtocol,
                vendor = mergedVendor,
                avTransportControlUrl = incoming.avTransportControlUrl ?: prev.avTransportControlUrl,
                renderingControlUrl = incoming.renderingControlUrl ?: prev.renderingControlUrl,
                ssapPort = mergedSsapPort,
                hasCiniToTvApp = prev.hasCiniToTvApp || incoming.hasCiniToTvApp,
            )
        }
        return next.sortedWith(
            compareBy<CastDevice> {
                when (it.protocol) {
                    CastProtocol.WebOs -> 0
                    CastProtocol.GoogleCast -> 1
                    CastProtocol.Dlna -> 2
                }
            }.thenBy { it.name.lowercase(Locale.ROOT) },
        )
    }

    private fun inferVendor(
        manufacturer: String,
        friendlyName: String,
        model: String,
        hasWebOsService: Boolean,
    ): CastVendor {
        if (hasWebOsService) return CastVendor.LG
        val mfgLower = manufacturer.lowercase(Locale.ROOT)
        val combined = "$manufacturer $friendlyName $model".lowercase(Locale.ROOT)
        return when {
            "lg electronics" in combined ||
                "lge" in combined ||
                "webos" in combined ||
                "[lg]" in combined ||
                LG_WORD_REGEX.containsMatchIn(mfgLower) -> CastVendor.LG
            "samsung" in combined -> CastVendor.Samsung
            "sony" in combined || "bravia" in combined -> CastVendor.Sony
            "roku" in combined -> CastVendor.Roku
            "google" in combined || "chromecast" in combined -> CastVendor.Google
            else -> CastVendor.Generic
        }
    }

    // --- Minimal Pure-Kotlin Cast V2 Protobuf Wire Encoder / Decoder ---

    fun encodeCastMessage(msg: CastWireMessage): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarintField(out, 1, msg.protocolVersion.toLong())
        writeStringField(out, 2, msg.sourceId)
        writeStringField(out, 3, msg.destinationId)
        writeStringField(out, 4, msg.namespace)
        writeVarintField(out, 5, msg.payloadType.toLong())
        msg.payloadUtf8?.let { writeStringField(out, 6, it) }
        val payload = out.toByteArray()
        val len = payload.size
        return byteArrayOf(
            ((len ushr 24) and 0xFF).toByte(),
            ((len ushr 16) and 0xFF).toByte(),
            ((len ushr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        ) + payload
    }

    fun decodeCastMessage(payload: ByteArray): CastWireMessage {
        var idx = 0
        var protocolVersion = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadType = 0
        var payloadUtf8: String? = null

        while (idx < payload.size) {
            val (tag, nextIdx) = readVarint(payload, idx)
            idx = nextIdx
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when (wireType) {
                0 -> {
                    val (v, after) = readVarint(payload, idx)
                    idx = after
                    when (fieldNum) {
                        1 -> protocolVersion = v.toInt()
                        5 -> payloadType = v.toInt()
                    }
                }
                2 -> {
                    val (len, afterLen) = readVarint(payload, idx)
                    if (len < 0L || len > (payload.size - afterLen).toLong()) break
                    val end = afterLen + len.toInt()
                    val bytes = payload.copyOfRange(afterLen, end)
                    idx = end
                    val text = String(bytes, Charsets.UTF_8)
                    when (fieldNum) {
                        2 -> sourceId = text
                        3 -> destinationId = text
                        4 -> namespace = text
                        6 -> payloadUtf8 = text
                    }
                }
                else -> break
            }
        }
        return CastWireMessage(
            protocolVersion = protocolVersion,
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadType = payloadType,
            payloadUtf8 = payloadUtf8,
        )
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarint(out: ByteArrayOutputStream, v: Long) {
        var value = v
        while (true) {
            if ((value and 0x7FL.inv()) == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value.toInt() and 0x7F) or 0x80))
            value = value ushr 7
        }
    }

    private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var shift = 0
        var result = 0L
        var i = start
        while (i < buf.size && shift < 64) {
            val b = buf[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
        }
        return result to i
    }
}

internal object CastLog {
    private const val TAG = "CineCast"

    fun i(area: String, msg: String) {
        runCatching { Log.i(TAG, "[$area] $msg") }
    }

    fun w(area: String, msg: String, err: Throwable? = null) {
        runCatching {
            if (err != null) Log.w(TAG, "[$area] $msg: ${err.javaClass.simpleName}: ${err.message}", err)
            else Log.w(TAG, "[$area] $msg")
        }
    }

    fun e(area: String, msg: String, err: Throwable? = null) {
        runCatching {
            if (err != null) Log.e(TAG, "[$area] $msg: ${err.javaClass.simpleName}: ${err.message}", err)
            else Log.e(TAG, "[$area] $msg")
        }
    }
}

internal object CastDebugOverrides {
    private fun sysProp(name: String): String? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, name, "") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun hostOverride(): String? = sysProp("debug.cinito.cast_host")

    fun portOverride(): Int? = sysProp("debug.cinito.cast_port")?.toIntOrNull()?.takeIf { it in 1024..65535 }
}
