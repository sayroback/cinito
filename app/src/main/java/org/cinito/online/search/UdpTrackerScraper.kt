package org.cinito.online.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight BEP-15 UDP tracker multi-hash scraper (`connect` + batched `scrape`).
 * Probes live seeders/leechers across up to 3 public UDP trackers in ~1.4s with <10 KB traffic
 * and without starting any P2P session inside `wt-engine`.
 */
open class UdpTrackerScraper(
    private val trackerUrlsProvider: () -> List<String> = { SearchUtils.DEFAULT_TRACKERS },
    private val dnsResolver: (String) -> InetAddress = { host -> InetAddress.getByName(host) },
    private val timeoutMs: Int = 2_200,
    private val cacheTtlMs: Long = 10 * 60 * 1_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val allowLoopbackForTest: Boolean = false,
    private val canUseNetwork: () -> Boolean = { true },
) {
    private val random = SecureRandom()
    private val cache = ConcurrentHashMap<String, TrackerSwarmStats>()

    open suspend fun scrapeBatch(
        results: List<TorrentSearchResult>,
        maxItems: Int = 40,
    ): Map<String, TrackerSwarmStats> = withContext(Dispatchers.IO) {
        if (results.isEmpty() || maxItems <= 0) return@withContext emptyMap()

        val now = clockMs()
        val hashes = LinkedHashMap<String, ByteArray>()

        for (item in results) {
            if (hashes.size >= maxItems) break
            val rawHash = item.infoHash ?: SearchUtils.extractInfoHash(item.magnetUri) ?: continue
            val decoded = SearchUtils.decodeInfoHashBytes(rawHash) ?: continue
            hashes.putIfAbsent(decoded.first, decoded.second)
        }
        if (hashes.isEmpty()) return@withContext emptyMap()

        val output = LinkedHashMap<String, TrackerSwarmStats>()
        val toQuery = LinkedHashMap<String, ByteArray>()
        for ((hex, raw) in hashes) {
            val cached = cache[hex]
            if (cached != null && now - cached.updatedAtMs < cacheTtlMs) {
                output[hex] = cached
            } else {
                toQuery[hex] = raw
            }
        }
        if (toQuery.isEmpty() || !canUseNetwork()) return@withContext output

        val trackers = trackerUrlsProvider()
            .filter { it.startsWith("udp://", ignoreCase = true) }
            .distinct()
            .take(5)
        if (trackers.isEmpty()) return@withContext output

        val orderedHashes = toQuery.keys.toList()
        val orderedBytes = orderedHashes.map { toQuery.getValue(it) }

        val perTrackerResponses = coroutineScope {
            val hashChunks = orderedHashes.chunked(MAX_SCRAPE_HASHES)
            val byteChunks = orderedBytes.chunked(MAX_SCRAPE_HASHES)
            trackers.map { url ->
                async {
                    runCatching {
                        val merged = LinkedHashMap<String, TrackerSwarmStats>(orderedHashes.size)
                        for (idx in hashChunks.indices) {
                            merged += scrapeSingleTracker(url, hashChunks[idx], byteChunks[idx], clockMs())
                        }
                        merged
                    }.getOrElse { emptyMap() }
                }
            }.awaitAll()
        }

        val anyTrackerResponded = perTrackerResponses.any { it.isNotEmpty() }
        if (!anyTrackerResponded) return@withContext output

        val aggregated = LinkedHashMap<String, TrackerSwarmStats>()
        for (hex in orderedHashes) {
            var best: TrackerSwarmStats? = null
            for (resp in perTrackerResponses) {
                val candidate = resp[hex] ?: continue
                if (best == null ||
                    candidate.seeders > best.seeders ||
                    (candidate.seeders == best.seeders && candidate.leechers > best.leechers)
                ) {
                    best = candidate
                }
            }
            if (best != null) {
                cache[hex] = best
                aggregated[hex] = best
                output[hex] = best
            }
        }
        output
    }

    private fun scrapeSingleTracker(
        trackerUrl: String,
        hashesHex: List<String>,
        hashesBytes: List<ByteArray>,
        nowMs: Long,
    ): Map<String, TrackerSwarmStats> {
        val uri = runCatching { URI(trackerUrl) }.getOrNull() ?: return emptyMap()
        if (!uri.scheme.equals("udp", ignoreCase = true)) return emptyMap()
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val port = if (uri.port in 1..65535) uri.port else return emptyMap()

        val addr = dnsResolver(host)
        if (!allowLoopbackForTest && isDisallowedLocalAddress(addr)) {
            return emptyMap()
        }

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(addr, port)

            val tx1 = random.nextInt()
            val connectReq = buildConnectRequest(tx1)
            socket.send(DatagramPacket(connectReq, connectReq.size))

            val connectBuf = ByteArray(64)
            val connectPkt = DatagramPacket(connectBuf, connectBuf.size)
            socket.receive(connectPkt)
            val connectionId = parseConnectResponse(connectBuf, connectPkt.length, tx1)
                ?: return emptyMap()

            val tx2 = random.nextInt()
            val scrapeReq = buildMultiScrapeRequest(connectionId, tx2, hashesBytes)
            socket.send(DatagramPacket(scrapeReq, scrapeReq.size))

            val maxRespSize = 8 + 12 * hashesBytes.size + 64
            val scrapeBuf = ByteArray(maxRespSize)
            val scrapePkt = DatagramPacket(scrapeBuf, scrapeBuf.size)
            socket.receive(scrapePkt)
            return parseMultiScrapeResponse(scrapeBuf, scrapePkt.length, tx2, hashesHex, nowMs)
        }
    }

    internal fun isDisallowedLocalAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        val raw = addr.address
        return when (raw.size) {
            4 -> isDisallowedIpv4Bytes(raw)
            16 -> isDisallowedIpv6Bytes(raw)
            else -> true
        }
    }

    private fun isDisallowedIpv4Bytes(b: ByteArray, off: Int = 0): Boolean {
        val b0 = b[off].toInt() and 0xFF
        val b1 = b[off + 1].toInt() and 0xFF
        return b0 == 0 || // 0.0.0.0/8 ("this host")
            b0 == 10 || // 10.0.0.0/8
            (b0 == 100 && (b1 and 0xC0) == 64) || // 100.64.0.0/10 CGNAT
            b0 == 127 || // 127.0.0.0/8 loopback
            (b0 == 169 && b1 == 254) || // 169.254.0.0/16 link-local
            (b0 == 172 && b1 in 16..31) || // 172.16.0.0/12
            (b0 == 192 && b1 == 168) || // 192.168.0.0/16
            b0 >= 224 // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved
    }

    private fun isDisallowedIpv6Bytes(b: ByteArray): Boolean {
        if (b.all { it == 0.toByte() }) return true // ::
        // IPv4-mapped ::ffff:a.b.c.d
        val isIpv4Mapped = (0 until 10).all { b[it] == 0.toByte() } &&
            b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
        if (isIpv4Mapped) return isDisallowedIpv4Bytes(b, off = 12)
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return (b0 and 0xFE) == 0xFC || // fc00::/7 Unique Local Address (fc00::/fd00::)
            (b0 == 0xFE && (b1 and 0xC0) == 0x80) || // fe80::/10 link-local
            b0 == 0xFF // ff00::/8 multicast
    }

    companion object {
        private const val MAX_SCRAPE_HASHES = 20
        private const val BEP15_PROTOCOL_ID = 0x41727101980L
        private const val ACTION_CONNECT = 0
        private const val ACTION_SCRAPE = 2

        fun buildConnectRequest(transactionId: Int): ByteArray =
            ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(BEP15_PROTOCOL_ID)
                .putInt(ACTION_CONNECT)
                .putInt(transactionId)
                .array()

        fun parseConnectResponse(bytes: ByteArray, length: Int, expectedTxId: Int): Long? {
            if (length < 16 || length > bytes.size) return null
            val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            val action = bb.getInt()
            val txId = bb.getInt()
            if (action != ACTION_CONNECT || txId != expectedTxId) return null
            return bb.getLong()
        }

        fun buildMultiScrapeRequest(
            connectionId: Long,
            transactionId: Int,
            hashes20Bytes: List<ByteArray>,
        ): ByteArray {
            val count = hashes20Bytes.size.coerceAtMost(MAX_SCRAPE_HASHES)
            val bb = ByteBuffer.allocate(16 + 20 * count).order(ByteOrder.BIG_ENDIAN)
            bb.putLong(connectionId)
            bb.putInt(ACTION_SCRAPE)
            bb.putInt(transactionId)
            for (i in 0 until count) {
                val h = hashes20Bytes[i]
                require(h.size == 20) { "BEP-15 requires 20-byte infoHash" }
                bb.put(h)
            }
            return bb.array()
        }

        fun parseMultiScrapeResponse(
            bytes: ByteArray,
            length: Int,
            expectedTxId: Int,
            hashesHex: List<String>,
            nowMs: Long,
        ): Map<String, TrackerSwarmStats> {
            if (length < 8 || length > bytes.size) return emptyMap()
            val bb = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            val action = bb.getInt()
            val txId = bb.getInt()
            if (action != ACTION_SCRAPE || txId != expectedTxId) return emptyMap()

            val entryCount = ((length - 8) / 12).coerceAtMost(hashesHex.size)
            val out = LinkedHashMap<String, TrackerSwarmStats>(entryCount)
            for (i in 0 until entryCount) {
                val seeders = bb.getInt().coerceAtLeast(0)
                val completed = bb.getInt().coerceAtLeast(0)
                val leechers = bb.getInt().coerceAtLeast(0)
                out[hashesHex[i].lowercase(Locale.ROOT)] = TrackerSwarmStats(
                    seeders = seeders,
                    leechers = leechers,
                    completed = completed,
                    updatedAtMs = nowMs,
                )
            }
            return out
        }
    }
}
