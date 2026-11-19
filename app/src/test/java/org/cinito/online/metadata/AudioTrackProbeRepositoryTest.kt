package org.cinito.online.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import webtor.app.DownloadEntry
import webtor.app.SavedFile
import webtor.core.EngineClient
import webtor.core.EngineResponse
import webtor.core.EngineTransport
import java.util.concurrent.atomic.AtomicInteger

class AudioTrackProbeRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun persistsAndReloadsSingleAndMultiTrackProbesAcrossInstances() = runTest {
        val cacheDir = tempFolder.newFolder("audio_probes")
        val untaggedSingle = ContainerProbeResult(
            containerFormat = "MKV",
            tracks = listOf(
                ProbedTrack(
                    kind = ProbeTrackKind.Audio,
                    languageCode = null,
                    bcp47 = null,
                    name = null,
                    codec = "A_AAC",
                    regionTag = AudioLanguageTag.Other,
                ),
            ),
        )
        val dummySource = object : ProbeByteSource {
            override val length: Long = 1024L
            override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int = -1
            override fun close() = Unit
        }

        val repo1 = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { _, _, _, _ -> dummySource },
            probeRunner = { untaggedSingle },
            magnetDriver = null,
        )

        val result = repo1.probeFile(
            entryKey = "entry-1",
            infoHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            fileIndex = 0,
            fileName = "Movie.2025.mkv",
            fileLength = 1_500_000_000L,
            engineId = null,
            contentUri = "content://media/1",
        )
        assertNotNull(result)
        assertTrue(result?.hasUntaggedSingleAudioTrack == true)

        val repo2 = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { _, _, _, _ -> null },
            probeRunner = { null },
            magnetDriver = null,
        )
        val cachedByEntry = repo2.cachedForEntry("entry-1", 0)
        val cachedByHash = repo2.cachedForHash("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 0)
        val cachedPrimary = repo2.cachedPrimaryForHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        assertNotNull(cachedByEntry)
        assertNotNull(cachedByHash)
        assertNotNull(cachedPrimary)
        assertEquals("1 pista (única · sin idioma)", cachedByHash?.compactAudioBadgeLabel())
    }

    @Test
    fun serializesProbesThroughSemaphoreAndDeduplicatesConcurrentCalls() = runTest {
        val cacheDir = tempFolder.newFolder("audio_probes_serial")
        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val runnerCalls = AtomicInteger(0)

        val dummySource = object : ProbeByteSource {
            override val length: Long = 2048L
            override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int = -1
            override fun close() = Unit
        }
        val sampleProbe = ContainerProbeResult(
            containerFormat = "MKV",
            tracks = listOf(
                ProbedTrack(ProbeTrackKind.Audio, "spa", "es-419", "Latino", "EAC3", AudioLanguageTag.Latino),
                ProbedTrack(ProbeTrackKind.Audio, "eng", "en", "English", "AC3", AudioLanguageTag.Other),
            ),
        )

        val repo = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { _, _, _, _ -> dummySource },
            probeRunner = {
                runnerCalls.incrementAndGet()
                val cur = inFlight.incrementAndGet()
                maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                Thread.sleep(25)
                inFlight.decrementAndGet()
                sampleProbe
            },
            magnetDriver = null,
        )

        val jobs = listOf(
            async { repo.probeFile("e1", null, 0, "ep1.mkv", 500_000_000L, "eng-1", null) },
            async { repo.probeFile("e2", null, 0, "ep2.mkv", 500_000_000L, "eng-2", null) },
            async { repo.probeFile("e1", null, 0, "ep1.mkv", 500_000_000L, "eng-1", null) },
        )
        jobs.awaitAll()

        assertEquals(1, maxConcurrent.get())
        assertEquals(2, runnerCalls.get())
    }

    @Test
    fun magnetCandidateProbeCachesPrimaryHashAndMarksUnavailableOnNull() = runTest {
        val cacheDir = tempFolder.newFolder("audio_probes_magnet")
        val multiAudio = ContainerProbeResult(
            containerFormat = "MP4",
            tracks = listOf(
                ProbedTrack(ProbeTrackKind.Audio, "eng", "en", "English", "AAC", AudioLanguageTag.Other),
                ProbedTrack(ProbeTrackKind.Subtitle, "spa", "es-419", "Latino", "SRT", AudioLanguageTag.Latino),
            ),
        )
        val driver = object : MagnetProbeDriver {
            override suspend fun probeCandidate(
                magnetUri: String,
                infoHashHint: String?,
                preferredFileIndex: Int?,
                timeoutMs: Long,
            ): MagnetProbeOutcome? {
                delay(10)
                if (magnetUri.contains("dead")) return null
                return MagnetProbeOutcome(
                    infoHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    fileIndex = 2,
                    fileName = "Film.mp4",
                    result = multiAudio,
                )
            }
        }

        val repo = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { _, _, _, _ -> null },
            magnetDriver = driver,
        )

        val ok = repo.probeCandidate(
            magnetUri = "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            infoHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        assertNotNull(ok)
        assertEquals(AudioLanguageTag.SubSpanish, repo.cachedPrimaryForHash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")?.primaryAudioTag)

        val fail = repo.probeCandidate(
            magnetUri = "magnet:?xt=urn:btih:deaddeaddeaddeaddeaddeaddeaddeaddeaddead",
            infoHash = "deaddeaddeaddeaddeaddeaddeaddeaddeaddead",
        )
        assertEquals(null, fail)
        assertTrue(
            AudioTrackProbeRepository.hashPrimaryKey("deaddeaddeaddeaddeaddeaddeaddeaddeaddead") in
                repo.unavailableKeys.value,
        )
    }

    @Test
    fun magnetDriverPreparesAndAlwaysRemovesTemporaryTorrentWithoutTouchingLibraryTorrents() = runTest {
        val calls = mutableListOf<Pair<String, String?>>()
        val hashNew = "cccccccccccccccccccccccccccccccccccccccc"
        val transportNew = EngineTransport { method, path, body ->
            calls += path to body
            when {
                method == "GET" && path.startsWith("/normalize") ->
                    EngineResponse(200, """{"id":"$hashNew","infoHash":"$hashNew"}""")
                method == "GET" && path == "/torrent/$hashNew" -> {
                    val alreadyAdded = calls.any { it.first == "/add" }
                    if (!alreadyAdded) {
                        EngineResponse(404, """{"error":"not found"}""")
                    } else {
                        EngineResponse(
                            200,
                            """
                            {
                              "id": "$hashNew",
                              "infoHash": "$hashNew",
                              "ready": true,
                              "done": false,
                              "paused": false,
                              "progress": 0.0,
                              "downloadSpeed": 0,
                              "uploadSpeed": 0,
                              "numPeers": 3,
                              "length": 1000000,
                              "downloaded": 0,
                              "uploaded": 0,
                              "files": [
                                {"index": 0, "name": "sample.mkv", "path": "sample.mkv", "length": 5000000, "progress": 0.0, "type": "video/x-matroska"},
                                {"index": 1, "name": "Movie.2025.Latino.mkv", "path": "Movie.2025.Latino.mkv", "length": 1800000000, "progress": 0.0, "type": "video/x-matroska"}
                              ]
                            }
                            """.trimIndent(),
                        )
                    }
                }
                method == "POST" && path == "/add" ->
                    EngineResponse(200, """{"id":"$hashNew","infoHash":"$hashNew"}""")
                method == "POST" && path == "/remove" ->
                    EngineResponse(200, """{"ok":true}""")
                else -> EngineResponse(404, """{"error":"unexpected"}""")
            }
        }
        val dummySource = object : ProbeByteSource {
            override val length: Long = 1800000000L
            override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int = -1
            override fun close() = Unit
        }
        val pickedIndices = mutableListOf<Int>()
        val driverNew = EngineMagnetProbeDriver(
            engineClient = EngineClient(transportNew),
            activeInfoHashesProvider = { emptySet() },
            previewOpener = { _, idx, _ ->
                pickedIndices += idx
                dummySource
            },
            probeRunner = {
                ContainerProbeResult(
                    "MKV",
                    listOf(ProbedTrack(ProbeTrackKind.Audio, "spa", "es-419", "Latino", "EAC3", AudioLanguageTag.Latino)),
                )
            },
        )
        val outcome = driverNew.probeCandidate("magnet:?xt=urn:btih:$hashNew", hashNew, null, 5_000L)
        assertNotNull(outcome)
        assertEquals(listOf(1), pickedIndices) // Skipped smaller sample.mkv!
        assertTrue(calls.any { it.first == "/add" && it.second?.contains("\"prepare\":true") == true })
        assertTrue(calls.any { it.first == "/remove" && it.second?.contains("\"destroyStore\":true") == true })

        // Existing library torrent must NOT be added or removed!
        val existingCalls = mutableListOf<String>()
        val hashExisting = "dddddddddddddddddddddddddddddddddddddddd"
        val driverExisting = EngineMagnetProbeDriver(
            engineClient = EngineClient { method, path, _ ->
                existingCalls += "$method $path"
                when {
                    path.startsWith("/normalize") -> EngineResponse(200, """{"id":"$hashExisting","infoHash":"$hashExisting"}""")
                    path == "/torrent/$hashExisting" -> EngineResponse(
                        200,
                        """{"id":"$hashExisting","infoHash":"$hashExisting","ready":true,"done":false,"paused":false,"progress":0.1,"downloadSpeed":0,"uploadSpeed":0,"numPeers":1,"length":100,"downloaded":10,"uploaded":0,"files":[{"index":0,"name":"Ep1.mkv","path":"Ep1.mkv","length":900000,"progress":0.1,"type":"video/x-matroska"}]}""",
                    )
                    else -> EngineResponse(404, "{}")
                }
            },
            activeInfoHashesProvider = { setOf(hashExisting) },
            previewOpener = { _, _, _ -> dummySource },
            probeRunner = {
                ContainerProbeResult(
                    "MKV",
                    listOf(ProbedTrack(ProbeTrackKind.Audio, "eng", "en", "English", "AAC", AudioLanguageTag.Other)),
                )
            },
        )
        val existingOutcome = driverExisting.probeCandidate("magnet:?xt=urn:btih:$hashExisting", hashExisting, null, 5_000L)
        assertNotNull(existingOutcome)
        assertFalse(existingCalls.any { it.contains("/add") })
        assertFalse(existingCalls.any { it.contains("/remove") })
    }

    @Test
    fun retriesProbeOnceLocalContentUriAppearsAfterRemoteFailure() = runTest {
        val cacheDir = tempFolder.newFolder("audio_probes_retry_local")
        val dummySource = object : ProbeByteSource {
            override val length: Long = 1024L
            override fun readAt(offset: Long, buffer: ByteArray, size: Int): Int = -1
            override fun close() = Unit
        }
        val probedResult = ContainerProbeResult(
            containerFormat = "MKV",
            tracks = listOf(ProbedTrack(ProbeTrackKind.Audio, "spa", "es-419", "Latino", "EAC3", AudioLanguageTag.Latino)),
        )
        val repo = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { _, _, _, contentUri -> if (contentUri != null) dummySource else null },
            probeRunner = { probedResult },
            magnetDriver = null,
        )

        val remoteOnlyEntry = DownloadEntry(
            key = "e-retry",
            title = "Serie 2025",
            source = "magnet:?xt=urn:btih:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            metadata = "",
            engineId = "eng-retry",
            destination = "/downloads",
            files = listOf(SavedFile(0, "Ep1.mkv", "Ep1.mkv", 500_000_000L, uri = null)),
            selected = setOf(0),
        )
        repo.probeLibraryEntries(listOf(remoteOnlyEntry))
        assertEquals(null, repo.cachedForEntry("e-retry", 0))

        val completedLocalEntry = remoteOnlyEntry.copy(
            files = listOf(SavedFile(0, "Ep1.mkv", "Ep1.mkv", 500_000_000L, uri = "content://media/retry/0")),
        )
        repo.probeLibraryEntries(listOf(completedLocalEntry))
        assertEquals(AudioLanguageTag.Latino, repo.cachedForEntry("e-retry", 0)?.primaryAudioTag)
    }

    @Test
    fun blocksRemoteMagnetProbeWhenWifiOnlyBlocksNetwork() = runTest {
        val cacheDir = tempFolder.newFolder("audio_probes_wifi_block")
        val calls = mutableListOf<String>()
        val hash = "ffffffffffffffffffffffffffffffffffffffff"
        val driver = EngineMagnetProbeDriver(
            engineClient = EngineClient { method, path, _ ->
                calls += "$method $path"
                when {
                    path.startsWith("/normalize") -> EngineResponse(200, """{"id":"$hash","infoHash":"$hash"}""")
                    else -> EngineResponse(404, "{}")
                }
            },
            activeInfoHashesProvider = { emptySet() },
            previewOpener = { _, _, _ -> null },
            canUseNetwork = { false },
        )
        val repo = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { _, _, _, _ -> null },
            magnetDriver = driver,
        )

        val result = repo.probeCandidate(
            magnetUri = "magnet:?xt=urn:btih:$hash",
            infoHash = hash,
        )
        assertEquals(null, result)
        assertFalse(calls.any { it.contains("/add") })
        assertEquals("Solo Wi-Fi activo", repo.failureReasons.value[AudioTrackProbeRepository.hashPrimaryKey(hash)])
    }
}
