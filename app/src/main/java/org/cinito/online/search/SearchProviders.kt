package org.cinito.online.search

import android.util.Log
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.putJsonArray
import org.cinito.online.network.HardenedNetworkClient
import org.jsoup.Jsoup
import java.time.Instant

internal const val PROVIDER_LOG_TAG = "SearchProvider"

private fun logUrl(url: String): String = url.substringBefore('?').substringBefore('#')

/** GET de texto con log de host+ruta, código HTTP y bytes. La query (puede contener apikey) no se registra. */
private suspend fun HardenedNetworkClient.httpText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String {
    val path = logUrl(url)
    try {
        val response = get(url, headers)
        val body = response.bodyAsText()
        Log.d(PROVIDER_LOG_TAG, "GET $path status=${response.status.value} bytes=${body.toByteArray().size}")
        return body
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(PROVIDER_LOG_TAG, "GET $path fallo ${describeThrowable(e)}", e)
        throw e
    }
}

/** GET JSON: cuerpo vacío produce null; JSON inválido lanza [ProviderParseException] con los primeros 200 caracteres. */
private suspend fun HardenedNetworkClient.httpJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
): JsonElement? {
    val body = httpText(url, headers)
    if (body.isBlank()) return null
    return try {
        Json.parseToJsonElement(body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(
            PROVIDER_LOG_TAG,
            "parse JSON fallo ${logUrl(url)} ${describeThrowable(e)} body[:200]=${body.take(200)}",
            e,
        )
        throw ProviderParseException("JSON inválido: ${describeThrowable(e, 1)}", e)
    }
}

/** POST JSON con log de host+ruta y fallos. El código HTTP no está disponible desde HardenedNetworkClient.postJson. */
private suspend fun HardenedNetworkClient.httpPostJson(url: String, payload: JsonElement): JsonElement? {
    val path = logUrl(url)
    Log.d(PROVIDER_LOG_TAG, "POST $path")
    try {
        return postJson(url, payload)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(PROVIDER_LOG_TAG, "POST $path fallo ${describeThrowable(e)}", e)
        throw e
    }
}

/** Interpreta el cuerpo de una respuesta; un fallo se registra con los primeros 200 caracteres y se relanza como [ProviderParseException]. */
private inline fun <T> parseLogged(url: String, body: String, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(
            PROVIDER_LOG_TAG,
            "parse fallo ${logUrl(url)} ${describeThrowable(e)} body[:200]=${body.take(200)}",
            e,
        )
        throw ProviderParseException("Respuesta inválida: ${describeThrowable(e, 1)}", e)
    }

private fun parseEpochOrNull(raw: String): Long? =
    try {
        Instant.parse(raw).epochSecond
    } catch (e: Exception) {
        Log.d(PROVIDER_LOG_TAG, "fecha ISO no parseable: ${describeThrowable(e, 1)}")
        null
    }

private fun parseRfc1123OrNull(raw: String): Long? =
    try {
        java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
    } catch (e: Exception) {
        Log.d(PROVIDER_LOG_TAG, "fecha RFC 1123 no parseable: ${describeThrowable(e, 1)}")
        null
    }

class YtsProvider(
    private val network: HardenedNetworkClient,
    private val apiBaseUrl: String = API_BASE,
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "yts",
        name = "YTS",
        url = "https://yts.bz",
        categories = setOf(SearchCategory.Movies),
        enabledByDefault = true,
        supportsNonEnglishQueries = false,
        supportsImdbSearch = true,
        requiresImdbSearch = false,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Movies) return emptyList()
        val encoded = SearchUtils.encodeUrl(query)
        val json = network.httpJson("$apiBaseUrl/list_movies.json?query_term=$encoded&limit=35") ?: return emptyList()
        return parseMovies(json.jsonObject)
    }

    override suspend fun searchByImdb(
        imdbId: String,
        category: SearchCategory,
        season: Int?,
        episode: Int?,
        mediaTypeHint: String?,
    ): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Movies) return emptyList()
        val cleanImdb = imdbId.trim().lowercase()
        if (!IMDB_REGEX.matches(cleanImdb)) return emptyList()
        val json = network.httpJson("$apiBaseUrl/list_movies.json?query_term=${SearchUtils.encodeUrl(cleanImdb)}&limit=35")
            ?: return emptyList()
        return parseMovies(json.jsonObject)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Movies) return emptyList()
        val json = network.httpJson("$apiBaseUrl/list_movies.json?sort_by=download_count&limit=25") ?: return emptyList()
        return parseMovies(json.jsonObject)
    }

    private suspend fun parseMovies(root: JsonObject): List<TorrentSearchResult> = withContext(Dispatchers.Default) {
        val movies = root["data"]?.jsonObject?.get("movies")?.jsonArray ?: return@withContext emptyList()
        movies.flatMap { movieElem ->
            val m = movieElem.jsonObject
            val titleLong = m["title_long"]?.jsonPrimitive?.content ?: return@flatMap emptyList()
            val poster = m["medium_cover_image"]?.jsonPrimitive?.content
            val imdbId = m["imdb_code"]?.jsonPrimitive?.content
            val detailsUrl = m["url"]?.jsonPrimitive?.content
            val torrents = m["torrents"]?.jsonArray ?: return@flatMap emptyList()
            torrents.mapNotNull { tElem ->
                val t = tElem.jsonObject
                val hash = t["hash"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val quality = t["quality"]?.jsonPrimitive?.content.orEmpty()
                val type = t["type"]?.jsonPrimitive?.content.orEmpty()
                val codec = t["video_codec"]?.jsonPrimitive?.content.orEmpty()
                val label = "$titleLong [$quality] [$type] [$codec]".replace("  ", " ").trim()
                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = label,
                    magnetUri = SearchUtils.buildMagnet(hash, label),
                    sizeText = t["size"]?.jsonPrimitive?.content,
                    sizeBytes = t["size_bytes"]?.jsonPrimitive?.longOrNull,
                    seeders = t["seeds"]?.jsonPrimitive?.intOrNull ?: 0,
                    peers = t["peers"]?.jsonPrimitive?.intOrNull ?: 0,
                    uploadDateEpochSec = t["date_uploaded_unix"]?.jsonPrimitive?.longOrNull,
                    category = SearchCategory.Movies,
                    posterUrl = poster,
                    imdbId = imdbId,
                    detailsUrl = detailsUrl,
                )
            }
        }
    }

    companion object {
        private const val API_BASE = "https://movies-api.accel.li/api/v2"
        private val IMDB_REGEX = Regex("""^tt\d{5,10}$""", RegexOption.IGNORE_CASE)
    }
}

class KnabenProvider(private val network: HardenedNetworkClient) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "knaben",
        name = "Knaben",
        url = "https://knaben.org",
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other, SearchCategory.Adult),
        enabledByDefault = true,
        supportsAdultContent = true,
        hasStrictAdultCategory = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> =
        search(query, category, SearchRequestOptions())

    override suspend fun search(
        query: String,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> {
        val payload = buildRequest(query.trim(), category, "seeders", options)
        val json = network.httpPostJson("$API_URL/v1", payload) ?: return emptyList()
        return parseHits(json.jsonObject, options)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        val options = SearchRequestOptions()
        val payload = buildRequest(null, category, "seeders", options)
        val json = network.httpPostJson("$API_URL/v1", payload) ?: return emptyList()
        return parseHits(json.jsonObject, options)
    }

    private fun buildRequest(
        query: String?,
        category: SearchCategory,
        orderBy: String,
        options: SearchRequestOptions,
    ): JsonObject = buildJsonObject {
        val isAdultOnly = options.adultOnly || category == SearchCategory.Adult
        query?.takeIf { it.isNotBlank() }?.let { put("query", JsonPrimitive(it)) }
        put("size", JsonPrimitive(100))
        put("order_by", JsonPrimitive(orderBy))
        put("order_direction", JsonPrimitive("desc"))
        put("hide_unsafe", JsonPrimitive(true))
        put("hide_xxx", JsonPrimitive(if (isAdultOnly) false else options.hideAdultContent))
        val catIds = if (isAdultOnly) {
            listOf(5000000)
        } else {
            when (category) {
                SearchCategory.Movies -> listOf(2000000, 3000000, 6000000)
                SearchCategory.Series -> listOf(2000000, 6000000)
                SearchCategory.Anime -> listOf(6000000)
                SearchCategory.Other -> if (!options.hideAdultContent) listOf(10000000, 5000000) else listOf(10000000)
                SearchCategory.Adult -> listOf(5000000)
                SearchCategory.All -> when {
                    options.broadGeneralSearch && !options.hideAdultContent -> listOf(2000000, 3000000, 6000000, 10000000, 5000000)
                    options.broadGeneralSearch -> listOf(2000000, 3000000, 6000000, 10000000)
                    else -> listOf(2000000, 3000000, 6000000)
                }
            }
        }
        putJsonArray("categories") {
            catIds.forEach { add(JsonPrimitive(it)) }
        }
    }

    private suspend fun parseHits(
        root: JsonObject,
        options: SearchRequestOptions = SearchRequestOptions(),
    ): List<TorrentSearchResult> = withContext(Dispatchers.Default) {
        val hits = root["hits"]?.jsonArray ?: return@withContext emptyList()
        hits.mapNotNull { elem ->
            val obj = elem.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (SearchUtils.isExecutableSpam(title)) return@mapNotNull null
            val magnet = obj["magnetUrl"]?.jsonPrimitive?.content?.takeIf { it.startsWith("magnet:") }
                ?: obj["hash"]?.jsonPrimitive?.content?.let { SearchUtils.buildMagnet(it, title) }
                ?: return@mapNotNull null
            val catId = (obj["categoryId"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }?.minOrNull()
            if (options.adultOnly) {
                if (catId !in 5000000L..5999999L) return@mapNotNull null
            } else if (options.hideAdultContent && catId in 5000000L..5999999L) {
                return@mapNotNull null
            }
            val cat = when (catId) {
                in 2000000L..2999999L -> SearchCategory.Series
                in 3000000L..3999999L -> SearchCategory.Movies
                in 5000000L..5999999L -> SearchCategory.Adult
                in 6000000L..6999999L -> SearchCategory.Anime
                else -> SearchCategory.Other
            }
            val epoch = obj["date"]?.jsonPrimitive?.content?.let { parseEpochOrNull(it) }
            val rawDesc = obj["description"]?.jsonPrimitive?.content
            val plainDesc = rawDesc?.takeIf { it.isNotBlank() }?.let { Jsoup.parse(it).text().trim() }
            SearchUtils.makeResult(
                providerId = info.id,
                providerName = info.name,
                title = title,
                magnetUri = magnet,
                sizeBytes = obj["bytes"]?.jsonPrimitive?.longOrNull,
                seeders = obj["liveSeeders"]?.jsonPrimitive?.intOrNull ?: obj["seeders"]?.jsonPrimitive?.intOrNull ?: 0,
                peers = obj["livePeers"]?.jsonPrimitive?.intOrNull ?: obj["peers"]?.jsonPrimitive?.intOrNull ?: 0,
                uploadDateEpochSec = epoch,
                category = cat,
                detailsUrl = obj["details"]?.jsonPrimitive?.content,
                description = plainDesc,
            )
        }
    }

    companion object {
        private const val API_URL = "https://api.knaben.org"
    }
}

class TorrentsCsvProvider(private val network: HardenedNetworkClient) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "torrentscsv",
        name = "TorrentsCSV",
        url = "https://torrents-csv.com",
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other),
        enabledByDefault = true,
        supportsAdultContent = false,
        hasStrictAdultCategory = false,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val encoded = SearchUtils.encodeUrl(query)
        val json = network.httpJson("${info.url}/service/search?q=$encoded") ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val arr = json.jsonObject["torrents"]?.jsonArray ?: return@withContext emptyList()
            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val hash = obj["infohash"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = name,
                    magnetUri = SearchUtils.buildMagnet(hash, name),
                    sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull,
                    seeders = obj["seeders"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0L..1_000_000L }?.toInt() ?: 0,
                    peers = obj["leechers"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0L..100_000L }?.toInt() ?: 0,
                    uploadDateEpochSec = obj["created_unix"]?.jsonPrimitive?.longOrNull,
                    category = SearchCategory.Other,
                )
            }
        }
    }
}

class NyaaProvider(private val network: HardenedNetworkClient) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "nyaa",
        name = "Nyaa",
        url = "https://nyaa.si",
        categories = setOf(SearchCategory.Anime, SearchCategory.Series),
        enabledByDefault = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Anime && category != SearchCategory.Series) {
            return emptyList()
        }
        val c = when (category) {
            SearchCategory.Anime -> "1_0"
            SearchCategory.Series -> "0_0"
            else -> "1_0"
        }
        val encoded = SearchUtils.encodeUrl(query)
        val xml = network.httpText("${info.url}/?page=rss&f=0&c=$c&s=seeders&o=desc&q=$encoded")
        return parseLogged(info.url, xml) { parseNyaaRss(xml, info.url, info.id, info.name, category) }
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Anime) return emptyList()
        val xml = network.httpText("${info.url}/?page=rss&c=1_0&s=seeders&o=desc")
        return parseLogged(info.url, xml) { parseNyaaRss(xml, info.url, info.id, info.name, SearchCategory.Anime) }
    }
}

class SukebeiProvider(
    private val network: HardenedNetworkClient,
    private val baseUrl: String = "https://sukebei.nyaa.net",
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "sukebei",
        name = "Sukebei",
        url = baseUrl,
        categories = setOf(SearchCategory.Adult),
        enabledByDefault = true,
        supportsAdultContent = true,
        hasStrictAdultCategory = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> =
        search(query, category, SearchRequestOptions())

    override suspend fun search(
        query: String,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> {
        if (category != SearchCategory.Adult && !options.adultOnly && category != SearchCategory.All) {
            return emptyList()
        }
        if (options.hideAdultContent && !options.adultOnly && category != SearchCategory.Adult) {
            return emptyList()
        }
        val encoded = SearchUtils.encodeUrl(query)
        val xml = network.httpText("$baseUrl/?page=rss&f=0&c=0_0&s=seeders&o=desc&q=$encoded")
        return parseLogged(baseUrl, xml) { parseNyaaRss(xml, baseUrl, info.id, info.name, SearchCategory.Adult) }
    }
}

class TheRarBgProvider(
    private val network: HardenedNetworkClient,
    private val baseUrl: String = "https://therarbg.com",
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "therarbg",
        name = "TheRarBg",
        url = baseUrl,
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other, SearchCategory.Adult),
        enabledByDefault = true,
        supportsAdultContent = true,
        hasStrictAdultCategory = true,
    )

    private val parser = TorznabXmlParser(info.id, info.name)

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> =
        search(query, category, SearchRequestOptions())

    override suspend fun search(
        query: String,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> {
        val isAdultOnly = options.adultOnly || category == SearchCategory.Adult
        val cat = if (isAdultOnly) {
            "6000"
        } else {
            when (category) {
                SearchCategory.Movies -> "2000"
                SearchCategory.Series -> "5000"
                SearchCategory.Anime -> "5070"
                SearchCategory.Adult -> "6000"
                else -> "2000,5000"
            }
        }
        val encoded = SearchUtils.encodeUrl(query)
        val xml = network.httpText("$baseUrl/api/torznab?t=search&q=$encoded&cat=$cat")
        val parsed = parseLogged(baseUrl, xml) { parser.parse(xml) }
        return when {
            isAdultOnly -> parsed.filter { it.category == SearchCategory.Adult || it.category == SearchCategory.Other }
            options.hideAdultContent -> parsed.filterNot { it.category == SearchCategory.Adult || SearchUtils.isAdultContent(it.title) }
            else -> parsed
        }
    }
}

private suspend fun parseNyaaRss(
    xml: String,
    baseUrl: String,
    providerId: String,
    providerName: String,
    defaultCat: SearchCategory,
): List<TorrentSearchResult> = withContext(Dispatchers.Default) {
    if (xml.isBlank()) return@withContext emptyList()
    val doc = Jsoup.parse(xml, baseUrl, org.jsoup.parser.Parser.xmlParser())
    doc.select("channel > item").mapNotNull { item ->
        val title = item.selectFirst("title")?.ownText()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (SearchUtils.isExecutableSpam(title)) return@mapNotNull null
        val hash = item.select("nyaa|infoHash").firstOrNull()?.ownText()?.trim()?.lowercase()
            ?.takeIf { it.length == 40 || it.length == 32 }
            ?: return@mapNotNull null
        val sizeText = item.select("nyaa|size").firstOrNull()?.ownText()?.trim()
        val seeders = item.select("nyaa|seeders").firstOrNull()?.ownText()?.trim()?.toIntOrNull() ?: 0
        val peers = item.select("nyaa|leechers").firstOrNull()?.ownText()?.trim()?.toIntOrNull() ?: 0
        val detailsUrl = item.selectFirst("guid")?.ownText()?.trim()?.takeIf { it.startsWith("https://") }
            ?: item.selectFirst("link")?.ownText()?.trim()?.takeIf { it.startsWith("https://") }
        val pubDate = item.selectFirst("pubDate")?.ownText()?.trim()?.let { raw ->
            parseRfc1123OrNull(raw)
        }
        SearchUtils.makeResult(
            providerId = providerId,
            providerName = providerName,
            title = title,
            magnetUri = SearchUtils.buildMagnet(hash, title),
            sizeText = sizeText,
            seeders = seeders,
            peers = peers,
            uploadDateEpochSec = pubDate,
            category = if (defaultCat == SearchCategory.All) SearchCategory.Anime else defaultCat,
            detailsUrl = detailsUrl,
        )
    }
}

class InternetArchiveProvider(private val network: HardenedNetworkClient) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "internetarchive",
        name = "Internet Archive",
        url = "https://archive.org",
        categories = setOf(SearchCategory.Movies, SearchCategory.Other),
        enabledByDefault = false,
        supportsNonEnglishQueries = false,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Movies && category != SearchCategory.Other) {
            return emptyList()
        }
        val encoded = SearchUtils.encodeUrl(query)
        val mediatypeClause = if (category == SearchCategory.Movies) "%20AND%20mediatype%3Amovies" else ""
        val reqUrl = "${info.url}/advancedsearch.php?q=title:($encoded)$mediatypeClause&fl[]=title,item_size,publicdate,mediatype,identifier,btih&rows=40&page=1&output=json"
        val json = network.httpJson(reqUrl) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val docs = json.jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray ?: return@withContext emptyList()
            docs.mapNotNull { elem ->
                val obj = elem.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val hash = obj["btih"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val identifier = obj["identifier"]?.jsonPrimitive?.content
                val epoch = obj["publicdate"]?.jsonPrimitive?.content?.let { parseEpochOrNull(it) }
                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = title,
                    magnetUri = SearchUtils.buildMagnet(hash, title),
                    sizeBytes = obj["item_size"]?.jsonPrimitive?.longOrNull,
                    seeders = 1,
                    peers = 1,
                    uploadDateEpochSec = epoch,
                    category = SearchCategory.Movies,
                    posterUrl = identifier?.let { "https://archive.org/services/img/$it" },
                    detailsUrl = identifier?.let { "https://archive.org/details/$it" },
                )
            }
        }
    }
}

class SubsPleaseProvider(private val network: HardenedNetworkClient) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "subsplease",
        name = "SubsPlease",
        url = "https://subsplease.org",
        categories = setOf(SearchCategory.Anime),
        enabledByDefault = false,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Anime) return emptyList()
        val encoded = SearchUtils.encodeUrl(query)
        val json = network.httpJson("${info.url}/api/?f=search&tz=UTC&s=$encoded") ?: return emptyList()
        return parseSubsPlease(json)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Anime) return emptyList()
        val json = network.httpJson("${info.url}/api/?f=latest&tz=UTC") ?: return emptyList()
        return parseSubsPlease(json)
    }

    private suspend fun parseSubsPlease(jsonElem: kotlinx.serialization.json.JsonElement): List<TorrentSearchResult> =
        withContext(Dispatchers.Default) {
            val obj = jsonElem as? JsonObject ?: run {
                Log.w(PROVIDER_LOG_TAG, "subsplease: la raíz JSON no es un objeto (${jsonElem::class.simpleName})")
                return@withContext emptyList()
            }
            obj.entries.flatMap { (showName, rawVal) ->
                val show = rawVal as? JsonObject ?: run {
                    Log.d(PROVIDER_LOG_TAG, "subsplease: entrada omitida, no es un objeto (${rawVal::class.simpleName})")
                    return@flatMap emptyList()
                }
                val downloads = show["downloads"]?.jsonArray ?: return@flatMap emptyList()
                downloads.mapNotNull { dlElem ->
                    val dl = dlElem.jsonObject
                    val magnet = dl["magnet"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val res = dl["res"]?.jsonPrimitive?.content.orEmpty()
                    val title = if (res.isNotBlank()) "$showName [${res}p]" else showName
                    SearchUtils.makeResult(
                        providerId = info.id,
                        providerName = info.name,
                        title = title,
                        magnetUri = magnet,
                        category = SearchCategory.Anime,
                    )
                }
            }
        }
}

class TorznabSearchProvider(
    private val config: TorznabConfig,
    private val network: HardenedNetworkClient,
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = config.id,
        name = config.name,
        url = config.url,
        categories = config.categories,
        isTorznab = true,
        enabledByDefault = true,
        supportsImdbSearch = true,
        requiresImdbSearch = false,
        supportsAdultContent = SearchCategory.Adult in config.categories,
        hasStrictAdultCategory = SearchCategory.Adult in config.categories,
    )
    private val parser = TorznabXmlParser(info.id, info.name)

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> =
        search(query, category, SearchRequestOptions())

    override suspend fun searchByImdb(
        imdbId: String,
        category: SearchCategory,
        season: Int?,
        episode: Int?,
        mediaTypeHint: String?,
    ): List<TorrentSearchResult> {
        val cleanImdb = imdbId.trim().lowercase()
        if (!IMDB_REGEX.matches(cleanImdb)) return emptyList()
        val baseUrl = config.url.trim().trimEnd('/')
        val apiEndpoint = if (baseUrl.endsWith("/api")) baseUrl else "$baseUrl/api"
        val isSeries = mediaTypeHint == "series" || category == SearchCategory.Series || episode != null
        val reqUrl = if (isSeries) {
            buildString {
                append("$apiEndpoint?apikey=${SearchUtils.encodeUrl(config.apiKey)}&extended=1&t=tvsearch&imdbid=$cleanImdb&cat=5000,5030,5040,5045,5070")
                if (season != null) append("&season=$season")
                if (episode != null) append("&ep=$episode")
            }
        } else {
            "$apiEndpoint?apikey=${SearchUtils.encodeUrl(config.apiKey)}&extended=1&t=movie&imdbid=$cleanImdb&cat=2000,2030,2040,2045"
        }
        val xml = network.httpText(reqUrl)
        return parseLogged(reqUrl, xml) { parser.parse(xml) }
    }

    override suspend fun search(
        query: String,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> {
        val baseUrl = config.url.trim().trimEnd('/')
        val apiEndpoint = if (baseUrl.endsWith("/api")) baseUrl else "$baseUrl/api"
        val encoded = SearchUtils.encodeUrl(query)
        val isAdultOnly = options.adultOnly || category == SearchCategory.Adult
        val catParam = if (isAdultOnly) {
            "&cat=6000"
        } else {
            when (category) {
                SearchCategory.Movies -> "&cat=2000"
                SearchCategory.Series -> "&cat=5000"
                SearchCategory.Anime -> "&cat=5070"
                else -> ""
            }
        }
        val reqUrl = "$apiEndpoint?apikey=${SearchUtils.encodeUrl(config.apiKey)}&extended=1&t=search&q=$encoded$catParam"
        val xml = network.httpText(reqUrl)
        val parsed = parseLogged(reqUrl, xml) { parser.parse(xml) }
        return when {
            isAdultOnly -> parsed.filter {
                it.category == SearchCategory.Adult || SearchUtils.isAdultContent(it.title, it.description)
            }
            options.hideAdultContent -> parsed.filterNot {
                it.category == SearchCategory.Adult || SearchUtils.isAdultContent(it.title, it.description)
            }
            else -> parsed
        }
    }

    companion object {
        private val IMDB_REGEX = Regex("""^tt\d{5,10}$""", RegexOption.IGNORE_CASE)
    }
}

class ThePirateBayProvider(
    private val network: HardenedNetworkClient,
    private val apiBaseUrl: String = DEFAULT_API_URL,
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "thepiratebay",
        name = "ThePirateBay",
        url = "https://thepiratebay.org",
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other, SearchCategory.Adult),
        enabledByDefault = true,
        supportsAdultContent = true,
        hasStrictAdultCategory = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> =
        search(query, category, SearchRequestOptions())

    override suspend fun search(
        query: String,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> {
        val isAdultOnly = options.adultOnly || category == SearchCategory.Adult
        val cat = if (isAdultOnly) {
            "500"
        } else {
            when (category) {
                // Exclude 210 (CAM/TS) from movie & non-broad video queries
                SearchCategory.Movies -> "201,202,204,207,209"
                SearchCategory.Series -> "205,208"
                SearchCategory.Anime -> "0"
                SearchCategory.Other -> "600"
                SearchCategory.Adult -> "500"
                SearchCategory.All -> if (options.broadGeneralSearch) "0" else "201,202,203,204,205,207,208,209,299"
            }
        }
        val encoded = SearchUtils.encodeUrl(query)
        val elem = network.httpJson("$apiBaseUrl/q.php?q=$encoded&cat=$cat") ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val arr = elem as? JsonArray ?: run {
                Log.w(PROVIDER_LOG_TAG, "tpb: la respuesta no es un arreglo JSON (${elem::class.simpleName})")
                return@withContext emptyList()
            }
            arr.mapNotNull { item ->
                val obj = item.jsonObject
                val title = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (title == "No results returned" || SearchUtils.isExecutableSpam(title)) return@mapNotNull null
                val hash = obj["info_hash"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: return@mapNotNull null
                if (hash.all { it == '0' }) return@mapNotNull null
                val recordId = obj["id"]?.jsonPrimitive?.content
                val catCode = obj["category"]?.jsonPrimitive?.content?.toIntOrNull()
                if (isAdultOnly) {
                    if (catCode != null && catCode !in 500..599) return@mapNotNull null
                } else if (options.hideAdultContent && catCode in 500..599) {
                    return@mapNotNull null
                }
                if (!isAdultOnly && category == SearchCategory.Series && catCode != null && catCode !in TV_CATEGORY_CODES) {
                    return@mapNotNull null
                }
                val mappedCat = when (catCode) {
                    in TV_CATEGORY_CODES -> SearchCategory.Series
                    in 200..299 -> SearchCategory.Movies
                    in 500..599 -> SearchCategory.Adult
                    else -> SearchCategory.Other
                }
                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = title,
                    magnetUri = SearchUtils.buildMagnet(hash, title),
                    sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull(),
                    seeders = obj["seeders"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    peers = obj["leechers"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    uploadDateEpochSec = obj["added"]?.jsonPrimitive?.content?.toLongOrNull(),
                    category = mappedCat,
                    imdbId = obj["imdb"]?.jsonPrimitive?.content?.takeIf { it.startsWith("tt") },
                    detailsUrl = recordId?.let { "${info.url}/description.php?id=$it" },
                    sourceRecordId = recordId,
                )
            }
        }
    }

    override suspend fun enrichResult(result: TorrentSearchResult): TorrentSearchResult {
        val recId = result.sourceRecordId ?: return result
        val obj = try {
            network.httpJson("$apiBaseUrl/t.php?id=$recId")?.jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PROVIDER_LOG_TAG, "tpb: enrich fallo ${describeThrowable(e)}", e)
            null
        } ?: return result

        val descr = obj["descr"]?.jsonPrimitive?.content?.trim()
        val lang = obj["language"]?.jsonPrimitive?.content?.trim().orEmpty()
        val textLang = obj["textlanguage"]?.jsonPrimitive?.content?.trim().orEmpty()
        val imdb = obj["imdb"]?.jsonPrimitive?.content?.takeIf { it.startsWith("tt") } ?: result.imdbId
        val audioField = lang.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
        val subtitleField = textLang.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }

        val reclassified = org.cinito.online.metadata.MediaLanguageClassifier.classify(
            title = result.title,
            description = descr?.takeIf { it.isNotBlank() },
            providerLanguageField = audioField,
            subtitleLanguageField = subtitleField,
        )
        return result.copy(
            description = descr?.takeIf { it.isNotBlank() } ?: result.description,
            imdbId = imdb,
            languageTag = reclassified.tag,
            languageEvidence = reclassified.matchedEvidence ?: result.languageEvidence,
        )
    }

    companion object {
        private const val DEFAULT_API_URL = "https://apibay.org"
        private val TV_CATEGORY_CODES = setOf(205, 208)
    }
}

class BitSearchProvider(
    private val network: HardenedNetworkClient,
    private val baseUrl: String = "https://bitsearch.to",
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "bitsearch",
        name = "BitSearch",
        url = baseUrl,
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other),
        enabledByDefault = true,
        maxQueriesPerSearch = 1,
        supportsAdultContent = false,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val catParam = when (category) {
            SearchCategory.Anime -> "&category=4"
            else -> ""
        }
        val encoded = SearchUtils.encodeUrl(query)
        val html = network.httpText("$baseUrl/search?q=$encoded&page=1&sortBy=seeders$catParam")
        return parseHtml(html, category)
    }

    private suspend fun parseHtml(html: String, defaultCat: SearchCategory): List<TorrentSearchResult> =
        withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html, baseUrl)
            val cards = doc.select("div.torrent-result-card").ifEmpty {
                doc.select("div.space-y-4 > div")
            }
            cards.mapNotNull { card ->
                val magnet = card.selectFirst("""a[href^="magnet:?"]""")?.attr("href") ?: return@mapNotNull null
                val titleEl = card.selectFirst("h3 a") ?: card.selectFirst("h3") ?: return@mapNotNull null
                val title = titleEl.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (SearchUtils.isExecutableSpam(title)) return@mapNotNull null
                val detailsUrl = titleEl.attr("abs:href").takeIf { it.startsWith("https://") }
                val cardText = card.text()
                val sizeText = card.selectFirst("div.torrent-result-meta i.fa-download ~ span")
                    ?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: SIZE_REGEX.find(cardText)?.value?.trim()
                val seeders = card.selectFirst("span.text-green-600 i.fa-arrow-up ~ span, i.fa-arrow-up ~ span")
                    ?.ownText()?.replace(",", "")?.toIntOrNull()
                    ?: SEEDERS_REGEX.find(cardText)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
                    ?: 0
                val peers = card.selectFirst("span.text-red-600 i.fa-arrow-down ~ span, i.fa-arrow-down ~ span")
                    ?.ownText()?.replace(",", "")?.toIntOrNull()
                    ?: LEECHERS_REGEX.find(cardText)?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
                    ?: 0

                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = title,
                    magnetUri = magnet,
                    sizeText = sizeText,
                    seeders = seeders,
                    peers = peers,
                    category = defaultCat,
                    detailsUrl = detailsUrl,
                )
            }
        }

    companion object {
        private val SIZE_REGEX = Regex("""\b\d+(?:[.,]\d+)?\s*(?:GiB|MiB|KiB|TiB|GB|MB|KB|TB)\b""", RegexOption.IGNORE_CASE)
        private val SEEDERS_REGEX = Regex("""(\d[\d,]*)\s*seeders?""", RegexOption.IGNORE_CASE)
        private val LEECHERS_REGEX = Regex("""(\d[\d,]*)\s*leechers?""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Stremio/Torrentio open aggregator indexing Cinecalidad, 1337x, TorrentGalaxy, MagnetDL & TPB
 * with explicit `language=latino` prioritization and 🇲🇽 / 🇪🇸 / Dual-Audio markers.
 */
class TorrentioProvider(
    private val network: HardenedNetworkClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "torrentio",
        name = "Torrentio · Cinecalidad+",
        url = baseUrl,
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime),
        enabledByDefault = true,
        supportsNonEnglishQueries = true,
        supportsImdbSearch = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val trimmed = query.trim()
        val imdbMatch = IMDB_REGEX.find(trimmed) ?: return emptyList()
        return searchByImdb(imdbMatch.value.lowercase(), category)
    }

    override suspend fun searchByImdb(
        imdbId: String,
        category: SearchCategory,
        season: Int?,
        episode: Int?,
        mediaTypeHint: String?,
    ): List<TorrentSearchResult> {
        val cleanImdb = imdbId.trim().lowercase()
        if (!IMDB_REGEX.matches(cleanImdb)) return emptyList()

        val paths = when {
            mediaTypeHint == "series" || category == SearchCategory.Series -> {
                if (episode == null) return emptyList()
                listOf("series/$cleanImdb:${season ?: 1}:$episode.json")
            }
            mediaTypeHint == "movie" || category == SearchCategory.Movies -> listOf(
                "movie/$cleanImdb.json",
            )
            else -> buildList {
                add("movie/$cleanImdb.json")
                if (episode != null) add("series/$cleanImdb:${season ?: 1}:$episode.json")
            }
        }

        val collected = mutableListOf<TorrentSearchResult>()
        for (path in paths) {
            val url = "$baseUrl/sort=seeders|qualityfilter=scr,cam|language=latino/stream/$path"
            val obj = try {
                network.httpJson(url)?.jsonObject
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PROVIDER_LOG_TAG, "torrentio: stream omitido ${describeThrowable(e)}", e)
                null
            } ?: continue
            val parsed = parseStreams(obj, cleanImdb, if (path.startsWith("series/")) SearchCategory.Series else SearchCategory.Movies)
            collected += parsed
            if (parsed.isNotEmpty() && mediaTypeHint == null && category == SearchCategory.All) {
                break
            }
        }
        return collected
    }

    private suspend fun parseStreams(
        root: JsonObject,
        imdbId: String,
        category: SearchCategory,
    ): List<TorrentSearchResult> = withContext(Dispatchers.Default) {
        val streams = root["streams"]?.jsonArray ?: return@withContext emptyList()
        streams.take(60).mapNotNull { elem ->
            val obj = elem.jsonObject
            val hash = obj["infoHash"]?.jsonPrimitive?.content?.trim()?.lowercase()
                ?.takeIf { it.length == 40 || it.length == 32 } ?: return@mapNotNull null
            val rawTitle = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val rawName = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val fileIdx = obj["fileIdx"]?.jsonPrimitive?.intOrNull

            val lines = rawTitle.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val releaseName = lines.firstOrNull() ?: return@mapNotNull null
            if (SearchUtils.isExecutableSpam(releaseName)) return@mapNotNull null

            val seeders = SEEDER_REGEX.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val sizeText = SIZE_REGEX.find(rawTitle)?.groupValues?.getOrNull(1)?.trim()
            val upstream = UPSTREAM_REGEX.find(rawTitle)?.groupValues?.getOrNull(1)?.trim()
            val qualityBadge = rawName.lineSequence().drop(1).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val extraFileLine = lines.getOrNull(1)?.takeIf { !it.startsWith("👤") && !FLAG_ONLY_REGEX.matches(it) }
            val flagLine = lines.lastOrNull()?.takeIf { it.contains("🇲🇽") || it.contains("🇪🇸") || it.contains("Dual Audio") }
            val streamCueText = "$rawName\n$rawTitle"
            val isSubbedOnlyStream = SUB_ONLY_STREAM_REGEX.containsMatchIn(streamCueText) &&
                !AUDIO_STREAM_REGEX.containsMatchIn(streamCueText)
            val audioFlagField = flagLine.takeUnless { isSubbedOnlyStream && it != null && !it.contains("Dual Audio", ignoreCase = true) }
            val subtitleFlagField = flagLine.takeIf { audioFlagField == null }

            val displayTitle = if (qualityBadge != null && !releaseName.contains(qualityBadge, ignoreCase = true)) {
                "$releaseName [$qualityBadge]"
            } else {
                releaseName
            }
            val descParts = listOfNotNull(
                audioFlagField?.let { "Audio: $it" } ?: subtitleFlagField?.let { "Subtítulos: $it" },
                extraFileLine?.takeIf { !it.equals(releaseName, ignoreCase = true) },
            )

            SearchUtils.makeResult(
                providerId = info.id,
                providerName = upstream?.let { "Torrentio · $it" } ?: "Torrentio",
                title = displayTitle,
                magnetUri = SearchUtils.buildMagnet(hash, releaseName),
                sizeText = sizeText,
                seeders = seeders,
                peers = 0,
                category = category,
                imdbId = imdbId,
                description = descParts.joinToString(" · "),
                providerLanguageField = audioFlagField,
                subtitleLanguageField = subtitleFlagField,
            )
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://torrentio.strem.fun"
        private val IMDB_REGEX = Regex("""^tt\d{5,10}$""", RegexOption.IGNORE_CASE)
        private val SEEDER_REGEX = Regex("""👤\s*(\d+)""")
        private val SIZE_REGEX = Regex("""💾\s*([\d.,]+\s*(?:TiB|GiB|MiB|KiB|TB|GB|MB|KB))""", RegexOption.IGNORE_CASE)
        private val UPSTREAM_REGEX = Regex("""⚙️\s*([^\n]+)""")
        private val FLAG_ONLY_REGEX = Regex("""^(?:Dual Audio|Dubbed|Subbed|[\s/🇲🇽🇪🇸🇬🇧🇮🇹🇫🇷🇩🇪🇯🇵🇵🇹🇧🇷]+)$""")
        private val SUB_ONLY_STREAM_REGEX = Regex("""(?i)(?:^|[\s.\-_(\[/,|])(subbed|vose|hardcoded[\s._-]*sub|hc[\s._-]*sub|sub[\s._-]*(?:esp|lat|only)|subtitulad[oa])(?:$|[\s.\-_)\]/,|])""")
        private val AUDIO_STREAM_REGEX = Regex("""(?i)(?:^|[\s.\-_(\[/,|])(dual[\s._-]*audio|dubbed|doblaje|doblado|audio[\s._-]*lat(?:ino)?|latino|latam|castellano|lat|dub)(?:$|[\s.\-_)\]/,|])""")
    }
}

class SolidTorrentsProvider(
    private val network: HardenedNetworkClient,
    private val baseUrl: String = "https://bitsearch.eu",
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "solidtorrents",
        name = "SolidTorrents",
        url = baseUrl,
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other, SearchCategory.Adult),
        enabledByDefault = true,
        supportsNonEnglishQueries = true,
        supportsAdultContent = true,
        hasStrictAdultCategory = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> =
        search(query, category, SearchRequestOptions())

    override suspend fun search(
        query: String,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> {
        val isAdultOnly = options.adultOnly || category == SearchCategory.Adult
        val encoded = SearchUtils.encodeUrl(query)
        val catParam = if (isAdultOnly) "&category=10" else ""
        val root = network.httpJson("$baseUrl/api/v1/search?q=$encoded$catParam&sort=seeders")?.jsonObject
            ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val arr = root["results"]?.jsonArray ?: return@withContext emptyList()
            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
                if (title.isEmpty() || SearchUtils.isExecutableSpam(title)) return@mapNotNull null
                val hash = obj["infohash"]?.jsonPrimitive?.content?.trim()?.lowercase()
                    ?.takeIf { it.length == 40 || it.length == 32 } ?: return@mapNotNull null
                val catNum = obj["category"]?.jsonPrimitive?.intOrNull
                val mappedCat = if (isAdultOnly || catNum == 10) {
                    SearchCategory.Adult
                } else {
                    when (catNum) {
                        3 -> SearchCategory.Series
                        4 -> SearchCategory.Anime
                        else -> if (category == SearchCategory.All) SearchCategory.Movies else category
                    }
                }
                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = title,
                    magnetUri = SearchUtils.buildMagnet(hash, title),
                    sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull,
                    seeders = obj["seeders"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0L..1_000_000L }?.toInt() ?: 0,
                    peers = obj["leechers"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0L..100_000L }?.toInt() ?: 0,
                    category = mappedCat,
                )
            }
        }
    }
}

class LimeTorrentsProvider(
    private val network: HardenedNetworkClient,
    private val baseUrl: String = "https://www.limetorrents.lol",
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "limetorrents",
        name = "LimeTorrents",
        url = baseUrl,
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other),
        enabledByDefault = true,
        supportsNonEnglishQueries = true,
        supportsAdultContent = false,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val encoded = SearchUtils.encodeUrl(query)
        val html = network.httpText("$baseUrl/search/all/$encoded/seeds/1/")
        return withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html, baseUrl)
            // Target only table.table2 so sponsored ad rows in table 1 are never included
            doc.select("table.table2 tr").mapNotNull { row ->
                val hashHref = row.selectFirst("""a[href*=".torrent"]""")?.attr("href") ?: return@mapNotNull null
                val hash = ITORRENTS_HASH_REGEX.find(hashHref)?.groupValues?.getOrNull(1)?.lowercase()
                    ?: return@mapNotNull null
                val titleEl = row.select("td.tdleft div.tt-name a")
                    .firstOrNull { !it.attr("href").contains(".torrent", ignoreCase = true) }
                    ?: return@mapNotNull null
                val title = titleEl.text().trim()
                if (title.isEmpty() || SearchUtils.isExecutableSpam(title)) return@mapNotNull null

                val sizeText = row.select("td.tdnormal")
                    .firstOrNull { SIZE_CELL_REGEX.containsMatchIn(it.text()) }
                    ?.text()?.trim()
                val seeders = row.selectFirst("td.tdseed")?.text()?.replace(",", "")?.toIntOrNull() ?: 0
                val peers = row.selectFirst("td.tdleech")?.text()?.replace(",", "")?.toIntOrNull() ?: 0
                val detailsUrl = titleEl.attr("abs:href").takeIf { it.startsWith("https://") }

                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = title,
                    magnetUri = SearchUtils.buildMagnet(hash, title),
                    sizeText = sizeText,
                    seeders = seeders,
                    peers = peers,
                    category = category,
                    detailsUrl = detailsUrl,
                )
            }
        }
    }

    companion object {
        private val ITORRENTS_HASH_REGEX = Regex("""/torrent/([a-fA-F0-9]{40})\.torrent""")
        private val SIZE_CELL_REGEX = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:TB|GB|MB|KB|GiB|MiB)\b""")
    }
}
