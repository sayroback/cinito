package webtor.app

import org.cinito.online.metadata.MediaTitleParser
import webtor.core.TorrentFile
import java.util.Locale

sealed interface QuickStreamPlan {
    data class AutoPlay(val selectedIndices: Set<Int>) : QuickStreamPlan
    data object NeedsFileSelection : QuickStreamPlan
}

/**
 * Decides whether a newly resolved torrent can start streaming immediately (1 video or exact
 * episode match in a pack) or needs the user to pick a specific file/episode.
 */
fun resolveQuickStreamSelection(
    files: List<TorrentFile>,
    target: QuickStreamTarget?,
): QuickStreamPlan {
    val videos = files.filter { it.name.isVideoName() || it.path.isVideoName() }
    if (videos.isEmpty()) return QuickStreamPlan.NeedsFileSelection

    if (videos.size == 1) {
        val chosen = videos.first()
        return QuickStreamPlan.AutoPlay(selectVideoWithMatchingSubtitles(files, chosen))
    }

    val season = target?.season
    val episode = target?.episode
    if (season != null && episode != null) {
        val exactMatch = videos.filter { file ->
            val parsed = MediaTitleParser.parse(file.path.ifBlank { file.name })
            (parsed.season == season && parsed.episode == episode) ||
                (season == 1 && parsed.season == null && parsed.episode == episode)
        }
        if (exactMatch.size == 1) {
            return QuickStreamPlan.AutoPlay(selectVideoWithMatchingSubtitles(files, exactMatch.first()))
        }
    }

    return QuickStreamPlan.NeedsFileSelection
}

/**
 * Finds the best matching video file index for a requested [season] / [episode] inside
 * an existing library [DownloadEntry]. Returns null when ambiguous or not found.
 */
fun findMatchingEpisodeFileIndex(
    entry: DownloadEntry,
    season: Int?,
    episode: Int?,
): Int? {
    val playableVideos = entry.files.filter { file ->
        (file.name.isVideoName() || file.path.isVideoName()) &&
            (file.index in entry.selected || (!entry.directPlay && file.isVerifiedComplete)) &&
            (entry.directPlay || !file.uri.isNullOrBlank())
    }
    if (playableVideos.isEmpty()) return null
    if (season != null && episode != null) {
        val matched = playableVideos.filter { file ->
            val parsed = MediaTitleParser.parse(file.path.ifBlank { file.name })
            (parsed.season == season && parsed.episode == episode) ||
                (season == 1 && parsed.season == null && parsed.episode == episode)
        }
        return matched.singleOrNull()?.index
    }
    if (!entry.hasMultipleVideos() && playableVideos.size == 1) {
        return playableVideos.first().index
    }
    return null
}

private fun selectVideoWithMatchingSubtitles(
    files: List<TorrentFile>,
    video: TorrentFile,
): Set<Int> {
    val stem = video.path.ifBlank { video.name }.substringBeforeLast('.').lowercase(Locale.ROOT)
    val matchingSubs = files.filter { file ->
        (file.name.isSubtitleName() || file.path.isSubtitleName()) && run {
            val path = file.path.ifBlank { file.name }.lowercase(Locale.ROOT)
            path == stem || path.startsWith("$stem.") || path.startsWith("$stem/")
        }
    }
    return (listOf(video) + matchingSubs).map { it.index }.toSet()
}
