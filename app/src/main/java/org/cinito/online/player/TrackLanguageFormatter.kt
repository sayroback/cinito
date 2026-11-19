package org.cinito.online.player

import java.util.Locale

object TrackLanguageFormatter {
    private val ISO_DISPLAY_NAMES = mapOf(
        "es" to "Español",
        "spa" to "Español",
        "es-419" to "Español Latino",
        "es-mx" to "Español (México)",
        "es-es" to "Español (España)",
        "lat" to "Español Latino",
        "en" to "Inglés",
        "eng" to "Inglés",
        "ja" to "Japonés",
        "jpn" to "Japonés",
        "fr" to "Francés",
        "fre" to "Francés",
        "fra" to "Francés",
        "de" to "Alemán",
        "deu" to "Alemán",
        "ger" to "Alemán",
        "it" to "Italiano",
        "ita" to "Italiano",
        "pt" to "Portugués",
        "por" to "Portugués",
        "pt-br" to "Portugués (Brasil)",
        "ko" to "Coreano",
        "kor" to "Coreano",
        "zh" to "Chino",
        "zho" to "Chino",
        "chi" to "Chino",
        "ru" to "Ruso",
        "rus" to "Ruso",
        "ar" to "Árabe",
        "ara" to "Árabe",
        "hi" to "Hindi",
        "hin" to "Hindi",
        "pl" to "Polaco",
        "pol" to "Polaco",
        "nl" to "Neerlandés",
        "nld" to "Neerlandés",
        "dut" to "Neerlandés",
        "ca" to "Catalán",
        "cat" to "Catalán",
        "und" to "Sin idioma",
    )

    private val LANGUAGE_GROUPS = listOf(
        setOf("es", "spa", "es-419", "es-mx", "es-es", "lat", "castellano", "espanol", "spanish"),
        setOf("en", "eng", "english", "ing"),
        setOf("ja", "jpn", "jap", "japanese"),
        setOf("fr", "fre", "fra", "french"),
        setOf("de", "deu", "ger", "german"),
        setOf("it", "ita", "italian"),
        setOf("pt", "por", "pt-br", "portuguese"),
        setOf("ko", "kor", "korean"),
        setOf("zh", "zho", "chi", "chinese"),
        setOf("ru", "rus", "russian"),
    )

    fun formatLanguage(code: String?): String {
        val raw = code?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
        if (raw.isEmpty()) return "Desconocido"
        ISO_DISPLAY_NAMES[raw]?.let { return it }
        val base = raw.substringBefore('-')
        ISO_DISPLAY_NAMES[base]?.let { return it }
        val locale = Locale.forLanguageTag(raw)
        val name = locale.getDisplayLanguage(Locale.forLanguageTag("es")).replaceFirstChar { it.uppercase() }
        return if (name.isNotBlank() && !name.equals(raw, ignoreCase = true)) name else raw.uppercase(Locale.ROOT)
    }

    fun matchesLanguage(preferred: String, candidate: String?): Boolean {
        val prefNorm = preferred.trim().lowercase(Locale.ROOT).replace('_', '-')
        val candNorm = candidate?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
        if (prefNorm.isEmpty() || candNorm.isEmpty()) return false
        if (prefNorm == candNorm || candNorm.startsWith("$prefNorm-")) return true
        val prefBase = prefNorm.substringBefore('-')
        val candBase = candNorm.substringBefore('-')
        if (prefBase == candBase) return true
        return LANGUAGE_GROUPS.any { group ->
            (prefNorm in group || prefBase in group) && (candNorm in group || candBase in group)
        }
    }

    fun formatChannels(channelCount: Int): String? = when {
        channelCount <= 0 -> null
        channelCount == 1 -> "Mono"
        channelCount == 2 -> "Estéreo"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        else -> "${channelCount}ch"
    }

    fun formatAudioCodec(mimeType: String?, codecs: String?): String? {
        val raw = (mimeType ?: codecs)?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "eac3-joc" in raw -> "Atmos"
            "ec-3" in raw || "eac3" in raw -> "EAC3"
            "ac-3" in raw || "ac3" in raw -> "AC3"
            "truehd" in raw || "true-hd" in raw -> "TrueHD"
            "dts" in raw || "dtshd" in raw -> "DTS"
            "opus" in raw -> "Opus"
            "flac" in raw -> "FLAC"
            "vorbis" in raw -> "Vorbis"
            "mp4a" in raw || "aac" in raw -> "AAC"
            "mpeg" in raw || "mp3" in raw -> "MP3"
            else -> codecs?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        }
    }

    fun formatSubtitleCodec(mimeType: String?, fileName: String? = null): String? {
        val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "ssa" in mime || "ass" in mime || ext == "ass" || ext == "ssa" -> "ASS/SSA"
            "subrip" in mime || "srt" in mime || ext == "srt" -> "SRT"
            "vtt" in mime || ext == "vtt" -> "VTT"
            "pgs" in mime || ext == "sup" -> "PGS"
            "vobsub" in mime || "dvdsub" in mime || ext == "sub" || ext == "idx" -> "VobSub"
            "ttml" in mime || ext == "ttml" -> "TTML"
            else -> null
        }
    }

    fun subtitleMimeTypeForFile(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            "ttml", "dfxp" -> "application/ttml+xml"
            else -> "application/x-subrip"
        }

    private val FILENAME_LANGUAGE_CODES = mapOf(
        "es" to "es",
        "spa" to "es",
        "spanish" to "es",
        "español" to "es",
        "espanol" to "es",
        "castellano" to "es",
        "latino" to "es",
        "es-es" to "es-es",
        "es-mx" to "es-mx",
        "lat" to "es-419",
        "es-419" to "es-419",
        "en" to "en",
        "eng" to "en",
        "english" to "en",
        "pt" to "pt",
        "por" to "pt",
        "portuguese" to "pt",
        "pt-br" to "pt-br",
        "fr" to "fr",
        "fre" to "fr",
        "fra" to "fr",
        "french" to "fr",
        "de" to "de",
        "ger" to "de",
        "deu" to "de",
        "german" to "de",
        "it" to "it",
        "ita" to "it",
        "italian" to "it",
        "ja" to "ja",
        "jpn" to "ja",
        "japanese" to "ja",
        "ko" to "ko",
        "kor" to "ko",
        "korean" to "ko",
        "zh" to "zh",
        "zho" to "zh",
        "chi" to "zh",
        "chinese" to "zh",
        "ru" to "ru",
        "rus" to "ru",
        "russian" to "ru",
        "ar" to "ar",
        "ara" to "ar",
        "hi" to "hi",
        "hin" to "hi",
        "pl" to "pl",
        "pol" to "pl",
        "nl" to "nl",
        "nld" to "nl",
        "dut" to "nl",
        "ca" to "ca",
        "cat" to "ca",
    )

    fun inferLanguageFromFilename(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.').lowercase(Locale.ROOT)
        val coarseTokens = stem
            .split('.', '[', ']', '(', ')', ' ')
            .filter { it.isNotBlank() }
        for (token in coarseTokens.asReversed()) {
            FILENAME_LANGUAGE_CODES[token.replace('_', '-')]?.let { return it }
        }
        val fineTokens = stem
            .split('.', '_', '-', '[', ']', '(', ')', ' ')
            .filter { it.isNotBlank() }
        for (token in fineTokens.asReversed()) {
            FILENAME_LANGUAGE_CODES[token]?.let { return it }
        }
        return null
    }
}
