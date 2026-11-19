package org.cinito.online.ui.library

import org.cinito.online.metadata.MediaEpisode
import org.cinito.online.metadata.MediaKind
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.metadata.ParsedMediaTitle
import webtor.app.DownloadEntry
import webtor.app.SavedFile
import webtor.app.isSubtitleName
import webtor.app.isVideoName
import webtor.app.liveDownloadSpeed
import webtor.app.liveStatus
import webtor.app.liveUploadSpeed
import webtor.app.resumeFile
import webtor.app.selectedEta

data class EpisodeUiItem(
    val entryKey: String,
    val fileIndex: Int,
    val displayTitle: String,
    val rawFileName: String,
    val season: Int?,
    val episode: Int?,
    val overview: String?,
    val thumbnailUrl: String?,
    val progress: Float,
    val playbackPositionMs: Long,
    val playbackDurationMs: Long,
    val sizeBytes: Long,
    val isVerifiedComplete: Boolean,
    val canPlay: Boolean,
    val file: SavedFile,
) {
    val isWatched: Boolean
        get() = playbackDurationMs > 0L && playbackPositionMs >= (playbackDurationMs * 0.90f).toLong()
}

data class SwarmHealth(
    val statusLabel: String,
    val downloadSpeedBps: Long,
    val uploadSpeedBps: Long,
    val peersConnected: Int,
    val etaMs: Long?,
    val isActive: Boolean,
    val isCompleted: Boolean,
    val isError: Boolean,
    val isDirectPlay: Boolean,
)

data class LibraryMediaCardModel(
    val entry: DownloadEntry,
    val parsedTitle: ParsedMediaTitle,
    val metadata: MediaMetadata?,
    val displayTitle: String,
    val year: Int?,
    val kind: MediaKind,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val rating: String?,
    val genres: List<String>,
    val episodesBySeason: Map<Int, List<EpisodeUiItem>>,
    val flatVideos: List<EpisodeUiItem>,
    val subtitleFiles: List<SavedFile>,
    val primaryPlayFile: SavedFile?,
    val swarm: SwarmHealth,
) {
    val isSeriesLayout: Boolean
        get() = kind == MediaKind.SERIES || kind == MediaKind.ANIME || flatVideos.size > 1

    val primaryEpisode: EpisodeUiItem?
        get() = primaryPlayFile?.let { target -> flatVideos.find { it.fileIndex == target.index } }
}

object LibraryGalleryBuilder {
    fun buildCardModel(entry: DownloadEntry, metadata: MediaMetadata?): LibraryMediaCardModel {
        val parsed = MediaTitleParser.parse(entry.title)
        val metaMap = metadata?.episodes.orEmpty()
            .associateBy { it.season to it.episode }

        val videoFiles = entry.files.filter { it.name.isVideoName() || it.path.isVideoName() }
        val subtitleFiles = entry.files.filter { it.name.isSubtitleName() || it.path.isSubtitleName() }

        val episodeItems = videoFiles.mapIndexed { idx, file ->
            val fileParsed = MediaTitleParser.parse(
                file.path.ifBlank { file.name },
                isAnimeHint = parsed.kind == MediaKind.ANIME,
            )
            val season = fileParsed.season ?: if (videoFiles.size > 1) 1 else null
            val episodeNum = fileParsed.episode ?: if (videoFiles.size > 1) idx + 1 else null
            val guideMeta: MediaEpisode? = if (season != null && episodeNum != null) {
                metaMap[season to episodeNum]
            } else {
                null
            }
            val playable = !entry.controlsBusy() && entry.metadataReady &&
                (file.index in entry.selected || (!entry.directPlay && file.isVerifiedComplete)) &&
                (entry.directPlay || !file.uri.isNullOrBlank())

            val parsedEpisodeTitle = MediaTitleParser.extractEpisodeTitle(file.name)
            EpisodeUiItem(
                entryKey = entry.key,
                fileIndex = file.index,
                displayTitle = guideMeta?.title?.takeIf { it.isNotBlank() }
                    ?: parsedEpisodeTitle?.takeIf { it.isNotBlank() }
                    ?: fileParsed.episodeBadge
                    ?: file.name,
                rawFileName = file.name,
                season = season,
                episode = episodeNum,
                overview = guideMeta?.overview,
                thumbnailUrl = guideMeta?.thumbnailUrl,
                progress = file.progress.toFloat().coerceIn(0f, 1f),
                playbackPositionMs = file.playbackPositionMs,
                playbackDurationMs = file.playbackDurationMs,
                sizeBytes = file.length,
                isVerifiedComplete = file.isVerifiedComplete,
                canPlay = playable,
                file = file,
            )
        }.sortedWith(
            compareBy<EpisodeUiItem> {
                val s = it.season
                when {
                    s == null -> Int.MAX_VALUE
                    s <= 0 -> Int.MAX_VALUE - 1
                    else -> s
                }
            }.thenBy { it.episode ?: it.fileIndex }.thenBy { it.fileIndex },
        )

        val groupedSeasons = if (episodeItems.any { it.season != null }) {
            episodeItems.groupByTo(
                sortedMapOf(compareBy<Int> { if (it <= 0) Int.MAX_VALUE else it }.thenBy { it }),
            ) { it.season ?: 1 }
        } else {
            emptyMap()
        }

        val liveStatus = entry.liveStatus()
        val dlSpeed = entry.liveDownloadSpeed() ?: 0L
        val ulSpeed = entry.liveUploadSpeed() ?: 0L
        val peers = liveStatus?.numPeers?.coerceAtLeast(0) ?: 0
        val swarm = SwarmHealth(
            statusLabel = entry.stateLabel(),
            downloadSpeedBps = dlSpeed,
            uploadSpeedBps = ulSpeed,
            peersConnected = peers,
            etaMs = entry.selectedEta(),
            isActive = dlSpeed > 0L || (entry.engineId != null && !entry.paused && !entry.complete),
            isCompleted = entry.complete,
            isError = entry.error != null || liveStatus?.error != null,
            isDirectPlay = entry.directPlay,
        )

        val primaryPlay = entry.resumeFile()
            ?: episodeItems.firstOrNull { it.canPlay }?.file
            ?: videoFiles.firstOrNull()

        val resolvedKind = when {
            parsed.kind == MediaKind.ANIME -> MediaKind.ANIME
            metadata?.type == "series" || groupedSeasons.isNotEmpty() || episodeItems.size > 1 -> MediaKind.SERIES
            else -> parsed.kind
        }

        return LibraryMediaCardModel(
            entry = entry,
            parsedTitle = parsed,
            metadata = metadata,
            displayTitle = metadata?.title?.takeIf { it.isNotBlank() } ?: parsed.cleanTitle.ifBlank { entry.title },
            year = metadata?.year ?: parsed.year,
            kind = resolvedKind,
            posterUrl = metadata?.posterUrl,
            backdropUrl = metadata?.backdropUrl,
            description = metadata?.description,
            rating = metadata?.rating,
            genres = metadata?.genres.orEmpty(),
            episodesBySeason = groupedSeasons,
            flatVideos = episodeItems,
            subtitleFiles = subtitleFiles,
            primaryPlayFile = primaryPlay,
            swarm = swarm,
        )
    }
}
