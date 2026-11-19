package org.cinito.online.search

import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.MediaLanguageClassifier
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.metadata.ParsedMediaTitle
import java.security.MessageDigest
import java.util.Locale

enum class SearchCategory(val label: String) {
    All("Todo"),
    Movies("Películas"),
    Series("Series"),
    Anime("Anime"),
    Other("Otros"),
}

enum class SearchLanguageFilter(val label: String) {
    PrioritizeLatino("Latino primero"),
    OnlyLatino("Solo Latino / Dual"),
    SpanishAll("Español (Lat + Cast)"),
    AllLanguages("Todos los idiomas"),
}

data class TorrentSearchResult(
    val id: String,
    val title: String,
    val parsed: ParsedMediaTitle,
    val sizeText: String?,
    val sizeBytes: Long?,
    val seeders: Int,
    val peers: Int,
    val providerId: String,
    val providerName: String,
    val magnetUri: String,
    val infoHash: String?,
    val uploadDateEpochSec: Long? = null,
    val category: SearchCategory = SearchCategory.All,
    val posterUrl: String? = null,
    val imdbId: String? = null,
    val detailsUrl: String? = null,
    val description: String? = null,
    val languageTag: AudioLanguageTag = AudioLanguageTag.Other,
    val languageEvidence: String? = null,
    val sourceRecordId: String? = null,
) {
    val normalizedInfoHash: String?
        get() = SearchUtils.normalizedInfoHash(this)
}

data class SearchTitleResolution(
    val displayTitle: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val imdbId: String? = null,
    val mediaType: String? = null,
)

data class SearchProviderInfo(
    val id: String,
    val name: String,
    val url: String,
    val categories: Set<SearchCategory>,
    val isTorznab: Boolean = false,
    val enabledByDefault: Boolean = true,
    val supportsNonEnglishQueries: Boolean = true,
    val maxQueriesPerSearch: Int = 3,
    val supportsImdbSearch: Boolean = false,
    val requiresImdbSearch: Boolean = supportsImdbSearch,
)

data class TorznabConfig(
    val id: String,
    val name: String,
    val url: String,
    val apiKey: String,
    val categories: Set<SearchCategory> = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime),
)

interface TorrentSearchProvider {
    val info: SearchProviderInfo
    suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult>
    suspend fun searchByImdb(
        imdbId: String,
        category: SearchCategory,
        season: Int? = null,
        episode: Int? = null,
        mediaTypeHint: String? = null,
    ): List<TorrentSearchResult> = emptyList()
    suspend fun latest(category: SearchCategory): List<TorrentSearchResult> = emptyList()
    suspend fun enrichResult(result: TorrentSearchResult): TorrentSearchResult = result
}

object SearchUtils {
    val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.qu.ax:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://exodus.desync.com:6969/announce",
    )

    private val BTIH_HEX = Regex("""(?i)xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})""")
    private val TR_PARAM_REGEX = Regex("""(?i)[?&]tr=([^&]+)""")

    fun extractInfoHash(magnetUri: String): String? =
        BTIH_HEX.find(magnetUri)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)

    fun normalizedInfoHash(result: TorrentSearchResult): String? {
        val raw = result.infoHash?.takeIf { it.isNotBlank() } ?: extractInfoHash(result.magnetUri)
        val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return decodeInfoHashBytes(clean)?.first ?: clean.lowercase(Locale.ROOT)
    }

    fun extractTrackersFromMagnet(magnetUri: String): List<String> =
        TR_PARAM_REGEX.findAll(magnetUri)
            .mapNotNull { runCatching { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    /**
     * Normalizes a 40-char hex or 32-char Base32 infoHash into `(canonicalHex40, raw20Bytes)`.
     */
    fun decodeInfoHashBytes(rawOrHex: String): Pair<String, ByteArray>? {
        val clean = rawOrHex.trim().lowercase(Locale.ROOT)
        if (clean.length == 40 && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
            val out = ByteArray(20)
            for (i in 0 until 20) {
                out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return clean to out
        }
        if (clean.length == 32) {
            val bytes = decodeBase32(clean) ?: return null
            if (bytes.size != 20) return null
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return hex to bytes
        }
        return null
    }

    private fun decodeBase32(input: String): ByteArray? {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = ByteArray(input.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var idx = 0
        for (ch in input) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[idx++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }
        return if (idx == out.size) out else out.copyOf(idx)
    }

    fun encodeUrl(value: String): String =
        java.net.URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    fun buildMagnet(infoHash: String, displayName: String? = null): String {
        val cleanHash = infoHash.trim().lowercase(Locale.ROOT)
        val tr = DEFAULT_TRACKERS.joinToString("&") { "tr=$it" }
        val dn = displayName?.takeIf { it.isNotBlank() }?.let { "&dn=${encodeUrl(it)}" }.orEmpty()
        return "magnet:?xt=urn:btih:$cleanHash$dn&$tr"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun parseSizeToBytes(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""(?i)([\d.,]+)\s*(B|KB|KiB|MB|MiB|GB|GiB|TB|TiB)""").find(raw) ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.ROOT)
        val factor = when {
            unit.startsWith("T") -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            unit.startsWith("G") -> 1024.0 * 1024.0 * 1024.0
            unit.startsWith("M") -> 1024.0 * 1024.0
            unit.startsWith("K") -> 1024.0
            else -> 1.0
        }
        return (value * factor).toLong()
    }

    fun stableId(providerId: String, key: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$providerId:$key".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "$providerId:$hash"
    }

    private val SPAM_EXEC_SUFFIX = Regex("""(?i)(?:\.|[\s\-_]+downloader\.)(?:exe|msi|scr|bat|cmd|apk|lnk)\s*$""")

    fun isExecutableSpam(title: String): Boolean = SPAM_EXEC_SUFFIX.containsMatchIn(title.trim())

    fun makeResult(
        providerId: String,
        providerName: String,
        title: String,
        magnetUri: String,
        sizeText: String? = null,
        sizeBytes: Long? = parseSizeToBytes(sizeText),
        seeders: Int = 0,
        peers: Int = 0,
        uploadDateEpochSec: Long? = null,
        category: SearchCategory = SearchCategory.All,
        posterUrl: String? = null,
        imdbId: String? = null,
        detailsUrl: String? = null,
        description: String? = null,
        providerLanguageField: String? = null,
        subtitleLanguageField: String? = null,
        sourceRecordId: String? = null,
    ): TorrentSearchResult {
        val hash = extractInfoHash(magnetUri)
        val parsed = MediaTitleParser.parse(title, isAnimeHint = category == SearchCategory.Anime)
        val lang = MediaLanguageClassifier.classify(
            title = title,
            description = description,
            providerLanguageField = providerLanguageField,
            subtitleLanguageField = subtitleLanguageField,
        )
        return TorrentSearchResult(
            id = stableId(providerId, hash ?: title),
            title = title,
            parsed = parsed,
            sizeText = sizeText ?: sizeBytes?.let(::formatBytes),
            sizeBytes = sizeBytes,
            seeders = seeders.coerceAtLeast(0),
            peers = peers.coerceAtLeast(0),
            providerId = providerId,
            providerName = providerName,
            magnetUri = magnetUri,
            infoHash = hash,
            uploadDateEpochSec = uploadDateEpochSec,
            category = category,
            posterUrl = posterUrl?.takeIf { it.startsWith("https://") },
            imdbId = imdbId,
            detailsUrl = detailsUrl?.takeIf { it.startsWith("https://") },
            description = description?.takeIf { it.isNotBlank() },
            languageTag = lang.tag,
            languageEvidence = lang.matchedEvidence,
            sourceRecordId = sourceRecordId,
        )
    }
}
