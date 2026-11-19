package org.cinito.online.search

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.metadata.ProbeTrackKind
import org.cinito.online.metadata.ProbedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import webtor.core.EngineClient
import webtor.core.EngineResponse
import webtor.core.EngineTransport
import webtor.core.TorrentFile
import java.util.concurrent.atomic.AtomicInteger

class SwarmDiagnosticsCoordinatorTest {
    private val sampleHash = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"

    private fun sampleResult(hash: String = sampleHash, suffix: String = "1"): TorrentSearchResult {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=Test+$suffix"
        return TorrentSearchResult(
            id = "test-$suffix",
            providerId = "torrentio",
            providerName = "Torrentio",
            title = "Movie $suffix 1080p WEB-DL Latino",
            magnetUri = magnet,
            infoHash = hash,
            sizeText = "1.5 GB",
            seeders = 25,
            peers = 40,
            sizeBytes = 1_500_000_000L,
            parsed = MediaTitleParser.parse("Movie $suffix 1080p WEB-DL Latino"),
            languageTag = AudioLanguageTag.Latino,
            languageEvidence = "title",
        )
    }

    @Test
    fun probeSingleMeasuresSpeedFastStartCleansUpAndCaches() = runBlocking {
        val addCalls = AtomicInteger(0)
        val removeCalls = AtomicInteger(0)
        val pollCalls = AtomicInteger(0)
        val focusCalls = AtomicInteger(0)
        var nowMs = 1_000L

        val transport = EngineTransport { method, path, body ->
            when {
                method == "POST" && path == "/add" -> {
                    addCalls.incrementAndGet()
                    EngineResponse(200, """{"id":"probe-1","infoHash":"$sampleHash"}""")
                }
                method == "GET" && path == "/torrent/probe-1" -> {
                    val n = pollCalls.incrementAndGet()
                    val ready = n >= 2
                    val speed = if (ready) 2_400_000L else 0L
                    val peers = if (ready) 14 else 3
                    val filesJson = if (ready) {
                        """[{"index":0,"name":"Movie.1080p.mp4","path":"Movie.1080p.mp4","length":1500000000,"progress":0.01,"type":"video/mp4"}]"""
                    } else {
                        "[]"
                    }
                    EngineResponse(
                        200,
                        """{"id":"probe-1","infoHash":"$sampleHash","ready":$ready,"done":false,"paused":false,"progress":0.01,"downloadSpeed":$speed,"uploadSpeed":0,"numPeers":$peers,"connectedSeeders":${if (ready) 4 else 0},"swarmAvailability":${if (ready) 2.75 else 0.0},"length":1500000000,"downloaded":262144,"uploaded":0,"files":$filesJson}""",
                    )
                }
                method == "POST" && path == "/focus" -> {
                    focusCalls.incrementAndGet()
                    EngineResponse(200, """{"ok":true}""")
                }
                method == "POST" && path == "/remove" -> {
                    assertTrue(body?.contains("\"destroyStore\":true") == true)
                    removeCalls.incrementAndGet()
                    EngineResponse(200, """{"ok":true}""")
                }
                else -> error("Unexpected transport call: $method $path")
            }
        }

        val coordinator = SwarmDiagnosticsCoordinator(
            engineClient = EngineClient(transport),
            containerProbeFn = { engineId, fileIndex, _ ->
                assertEquals("probe-1", engineId)
                assertEquals(0, fileIndex)
                ContainerProbeResult(
                    containerFormat = "MP4",
                    tracks = listOf(
                        ProbedTrack(
                            kind = ProbeTrackKind.Audio,
                            languageCode = "spa",
                            bcp47 = "es-419",
                            name = "Latino AAC",
                            codec = "AAC",
                            regionTag = AudioLanguageTag.Latino,
                        ),
                    ),
                    isFastStartMp4 = true,
                )
            },
            clockMs = { nowMs },
            delayFn = { dt -> nowMs += dt },
            maxProbeDurationMs = 4_500L,
            postReadySampleWindowMs = 200L,
            pollIntervalMs = 150L,
        )

        val first = coordinator.probeSingle(sampleResult())
        assertNotNull(first)
        assertEquals(2_400_000L, first?.measuredDownloadBps)
        assertEquals(14, first?.activePeers)
        assertEquals(4, first?.connectedSeeders)
        assertEquals(2.75, first?.swarmAvailability!!, 0.0001)
        assertEquals(true, first?.isFastStartMp4)
        assertTrue(first?.hasVerifiedLatinoAudio == true)
        assertTrue(first?.hasVerifiedSpanishAudio == true)
        assertEquals(1_500_000_000L, first?.targetFileSizeBytes)
        assertEquals(1, addCalls.get())
        assertEquals(1, focusCalls.get())
        assertEquals(1, removeCalls.get())

        val second = coordinator.probeSingle(sampleResult())
        assertEquals(first, second)
        assertEquals(1, addCalls.get())
    }

    @Test
    fun probeSingleReusesExistingLibraryTorrentWithoutAddingOrRemoving() = runBlocking {
        val transport = EngineTransport { method, path, _ ->
            when {
                method == "GET" && path == "/torrent/lib-entry-1" -> {
                    EngineResponse(
                        200,
                        """{"id":"lib-entry-1","infoHash":"$sampleHash","ready":true,"done":false,"paused":false,"progress":0.4,"downloadSpeed":3100000,"uploadSpeed":0,"numPeers":18,"length":1200000000,"downloaded":40000000,"uploaded":0,"files":[{"index":0,"name":"Movie.mkv","path":"Movie.mkv","length":1200000000,"progress":0.4,"type":"video/x-matroska"}]}""",
                    )
                }
                else -> error("Must not call $method $path for existing library torrent")
            }
        }

        val coordinator = SwarmDiagnosticsCoordinator(
            engineClient = EngineClient(transport),
            activeEngineIdByHash = { hash -> if (hash == sampleHash) "lib-entry-1" else null },
            delayFn = { },
            postReadySampleWindowMs = 0L,
        )

        val probe = coordinator.probeSingle(sampleResult())
        assertNotNull(probe)
        assertEquals(3_100_000L, probe?.measuredDownloadBps)
        assertEquals(18, probe?.activePeers)
    }

    @Test
    fun concurrentProbesAreSerializedAndCancelledProbeCleansUpTemporaryStorage() = runBlocking {
        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val removedIds = mutableListOf<String>()
        var seq = 0

        val transport = EngineTransport { method, path, body ->
            when {
                method == "POST" && path == "/add" -> {
                    val cur = activeCount.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                    val id = "tmp-${++seq}"
                    EngineResponse(200, """{"id":"$id","infoHash":"hash-$seq"}""")
                }
                method == "GET" && path.startsWith("/torrent/") -> {
                    EngineResponse(
                        200,
                        """{"id":"${path.substringAfterLast('/')}","ready":true,"done":false,"paused":false,"progress":0.01,"downloadSpeed":1200000,"uploadSpeed":0,"numPeers":7,"length":1000000,"downloaded":1024,"uploaded":0,"files":[{"index":0,"name":"clip.mp4","path":"clip.mp4","length":1000000,"progress":0.01,"type":"video/mp4"}]}""",
                    )
                }
                method == "POST" && path == "/focus" -> EngineResponse(200, """{"ok":true}""")
                method == "POST" && path == "/remove" -> {
                    activeCount.decrementAndGet()
                    synchronized(removedIds) { removedIds += (body ?: "") }
                    EngineResponse(200, """{"ok":true}""")
                }
                else -> error("Unexpected $method $path")
            }
        }

        val coordinator = SwarmDiagnosticsCoordinator(
            engineClient = EngineClient(transport),
            delayFn = { delay(35L) },
            maxProbeDurationMs = 150L,
            postReadySampleWindowMs = 60L,
            pollIntervalMs = 35L,
        )

        val h1 = "1111111111111111111111111111111111111111"
        val h2 = "2222222222222222222222222222222222222222"
        coroutineScope {
            val jobs = listOf(
                async { coordinator.probeSingle(sampleResult(h1, "A")) },
                async { coordinator.probeSingle(sampleResult(h2, "B")) },
            )
            jobs.awaitAll()
        }
        assertEquals(1, maxConcurrent.get())
        assertEquals(2, removedIds.size)

        // Now verify NonCancellable cleanup on abrupt coroutine cancellation.
        val h3 = "3333333333333333333333333333333333333333"
        val slowCoordinator = SwarmDiagnosticsCoordinator(
            engineClient = EngineClient(transport),
            delayFn = { delay(10_000L) },
            maxProbeDurationMs = 20_000L,
            postReadySampleWindowMs = 10_000L,
            pollIntervalMs = 5_000L,
        )
        val job = launch { slowCoordinator.probeSingle(sampleResult(h3, "C")) }
        delay(50L)
        job.cancelAndJoin()
        assertEquals(3, removedIds.size)
        assertFalse(removedIds.last().contains("\"destroyStore\":false"))
    }

    @Test
    fun selectTargetVideoFilePrefersRequestedEpisodeInSeasonPack() {
        val ep1 = TorrentFile(0, "Show.S02E01.1080p.mkv", "Show.S02E01.1080p.mkv", 1_100_000_000L, 0.0, "video/x-matroska")
        val ep2 = TorrentFile(1, "Show.S02E02.1080p.mkv", "Show.S02E02.1080p.mkv", 1_400_000_000L, 0.0, "video/x-matroska")
        val ep3 = TorrentFile(2, "Show.S02E03.1080p.mkv", "Show.S02E03.1080p.mkv", 1_600_000_000L, 0.0, "video/x-matroska")

        val chosen = SwarmDiagnosticsCoordinator.selectTargetVideoFile(
            files = listOf(ep1, ep2, ep3),
            season = 2,
            episode = 2,
        )
        assertEquals(1, chosen?.index)
    }

    @Test
    fun claimExternalOwnershipCancelsProbeWithoutDestroyingStore() = runBlocking {
        val removedCount = AtomicInteger(0)
        val addedHash = "4444444444444444444444444444444444444444"
        val transport = EngineTransport { method, path, _ ->
            when {
                method == "POST" && path == "/add" ->
                    EngineResponse(200, """{"id":"shared-rec","infoHash":"$addedHash"}""")
                method == "GET" && path == "/torrent/shared-rec" ->
                    EngineResponse(200, """{"id":"shared-rec","infoHash":"$addedHash","ready":false,"done":false,"paused":false,"progress":0.0,"downloadSpeed":0,"uploadSpeed":0,"numPeers":2,"length":0,"downloaded":0,"uploaded":0,"files":[]}""")
                method == "POST" && path == "/remove" -> {
                    removedCount.incrementAndGet()
                    EngineResponse(200, """{"ok":true}""")
                }
                else -> error("Unexpected $method $path")
            }
        }
        val coordinator = SwarmDiagnosticsCoordinator(
            engineClient = EngineClient(transport),
            delayFn = { delay(2_000L) },
            maxProbeDurationMs = 10_000L,
            pollIntervalMs = 2_000L,
        )
        val probeJob = launch { coordinator.probeSingle(sampleResult(addedHash, "Claim")) }
        delay(60L)
        coordinator.claimExternalOwnership(addedHash)
        probeJob.join()
        assertEquals(0, removedCount.get())
    }
}
