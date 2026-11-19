package org.cinito.online.metadata

import java.util.Locale

enum class AudioLanguageTag(
    val shortLabel: String,
    val guessLabel: String = shortLabel,
) {
    Latino("Audio Latino", "Posible Latino"),
    SpanishGeneric("Dual / Español", "Posible Español"),
    Castellano("Castellano", "Posible Castellano"),
    SubSpanish("Sub Español", "Sub Español"),
    Other("VO / Inglés", "Por nombre (VO)"),
}

data class LanguageClassification(
    val tag: AudioLanguageTag,
    val matchedEvidence: String? = null,
)

object MediaLanguageClassifier {
    private val LATINO_REGEX = Regex(
        """(?i)(?:^|[\s.\-_(\[/,|])(latino|audio[\s._-]*lat(?:ino)?|latam|dual[\s._-]*lat(?:ino)?|esp[\s._-]*lat(?:ino)?|espa[nñ]ol[\s._-]*latino|es-?419|es-?mx|latin[\s._-]*spanish|lat)(?:$|[\s.\-_)\]/,|])"""
    )
    private val CASTELLANO_REGEX = Regex(
        """(?i)(?:^|[\s.\-_(\[/,|])(castellano|(?:audio[\s._-]*cast|[(\[]\s*cast\s*[)\]])|espa[nñ]ol[\s._-]*espa[nñ]a|esp[\s._-]*es|es-?es|spanish[\s._-]*spain)(?:$|[\s.\-_)\]/,|])"""
    )
    private val SUB_SPANISH_REGEX = Regex(
        """(?i)(?:^|[\s.\-_(\[/,|])(vose|sub(?:s|titles?|t[ií]tulos?|titulado)?[\s._:-]*(?:esp(?:a[nñ]ol)?|espanol|spanish|es|lat(?:ino)?)|espa[nñ]ol[\s._-]*sub(?:titulado)?)(?:$|[\s.\-_)\]/,|])"""
    )
    private val SPANISH_GENERIC_REGEX = Regex(
        """(?i)(?:^|[\s.\-_(\[/,|])(espa[nñ]ol|espanol|spanish|esp|spa|dual[\s._-]*esp)(?:$|[\s.\-_)\]/,|])"""
    )
    private val SUB_CONTEXT_BEFORE = Regex(
        """(?i)(?:^|[\s.\-_(\[/,|])(?:vose|included\s+subtitles|subbed|sub(?:s|titles?|t[ií]tulos?|titulado)?)[\s._:=,\-/()a-z0-9]{0,80}$"""
    )
    private val SUBTITLE_SECTION_HEADER = Regex(
        """(?i)^\s*(?:--+\s*)?(?:included\s+)?(?:subtitles?|subt[ií]tulos?|subs|text(?:\s*(?:language|#\d+))?)\s*[:=](.*)$"""
    )
    private val NON_SUBTITLE_SECTION_HEADER = Regex(
        """(?i)^\s*(?:--+\s*)?(?:audio(?:\s*\d+)?|video|general|cast|reparto|sinopsis|synopsis|plot|fuente|source|language|idioma)\b\s*(?:[:=]|$)"""
    )
    private val INLINE_SUB_SEGMENT = Regex(
        """(?i)(?:^|[\s|·,;(\[])(?:included\s+)?(?:subtitles?|subt[ií]tulos?|subs?)\s*[:=]\s*.*?(?=\s*(?:\||·|--|\b(?:audio|idioma|language)\s*[:=])|$)"""
    )
    private val AUDIO_WORDS_ON_LINE = Regex("""(?i)(?:^|[\s.\-_(\[/,|])(audio|dual|dub|dubbed|doblaje|latino|castellano)(?:$|[\s.\-_)\]/,|])""")
    private val FLAG_ONLY_STRING = Regex("""^[\s/|·🇲🇽🇪🇸]+$""")
    private val SUBTITLE_ONLY_LINE_CUES = Regex(
        """(?i)(?:^|[\s.\-_(\[/,|])(vose|subbed|subtitulad[oa]|hc[\s._-]*sub|hardcoded[\s._-]*sub|sub[\s._-]*(?:esp|lat|spa|only))(?:$|[\s.\-_)\]/,|])"""
    )

    private data class SplitText(
        val audioFocused: String,
        val subtitleFocused: String,
    )

    private fun separateAudioAndSubtitleSections(text: String): SplitText {
        if (text.isBlank()) return SplitText("", "")
        val audioLines = mutableListOf<String>()
        val subtitleLines = mutableListOf<String>()
        var inSubtitleSection = false

        for (rawLine in text.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                inSubtitleSection = false
                audioLines += ""
                continue
            }
            val subHeaderMatch = SUBTITLE_SECTION_HEADER.matchEntire(rawLine)
            if (subHeaderMatch != null) {
                inSubtitleSection = true
                val remainder = subHeaderMatch.groupValues[1].trim()
                if (remainder.isNotEmpty()) subtitleLines += remainder
                continue
            }
            if (inSubtitleSection) {
                if (NON_SUBTITLE_SECTION_HEADER.containsMatchIn(rawLine)) {
                    inSubtitleSection = false
                } else {
                    subtitleLines += rawLine
                    continue
                }
            }
            var cleanedLine = rawLine
            val inlineMatches = INLINE_SUB_SEGMENT.findAll(rawLine).toList()
            if (inlineMatches.isNotEmpty()) {
                for (m in inlineMatches) {
                    subtitleLines += m.value
                }
                cleanedLine = INLINE_SUB_SEGMENT.replace(rawLine, " ")
            }
            audioLines += cleanedLine
        }
        return SplitText(
            audioFocused = audioLines.joinToString("\n"),
            subtitleFocused = subtitleLines.joinToString("\n"),
        )
    }

    private fun isSubtitleOnlyMatch(text: String, matchRange: IntRange): Boolean {
        val searchFrom = (matchRange.first - 1).coerceAtLeast(0)
        val lineStart = if (matchRange.first == 0) {
            0
        } else {
            text.lastIndexOf('\n', searchFrom).let { if (it < 0) 0 else it + 1 }.coerceAtMost(matchRange.first)
        }
        val lineEnd = text.indexOf('\n', matchRange.last).let { if (it < 0) text.length else it }
        val linePrefix = text.substring(lineStart, matchRange.first)
        val fullLine = text.substring(lineStart, lineEnd)
        if (!SUB_CONTEXT_BEFORE.containsMatchIn(linePrefix)) {
            return SUBTITLE_ONLY_LINE_CUES.containsMatchIn(fullLine) &&
                !AUDIO_WORDS_ON_LINE.containsMatchIn(fullLine)
        }
        // e.g. "Audio: English | Sub: Esp" on one line: ensure the segment right before the match is the subtitle part
        return if (AUDIO_WORDS_ON_LINE.containsMatchIn(linePrefix)) {
            val lastAudio = AUDIO_WORDS_ON_LINE.findAll(fullLine.substring(0, matchRange.first - lineStart)).lastOrNull()
            val tail = if (lastAudio != null) linePrefix.substring(lastAudio.range.last + 1) else linePrefix
            SUB_CONTEXT_BEFORE.containsMatchIn(tail)
        } else {
            true
        }
    }

    private fun findFlagAudioMatch(
        text: String,
        flag: String,
        titleIsSubOnly: Boolean,
    ): IntRange? {
        var idx = text.indexOf(flag)
        while (idx >= 0) {
            val range = idx until (idx + flag.length)
            val lineStart = text.lastIndexOf('\n', range.first).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', range.last).let { if (it < 0) text.length else it }
            val fullLine = text.substring(lineStart, lineEnd)
            val blockedByTitleSubCue = titleIsSubOnly && !AUDIO_WORDS_ON_LINE.containsMatchIn(fullLine)
            if (!blockedByTitleSubCue && !isSubtitleOnlyMatch(text, range)) return range
            idx = text.indexOf(flag, idx + flag.length)
        }
        return null
    }

    fun classify(
        title: String,
        description: String? = null,
        extraPaths: List<String> = emptyList(),
        providerLanguageField: String? = null,
        subtitleLanguageField: String? = null,
    ): LanguageClassification {
        val descSplit = separateAudioAndSubtitleSections(description.orEmpty())
        val subtitleOnlyTitleFlags = SUBTITLE_ONLY_LINE_CUES.containsMatchIn(title) &&
            !AUDIO_WORDS_ON_LINE.containsMatchIn(title)
        val effectiveAudioProviderField = providerLanguageField?.takeUnless {
            subtitleOnlyTitleFlags && FLAG_ONLY_STRING.matches(it)
        }

        val audioCombined = buildString {
            append(title)
            if (descSplit.audioFocused.isNotBlank()) {
                append('\n').append(descSplit.audioFocused)
            }
            if (extraPaths.isNotEmpty()) {
                append('\n').append(extraPaths.joinToString(" "))
            }
            if (!effectiveAudioProviderField.isNullOrBlank()) {
                append('\n').append(effectiveAudioProviderField)
            }
        }
        val subtitleCombined = buildString {
            append(descSplit.subtitleFocused)
            if (!subtitleLanguageField.isNullOrBlank()) {
                if (isNotEmpty()) append('\n')
                append(subtitleLanguageField)
            }
            if (subtitleOnlyTitleFlags && !providerLanguageField.isNullOrBlank() && FLAG_ONLY_STRING.matches(providerLanguageField)) {
                if (isNotEmpty()) append('\n')
                append(providerLanguageField)
            }
        }
        val fullCombined = buildString {
            append(audioCombined)
            if (subtitleCombined.isNotBlank()) append('\n').append(subtitleCombined)
        }

        // Check dubbed audio signals first (excluding matches in subtitle contexts/sections)
        val latinoMatch = LATINO_REGEX.findAll(audioCombined).firstOrNull {
            !isSubtitleOnlyMatch(audioCombined, it.groups[1]?.range ?: it.range)
        }
        if (latinoMatch != null) {
            return LanguageClassification(AudioLanguageTag.Latino, latinoMatch.groupValues[1].trim())
        }
        if (findFlagAudioMatch(audioCombined, "🇲🇽", subtitleOnlyTitleFlags) != null) {
            return LanguageClassification(AudioLanguageTag.Latino, "🇲🇽")
        }

        val castellanoMatch = CASTELLANO_REGEX.findAll(audioCombined).firstOrNull {
            !isSubtitleOnlyMatch(audioCombined, it.groups[1]?.range ?: it.range)
        }
        if (castellanoMatch != null) {
            return LanguageClassification(AudioLanguageTag.Castellano, castellanoMatch.groupValues[1].trim())
        }
        if (findFlagAudioMatch(audioCombined, "🇪🇸", subtitleOnlyTitleFlags) != null) {
            return LanguageClassification(AudioLanguageTag.Castellano, "🇪🇸")
        }

        val genericMatch = SPANISH_GENERIC_REGEX.findAll(audioCombined).firstOrNull {
            !isSubtitleOnlyMatch(audioCombined, it.groups[1]?.range ?: it.range)
        }
        if (genericMatch != null) {
            return LanguageClassification(AudioLanguageTag.SpanishGeneric, genericMatch.groupValues[1].trim())
        }

        if (!effectiveAudioProviderField.isNullOrBlank()) {
            val lang = effectiveAudioProviderField.lowercase(Locale.ROOT)
            if (lang.contains("spanish") || lang == "es" || lang == "spa") {
                return LanguageClassification(AudioLanguageTag.SpanishGeneric, effectiveAudioProviderField.trim())
            }
        }

        val subMatch = SUB_SPANISH_REGEX.find(fullCombined)
        if (subMatch != null) {
            return LanguageClassification(AudioLanguageTag.SubSpanish, subMatch.groupValues[1].trim())
        }
        val subFallback = LATINO_REGEX.find(fullCombined)
            ?: CASTELLANO_REGEX.find(fullCombined)
            ?: SPANISH_GENERIC_REGEX.find(fullCombined)
        if (subFallback != null) {
            return LanguageClassification(AudioLanguageTag.SubSpanish, subFallback.groupValues[1].trim())
        }
        if ("🇲🇽" in subtitleCombined) return LanguageClassification(AudioLanguageTag.SubSpanish, "Sub 🇲🇽")
        if ("🇪🇸" in subtitleCombined) return LanguageClassification(AudioLanguageTag.SubSpanish, "Sub 🇪🇸")
        if ("🇲🇽" in fullCombined) return LanguageClassification(AudioLanguageTag.SubSpanish, "Sub 🇲🇽")
        if ("🇪🇸" in fullCombined) return LanguageClassification(AudioLanguageTag.SubSpanish, "Sub 🇪🇸")

        return LanguageClassification(AudioLanguageTag.Other, null)
    }

    fun classifyTrackMetadata(
        languageCode: String?,
        bcp47: String?,
        trackName: String?,
    ): AudioLanguageTag {
        val nameLower = trackName?.lowercase(Locale.ROOT).orEmpty()
        val bcpLower = bcp47?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()
        val langLower = languageCode?.lowercase(Locale.ROOT)?.replace('_', '-').orEmpty()

        return when {
            bcpLower in setOf("es-419", "es-mx", "es-ar", "es-co", "es-cl", "es-pe", "es-ve") -> AudioLanguageTag.Latino
            LATINO_REGEX.containsMatchIn(nameLower) || "latam" in nameLower || "latin" in nameLower || "mexico" in nameLower || "méxico" in nameLower -> AudioLanguageTag.Latino
            bcpLower == "es-es" || CASTELLANO_REGEX.containsMatchIn(nameLower) || "spain" in nameLower || "españa" in nameLower -> AudioLanguageTag.Castellano
            langLower in setOf("es", "spa") || bcpLower.startsWith("es") || SPANISH_GENERIC_REGEX.containsMatchIn(nameLower) -> AudioLanguageTag.SpanishGeneric
            else -> AudioLanguageTag.Other
        }
    }
}
