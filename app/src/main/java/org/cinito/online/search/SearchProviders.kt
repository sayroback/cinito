package org.cinito.online.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
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
        val json = network.getJson("$apiBaseUrl/list_movies.json?query_term=$encoded&limit=35") ?: return emptyList()
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
        val json = network.getJson("$apiBaseUrl/list_movies.json?query_term=${SearchUtils.encodeUrl(cleanImdb)}&limit=35")
            ?: return emptyList()
        return parseMovies(json.jsonObject)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Movies) return emptyList()
        val json = network.getJson("$apiBaseUrl/list_movies.json?sort_by=download_count&limit=25") ?: return emptyList()
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
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other),
        enabledByDefault = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val payload = buildRequest(query.trim(), category, "seeders")
        val json = network.postJson("$API_URL/v1", payload) ?: return emptyList()
        return parseHits(json.jsonObject)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        val payload = buildRequest(null, category, "seeders")
        val json = network.postJson("$API_URL/v1", payload) ?: return emptyList()
        return parseHits(json.jsonObject)
    }

    private fun buildRequest(query: String?, category: SearchCategory, orderBy: String): JsonObject = buildJsonObject {
        query?.takeIf { it.isNotBlank() }?.let { put("query", JsonPrimitive(it)) }
        put("size", JsonPrimitive(100))
        put("order_by", JsonPrimitive(orderBy))
        put("order_direction", JsonPrimitive("desc"))
        put("hide_unsafe", JsonPrimitive(true))
        put("hide_xxx", JsonPrimitive(true))
        val catIds = when (category) {
            SearchCategory.Movies -> listOf(2000000, 3000000, 6000000)
            SearchCategory.Series -> listOf(2000000, 6000000)
            SearchCategory.Anime -> listOf(6000000)
            SearchCategory.Other -> listOf(10000000)
            SearchCategory.All -> listOf(2000000, 3000000, 6000000)
        }
        putJsonArray("categories") {
            catIds.forEach { add(JsonPrimitive(it)) }
        }
    }

    private suspend fun parseHits(root: JsonObject): List<TorrentSearchResult> = withContext(Dispatchers.Default) {
        val hits = root["hits"]?.jsonArray ?: return@withContext emptyList()
        hits.mapNotNull { elem ->
            val obj = elem.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (SearchUtils.isExecutableSpam(title)) return@mapNotNull null
            val magnet = obj["magnetUrl"]?.jsonPrimitive?.content?.takeIf { it.startsWith("magnet:") }
                ?: obj["hash"]?.jsonPrimitive?.content?.let { SearchUtils.buildMagnet(it, title) }
                ?: return@mapNotNull null
            val catId = (obj["categoryId"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }?.minOrNull()
            val cat = when (catId) {
                in 2000000L..2999999L -> SearchCategory.Series
                in 3000000L..3999999L -> SearchCategory.Movies
                in 6000000L..6999999L -> SearchCategory.Anime
                else -> SearchCategory.Other
            }
            val epoch = obj["date"]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            val rawDesc = obj["description"]?.jsonPrimitive?.content
            val plainDesc = rawDesc?.takeIf { it.isNotBlank() }?.let { Jsoup.parse(it).text().trim() }
            SearchUtils.makeResult(
                providerId = info.id,
                providerName = info.name,
                title = title,
                magnetUri = magnet,
                sizeBytes = obj["bytes"]?.jsonPrimitive?.longOrNull,
                seeders = obj["seeders"]?.jsonPrimitive?.intOrNull ?: 0,
                peers = obj["peers"]?.jsonPrimitive?.intOrNull ?: 0,
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
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val encoded = SearchUtils.encodeUrl(query)
        val json = network.getJson("${info.url}/service/search?q=$encoded") ?: return emptyList()
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
                    seeders = obj["seeders"]?.jsonPrimitive?.intOrNull ?: 0,
                    peers = obj["leechers"]?.jsonPrimitive?.intOrNull ?: 0,
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
        val html = network.getText("${info.url}/?f=0&c=$c&s=seeders&o=desc&q=$encoded")
        return parseHtml(html, category)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Anime) return emptyList()
        val html = network.getText("${info.url}/?c=1_0&s=seeders&o=desc")
        return parseHtml(html, SearchCategory.Anime)
    }

    private suspend fun parseHtml(html: String, defaultCat: SearchCategory): List<TorrentSearchResult> = withContext(Dispatchers.Default) {
        val doc = Jsoup.parse(html, info.url)
        doc.select("table.torrent-list > tbody > tr").mapNotNull { row ->
            val titleAnchor = row.selectFirst("td:nth-child(2) > a:not(.comments)") ?: return@mapNotNull null
            val title = titleAnchor.ownText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val magnet = row.selectFirst("""td:nth-child(3) > a[href^="magnet:"]""")?.attr("href") ?: return@mapNotNull null
            val sizeText = row.selectFirst("td:nth-child(4)")?.ownText()
            val timestamp = row.selectFirst("td:nth-child(5)")?.attr("data-timestamp")?.toLongOrNull()
            val seeders = row.selectFirst("td:nth-child(6)")?.ownText()?.toIntOrNull() ?: 0
            val peers = row.selectFirst("td:nth-child(7)")?.ownText()?.toIntOrNull() ?: 0
            val detailsUrl = titleAnchor.attr("abs:href")
            SearchUtils.makeResult(
                providerId = info.id,
                providerName = info.name,
                title = title,
                magnetUri = magnet,
                sizeText = sizeText,
                seeders = seeders,
                peers = peers,
                uploadDateEpochSec = timestamp,
                category = if (defaultCat == SearchCategory.All) SearchCategory.Anime else defaultCat,
                detailsUrl = detailsUrl,
            )
        }
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
        val json = network.getJson(reqUrl) ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val docs = json.jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray ?: return@withContext emptyList()
            docs.mapNotNull { elem ->
                val obj = elem.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val hash = obj["btih"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val identifier = obj["identifier"]?.jsonPrimitive?.content
                val epoch = obj["publicdate"]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
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
        val json = network.getJson("${info.url}/api/?f=search&tz=UTC&s=$encoded") ?: return emptyList()
        return parseSubsPlease(json)
    }

    override suspend fun latest(category: SearchCategory): List<TorrentSearchResult> {
        if (category != SearchCategory.All && category != SearchCategory.Anime) return emptyList()
        val json = network.getJson("${info.url}/api/?f=latest&tz=UTC") ?: return emptyList()
        return parseSubsPlease(json)
    }

    private suspend fun parseSubsPlease(jsonElem: kotlinx.serialization.json.JsonElement): List<TorrentSearchResult> =
        withContext(Dispatchers.Default) {
            val obj = runCatching { jsonElem.jsonObject }.getOrNull() ?: return@withContext emptyList()
            obj.entries.flatMap { (showName, rawVal) ->
                val show = runCatching { rawVal.jsonObject }.getOrNull() ?: return@flatMap emptyList()
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
    )
    private val parser = TorznabXmlParser(info.id, info.name)

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val baseUrl = config.url.trim().trimEnd('/')
        val apiEndpoint = if (baseUrl.endsWith("/api")) baseUrl else "$baseUrl/api"
        val encoded = SearchUtils.encodeUrl(query)
        val catParam = when (category) {
            SearchCategory.Movies -> "&cat=2000"
            SearchCategory.Series -> "&cat=5000"
            SearchCategory.Anime -> "&cat=5070"
            else -> ""
        }
        val reqUrl = "$apiEndpoint?apikey=${SearchUtils.encodeUrl(config.apiKey)}&extended=1&t=search&q=$encoded$catParam"
        val xml = network.getText(reqUrl)
        return parser.parse(xml)
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
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other),
        enabledByDefault = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val cat = when (category) {
            SearchCategory.Movies -> "200"
            // TPB category 205 is SD TV only and misses 208 (HD TV); query 200 (all video) and filter 205..209
            SearchCategory.Series -> "200"
            SearchCategory.Anime -> "0"
            SearchCategory.Other -> "600"
            SearchCategory.All -> "0"
        }
        val encoded = SearchUtils.encodeUrl(query)
        val elem = network.getJson("$apiBaseUrl/q.php?q=$encoded&cat=$cat") ?: return emptyList()
        return withContext(Dispatchers.Default) {
            val arr = runCatching { elem.jsonArray }.getOrNull() ?: return@withContext emptyList()
            arr.mapNotNull { item ->
                val obj = item.jsonObject
                val title = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (title == "No results returned" || SearchUtils.isExecutableSpam(title)) return@mapNotNull null
                val hash = obj["info_hash"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: return@mapNotNull null
                if (hash.all { it == '0' }) return@mapNotNull null
                val recordId = obj["id"]?.jsonPrimitive?.content
                val catCode = obj["category"]?.jsonPrimitive?.content?.toIntOrNull()
                if (category == SearchCategory.Series && catCode != null && catCode !in TV_CATEGORY_CODES) {
                    return@mapNotNull null
                }
                val mappedCat = when (catCode) {
                    in TV_CATEGORY_CODES -> SearchCategory.Series
                    in 200..299 -> SearchCategory.Movies
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
        val obj = runCatching {
            network.getJson("$apiBaseUrl/t.php?id=$recId")?.jsonObject
        }.getOrNull() ?: return result

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
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val catParam = when (category) {
            SearchCategory.Anime -> "&category=4"
            else -> ""
        }
        val encoded = SearchUtils.encodeUrl(query)
        val html = network.getText("$baseUrl/search?q=$encoded&page=1&sortBy=seeders$catParam")
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
            val url = "$baseUrl/language=latino/stream/$path"
            val obj = runCatching { network.getJson(url)?.jsonObject }.getOrNull() ?: continue
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
    private val baseUrl: String = "https://solidtorrents.to",
) : TorrentSearchProvider {
    override val info = SearchProviderInfo(
        id = "solidtorrents",
        name = "SolidTorrents",
        url = baseUrl,
        categories = setOf(SearchCategory.Movies, SearchCategory.Series, SearchCategory.Anime, SearchCategory.Other),
        enabledByDefault = true,
        supportsNonEnglishQueries = true,
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val encoded = SearchUtils.encodeUrl(query)
        val root = network.getJson("$baseUrl/api/v1/search?q=$encoded&sort=seeders")?.jsonObject
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
                val mappedCat = when (catNum) {
                    3 -> SearchCategory.Series
                    4 -> SearchCategory.Anime
                    else -> if (category == SearchCategory.All) SearchCategory.Movies else category
                }
                SearchUtils.makeResult(
                    providerId = info.id,
                    providerName = info.name,
                    title = title,
                    magnetUri = SearchUtils.buildMagnet(hash, title),
                    sizeBytes = obj["size"]?.jsonPrimitive?.longOrNull,
                    seeders = obj["seeders"]?.jsonPrimitive?.intOrNull ?: 0,
                    peers = obj["leechers"]?.jsonPrimitive?.intOrNull ?: 0,
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
    )

    override suspend fun search(query: String, category: SearchCategory): List<TorrentSearchResult> {
        val encoded = SearchUtils.encodeUrl(query)
        val html = network.getText("$baseUrl/search/all/$encoded/seeds/1/")
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
