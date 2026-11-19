package org.cinito.online.ui.explore

import org.cinito.online.metadata.CatalogFeed
import org.cinito.online.metadata.CatalogGenre
import org.cinito.online.metadata.CatalogGenreRegistry
import org.cinito.online.metadata.MediaEpisode
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.search.PlayabilityAssessment
import org.cinito.online.search.PlayabilityVerdict
import org.cinito.online.search.ProviderFailure
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchLanguageFilter
import org.cinito.online.search.SearchService
import org.cinito.online.search.SearchSortOrder
import org.cinito.online.search.SearchTitleResolution
import org.cinito.online.search.SwarmProbeResult
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.search.TrackerSwarmStats
import webtor.app.DownloadEntry
import java.util.Locale

enum class DiscoverCategoryTab(val label: String) {
    All("Todo"),
    Movies("Películas"),
    Series("Series"),
}

data class CatalogSourceQuery(
    val query: String,
    val alternateQuery: String?,
    val imdbId: String?,
    val category: SearchCategory,
    val season: Int?,
    val episode: Int?,
)

data class EpisodeSourcesSnapshot(
    val rawResults: List<TorrentSearchResult> = emptyList(),
    val queriedFilters: Set<SearchLanguageFilter> = emptySet(),
    val completedProviders: Int = 0,
    val totalProviders: Int = 0,
    val failures: List<ProviderFailure> = emptyList(),
    val swarmStatsByHash: Map<String, TrackerSwarmStats> = emptyMap(),
    val probeResultsByHash: Map<String, SwarmProbeResult> = emptyMap(),
    val isFinished: Boolean = false,
)

data class CatalogDetailState(
    val cacheKey: String,
    val media: MediaMetadata,
    val isLoadingDetails: Boolean = false,
    val selectedSeason: Int = 1,
    val selectedEpisode: Int? = null,
    val languageFilter: SearchLanguageFilter = SearchLanguageFilter.PrioritizeLatino,
    val sortOrder: SearchSortOrder = SearchSortOrder.BestPlayability,
    val rawResults: List<TorrentSearchResult> = emptyList(),
    val queriedFilters: Set<SearchLanguageFilter> = emptySet(),
    val completedProviders: Int = 0,
    val totalProviders: Int = 0,
    val failures: List<ProviderFailure> = emptyList(),
    val isSearching: Boolean = false,
    val isScrapingSwarm: Boolean = false,
    val swarmStatsByHash: Map<String, TrackerSwarmStats> = emptyMap(),
    val probeResultsByHash: Map<String, SwarmProbeResult> = emptyMap(),
    val probingHashes: Set<String> = emptySet(),
    val enrichingIds: Set<String> = emptySet(),
    val showAllResults: Boolean = false,
    val episodeSnapshots: Map<String, EpisodeSourcesSnapshot> = emptyMap(),
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
) {
    fun episodeSnapshotKey(season: Int = selectedSeason, episode: Int? = selectedEpisode): String =
        "$season:${episode ?: -1}"
}

data class DiscoverUiState(
    val categoryTab: DiscoverCategoryTab = DiscoverCategoryTab.All,
    val selectedFeed: CatalogFeed = CatalogFeed.TrendingToday,
    val selectedGenre: String? = null,
    val catalogQuery: String = "",
    val submittedCatalogQuery: String = "",
    val isCatalogSearching: Boolean = false,
    val catalogSearchResults: List<MediaMetadata> = emptyList(),
    val movies: List<MediaMetadata> = emptyList(),
    val series: List<MediaMetadata> = emptyList(),
    val feedItems: List<MediaMetadata> = emptyList(),
    val highlightItems: List<MediaMetadata> = emptyList(),
    val newReleasesRail: List<MediaMetadata> = emptyList(),
    val airingSeriesRail: List<MediaMetadata> = emptyList(),
    val dailyGenreId: String? = null,
    val dailyGenreLabel: String? = null,
    val dailyGenreRail: List<MediaMetadata> = emptyList(),
    val topRatedRail: List<MediaMetadata> = emptyList(),
    val moviesCursor: String? = null,
    val seriesCursor: String? = null,
    val hasMoreMovies: Boolean = false,
    val hasMoreSeries: Boolean = false,
    val moviesTotalCount: Int? = null,
    val seriesTotalCount: Int? = null,
    val loadedDay: String? = null,
    val loadedAtMs: Long = 0L,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
    val gridScrollIndex: Int = 0,
    val gridScrollOffset: Int = 0,
    val activeDetail: CatalogDetailState? = null,
    val detailCache: Map<String, CatalogDetailState> = emptyMap(),
) {
    val hasMoreForActiveTab: Boolean
        get() = when (categoryTab) {
            DiscoverCategoryTab.All -> hasMoreMovies || hasMoreSeries
            DiscoverCategoryTab.Movies -> hasMoreMovies
            DiscoverCategoryTab.Series -> hasMoreSeries
        }

    val totalCountForActiveTab: Int?
        get() = when (categoryTab) {
            DiscoverCategoryTab.All -> listOfNotNull(moviesTotalCount, seriesTotalCount).sum().takeIf { it > 0 }
            DiscoverCategoryTab.Movies -> moviesTotalCount
            DiscoverCategoryTab.Series -> seriesTotalCount
        }
}

data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val activeAlternateQuery: String? = null,
    val activeImdbId: String? = null,
    val activeSeasonHint: Int? = null,
    val activeEpisodeHint: Int? = null,
    val category: SearchCategory = SearchCategory.All,
    val sortOrder: SearchSortOrder = SearchSortOrder.BestPlayability,
    val languageFilter: SearchLanguageFilter = SearchLanguageFilter.PrioritizeLatino,
    val rawResults: List<TorrentSearchResult> = emptyList(),
    val queriedScopes: Set<String> = emptySet(),
    val resolvedContext: SearchTitleResolution? = null,
    val completedProviders: Int = 0,
    val totalProviders: Int = 0,
    val failures: List<ProviderFailure> = emptyList(),
    val isSearching: Boolean = false,
    val isScrapingSwarm: Boolean = false,
    val swarmStatsByHash: Map<String, TrackerSwarmStats> = emptyMap(),
    val probeResultsByHash: Map<String, SwarmProbeResult> = emptyMap(),
    val probingHashes: Set<String> = emptySet(),
    val enrichingIds: Set<String> = emptySet(),
    val originCatalogTitle: String? = null,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
) {
    fun scopeKey(lang: SearchLanguageFilter = languageFilter): String =
        listOf(
            submittedQuery.trim().lowercase(Locale.ROOT),
            activeAlternateQuery?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            activeImdbId.orEmpty(),
            category.name,
            lang.name,
            activeSeasonHint?.toString().orEmpty(),
            activeEpisodeHint?.toString().orEmpty(),
        ).joinToString("|")
}

fun formatSeasonEpisodeCode(season: Int, episode: Int?): String = if (episode != null) {
    "S%02dE%02d".format(Locale.US, season.coerceAtLeast(0), episode.coerceAtLeast(1))
} else {
    "S%02d".format(Locale.US, season.coerceAtLeast(0))
}

/** Sorts regular seasons (>= 1) ascending and places Specials (season <= 0) at the end. */
fun sortSeasonNumbers(seasons: Iterable<Int>): List<Int> =
    seasons.distinct().sortedWith(compareBy<Int> { if (it <= 0) Int.MAX_VALUE else it }.thenBy { it })

fun orderedSeriesSeasons(episodes: List<MediaEpisode>): List<Int> =
    sortSeasonNumbers(episodes.map { it.season })

fun formatSeasonChipLabel(season: Int, episodeCount: Int? = null): String {
    val base = if (season <= 0) "Especiales" else "Temporada $season"
    return if (episodeCount != null && episodeCount > 0) "$base · $episodeCount" else base
}

private val ISO_DATE_PREFIX_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")

fun isEpisodeFutureRelease(released: String?, todayIso: String): Boolean {
    val day = released?.trim()?.take(10) ?: return false
    if (!ISO_DATE_PREFIX_REGEX.matches(day) || !ISO_DATE_PREFIX_REGEX.matches(todayIso)) return false
    return day > todayIso
}

fun preferredEpisodeInSeason(
    episodes: List<MediaEpisode>,
    season: Int,
    todayIso: String,
): MediaEpisode? {
    val inSeason = episodes.filter { it.season == season }.sortedBy { it.episode }
    if (inSeason.isEmpty()) return null
    return inSeason.firstOrNull { !isEpisodeFutureRelease(it.released, todayIso) } ?: inSeason.first()
}

fun pickInitialSeriesTarget(
    episodes: List<MediaEpisode>,
    todayIso: String,
): Pair<Int, Int?> {
    val seasons = orderedSeriesSeasons(episodes)
    val initialSeason = seasons.firstOrNull() ?: 1
    val initialEp = preferredEpisodeInSeason(episodes, initialSeason, todayIso)?.episode
    return initialSeason to initialEp
}

fun buildCatalogSourceQuery(
    media: MediaMetadata,
    season: Int?,
    episode: Int?,
): CatalogSourceQuery {
    val isSeries = media.type == "series"
    val isAnime = media.genres.any { it.equals("Anime", ignoreCase = true) }
    val category = when {
        isAnime -> SearchCategory.Anime
        isSeries -> SearchCategory.Series
        else -> SearchCategory.Movies
    }
    val imdbId = media.id.takeIf { it.startsWith("tt") }
    val distinctOriginal = media.originalTitle
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(media.title, ignoreCase = true) }

    if (isSeries) {
        val effSeason = (season ?: 1).coerceAtLeast(1)
        val suffix = if (episode != null) {
            formatSeasonEpisodeCode(effSeason, episode)
        } else if (season != null && media.episodes.isNotEmpty()) {
            formatSeasonEpisodeCode(effSeason, null)
        } else {
            null
        }
        val query = if (suffix != null) "${media.title} $suffix" else media.title
        val altQuery = distinctOriginal?.let { orig -> if (suffix != null) "$orig $suffix" else orig }
        return CatalogSourceQuery(
            query = query,
            alternateQuery = altQuery,
            imdbId = imdbId,
            category = category,
            season = if (episode != null) effSeason else season,
            episode = episode,
        )
    }

    val yearSuffix = media.year?.let { " $it" }.orEmpty()
    return CatalogSourceQuery(
        query = "${media.title}$yearSuffix",
        alternateQuery = distinctOriginal?.let { "$it$yearSuffix" },
        imdbId = imdbId,
        category = category,
        season = null,
        episode = null,
    )
}

fun extractAvailableGenres(
    movies: List<MediaMetadata>,
    series: List<MediaMetadata>,
    tab: DiscoverCategoryTab,
): List<String> {
    val source = when (tab) {
        DiscoverCategoryTab.All -> movies + series
        DiscoverCategoryTab.Movies -> movies
        DiscoverCategoryTab.Series -> series
    }
    val counts = LinkedHashMap<String, Int>()
    for (item in source) {
        for (genre in item.genres) {
            val clean = genre.trim()
            if (clean.isNotEmpty()) {
                counts[clean] = (counts[clean] ?: 0) + 1
            }
        }
    }
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
        .take(12)
}

fun filterCatalogItems(
    movies: List<MediaMetadata>,
    series: List<MediaMetadata>,
    tab: DiscoverCategoryTab,
    selectedGenre: String?,
): List<MediaMetadata> {
    val combined = when (tab) {
        DiscoverCategoryTab.All -> interleaveCatalog(movies, series)
        DiscoverCategoryTab.Movies -> movies
        DiscoverCategoryTab.Series -> series
    }
    if (selectedGenre.isNullOrBlank()) return combined
    return combined.filter { item ->
        item.genres.any { it.equals(selectedGenre, ignoreCase = true) }
    }
}

fun interleaveCatalog(
    movies: List<MediaMetadata>,
    series: List<MediaMetadata>,
): List<MediaMetadata> = buildList(movies.size + series.size) {
    val max = maxOf(movies.size, series.size)
    for (i in 0 until max) {
        if (i < movies.size) add(movies[i])
        if (i < series.size) add(series[i])
    }
}

fun appendCatalogDeduplicating(
    existing: List<MediaMetadata>,
    incoming: List<MediaMetadata>,
): Pair<List<MediaMetadata>, List<MediaMetadata>> {
    if (incoming.isEmpty()) return existing to emptyList()
    val seen = HashSet<String>(existing.size + incoming.size)
    for (item in existing) {
        seen += "${item.type}:${item.id}"
    }
    val added = ArrayList<MediaMetadata>(incoming.size)
    for (item in incoming) {
        if (seen.add("${item.type}:${item.id}")) {
            added += item
        }
    }
    return if (added.isEmpty()) existing to emptyList() else (existing + added) to added
}

fun dailyFeaturedGenre(dayIso: String): CatalogGenre {
    val genres = CatalogGenreRegistry.genres
    val daySeed = dayIso.fold(0) { acc, ch -> (acc * 31 + ch.code) and 0x7fffffff }
    return genres[daySeed % genres.size]
}

fun selectDailyHighlights(
    movies: List<MediaMetadata>,
    series: List<MediaMetadata>,
    dayIso: String,
    limit: Int = 6,
    newReleases: List<MediaMetadata> = emptyList(),
    topRated: List<MediaMetadata> = emptyList(),
): List<MediaMetadata> {
    val pool = buildList {
        addAll(interleaveCatalog(movies.take(14), series.take(12)))
        addAll(newReleases.take(10))
        addAll(topRated.take(8))
    }
        .filter { !it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank() }
        .distinctBy { "${it.type}:${it.id}" }
    if (pool.isEmpty()) return emptyList()
    val daySeed = dayIso.fold(17L) { acc, ch -> acc * 31L + ch.code.toLong() }
    val shuffled = pool.toMutableList()
    java.util.Collections.shuffle(shuffled, java.util.Random(daySeed))
    return shuffled.take(limit)
}

fun isRelevantCatalogResult(
    result: TorrentSearchResult,
    media: MediaMetadata,
): Boolean {
    if (!result.imdbId.isNullOrBlank() && media.id.startsWith("tt", ignoreCase = true)) {
        if (result.imdbId.equals(media.id, ignoreCase = true)) return true
    }
    val targets = listOfNotNull(media.title, media.originalTitle)
        .map(::normalizeCatalogTitle)
        .filter { it.isNotEmpty() }
        .distinct()
    if (targets.isEmpty()) return true

    val cleanCand = normalizeCatalogTitle(result.parsed.cleanTitle)
    val rawCand = normalizeCatalogTitle(result.title)
    if (cleanCand.isEmpty()) return true

    val candYear = result.parsed.year
    val mediaYear = media.year
    if (candYear != null && mediaYear != null && kotlin.math.abs(candYear - mediaYear) > 1) {
        return false
    }

    return targets.any { target ->
        when {
            cleanCand == target -> true
            cleanCand.startsWith("$target ") || cleanCand.endsWith(" $target") -> {
                // Reject false-prefix collisions like "call me by your name" or "tell my your name" for "your name"
                val extraWords = cleanCand.removePrefix(target).removeSuffix(target).trim()
                !cleanCand.endsWith(" $target") || extraWords in ALLOWED_TITLE_ARTICLES
            }
            target.split(' ').size >= 3 && rawCand.contains(target) -> true
            else -> false
        }
    }
}

private val ALLOWED_TITLE_ARTICLES = setOf("the", "el", "la", "los", "las", "un", "una")

fun filterCatalogDetailResults(
    sortedResults: List<TorrentSearchResult>,
    assessmentsById: Map<String, PlayabilityAssessment>,
    media: MediaMetadata,
    showAllResults: Boolean,
): List<TorrentSearchResult> {
    if (showAllResults || sortedResults.isEmpty()) return sortedResults
    val titleMatched = sortedResults.filter { isRelevantCatalogResult(it, media) }
        .ifEmpty { sortedResults }
    val liveOnly = titleMatched.filter { res ->
        val eval = assessmentsById[res.id]
        val seeders = eval?.effectiveSeeders ?: res.seeders
        eval?.verdict != PlayabilityVerdict.Dead && seeders > 0
    }
    return (liveOnly.ifEmpty { titleMatched }).take(25)
}

fun isResultInLibrary(result: TorrentSearchResult, library: List<DownloadEntry>): Boolean {
    val hash = result.infoHash ?: org.cinito.online.search.SearchUtils.extractInfoHash(result.magnetUri)
    return hash != null && library.any { it.key.equals(hash, ignoreCase = true) }
}

fun findMatchingLibraryEntry(
    media: MediaMetadata,
    library: List<DownloadEntry>,
    resolvedResults: List<TorrentSearchResult> = emptyList(),
): DownloadEntry? {
    if (library.isEmpty()) return null
    val resultHashes = resolvedResults.mapNotNull {
        (it.infoHash ?: org.cinito.online.search.SearchUtils.extractInfoHash(it.magnetUri))?.lowercase(Locale.ROOT)
    }.toSet()
    if (resultHashes.isNotEmpty()) {
        library.firstOrNull { it.key.lowercase(Locale.ROOT) in resultHashes }?.let { return it }
    }

    val targets = listOfNotNull(media.title, media.originalTitle)
        .map(::normalizeCatalogTitle)
        .filter { it.length >= 2 }
        .toSet()
    if (targets.isEmpty()) return null

    return library.firstOrNull { entry ->
        val parsed = MediaTitleParser.parse(entry.title)
        val normalizedEntry = normalizeCatalogTitle(parsed.cleanTitle)
        val titleMatches = normalizedEntry in targets
        val yearMatches = media.year == null || parsed.year == null || media.year == parsed.year
        titleMatches && yearMatches
    }
}

/**
 * Lightweight pre-parsed library title index so Discover grids don't re-run title regexes on
 * every live torrent progress tick.
 */
data class CatalogLibraryIndex(
    private val yearsByNormalizedTitle: Map<String, Set<Int?>>,
) {
    fun contains(media: MediaMetadata): Boolean {
        if (yearsByNormalizedTitle.isEmpty()) return false
        val targets = listOfNotNull(media.title, media.originalTitle)
            .map(::normalizeCatalogTitle)
            .filter { it.length >= 2 }
        for (target in targets) {
            val years = yearsByNormalizedTitle[target] ?: continue
            if (media.year == null || null in years || media.year in years) {
                return true
            }
        }
        return false
    }
}

fun buildCatalogLibraryIndex(titles: List<String>): CatalogLibraryIndex {
    if (titles.isEmpty()) return CatalogLibraryIndex(emptyMap())
    val map = HashMap<String, MutableSet<Int?>>(titles.size)
    for (rawTitle in titles) {
        val parsed = MediaTitleParser.parse(rawTitle)
        val norm = normalizeCatalogTitle(parsed.cleanTitle)
        if (norm.length >= 2) {
            map.getOrPut(norm) { HashSet() }.add(parsed.year)
        }
    }
    return CatalogLibraryIndex(map)
}

private val CAM_RELEASE_REGEX = Regex("""(?i)\b(hdcam|camrip|cam|hdts|telesync|telecine|workprint|wp|scr|screener|ts)\b""")

/**
 * Picks the healthiest immediate stream candidate matching the active [languageFilter]
 * (preferring healthy seeders, non-CAM releases, preferred language tier, and 1080p/720p streamable bitrates).
 */
fun pickBestAutoStreamResult(
    results: List<TorrentSearchResult>,
    languageFilter: SearchLanguageFilter,
    playabilityContext: org.cinito.online.search.PlayabilityContext = org.cinito.online.search.PlayabilityContext(),
    scrapeStatsByHash: Map<String, org.cinito.online.search.TrackerSwarmStats> = emptyMap(),
    probeResultsByHash: Map<String, org.cinito.online.search.SwarmProbeResult> = emptyMap(),
    media: MediaMetadata? = null,
): TorrentSearchResult? {
    val relevantPool = if (media != null) {
        results.filter { isRelevantCatalogResult(it, media) }.ifEmpty { results }
    } else {
        results
    }
    val (sorted, assessments) = SearchService.evaluatePlayabilityAndSort(
        results = relevantPool,
        sortOrder = SearchSortOrder.BestPlayability,
        languageFilter = languageFilter,
        playabilityContext = playabilityContext,
        scrapeStatsByHash = scrapeStatsByHash,
        probeResultsByHash = probeResultsByHash,
    )
    if (sorted.isEmpty()) return null
    val liveNonDead = sorted.filter {
        assessments[it.id]?.verdict != org.cinito.online.search.PlayabilityVerdict.Dead
    }.ifEmpty { sorted }
    val cleanReleases = liveNonDead.filterNot { CAM_RELEASE_REGEX.containsMatchIn(it.title) }
        .ifEmpty { liveNonDead }
    return cleanReleases.firstOrNull()
}

private fun normalizeCatalogTitle(raw: String): String =
    raw.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
