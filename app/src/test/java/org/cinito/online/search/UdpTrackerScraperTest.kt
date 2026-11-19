package org.cinito.online.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class UdpTrackerScraperTest {

    @Test
    fun serializesAndParsesBep15ConnectAndMultiScrapeFrames() {
        val connectReq = UdpTrackerScraper.buildConnectRequest(transactionId = 42)
        assertEquals(16, connectReq.size)

        val connectResp = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(42)
            .putLong(0x1122334455667788L)
            .array()
        assertEquals(0x1122334455667788L, UdpTrackerScraper.parseConnectResponse(connectResp, 16, 42))
        assertNull(UdpTrackerScraper.parseConnectResponse(connectResp, 16, 99))

        val hash1Hex = "0123456789abcdef0123456789abcdef01234567"
        val hash2Hex = "fedcba9876543210fedcba9876543210fedcba98"
        val decoded1 = SearchUtils.decodeInfoHashBytes(hash1Hex)!!.second
        val decoded2 = SearchUtils.decodeInfoHashBytes(hash2Hex)!!.second

        val scrapeReq = UdpTrackerScraper.buildMultiScrapeRequest(0x1122334455667788L, 7, listOf(decoded1, decoded2))
        assertEquals(16 + 40, scrapeReq.size)

        val scrapeResp = ByteBuffer.allocate(8 + 24).order(ByteOrder.BIG_ENDIAN)
            .putInt(2)
            .putInt(7)
            .putInt(54).putInt(320).putInt(11)
            .putInt(0).putInt(10).putInt(2)
            .array()
        val parsed = UdpTrackerScraper.parseMultiScrapeResponse(scrapeResp, scrapeResp.size, 7, listOf(hash1Hex, hash2Hex), 1234L)
        assertEquals(54, parsed[hash1Hex]?.seeders)
        assertEquals(11, parsed[hash1Hex]?.leechers)
        assertEquals(0, parsed[hash2Hex]?.seeders)

        // Malformed oversized length must return null/empty instead of throwing IndexOutOfBoundsException
        assertNull(UdpTrackerScraper.parseConnectResponse(connectResp, 999, 42))
        assertTrue(UdpTrackerScraper.parseMultiScrapeResponse(scrapeResp, 999, 7, listOf(hash1Hex), 1234L).isEmpty())

        val scraper = UdpTrackerScraper()
        assertTrue(scraper.isDisallowedLocalAddress(InetAddress.getByName("fd00::1")))
        assertTrue(scraper.isDisallowedLocalAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(scraper.isDisallowedLocalAddress(InetAddress.getByName("0.0.0.1")))
        assertTrue(!scraper.isDisallowedLocalAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun scrapesBatchViaLocalUdpSocketAggregatesAndCachesResults() = runTest {
        val requestsHandled = AtomicInteger(0)
        val serverSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            val buf = ByteArray(1024)
            while (!serverSocket.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    serverSocket.receive(pkt)
                } catch (_: Exception) {
                    break
                }
                val bb = ByteBuffer.wrap(pkt.data, 0, pkt.length).order(ByteOrder.BIG_ENDIAN)
                if (pkt.length >= 16) {
                    val connOrProto = bb.getLong()
                    val action = bb.getInt()
                    val txId = bb.getInt()
                    if (action == 0 && connOrProto == 0x41727101980L) {
                        val resp = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                            .putInt(0).putInt(txId).putLong(987654321L).array()
                        serverSocket.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
                    } else if (action == 2 && connOrProto == 987654321L) {
                        requestsHandled.incrementAndGet()
                        val count = (pkt.length - 16) / 20
                        val out = ByteBuffer.allocate(8 + 12 * count).order(ByteOrder.BIG_ENDIAN)
                        out.putInt(2).putInt(txId)
                        for (i in 0 until count) {
                            out.putInt(25 + i * 10).putInt(100).putInt(3 + i)
                        }
                        val bytes = out.array()
                        serverSocket.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val port = serverSocket.localPort
            var now = 10_000L
            val scraper = UdpTrackerScraper(
                trackerUrlsProvider = { listOf("udp://127.0.0.1:$port/announce") },
                timeoutMs = 1_000,
                cacheTtlMs = 60_000L,
                clockMs = { now },
                allowLoopbackForTest = true,
            )

            val h1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val h2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            val items = listOf(
                SearchUtils.makeResult("p1", "P1", "Movie A 1080p", "magnet:?xt=urn:btih:$h1", seeders = 5),
                SearchUtils.makeResult("p1", "P1", "Movie B 1080p", "magnet:?xt=urn:btih:$h2", seeders = 2),
            )

            val stats = scraper.scrapeBatch(items)
            assertEquals(25, stats[h1]?.seeders)
            assertEquals(3, stats[h1]?.leechers)
            assertEquals(35, stats[h2]?.seeders)
            assertEquals(1, requestsHandled.get())

            // Second call within TTL must hit in-memory cache without another UDP exchange
            now += 5_000L
            val cached = scraper.scrapeBatch(items)
            assertEquals(35, cached[h2]?.seeders)
            assertEquals(1, requestsHandled.get())
            assertTrue(SearchUtils.decodeInfoHashBytes("JBSWY3DPEBLW64TMMQQQAAAAAAAAAAAA") != null)
        } finally {
            serverSocket.close()
            serverThread.interrupt()
        }
    }

    @Test
    fun ignoresUntrustedTrackersInjectedInMagnetLinks() = runTest {
        val evilTrackerHits = AtomicInteger(0)
        val evilSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val evilThread = Thread {
            val buf = ByteArray(256)
            while (!evilSocket.isClosed) {
                try {
                    evilSocket.receive(DatagramPacket(buf, buf.size))
                    evilTrackerHits.incrementAndGet()
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val evilPort = evilSocket.localPort
            val scraper = UdpTrackerScraper(
                trackerUrlsProvider = { emptyList() },
                timeoutMs = 300,
                allowLoopbackForTest = true,
            )
            val h1 = "cccccccccccccccccccccccccccccccccccccccc"
            val itemWithInjectedTracker = SearchUtils.makeResult(
                "p1",
                "P1",
                "Movie C",
                "magnet:?xt=urn:btih:$h1&tr=udp://127.0.0.1:$evilPort/announce",
                seeders = 10,
            )
            val stats = scraper.scrapeBatch(listOf(itemWithInjectedTracker))
            assertTrue(stats.isEmpty())
            assertEquals(0, evilTrackerHits.get())
        } finally {
            evilSocket.close()
            evilThread.interrupt()
        }
    }
}
