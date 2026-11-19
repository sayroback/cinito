package org.cinito.online.metadata

import java.util.Locale

enum class MediaKind { MOVIE, SERIES, ANIME, OTHER }

data class ParsedMediaTitle(
    val rawTitle: String,
    val cleanTitle: String,
    val year: Int? = null,
    val kind: MediaKind = MediaKind.OTHER,
    val season: Int? = null,
    val episode: Int? = null,
    val isSeasonPack: Boolean = false,
    val qualityBadges: List<String> = emptyList(),
) {
    val episodeBadge: String?
        get() = when {
            season != null && episode != null -> "T%02d · E%02d".format(Locale.US, season, episode)
            season != null && isSeasonPack -> "Temporada $season"
            episode != null -> "Ep. %02d".format(Locale.US, episode)
            else -> null
        }

    val lookupKey: String
        get() = buildString {
            append(cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim())
            if (year != null) append("|$year")
            append("|${kind.name}")
        }
}

object MediaTitleParser {
    private val RELEASE_GROUP_PREFIX = Regex("""^\[[^\]]{1,32}\]\s*""")
    private val BRACKET_NOISE = Regex("""\[[^\]]*\]|\((?!(?:19|20)\d{2}\))[^)]*\)""")
    private val SEASON_EPISODE = Regex("""(?i)\bS(\d{1,2})[.\-_ ]?E(\d{1,3})(?:[.\-_ ]?E\d{1,3})?\b|\b(\d{1,2})x(\d{1,3})\b""")
    private val SEASON_ONLY = Regex("""(?i)\b(?:S(\d{1,2})|Season\s*(\d{1,2})|Temporada\s*(\d{1,2}))\b""")
    private val ANIME_EPISODE = Regex("""\s[-–]\s(\d{1,3})(?:v\d+)?(?=\s|\(|\[|$)""")
    private val YEAR_PATTERN = Regex("""\b(19[2-9]\d|20[0-3]\d)\b""")
    private val EXTENSION_PATTERN = Regex("""\.(mkv|mp4|m4v|avi|webm|mov|ts|m2ts)$""", RegexOption.IGNORE_CASE)

    private val NOISE_CUTOFF = Regex(
        """(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr10\+?|hdr|dv|dolby\.?vision|web[- .]?dl|webrip|bluray|blu[- .]?ray|bdrip|brrip|hdrip|remux|x264|x265|h\.?264|h\.?265|hevc|av1|10bit|aac|ac3|eac3|ddp?5\.?1|dts(?:-hd)?|truehd|atmos|flac|opus|dual|latino|lat|castellano|espa[nñ]ol|multi|sub|vose|repack|proper|extended|uncut|complete)\b"""
    )

    private val BADGE_MATCHERS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\b(2160p|4k|uhd)\b""") to "4K",
        Regex("""(?i)\b1080p\b""") to "1080p",
        Regex("""(?i)\b720p\b""") to "720p",
        Regex("""(?i)\b(dolby\.?vision|\bdv\b)\b""") to "DV",
        Regex("""(?i)\bhdr10\+?\b|\bhdr\b""") to "HDR",
        Regex("""(?i)\b(x265|h\.?265|hevc)\b""") to "HEVC",
        Regex("""(?i)\bav1\b""") to "AV1",
        Regex("""(?i)\b10bit\b""") to "10-bit",
        Regex("""(?i)\batmos\b""") to "Atmos",
        Regex("""(?i)\b(eac3|ddp5\.?1|dd\+5\.?1)\b""") to "DDP 5.1",
        Regex("""(?i)\b(ac3|dd5\.?1)\b""") to "AC3",
        Regex("""(?i)\bdts(?:-hd)?\b""") to "DTS",
        Regex("""(?i)\btruehd\b""") to "TrueHD",
        Regex("""(?i)\bdual\b""") to "Dual",
        Regex("""(?i)\b(latino|lat)\b""") to "Latino",
        Regex("""(?i)\b(castellano|espa[nñ]ol)\b""") to "Español",
        Regex("""(?i)\bmulti\b""") to "Multi",
    )

    fun parse(raw: String, isAnimeHint: Boolean = false): ParsedMediaTitle {
        val withoutExt = EXTENSION_PATTERN.replace(raw.trim(), "")
        val hadLeadingGroup = RELEASE_GROUP_PREFIX.containsMatchIn(withoutExt)
        val badges = extractBadges(withoutExt)

        val seMatch = SEASON_EPISODE.find(withoutExt)
        val seasonFromSe = seMatch?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toIntOrNull() }
        val episodeFromSe = seMatch?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).toIntOrNull() }

        val seasonOnlyMatch = if (seMatch == null) SEASON_ONLY.find(withoutExt) else null
        val seasonOnly = seasonOnlyMatch?.let {
            (it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { it.groupValues[3] }).toIntOrNull()
        }

        val animeEpMatch = if (seMatch == null) ANIME_EPISODE.find(withoutExt) else null
        val animeEpisode = animeEpMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        var working = RELEASE_GROUP_PREFIX.replace(withoutExt, "")
        working = BRACKET_NOISE.replace(working, " ")
        working = working.replace('.', ' ').replace('_', ' ')

        // Determine the earliest technical/episode marker to slice the clean title before it
        val cutPositions = mutableListOf<Int>()
        SEASON_EPISODE.find(working)?.let { cutPositions.add(it.range.first) }
        SEASON_ONLY.find(working)?.let { cutPositions.add(it.range.first) }
        ANIME_EPISODE.find(working)?.let { cutPositions.add(it.range.first) }
        NOISE_CUTOFF.find(working)?.let { cutPositions.add(it.range.first) }

        val earliestCut = cutPositions.minOrNull()
        var titleCandidate = if (earliestCut != null && earliestCut > 1) {
            working.substring(0, earliestCut)
        } else {
            working
        }

        val yearMatches = YEAR_PATTERN.findAll(working).toList()
        val yearMatchInTitle = YEAR_PATTERN.findAll(titleCandidate).lastOrNull()
        val year = (yearMatchInTitle ?: yearMatches.firstOrNull())?.groupValues?.get(1)?.toIntOrNull()

        if (yearMatchInTitle != null && yearMatchInTitle.range.first > 1) {
            titleCandidate = titleCandidate.substring(0, yearMatchInTitle.range.first)
        }

        val cleanTitle = titleCandidate
            .replace(Regex("""[-–:\s]+$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .ifEmpty { raw.trim() }

        val kind = when {
            isAnimeHint || (hadLeadingGroup && animeEpisode != null) -> MediaKind.ANIME
            seasonFromSe != null || seasonOnly != null -> MediaKind.SERIES
            animeEpisode != null -> MediaKind.ANIME
            year != null || badges.isNotEmpty() -> MediaKind.MOVIE
            else -> MediaKind.OTHER
        }

        return ParsedMediaTitle(
            rawTitle = raw,
            cleanTitle = cleanTitle,
            year = year,
            kind = kind,
            season = seasonFromSe ?: seasonOnly,
            episode = episodeFromSe ?: animeEpisode,
            isSeasonPack = seasonFromSe == null && seasonOnly != null,
            qualityBadges = badges,
        )
    }

    /** Extracts a readable episode title appearing after an episode marker (e.g. S02E01 or - 05) and before release tags. */
    fun extractEpisodeTitle(rawFileName: String): String? {
        val withoutExt = EXTENSION_PATTERN.replace(rawFileName.trim(), "")
        val marker = SEASON_EPISODE.find(withoutExt) ?: ANIME_EPISODE.find(withoutExt) ?: return null
        val afterMarker = withoutExt.substring(marker.range.last + 1)
            .let { BRACKET_NOISE.replace(it, " ") }
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
        if (afterMarker.isEmpty()) return null
        val cut = NOISE_CUTOFF.find(afterMarker)?.range?.first
        val candidate = (if (cut != null) afterMarker.substring(0, cut) else afterMarker)
            .replace(Regex("""^[\s\-–:]+|[\s\-–:]+$"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return candidate.takeIf { it.length >= 2 && it.any(Char::isLetter) }
    }

    private fun extractBadges(input: String): List<String> {
        val result = LinkedHashSet<String>()
        for ((regex, label) in BADGE_MATCHERS) {
            if (regex.containsMatchIn(input)) {
                // Prefer 4K over lower resolutions if multiple tags accidentally appear
                if (label == "1080p" && "4K" in result) continue
                if (label == "720p" && ("4K" in result || "1080p" in result)) continue
                result.add(label)
            }
        }
        return result.take(5)
    }
}
