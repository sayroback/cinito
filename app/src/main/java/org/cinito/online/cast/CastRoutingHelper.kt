package org.cinito.online.cast

import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.settings.SubtitleStyleConfig
import webtor.app.DownloadEntry
import webtor.app.PlaybackItem
import java.util.Locale

/**
 * Pure helpers for resolving container audio tracks and constructing [CastMediaSource] objects
 * cleanly without duplicating language-matching logic across playback entry points.
 */
object CastRoutingHelper {

    private val STREAM_FILE_INDEX_REGEX = Regex("""/stream/[^/]+/(\d+)(?:\?.*)?$""")

    private val ISO_GROUPS: Map<String, Set<String>> = mapOf(
        "es" to setOf("es", "spa"),
        "en" to setOf("en", "eng"),
        "ja" to setOf("ja", "jpn"),
        "fr" to setOf("fr", "fra", "fre"),
        "de" to setOf("de", "deu", "ger"),
        "it" to setOf("it", "ita"),
        "pt" to setOf("pt", "por"),
        "ko" to setOf("ko", "kor"),
        "zh" to setOf("zh", "zho", "chi"),
        "ru" to setOf("ru", "rus"),
        "hi" to setOf("hi", "hin"),
    )

    fun extractFileIndexFromStreamUri(sourceUri: String): Int? =
        STREAM_FILE_INDEX_REGEX.find(sourceUri)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun buildCastAudioTracks(probe: ContainerProbeResult?): List<CastAudioTrack> {
        if (probe == null) return emptyList()
        return probe.audioTracks.mapIndexed { idx, track ->
            CastAudioTrack(
                index = idx,
                name = track.displayLabel,
                language = track.bcp47 ?: track.languageCode,
                codec = track.codec,
                regionTag = track.regionTag,
            )
        }
    }

    fun pickPreferredAudioIndex(
        tracks: List<CastAudioTrack>,
        preferredLanguage: String?,
        prioritizeLatino: Boolean = true,
    ): Int? {
        if (tracks.isEmpty()) return null
        val normalizedPref = normalizeLangPrefix(preferredLanguage) ?: return null
        if (normalizedPref == "es") {
            val priorityOrder = if (prioritizeLatino) {
                listOf(AudioLanguageTag.Latino, AudioLanguageTag.SpanishGeneric, AudioLanguageTag.Castellano)
            } else {
                listOf(AudioLanguageTag.Castellano, AudioLanguageTag.SpanishGeneric, AudioLanguageTag.Latino)
            }
            for (tag in priorityOrder) {
                tracks.firstOrNull { it.regionTag == tag }?.let { return it.index }
            }
        }
        val acceptedCodes = ISO_GROUPS[normalizedPref] ?: setOf(normalizedPref)
        return tracks.firstOrNull { track ->
            val trackCode = normalizeLangPrefix(track.language)
            trackCode != null && trackCode in acceptedCodes
        }?.index
    }

    fun resolveAudioTracks(
        probeRepo: AudioTrackProbeRepository,
        entry: DownloadEntry?,
        sourceUri: String,
        infoHashHint: String? = null,
        fileIndexHint: Int? = null,
    ): List<CastAudioTrack> {
        val probe = resolveProbe(
            probes = probeRepo.probes.value,
            entry = entry,
            sourceUri = sourceUri,
            infoHashHint = infoHashHint,
            fileIndexHint = fileIndexHint,
        )
        return buildCastAudioTracks(probe)
    }

    fun resolveEmbeddedSubtitleLabels(
        probeRepo: AudioTrackProbeRepository,
        entry: DownloadEntry?,
        sourceUri: String,
        infoHashHint: String? = null,
        fileIndexHint: Int? = null,
    ): List<String> {
        val probe = resolveProbe(
            probes = probeRepo.probes.value,
            entry = entry,
            sourceUri = sourceUri,
            infoHashHint = infoHashHint,
            fileIndexHint = fileIndexHint,
        ) ?: return emptyList()
        return probe.subtitleTracks.mapIndexed { idx, track ->
            track.displayLabel.takeIf { it.isNotBlank() } ?: "Subtítulo ${idx + 1}"
        }
    }

    fun resolveEmbeddedSubtitleLabels(
        probeRepo: AudioTrackProbeRepository,
        hash: String?,
        fileIndex: Int?,
        fileName: String = "",
    ): List<String> {
        val probe = AudioTrackProbeRepository.lookupFromMap(
            map = probeRepo.probes.value,
            entryKey = null,
            infoHash = hash,
            fileIndex = fileIndex,
        ) ?: return emptyList()
        return probe.subtitleTracks.mapIndexed { idx, track ->
            track.displayLabel.takeIf { it.isNotBlank() }
                ?: fileName.takeIf { false }
                ?: "Subtítulo ${idx + 1}"
        }
    }

    fun resolveEmbeddedSubtitles(
        probeRepo: AudioTrackProbeRepository,
        entry: DownloadEntry?,
        sourceUri: String,
        infoHashHint: String? = null,
        fileIndexHint: Int? = null,
    ): List<EmbeddedSubtitleSpec> {
        val probe = resolveProbe(
            probes = probeRepo.probes.value,
            entry = entry,
            sourceUri = sourceUri,
            infoHashHint = infoHashHint,
            fileIndexHint = fileIndexHint,
        ) ?: return emptyList()
        return probe.subtitleTracks.mapIndexed { idx, track ->
            EmbeddedSubtitleSpec(
                trackNumber = track.trackNumber ?: (idx + 1),
                label = track.displayLabel.takeIf { it.isNotBlank() } ?: "Subtítulo ${idx + 1}",
                language = track.bcp47 ?: track.languageCode,
                rawCodecId = track.rawCodecId,
                isTextBased = track.isTextSubtitle,
            )
        }
    }

    fun resolveProbe(
        probes: Map<String, ContainerProbeResult>,
        entry: DownloadEntry?,
        sourceUri: String,
        infoHashHint: String? = null,
        fileIndexHint: Int? = null,
    ): ContainerProbeResult? {
        val resolvedHash = infoHashHint?.takeIf { it.isNotBlank() }
            ?: entry?.let { AudioTrackProbeRepository.extractEntryInfoHash(it) }
        val resolvedFileIdx = fileIndexHint
            ?: entry?.files?.firstOrNull { it.uri == sourceUri }?.index
            ?: extractFileIndexFromStreamUri(sourceUri)
        return AudioTrackProbeRepository.lookupFromMap(
            map = probes,
            entryKey = entry?.key,
            infoHash = resolvedHash,
            fileIndex = resolvedFileIdx,
        )
    }

    fun buildMediaSource(
        title: String,
        sourceUri: String,
        mimeType: String,
        positionMs: Long,
        durationMs: Long,
        subtitles: List<PlaybackItem>,
        audioTracks: List<CastAudioTrack>,
        preferredAudioLanguage: String?,
        prioritizeLatino: Boolean,
        explicitAudioIndex: Int? = null,
        playlist: List<PlaybackItem> = emptyList(),
        currentPlaylistIndex: Int = 0,
        preferredWebOsMode: WebOsCastMode = WebOsCastMode.Auto,
        subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
        initialSubtitleIndex: Int? = null,
        preserveInitialSubtitle: Boolean = false,
        embeddedSubtitleLabels: List<String> = emptyList(),
        leaseKey: String? = null,
        bookmarkUri: String = sourceUri,
        embeddedSubtitles: List<EmbeddedSubtitleSpec> = emptyList(),
        isSourceFileComplete: Boolean = false,
    ): CastMediaSource {
        val resolvedAudioIndex = explicitAudioIndex?.takeIf { idx -> audioTracks.any { it.index == idx } }
            ?: pickPreferredAudioIndex(
                tracks = audioTracks,
                preferredLanguage = preferredAudioLanguage,
                prioritizeLatino = prioritizeLatino,
            )
        return CastMediaSource(
            title = title,
            sourceUri = sourceUri,
            mimeType = mimeType,
            positionMs = positionMs,
            durationMs = durationMs,
            subtitles = subtitles,
            audioTracks = audioTracks,
            initialAudioIndex = resolvedAudioIndex,
            leaseKey = leaseKey,
            bookmarkUri = bookmarkUri,
            playlist = playlist,
            currentPlaylistIndex = currentPlaylistIndex,
            preferredWebOsMode = preferredWebOsMode,
            subtitleStyle = subtitleStyle,
            initialSubtitleIndex = initialSubtitleIndex,
            preserveInitialSubtitle = preserveInitialSubtitle,
            embeddedSubtitleLabels = embeddedSubtitleLabels,
            embeddedSubtitles = embeddedSubtitles,
            isSourceFileComplete = isSourceFileComplete,
        )
    }

    private fun normalizeLangPrefix(raw: String?): String? {
        val token = raw?.trim()?.lowercase(Locale.ROOT)
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return ISO_GROUPS.entries.firstOrNull { token in it.value }?.key ?: token
    }
}
