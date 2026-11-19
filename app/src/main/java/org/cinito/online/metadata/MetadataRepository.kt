package org.cinito.online.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.search.SearchUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

enum class CatalogRegion(val label: String) {
    MexicoLatino("México / Español Latino"),
    GlobalEnglish("Global (IMDb / Inglés)"),
}

enum class CatalogFeed(val label: String) {
    TrendingToday("Hoy"),
    NewReleases("Estrenos"),
    Popular("Populares"),
    TopRated("Aclamadas"),
}

@Serializable
data class CatalogGenre(
    val id: String,
    val label: String,
    val cinemetaName: String,
    val tmdbMovieId: Int? = null,
    val tmdbSeriesId: Int? = null,
)

object CatalogGenreRegistry {
    val genres: List<CatalogGenre> = listOf(
        CatalogGenre("act", "Acción", "Action", tmdbMovieId = 28, tmdbSeriesId = 10759),
        CatalogGenre("trl", "Suspenso", "Thriller", tmdbMovieId = 53, tmdbSeriesId = 9648),
        CatalogGenre("drm", "Drama", "Drama", tmdbMovieId = 18, tmdbSeriesId = 18),
        CatalogGenre("cmy", "Comedia", "Comedy", tmdbMovieId = 35, tmdbSeriesId = 35),
        CatalogGenre("hrr", "Terror", "Horror", tmdbMovieId = 27, tmdbSeriesId = 9648),
        CatalogGenre("scf", "Sci-Fi", "Sci-Fi", tmdbMovieId = 878, tmdbSeriesId = 10765),
        CatalogGenre("ani", "Animación", "Animation", tmdbMovieId = 16, tmdbSeriesId = 16),
        CatalogGenre("crm", "Crimen", "Crime", tmdbMovieId = 80, tmdbSeriesId = 80),
        CatalogGenre("fnt", "Fantasía", "Fantasy", tmdbMovieId = 14, tmdbSeriesId = 10765),
        CatalogGenre("rma", "Romance", "Romance", tmdbMovieId = 10749, tmdbSeriesId = 10749),
        CatalogGenre("fml", "Familia", "Family", tmdbMovieId = 10751, tmdbSeriesId = 10751),
        CatalogGenre("doc", "Documental", "Documentary", tmdbMovieId = 99, tmdbSeriesId = 99),
    )

    fun findById(id: String?): CatalogGenre? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { clean ->
        genres.firstOrNull {
            it.id.equals(clean, ignoreCase = true) ||
                it.label.equals(clean, ignoreCase = true) ||
                it.cinemetaName.equals(clean, ignoreCase = true)
        }
    }
}

@Serializable
data class CatalogPage(
    val items: List<MediaMetadata>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
)

@Serializable
private data class CachedCatalogPage(
    val day: String,
    val savedAtMs: Long,
    val page: CatalogPage,
)

@Serializable
data class MediaEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String? = null,
    val thumbnailUrl: String? = null,
    val released: String? = null,
)

@Serializable
data class MediaMetadata(
    val id: String,
    val type: String, // "movie" or "series"
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val rating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val episodes: List<MediaEpisode> = emptyList(),
)

/**
 * Resolves rich cinema posters, backdrops, synopses, and episode guides via
 * JustWatch MX (es_MX keyless), optional TMDB (es-MX), Cinemeta & TVmaze
 * through HardenedNetworkClient (DoH + HTTPS-only), with local disk cache.
 */
class MetadataRepository(
    private val network: HardenedNetworkClient,
    private val cacheDir: File,
    private val baseCinemetaUrl: String = DEFAULT_CINEMETA_URL,
    private val baseTvMazeUrl: String = DEFAULT_TVMAZE_URL,
    private val baseJustWatchUrl: String = DEFAULT_JUSTWATCH_URL,
    private val baseTmdbUrl: String = DEFAULT_TMDB_URL,
    private val todayProvider: () -> String = { java.time.LocalDate.now().toString() },
    private val clockProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val memory = ConcurrentHashMap<String, MediaMetadata>()
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private val writeLock = Mutex()
    @Volatile var lookupEnabled: Boolean = true
    @Volatile var catalogRegion: CatalogRegion = CatalogRegion.GlobalEnglish
    @Volatile var tmdbApiKey: String? = null
    @Volatile var canUseNetwork: () -> Boolean = { true }

    init {
        cacheDir.mkdirs()
    }

    suspend fun getTopCatalog(type: String): List<MediaMetadata> {
        val region = catalogRegion
        val cacheKey = "catalog_${region.name}_$type"
        readCatalogCache(cacheKey)?.let { return it }
        if (!lookupEnabled) return emptyList()

        val items = when (region) {
            CatalogRegion.MexicoLatino -> {
                fetchTmdbMxTrending(type)
                    .ifEmpty { fetchJustWatchMxCatalog(type) }
                    .ifEmpty { fetchCinemetaTopCatalog(type) }
            }
            CatalogRegion.GlobalEnglish -> fetchCinemetaTopCatalog(type)
        }
        if (items.isNotEmpty()) {
            writeCatalogCache(cacheKey, items)
        }
        return items
    }

    /**
     * Fetches a paginated, daily-fresh catalog slice for [DiscoverScreen] infinite scroll,
     * supporting server-side feeds ([CatalogFeed]) and server-side genres ([CatalogGenre]).
     */
    suspend fun getCatalogPage(
        type: String,
        feed: CatalogFeed = CatalogFeed.TrendingToday,
        genre: CatalogGenre? = null,
        cursor: String? = null,
        forceRefresh: Boolean = false,
    ): CatalogPage {
        val region = catalogRegion
        val day = todayProvider()
        val genreKey = genre?.id ?: "all"
        val cursorKey = cursor?.let(::sha256)?.take(16) ?: "p0"
        val cacheKey = "catpage_${region.name}_${feed.name}_${type}_${genreKey}_$cursorKey"
        val ttlMs = when (feed) {
            CatalogFeed.TrendingToday, CatalogFeed.NewReleases -> DAILY_FEED_TTL_MS
            CatalogFeed.Popular, CatalogFeed.TopRated -> CATALOG_TTL_MS
        }

        if (!forceRefresh) {
            readCatalogPageCache(cacheKey, expectedDay = day, ttlMs = ttlMs)?.let { return it }
        }
        if (!lookupEnabled) return CatalogPage(emptyList())

        val page = if (!cursor.isNullOrBlank()) {
            fetchPagedWithExistingCursor(type, feed, genre, cursor, day, region)
        } else {
            fetchFirstCatalogPage(type, feed, genre, day, region)
        }

        if (page.items.isNotEmpty()) {
            writeCatalogPageCache(
                cacheKey,
                CachedCatalogPage(day = day, savedAtMs = clockProvider(), page = page),
            )
        }
        return page
    }

    private suspend fun fetchFirstCatalogPage(
        type: String,
        feed: CatalogFeed,
        genre: CatalogGenre?,
        day: String,
        region: CatalogRegion,
    ): CatalogPage {
        if (region == CatalogRegion.GlobalEnglish) {
            return fetchCinemetaPage(type, feed, genre, cursor = null, day = day)
        }
        tryNonCancellation { fetchTmdbPage(type, feed, genre, cursor = null, day = day) }
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { return it }
        tryNonCancellation { fetchJustWatchMxPage(type, feed, genre, cursor = null, day = day) }
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { return it }
        return fetchCinemetaPage(type, feed, genre, cursor = null, day = day)
    }

    private suspend fun fetchPagedWithExistingCursor(
        type: String,
        feed: CatalogFeed,
        genre: CatalogGenre?,
        cursor: String,
        day: String,
        region: CatalogRegion,
    ): CatalogPage = when {
        cursor.startsWith(CURSOR_PREFIX_TMDB) ->
            fetchTmdbPage(type, feed, genre, cursor.removePrefix(CURSOR_PREFIX_TMDB), day)
                ?: throw IOException("TMDB API key missing for pagination")
        cursor.startsWith(CURSOR_PREFIX_JUSTWATCH) ->
            fetchJustWatchMxPage(type, feed, genre, cursor.removePrefix(CURSOR_PREFIX_JUSTWATCH), day)
                ?: throw IOException("JustWatch MX page missing")
        cursor.startsWith(CURSOR_PREFIX_CINEMETA) ->
            fetchCinemetaPage(type, feed, genre, cursor.removePrefix(CURSOR_PREFIX_CINEMETA), day)
        region == CatalogRegion.GlobalEnglish ->
            fetchCinemetaPage(type, feed, genre, cursor, day)
        !tmdbApiKey.isNullOrBlank() ->
            fetchTmdbPage(type, feed, genre, cursor, day)
                ?: throw IOException("TMDB page unavailable")
        else ->
            fetchJustWatchMxPage(type, feed, genre, cursor, day)
                ?: throw IOException("JustWatch MX page unavailable")
    }

    private suspend inline fun <T> tryNonCancellation(block: suspend () -> T): T? =
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }

    private suspend fun fetchCinemetaTopCatalog(type: String): List<MediaMetadata> {
        val url = "$baseCinemetaUrl/catalog/$type/top.json"
        val root = runCatching { network.getJson(url)?.jsonObject }.getOrNull() ?: return emptyList()
        return parseCatalogMetas(root, type)
    }

    private suspend fun fetchCinemetaPage(
        type: String,
        feed: CatalogFeed,
        genre: CatalogGenre?,
        cursor: String?,
        day: String,
    ): CatalogPage {
        val currentYear = day.take(4).toIntOrNull() ?: 2026
        val skip = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val catalogId = when {
            genre != null && feed == CatalogFeed.TopRated -> "imdbRating"
            genre != null -> "top"
            feed == CatalogFeed.NewReleases -> "year"
            feed == CatalogFeed.TopRated -> "imdbRating"
            else -> "top"
        }
        val extras = buildList {
            if (catalogId == "year") {
                add("genre=$currentYear")
            } else if (genre != null) {
                add("genre=${SearchUtils.encodeUrl(genre.cinemetaName)}")
            }
            if (skip > 0) {
                add("skip=$skip")
            }
        }
        val url = if (extras.isEmpty()) {
            "$baseCinemetaUrl/catalog/$type/$catalogId.json"
        } else {
            "$baseCinemetaUrl/catalog/$type/$catalogId/${extras.joinToString("&")}.json"
        }
        val root = network.getJson(url)?.jsonObject
            ?: throw IOException("Cinemeta upstream returned empty or invalid JSON")
        val items = parseCatalogMetas(root, type).distinctBy { "${it.type}:${it.id}" }
        val hasMore = items.size >= CINEMETA_PAGE_THRESHOLD
        val nextCursor = if (hasMore) "$CURSOR_PREFIX_CINEMETA${skip + items.size}" else null
        return CatalogPage(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    private suspend fun fetchJustWatchMxCatalog(type: String): List<MediaMetadata> {
        val objectType = if (type == "series") "SHOW" else "MOVIE"
        val query = """
            query GetPopularTitles(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}filter: TitleFilter) {
              popularTitles(country: ${'$'}country, first: ${'$'}first, filter: ${'$'}filter) {
                edges {
                  node {
                    id
                    objectType
                    content(country: ${'$'}country, language: ${'$'}language) {
                      title
                      originalTitle
                      originalReleaseYear
                      shortDescription
                      runtime
                      genres { translation(language: ${'$'}language) }
                      scoring { imdbScore }
                      externalIds { imdbId }
                      posterUrl(profile: S592, format: JPG)
                      backdrops(profile: S1440, format: JPG) { backdropUrl }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val payload = buildJsonObject {
            put("operationName", JsonPrimitive("GetPopularTitles"))
            put("query", JsonPrimitive(query))
            put("variables", buildJsonObject {
                put("country", JsonPrimitive("MX"))
                put("language", JsonPrimitive("es"))
                put("first", JsonPrimitive(30))
                put("filter", buildJsonObject {
                    putJsonArray("objectTypes") { add(JsonPrimitive(objectType)) }
                })
            })
        }
        val root = runCatching { network.postJson(baseJustWatchUrl, payload)?.jsonObject }.getOrNull()
            ?: return emptyList()
        return parseJustWatchEdges(root, defaultType = type)
    }

    private suspend fun fetchJustWatchMxPage(
        type: String,
        feed: CatalogFeed,
        genre: CatalogGenre?,
        cursor: String?,
        day: String,
    ): CatalogPage? {
        val objectType = if (type == "series") "SHOW" else "MOVIE"
        val sortBy = when (feed) {
            CatalogFeed.TrendingToday -> "TRENDING"
            CatalogFeed.NewReleases -> "RELEASE_YEAR"
            CatalogFeed.Popular -> "POPULAR"
            CatalogFeed.TopRated -> "TMDB_POPULARITY"
        }
        val currentYear = day.take(4).toIntOrNull() ?: 2026
        val query = """
            query GetPopularTitles(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}after: String, ${'$'}sortBy: PopularTitlesSorting!, ${'$'}filter: TitleFilter) {
              popularTitles(country: ${'$'}country, first: ${'$'}first, after: ${'$'}after, sortBy: ${'$'}sortBy, filter: ${'$'}filter) {
                totalCount
                pageInfo {
                  endCursor
                  hasNextPage
                }
                edges {
                  node {
                    id
                    objectType
                    content(country: ${'$'}country, language: ${'$'}language) {
                      title
                      originalTitle
                      originalReleaseYear
                      shortDescription
                      runtime
                      genres { translation(language: ${'$'}language) }
                      scoring { imdbScore }
                      externalIds { imdbId }
                      posterUrl(profile: S592, format: JPG)
                      backdrops(profile: S1440, format: JPG) { backdropUrl }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val payload = buildJsonObject {
            put("operationName", JsonPrimitive("GetPopularTitles"))
            put("query", JsonPrimitive(query))
            put("variables", buildJsonObject {
                put("country", JsonPrimitive("MX"))
                put("language", JsonPrimitive("es"))
                put("first", JsonPrimitive(JUSTWATCH_PAGE_SIZE))
                if (!cursor.isNullOrBlank()) {
                    put("after", JsonPrimitive(cursor))
                }
                put("sortBy", JsonPrimitive(sortBy))
                put("filter", buildJsonObject {
                    putJsonArray("objectTypes") { add(JsonPrimitive(objectType)) }
                    if (genre != null) {
                        putJsonArray("genres") { add(JsonPrimitive(genre.id)) }
                    }
                    if (feed == CatalogFeed.NewReleases) {
                        put("releaseYear", buildJsonObject {
                            put("min", JsonPrimitive(currentYear - 1))
                            put("max", JsonPrimitive(currentYear))
                        })
                    }
                })
            })
        }
        val root = network.postJson(baseJustWatchUrl, payload)?.jsonObject
            ?: throw IOException("JustWatch MX upstream returned empty or invalid JSON")
        val conn = root["data"]?.jsonObject?.get("popularTitles")?.jsonObject
            ?: throw IOException("JustWatch MX upstream missing popularTitles envelope")
        val pageInfo = conn["pageInfo"]?.jsonObject
        val endCursor = pageInfo?.get("endCursor")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val hasNextPage = pageInfo?.get("hasNextPage")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val totalCount = conn["totalCount"]?.jsonPrimitive?.intOrNull
        val items = parseJustWatchEdges(root, defaultType = type)
            .distinctBy { "${it.type}:${it.id}" }
            .let { parsed ->
                if (feed == CatalogFeed.NewReleases) {
                    parsed.filter { it.year == null || it.year <= currentYear }
                } else {
                    parsed
                }
            }
        return CatalogPage(
            items = items,
            nextCursor = if (hasNextPage && endCursor != null && items.isNotEmpty()) {
                "$CURSOR_PREFIX_JUSTWATCH$endCursor"
            } else {
                null
            },
            hasMore = hasNextPage && endCursor != null && items.isNotEmpty(),
            totalCount = totalCount,
        )
    }

    private suspend fun fetchJustWatchMxSearch(queryText: String, type: String): List<MediaMetadata> {
        val objectType = if (type == "series") "SHOW" else "MOVIE"
        val query = """
            query SearchTitles(${'$'}country: Country!, ${'$'}language: Language!, ${'$'}first: Int!, ${'$'}filter: TitleFilter) {
              popularTitles(country: ${'$'}country, first: ${'$'}first, filter: ${'$'}filter) {
                edges {
                  node {
                    id
                    objectType
                    content(country: ${'$'}country, language: ${'$'}language) {
                      title
                      originalTitle
                      originalReleaseYear
                      shortDescription
                      runtime
                      genres { translation(language: ${'$'}language) }
                      scoring { imdbScore }
                      externalIds { imdbId }
                      posterUrl(profile: S592, format: JPG)
                      backdrops(profile: S1440, format: JPG) { backdropUrl }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val payload = buildJsonObject {
            put("operationName", JsonPrimitive("SearchTitles"))
            put("query", JsonPrimitive(query))
            put("variables", buildJsonObject {
                put("country", JsonPrimitive("MX"))
                put("language", JsonPrimitive("es"))
                put("first", JsonPrimitive(10))
                put("filter", buildJsonObject {
                    put("searchQuery", JsonPrimitive(queryText))
                    putJsonArray("objectTypes") { add(JsonPrimitive(objectType)) }
                })
            })
        }
        val root = runCatching { network.postJson(baseJustWatchUrl, payload)?.jsonObject }.getOrNull()
            ?: return emptyList()
        return parseJustWatchEdges(root, defaultType = type)
    }

    private fun parseJustWatchEdges(root: JsonObject, defaultType: String): List<MediaMetadata> {
        val edges = root["data"]?.jsonObject
            ?.get("popularTitles")?.jsonObject
            ?.get("edges")?.jsonArray
            ?: return emptyList()
        return edges.mapNotNull { edgeElem ->
            val node = edgeElem.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
            val content = node["content"]?.jsonObject ?: return@mapNotNull null
            val title = content["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val originalTitle = content["originalTitle"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val imdbId = content["externalIds"]?.jsonObject?.get("imdbId")?.jsonPrimitive?.content?.takeIf { it.startsWith("tt") }
            val nodeId = node["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val posterPath = content["posterUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val poster = posterPath?.let { if (it.startsWith("https://")) it else "https://images.justwatch.com$it" }
            val backdropPath = runCatching {
                content["backdrops"]?.jsonArray?.firstOrNull()?.jsonObject?.get("backdropUrl")?.jsonPrimitive?.content
            }.getOrNull()
            val backdrop = backdropPath?.let { if (it.startsWith("https://")) it else "https://images.justwatch.com$it" }
            val year = content["originalReleaseYear"]?.jsonPrimitive?.intOrNull
            val score = content["scoring"]?.jsonObject?.get("imdbScore")?.jsonPrimitive?.doubleOrNull?.let {
                String.format(Locale.US, "%.1f", it)
            }
            val runtimeMins = content["runtime"]?.jsonPrimitive?.intOrNull
            val genres = content["genres"]?.jsonArray?.mapNotNull {
                it.jsonObject["translation"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            }.orEmpty()
            MediaMetadata(
                id = imdbId ?: "jw:$nodeId",
                type = defaultType,
                title = title,
                originalTitle = originalTitle,
                year = year,
                posterUrl = poster,
                backdropUrl = backdrop ?: poster,
                description = content["shortDescription"]?.jsonPrimitive?.content,
                rating = score,
                runtime = runtimeMins?.let { "$it min" },
                genres = genres,
            )
        }
    }

    private suspend fun fetchTmdbMxTrending(type: String): List<MediaMetadata> {
        val apiKey = tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val tmdbType = if (type == "series") "tv" else "movie"
        val url = "$baseTmdbUrl/trending/$tmdbType/week?language=es-MX&region=MX&api_key=${SearchUtils.encodeUrl(apiKey)}"
        val root = runCatching { network.getJson(url)?.jsonObject }.getOrNull() ?: return emptyList()
        return parseTmdbResults(root, type)
    }

    private suspend fun fetchTmdbPage(
        type: String,
        feed: CatalogFeed,
        genre: CatalogGenre?,
        cursor: String?,
        day: String,
    ): CatalogPage? {
        val apiKey = tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val tmdbType = if (type == "series") "tv" else "movie"
        val encKey = SearchUtils.encodeUrl(apiKey)
        val tmdbGenreId = genre?.let { if (tmdbType == "tv") it.tmdbSeriesId else it.tmdbMovieId }
        val genreParam = tmdbGenreId?.let { "&with_genres=$it" }.orEmpty()

        val url = when {
            feed == CatalogFeed.TrendingToday && tmdbGenreId == null ->
                "$baseTmdbUrl/trending/$tmdbType/day?language=es-MX&region=MX&page=$page&api_key=$encKey"
            feed == CatalogFeed.NewReleases && tmdbGenreId == null -> {
                val ep = if (tmdbType == "tv") "tv/on_the_air" else "movie/now_playing"
                "$baseTmdbUrl/$ep?language=es-MX&region=MX&page=$page&api_key=$encKey"
            }
            feed == CatalogFeed.NewReleases -> {
                val dateField = if (tmdbType == "tv") "first_air_date" else "primary_release_date"
                "$baseTmdbUrl/discover/$tmdbType?language=es-MX&region=MX&sort_by=$dateField.desc&$dateField.lte=$day$genreParam&page=$page&api_key=$encKey"
            }
            feed == CatalogFeed.TopRated ->
                "$baseTmdbUrl/discover/$tmdbType?language=es-MX&region=MX&sort_by=vote_average.desc&vote_count.gte=250$genreParam&page=$page&api_key=$encKey"
            else ->
                "$baseTmdbUrl/discover/$tmdbType?language=es-MX&region=MX&sort_by=popularity.desc$genreParam&page=$page&api_key=$encKey"
        }
        val root = network.getJson(url)?.jsonObject
            ?: throw IOException("TMDB upstream returned empty or invalid JSON")
        val items = parseTmdbResults(root, type).distinctBy { "${it.type}:${it.id}" }
        val curPage = root["page"]?.jsonPrimitive?.intOrNull ?: page
        val totalPages = root["total_pages"]?.jsonPrimitive?.intOrNull ?: curPage
        val totalResults = root["total_results"]?.jsonPrimitive?.intOrNull
        val hasMore = curPage < totalPages && items.isNotEmpty()
        return CatalogPage(
            items = items,
            nextCursor = if (hasMore) "$CURSOR_PREFIX_TMDB${curPage + 1}" else null,
            hasMore = hasMore,
            totalCount = totalResults,
        )
    }

    private fun parseTmdbResults(root: JsonObject, type: String): List<MediaMetadata> {
        val tmdbType = if (type == "series") "tv" else "movie"
        val results = root["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { elem ->
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = (if (tmdbType == "tv") obj["name"] else obj["title"])?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val orig = (if (tmdbType == "tv") obj["original_name"] else obj["original_title"])?.jsonPrimitive?.content
            val date = (if (tmdbType == "tv") obj["first_air_date"] else obj["release_date"])?.jsonPrimitive?.content
            val poster = obj["poster_path"]?.jsonPrimitive?.content?.takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = obj["backdrop_path"]?.jsonPrimitive?.content?.takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val vote = obj["vote_average"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
                ?.let { String.format(Locale.US, "%.1f", it) }
            val genreLabels = obj["genre_ids"]?.jsonArray?.mapNotNull { gElem ->
                val gId = gElem.jsonPrimitive.intOrNull ?: return@mapNotNull null
                CatalogGenreRegistry.genres.firstOrNull {
                    (if (tmdbType == "tv") it.tmdbSeriesId else it.tmdbMovieId) == gId
                }?.label
            }.orEmpty().distinct()
            MediaMetadata(
                id = "tmdb:$id",
                type = type,
                title = title,
                originalTitle = orig,
                year = date?.take(4)?.toIntOrNull(),
                posterUrl = poster,
                backdropUrl = backdrop ?: poster,
                description = obj["overview"]?.jsonPrimitive?.content,
                rating = vote,
                genres = genreLabels,
            )
        }
    }

    suspend fun searchCatalog(query: String, type: String): List<MediaMetadata> {
        if (!lookupEnabled || query.isBlank()) return emptyList()
        if (catalogRegion == CatalogRegion.MexicoLatino) {
            val mx = fetchJustWatchMxSearch(query, type)
            if (mx.isNotEmpty()) return mx.distinctBy { "${it.type}:${it.id}" }
        }
        val encoded = SearchUtils.encodeUrl(query)
        val url = "$baseCinemetaUrl/catalog/$type/top/search=$encoded.json"
        val root = runCatching { network.getJson(url)?.jsonObject }.getOrNull() ?: return emptyList()
        return parseCatalogMetas(root, type).distinctBy { "${it.type}:${it.id}" }
    }

    /**
     * Searches both movies and series catalogs in parallel and deduplicates results
     * by IMDb/catalog ID or normalized title+year+type.
     */
    suspend fun searchCatalogAll(query: String): List<MediaMetadata> {
        if (!lookupEnabled || query.isBlank()) return emptyList()
        return coroutineScope {
            val moviesDeferred = async { runCatching { searchCatalog(query, "movie") }.getOrDefault(emptyList()) }
            val seriesDeferred = async { runCatching { searchCatalog(query, "series") }.getOrDefault(emptyList()) }
            val movies = moviesDeferred.await()
            val series = seriesDeferred.await()
            val interleaved = buildList {
                val maxLen = maxOf(movies.size, series.size)
                for (i in 0 until maxLen) {
                    if (i < movies.size) add(movies[i])
                    if (i < series.size) add(series[i])
                }
            }
            val seen = LinkedHashSet<String>()
            interleaved.filter { item ->
                val dedupeKey = if (item.id.startsWith("tt")) {
                    "${item.type}:${item.id}"
                } else {
                    "${item.type}:${item.title.lowercase(Locale.ROOT)}:${item.year ?: 0}"
                }
                seen.add(dedupeKey)
            }
        }
    }

    /**
     * Enriches a catalog card (e.g. from JustWatch MX or TMDB) with canonical IMDb ID,
     * runtime, genres, and full episode guides while keeping localized Spanish text.
     */
    suspend fun enrichCatalogMediaDetails(media: MediaMetadata): MediaMetadata {
        if (!lookupEnabled) return media

        var enriched = media
        var resolvedTmdbId: String? = media.id.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")
        if (resolvedTmdbId != null) {
            val tmdbData = fetchTmdbDetails(resolvedTmdbId, enriched.type)
            if (tmdbData != null) {
                enriched = enriched.copy(
                    id = tmdbData.id.takeIf { it.startsWith("tt") } ?: enriched.id,
                    description = enriched.description?.takeIf { it.isNotBlank() } ?: tmdbData.description,
                    runtime = enriched.runtime ?: tmdbData.runtime,
                    genres = enriched.genres.ifEmpty { tmdbData.genres },
                    backdropUrl = enriched.backdropUrl ?: tmdbData.backdropUrl,
                )
            }
        }

        if (!enriched.id.startsWith("tt")) {
            val lookupTitle = enriched.originalTitle?.takeIf { it.isNotBlank() } ?: enriched.title
            val resolved = resolveSearchQuery(lookupTitle, enriched.type)
                ?: if (!enriched.originalTitle.isNullOrBlank() && enriched.originalTitle != enriched.title) {
                    resolveSearchQuery(enriched.title, enriched.type)
                } else {
                    null
                }
            if (resolved != null && resolved.id.startsWith("tt")) {
                enriched = enriched.copy(
                    id = resolved.id,
                    originalTitle = enriched.originalTitle ?: resolved.originalTitle,
                    runtime = enriched.runtime ?: resolved.runtime,
                    genres = enriched.genres.ifEmpty { resolved.genres },
                    description = enriched.description?.takeIf { it.isNotBlank() } ?: resolved.description,
                    backdropUrl = enriched.backdropUrl ?: resolved.backdropUrl,
                )
            }
        }

        val needsMoreMetadata = (enriched.type == "series" && enriched.episodes.isEmpty()) ||
            enriched.runtime == null ||
            enriched.genres.isEmpty() ||
            enriched.backdropUrl == null
        if (enriched.id.startsWith("tt") && needsMoreMetadata) {
            val cinemeta = getDetails(enriched.type, enriched.id)
            if (cinemeta != null) {
                enriched = enriched.copy(
                    originalTitle = enriched.originalTitle ?: cinemeta.title.takeIf { !it.equals(enriched.title, ignoreCase = true) },
                    runtime = enriched.runtime ?: cinemeta.runtime,
                    genres = enriched.genres.ifEmpty { cinemeta.genres },
                    description = enriched.description?.takeIf { it.isNotBlank() } ?: cinemeta.description,
                    backdropUrl = enriched.backdropUrl ?: cinemeta.backdropUrl,
                    posterUrl = enriched.posterUrl ?: cinemeta.posterUrl,
                    rating = enriched.rating ?: cinemeta.rating,
                    episodes = enriched.episodes.ifEmpty { cinemeta.episodes },
                )
            }
        }

        if (enriched.type == "series") {
            if (enriched.episodes.isEmpty()) {
                val tvMazeEpisodes = fetchTvMazeEpisodes(
                    imdbId = enriched.id.takeIf { it.startsWith("tt") },
                    title = enriched.originalTitle?.takeIf { it.isNotBlank() } ?: enriched.title,
                )
                if (tvMazeEpisodes.isNotEmpty()) {
                    enriched = enriched.copy(episodes = tvMazeEpisodes)
                }
            }
            val apiKey = tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() }
            if (apiKey != null && catalogRegion == CatalogRegion.MexicoLatino) {
                val tmdbId = resolvedTmdbId
                    ?: enriched.id.takeIf { it.startsWith("tt") }?.let { findTmdbSeriesIdByImdb(it, apiKey) }
                if (tmdbId != null) {
                    val localizedEpisodes = fetchTmdbSeriesEpisodes(tmdbId, enriched.episodes, apiKey)
                    if (localizedEpisodes.isNotEmpty()) {
                        enriched = enriched.copy(
                            episodes = mergeLocalizedEpisodes(enriched.episodes, localizedEpisodes),
                        )
                    }
                }
            }
        }

        return enriched
    }

    private suspend fun findTmdbSeriesIdByImdb(imdbId: String, apiKey: String): String? {
        val url = "$baseTmdbUrl/find/${SearchUtils.encodeUrl(imdbId)}?external_source=imdb_id&language=es-MX&api_key=${SearchUtils.encodeUrl(apiKey)}"
        val obj = tryNonCancellation { network.getJson(url)?.jsonObject } ?: return null
        return obj["tv_results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.intOrNull?.toString()
    }

    private suspend fun fetchTmdbSeriesEpisodes(
        tmdbNumericId: String,
        existingEpisodes: List<MediaEpisode>,
        apiKey: String,
    ): List<MediaEpisode> {
        val encId = SearchUtils.encodeUrl(tmdbNumericId)
        val encKey = SearchUtils.encodeUrl(apiKey)
        val detailUrl = "$baseTmdbUrl/tv/$encId?language=es-MX&api_key=$encKey"
        val seasonNums = tryNonCancellation { network.getJson(detailUrl)?.jsonObject }
            ?.get("seasons")?.jsonArray
            ?.mapNotNull { elem ->
                val s = elem.jsonObject
                val num = s["season_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val count = s["episode_count"]?.jsonPrimitive?.intOrNull ?: 1
                num.takeIf { it >= 1 && count > 0 }
            }
            .orEmpty()
            .ifEmpty { existingEpisodes.map { it.season }.filter { it >= 1 }.distinct().sorted() }
            .distinct()
            .sorted()
            .take(MAX_TMDB_SEASONS_ENRICH)
        if (seasonNums.isEmpty()) return emptyList()

        return coroutineScope {
            seasonNums.map { seasonNum ->
                async {
                    val seasonUrl = "$baseTmdbUrl/tv/$encId/season/$seasonNum?language=es-MX&api_key=$encKey"
                    val root = tryNonCancellation { network.getJson(seasonUrl)?.jsonObject } ?: return@async emptyList()
                    root["episodes"]?.jsonArray?.mapNotNull { epElem ->
                        val epObj = epElem.jsonObject
                        val epNum = epObj["episode_number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val name = epObj["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                            ?: "Episodio $epNum"
                        val overview = epObj["overview"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                        val still = epObj["still_path"]?.jsonPrimitive?.content?.takeIf { it.startsWith("/") }
                            ?.let { "https://image.tmdb.org/t/p/w500$it" }
                        val airDate = epObj["air_date"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        MediaEpisode(
                            id = "tmdb:$tmdbNumericId:$seasonNum:$epNum",
                            season = seasonNum,
                            episode = epNum,
                            title = name,
                            overview = overview,
                            thumbnailUrl = still,
                            released = airDate,
                        )
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }
    }

    private fun mergeLocalizedEpisodes(
        base: List<MediaEpisode>,
        localized: List<MediaEpisode>,
    ): List<MediaEpisode> {
        if (base.isEmpty()) return localized.sortedWith(compareBy<MediaEpisode> { it.season }.thenBy { it.episode })
        val byKey = localized.associateBy { it.season to it.episode }
        val mergedBase = base.map { item ->
            val loc = byKey[item.season to item.episode] ?: return@map item
            item.copy(
                title = loc.title.takeIf { !isPlaceholderEpisodeTitle(it) } ?: item.title,
                overview = loc.overview ?: item.overview,
                thumbnailUrl = loc.thumbnailUrl ?: item.thumbnailUrl,
                released = loc.released ?: item.released,
            )
        }
        val baseKeys = base.mapTo(HashSet()) { it.season to it.episode }
        val extra = localized.filterNot { (it.season to it.episode) in baseKeys }
        return (mergedBase + extra).sortedWith(compareBy<MediaEpisode> { it.season }.thenBy { it.episode })
    }

    private fun isPlaceholderEpisodeTitle(title: String): Boolean =
        title.matches(Regex("""(?i)^(?:episodio|episode)\s+\d+$"""))

    private suspend fun fetchTmdbDetails(tmdbNumericId: String, type: String): MediaMetadata? {
        val apiKey = tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val tmdbType = if (type == "series") "tv" else "movie"
        val url = "$baseTmdbUrl/$tmdbType/${SearchUtils.encodeUrl(tmdbNumericId)}?language=es-MX&append_to_response=external_ids&api_key=${SearchUtils.encodeUrl(apiKey)}"
        val obj = runCatching { network.getJson(url)?.jsonObject }.getOrNull() ?: return null
        val imdbId = obj["external_ids"]?.jsonObject?.get("imdb_id")?.jsonPrimitive?.content
            ?.takeIf { it.startsWith("tt") }
        val runtimeMins = obj["runtime"]?.jsonPrimitive?.intOrNull
            ?: runCatching { obj["episode_run_time"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.intOrNull }.getOrNull()
        val genres = obj["genres"]?.jsonArray?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        }.orEmpty()
        val overview = obj["overview"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val backdrop = obj["backdrop_path"]?.jsonPrimitive?.content?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/w1280$it" }
        return MediaMetadata(
            id = imdbId ?: "tmdb:$tmdbNumericId",
            type = type,
            title = (if (tmdbType == "tv") obj["name"] else obj["title"])?.jsonPrimitive?.content.orEmpty(),
            originalTitle = (if (tmdbType == "tv") obj["original_name"] else obj["original_title"])?.jsonPrimitive?.content,
            backdropUrl = backdrop,
            description = overview,
            runtime = runtimeMins?.let { "$it min" },
            genres = genres,
        )
    }

    /**
     * Resolves a user search query (e.g. Mexican title "Wildwood: El bosque salvaje" or "Intensamente 2")
     * into its canonical metadata with `originalTitle` and `imdbId` for bilingual & Torrentio lookup.
     */
    suspend fun resolveSearchQuery(query: String, typeHint: String? = null): MediaMetadata? {
        if (!lookupEnabled || query.isBlank()) return null
        val trimmed = query.trim()
        if (trimmed.startsWith("tt") && trimmed.drop(2).all(Char::isDigit)) {
            val primaryType = typeHint ?: "movie"
            return getDetails(primaryType, trimmed)
                ?: if (typeHint == null) getDetails("series", trimmed) else null
        }

        val parsed = MediaTitleParser.parse(trimmed)
        val attempts = linkedSetOf<String>()
        parsed.cleanTitle.takeIf { it.isNotBlank() }?.let { clean ->
            attempts += clean
            clean.substringBefore(':').trim().takeIf { it.isNotBlank() }?.let { attempts += it }
        }
        trimmed.substringBefore(':').trim().takeIf { it.isNotBlank() }?.let { attempts += it }

        val typesToTry = when (typeHint) {
            "movie", "series" -> listOf(typeHint)
            else -> listOf("movie", "series")
        }

        for (attempt in attempts) {
            for (t in typesToTry) {
                val candidates = searchCatalog(attempt, t)
                val best = selectBestCandidate(candidates, parsed.copy(cleanTitle = attempt))
                if (best != null) {
                    if (!best.id.startsWith("tt") && !best.originalTitle.isNullOrBlank()) {
                        val enc = SearchUtils.encodeUrl(best.originalTitle)
                        val cm = runCatching {
                            network.getJson("$baseCinemetaUrl/catalog/${best.type}/top/search=$enc.json")?.jsonObject
                        }.getOrNull()?.let { parseCatalogMetas(it, best.type) }.orEmpty()
                        val matched = selectBestCandidate(cm, parsed.copy(cleanTitle = best.originalTitle))
                        if (matched != null && matched.id.startsWith("tt")) {
                            return best.copy(id = matched.id)
                        }
                    }
                    return best
                }
            }
        }
        return null
    }

    suspend fun getDetails(type: String, imdbId: String): MediaMetadata? {
        val key = "meta_${type}_$imdbId"
        memory[key]?.let { return it }
        readItemCache(key)?.let {
            memory[key] = it
            return it
        }
        if (!lookupEnabled) return null

        val url = "$baseCinemetaUrl/meta/$type/$imdbId.json"
        val root = runCatching { network.getJson(url)?.jsonObject }.getOrNull() ?: return null
        val metaObj = root["meta"]?.jsonObject ?: return null
        val parsed = parseMetaObject(metaObj, defaultType = type) ?: return null
        memory[key] = parsed
        writeItemCache(key, parsed)
        return parsed
    }

    suspend fun resolveForParsedTitle(parsed: ParsedMediaTitle): MediaMetadata? {
        val cacheKey = "title_${sha256(parsed.lookupKey)}"
        memory[cacheKey]?.let { return it }
        readItemCache(cacheKey)?.let {
            memory[cacheKey] = it
            return it
        }
        if (!lookupEnabled || !canUseNetwork()) return null
        val lastMiss = negativeCache[cacheKey] ?: readNegativeCache(cacheKey)
        if (lastMiss != null && clockProvider() - lastMiss < NEGATIVE_TTL_MS) {
            negativeCache[cacheKey] = lastMiss
            return null
        }

        val targetType = when (parsed.kind) {
            MediaKind.SERIES, MediaKind.ANIME -> "series"
            MediaKind.MOVIE -> "movie"
            MediaKind.OTHER -> "movie"
        }

        val candidates = searchCatalog(parsed.cleanTitle, targetType).ifEmpty {
            if (targetType == "movie") searchCatalog(parsed.cleanTitle, "series")
            else emptyList()
        }

        val bestMatch = selectBestCandidate(candidates, parsed)
        val resolved = if (bestMatch != null) {
            if (bestMatch.id.startsWith("tt")) {
                val cinemetaDetails = getDetails(bestMatch.type, bestMatch.id)
                if (catalogRegion == CatalogRegion.MexicoLatino && cinemetaDetails != null) {
                    bestMatch.copy(
                        episodes = cinemetaDetails.episodes,
                        runtime = bestMatch.runtime ?: cinemetaDetails.runtime,
                        description = bestMatch.description?.takeIf { it.isNotBlank() } ?: cinemetaDetails.description,
                        genres = bestMatch.genres.ifEmpty { cinemetaDetails.genres },
                    )
                } else {
                    cinemetaDetails ?: bestMatch
                }
            } else {
                bestMatch
            }
        } else if (targetType == "series") {
            fetchTvMazeFallback(parsed.cleanTitle)
        } else {
            null
        }

        if (resolved != null) {
            memory[cacheKey] = resolved
            writeItemCache(cacheKey, resolved)
        } else {
            val now = clockProvider()
            negativeCache[cacheKey] = now
            writeNegativeCache(cacheKey, now)
        }
        return resolved
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            memory.clear()
            negativeCache.clear()
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun getAiringSeriesSchedule(
        day: String = todayProvider(),
        forceRefresh: Boolean = false,
    ): List<MediaMetadata> {
        val cacheKey = "catpage_tvmaze_schedule_$day"
        if (!forceRefresh) {
            readCatalogPageCache(cacheKey, expectedDay = day, ttlMs = DAILY_FEED_TTL_MS)?.let { return it.items }
        }
        if (!lookupEnabled) return emptyList()
        val arr = tryNonCancellation {
            network.getJson("$baseTvMazeUrl/schedule?country=US&date=${SearchUtils.encodeUrl(day)}")?.jsonArray
        } ?: return emptyList()
        val shows = arr.mapNotNull { elem ->
            val slot = elem.jsonObject
            val show = slot["show"]?.jsonObject ?: return@mapNotNull null
            val showType = show["type"]?.jsonPrimitive?.content.orEmpty()
            if (showType.equals("Talk Show", ignoreCase = true) ||
                showType.equals("News", ignoreCase = true) ||
                showType.equals("Sports", ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            val id = show["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = show["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val imdbId = show["externals"]?.jsonObject?.get("imdb")?.jsonPrimitive?.content?.takeIf { it.startsWith("tt") }
            val imageObj = show["image"]?.jsonObject
            val poster = imageObj?.get("original")?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
                ?: imageObj?.get("medium")?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
                ?: return@mapNotNull null
            val summary = show["summary"]?.jsonPrimitive?.content?.let(::stripHtmlTags)?.takeIf { it.isNotBlank() }
            val year = show["premiered"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull()
            val rating = show["rating"]?.jsonObject?.get("average")?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }?.let {
                String.format(Locale.US, "%.1f", it)
            }
            val genres = show["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }.orEmpty()
            MediaMetadata(
                id = imdbId ?: "tvmaze:$id",
                type = "series",
                title = name,
                year = year,
                posterUrl = poster,
                backdropUrl = poster,
                description = summary,
                rating = rating,
                genres = genres,
            )
        }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
            .take(16)

        if (shows.isNotEmpty()) {
            writeCatalogPageCache(
                cacheKey,
                CachedCatalogPage(
                    day = day,
                    savedAtMs = clockProvider(),
                    page = CatalogPage(items = shows, hasMore = false),
                ),
            )
        }
        return shows
    }

    private suspend fun fetchTvMazeEpisodes(imdbId: String?, title: String): List<MediaEpisode> {
        val showId = if (!imdbId.isNullOrBlank()) {
            tryNonCancellation {
                network.getJson("$baseTvMazeUrl/lookup/shows?imdb=${SearchUtils.encodeUrl(imdbId)}")?.jsonObject
            }?.get("id")?.jsonPrimitive?.intOrNull
        } else {
            null
        } ?: title.trim().takeIf { it.isNotEmpty() }?.let { clean ->
            tryNonCancellation {
                network.getJson("$baseTvMazeUrl/singlesearch/shows?q=${SearchUtils.encodeUrl(clean)}")?.jsonObject
            }?.get("id")?.jsonPrimitive?.intOrNull
        } ?: return emptyList()
        return fetchTvMazeEpisodesByShowId(showId)
    }

    private suspend fun fetchTvMazeEpisodesByShowId(showId: Int): List<MediaEpisode> {
        val arr = tryNonCancellation {
            network.getJson("$baseTvMazeUrl/shows/$showId/episodes?specials=1")?.jsonArray
        } ?: return emptyList()
        return arr.mapNotNull { elem ->
            val ep = elem.jsonObject
            val season = ep["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val num = ep["number"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val title = ep["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Episodio $num"
            val summary = ep["summary"]?.jsonPrimitive?.content?.let(::stripHtmlTags)?.takeIf { it.isNotBlank() }
            val thumb = ep["image"]?.jsonObject?.let { img ->
                img["medium"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
                    ?: img["original"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
            }
            val airDate = ep["airdate"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            MediaEpisode(
                id = "tvmaze:$showId:$season:$num",
                season = season,
                episode = num,
                title = title,
                overview = summary,
                thumbnailUrl = thumb,
                released = airDate,
            )
        }.sortedWith(compareBy<MediaEpisode> { it.season }.thenBy { it.episode })
    }

    private fun stripHtmlTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), "").trim()

    private suspend fun fetchTvMazeFallback(title: String): MediaMetadata? {
        val encoded = SearchUtils.encodeUrl(title)
        val obj = tryNonCancellation {
            network.getJson("$baseTvMazeUrl/singlesearch/shows?q=$encoded")?.jsonObject
        } ?: return null

        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val imageObj = obj["image"]?.jsonObject
        val poster = imageObj?.get("original")?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
            ?: imageObj?.get("medium")?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
        val summary = obj["summary"]?.jsonPrimitive?.content
            ?.let(::stripHtmlTags)
            ?.takeIf { it.isNotBlank() }
        val year = obj["premiered"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull()
        val rating = obj["rating"]?.jsonObject?.get("average")?.jsonPrimitive?.doubleOrNull?.let {
            String.format(java.util.Locale.US, "%.1f", it)
        }
        val genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        val episodes = fetchTvMazeEpisodesByShowId(id)

        return MediaMetadata(
            id = "tvmaze:$id",
            type = "series",
            title = name,
            year = year,
            posterUrl = poster,
            backdropUrl = poster,
            description = summary,
            rating = rating,
            genres = genres,
            episodes = episodes,
        )
    }

    private fun selectBestCandidate(candidates: List<MediaMetadata>, parsed: ParsedMediaTitle): MediaMetadata? {
        if (candidates.isEmpty()) return null
        val cleanLower = parsed.cleanTitle.lowercase()
        return candidates.withIndex().maxByOrNull { (idx, candidate) ->
            var score = (candidates.size - idx).coerceAtLeast(0)
            val candLower = candidate.title.lowercase()
            val origLower = candidate.originalTitle?.lowercase().orEmpty()
            if (candLower == cleanLower || origLower == cleanLower) score += 100
            else if (candLower.contains(cleanLower) || cleanLower.contains(candLower) ||
                (origLower.isNotEmpty() && (origLower.contains(cleanLower) || cleanLower.contains(origLower)))
            ) score += 40
            if (parsed.year != null && candidate.year == parsed.year) score += 50
            if (candidate.posterUrl != null) score += 10
            if (candidate.id.startsWith("tt")) score += 5
            score
        }?.value
    }

    private fun parseCatalogMetas(root: JsonObject, defaultType: String): List<MediaMetadata> {
        val metas = root["metas"]?.jsonArray ?: return emptyList()
        return metas.mapNotNull { elem ->
            runCatching { parseMetaObject(elem.jsonObject, defaultType) }.getOrNull()
        }
    }

    private fun parseMetaObject(obj: JsonObject, defaultType: String): MediaMetadata? {
        val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
        val type = obj["type"]?.jsonPrimitive?.content ?: defaultType
        val year = obj["releaseInfo"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull()
            ?: obj["year"]?.jsonPrimitive?.intOrNull
        val poster = obj["poster"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
        val backdrop = obj["background"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") }
        val description = obj["description"]?.jsonPrimitive?.content
        val rating = obj["imdbRating"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val runtime = obj["runtime"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val genres = obj["genres"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.content.takeIf(String::isNotBlank)
        } ?: emptyList()

        val episodes = obj["videos"]?.jsonArray?.mapNotNull { vidElem ->
            val v = vidElem.jsonObject
            val vidId = v["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val season = v["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val ep = v["episode"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val epTitle = v["title"]?.jsonPrimitive?.content
                ?: v["name"]?.jsonPrimitive?.content
                ?: "Episodio $ep"
            MediaEpisode(
                id = vidId,
                season = season,
                episode = ep,
                title = epTitle,
                overview = v["overview"]?.jsonPrimitive?.content,
                thumbnailUrl = v["thumbnail"]?.jsonPrimitive?.content?.takeIf { it.startsWith("https://") },
                released = v["released"]?.jsonPrimitive?.content,
            )
        }?.sortedWith(compareBy<MediaEpisode> { it.season }.thenBy { it.episode }) ?: emptyList()

        return MediaMetadata(
            id = id,
            type = type,
            title = name,
            year = year,
            posterUrl = poster,
            backdropUrl = backdrop,
            description = description,
            rating = rating,
            runtime = runtime,
            genres = genres,
            episodes = episodes,
        )
    }

    private suspend fun readItemCache(key: String): MediaMetadata? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        if (!file.isFile) return@withContext null
        if (System.currentTimeMillis() - file.lastModified() > ITEM_TTL_MS) return@withContext null
        runCatching { json.decodeFromString<MediaMetadata>(file.readText()) }.getOrNull()
    }

    private suspend fun writeItemCache(key: String, value: MediaMetadata) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val tmp = File(cacheDir, "$key.tmp")
                tmp.writeText(json.encodeToString(value))
                tmp.renameTo(File(cacheDir, "$key.json"))
            }
        }
    }

    private suspend fun readCatalogCache(key: String): List<MediaMetadata>? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        if (!file.isFile) return@withContext null
        if (System.currentTimeMillis() - file.lastModified() > CATALOG_TTL_MS) return@withContext null
        runCatching { json.decodeFromString<List<MediaMetadata>>(file.readText()) }.getOrNull()
    }

    private suspend fun writeCatalogCache(key: String, value: List<MediaMetadata>) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val tmp = File(cacheDir, "$key.tmp")
                tmp.writeText(json.encodeToString(value))
                tmp.renameTo(File(cacheDir, "$key.json"))
            }
        }
    }

    private suspend fun readCatalogPageCache(
        key: String,
        expectedDay: String,
        ttlMs: Long,
    ): CatalogPage? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        if (!file.isFile) return@withContext null
        val cached = runCatching { json.decodeFromString<CachedCatalogPage>(file.readText()) }.getOrNull()
            ?: return@withContext null
        if (cached.day != expectedDay) return@withContext null
        if (clockProvider() - cached.savedAtMs > ttlMs) return@withContext null
        cached.page
    }

    private suspend fun writeCatalogPageCache(key: String, value: CachedCatalogPage) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val tmp = File(cacheDir, "$key.tmp")
                tmp.writeText(json.encodeToString(value))
                tmp.renameTo(File(cacheDir, "$key.json"))
            }
        }
    }

    private suspend fun readNegativeCache(key: String): Long? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.miss")
        if (!file.isFile) return@withContext null
        val ts = runCatching { file.readText().trim().toLongOrNull() }.getOrNull() ?: return@withContext null
        if (clockProvider() - ts > NEGATIVE_TTL_MS) return@withContext null
        ts
    }

    private suspend fun writeNegativeCache(key: String, timestampMs: Long) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                File(cacheDir, "$key.miss").writeText(timestampMs.toString())
            }
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_CINEMETA_URL = "https://v3-cinemeta.strem.io"
        const val DEFAULT_TVMAZE_URL = "https://api.tvmaze.com"
        const val DEFAULT_JUSTWATCH_URL = "https://apis.justwatch.com/graphql"
        const val DEFAULT_TMDB_URL = "https://api.themoviedb.org/3"
        private const val JUSTWATCH_PAGE_SIZE = 30
        private const val MAX_TMDB_SEASONS_ENRICH = 8
        private const val CINEMETA_PAGE_THRESHOLD = 45
        private const val CURSOR_PREFIX_TMDB = "tmdb:"
        private const val CURSOR_PREFIX_JUSTWATCH = "jw:"
        private const val CURSOR_PREFIX_CINEMETA = "cm:"
        private const val DAILY_FEED_TTL_MS = 4L * 3600L * 1000L
        private const val CATALOG_TTL_MS = 6L * 3600L * 1000L
        private const val ITEM_TTL_MS = 7L * 24L * 3600L * 1000L
        private const val NEGATIVE_TTL_MS = 24L * 3600L * 1000L
    }
}
