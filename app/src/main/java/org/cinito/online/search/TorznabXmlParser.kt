package org.cinito.online.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure-Java/Kotlin XML parser via Jsoup Parser.xmlParser() (does not resolve external entities / XXE).
 */
class TorznabXmlParser(
    private val providerId: String,
    private val providerName: String,
) {
    fun parse(xml: String): List<TorrentSearchResult> {
        if (xml.isBlank()) return emptyList()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("channel > item").mapNotNull { item ->
            runCatching { parseItem(item) }.getOrNull()
        }
    }

    private fun parseItem(item: Element): TorrentSearchResult? {
        val title = item.selectFirst("title")?.ownText()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val magnetAttr = item.select("torznab|attr[name=magneturl]").firstOrNull()?.attr("value")
        val infoHashAttr = item.select("torznab|attr[name=infohash]").firstOrNull()?.attr("value")
        val linkText = item.selectFirst("link")?.ownText()?.takeIf { it.startsWith("magnet:") }
        val enclosureMagnet = item.selectFirst("enclosure")?.attr("url")?.takeIf { it.startsWith("magnet:") }

        val magnet = magnetAttr?.takeIf { it.startsWith("magnet:") }
            ?: linkText
            ?: enclosureMagnet
            ?: infoHashAttr?.takeIf { it.isNotBlank() }?.let { SearchUtils.buildMagnet(it, title) }
            ?: return null

        val sizeBytes = item.selectFirst("size")?.ownText()?.toLongOrNull()
            ?: item.select("torznab|attr[name=size]").firstOrNull()?.attr("value")?.toLongOrNull()
        val seeders = item.select("torznab|attr[name=seeders]").firstOrNull()?.attr("value")?.toIntOrNull() ?: 0
        val peers = item.select("torznab|attr[name=peers]").firstOrNull()?.attr("value")?.toIntOrNull() ?: 0
        val pubDateEpoch = item.selectFirst("pubDate")?.ownText()?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
        }
        val catCode = item.select("torznab|attr[name=category]").firstOrNull()?.attr("value")?.toIntOrNull()
        val category = when (catCode) {
            in 2000..2999 -> SearchCategory.Movies
            in 5000..5999 -> if (catCode == 5070) SearchCategory.Anime else SearchCategory.Series
            else -> SearchCategory.Other
        }

        return SearchUtils.makeResult(
            providerId = providerId,
            providerName = providerName,
            title = title,
            magnetUri = magnet,
            sizeBytes = sizeBytes,
            seeders = seeders,
            peers = peers,
            uploadDateEpochSec = pubDateEpoch,
            category = category,
        )
    }
}
