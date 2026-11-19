package org.cinito.online.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorznabXmlParserTest {
    @Test
    fun parsesTorznabItemsAndIgnoresMalformedEntries() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/nzb">
              <channel>
                <item>
                  <title>Dune.Part.Two.2024.1080p.WEB-DL</title>
                  <size>4294967296</size>
                  <pubDate>Mon, 15 Apr 2024 12:00:00 +0000</pubDate>
                  <torznab:attr name="magneturl" value="magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&amp;dn=Dune" />
                  <torznab:attr name="seeders" value="420" />
                  <torznab:attr name="peers" value="85" />
                  <torznab:attr name="category" value="2000" />
                </item>
                <item>
                  <title>Frieren.S01E12.1080p</title>
                  <torznab:attr name="infohash" value="fedcba9876543210fedcba9876543210fedcba98" />
                  <torznab:attr name="size" value="1073741824" />
                  <torznab:attr name="seeders" value="95" />
                  <torznab:attr name="peers" value="12" />
                  <torznab:attr name="category" value="5070" />
                </item>
                <item>
                  <title>MissingMagnetItem</title>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = TorznabXmlParser("jackett_1", "Jackett").parse(xml)

        assertEquals(2, parsed.size)
        assertEquals("Dune Part Two", parsed[0].parsed.cleanTitle)
        assertEquals(420, parsed[0].seeders)
        assertEquals(85, parsed[0].peers)
        assertEquals(SearchCategory.Movies, parsed[0].category)
        assertEquals("0123456789abcdef0123456789abcdef01234567", parsed[0].infoHash)

        assertEquals(SearchCategory.Anime, parsed[1].category)
        assertTrue(parsed[1].magnetUri.startsWith("magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98"))
    }

    @Test
    fun safelyHandlesDoctypeEntityWithoutExternalExpansion() {
        val xxeAttempt = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/nzb">
              <channel>
                <item>
                  <title>&xxe;</title>
                  <torznab:attr name="infohash" value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = TorznabXmlParser("p", "P").parse(xxeAttempt)
        assertTrue(parsed.none { it.title.contains("root:") })
    }
}
