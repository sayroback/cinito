package org.cinito.online.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.metadata.MediaTitleParser
import webtor.core.EngineClient
import webtor.core.TorrentFile
import webtor.core.TorrentStatus
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded Tier-2 active swarm prober.
 * Uses [Semaphore](1) so at most one temporary engine session runs at any moment,
 * respects strict time and downloaded-byte budgets, verifies container audio/FastStart
 * headers, and guarantees temporary storage cleanup even on coroutine cancellation.
 */
class SwarmDiagnosticsCoordinator(
    private val engineClient: EngineClient? = null,
    private val activeEngineIdByHash: (String) -> String? = { null },
    private val containerProbeFn: suspend (engineId: String, fileIndex: Int, fileLength: Long) -> ContainerProbeResult? = { _, _, _ -> null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val maxProbeDurationMs: Long = DEFAULT_MAX_PROBE_DURATION_MS,
    private val postReadySampleWindowMs: Long = DEFAULT_POST_READY_SAMPLE_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val maxByteBudget: Long = DEFAULT_MAX_BYTE_BUDGET,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val canUseNetwork: () -> Boolean = { true },
) {
    private val probeSemaphore = Semaphore(1)
    private val cache = ConcurrentHashMap<String, CachedProbe>()
    private val claimedHashes = ConcurrentHashMap<String, Long>()
    private val activeProbeJobs = ConcurrentHashMap<String, Job>()

    private data class CachedProbe(
        val result: SwarmProbeResult,
        val expiresAtMs: Long,
    )

    val isAvailable: Boolean get() = engineClient != null

    /**
     * Marks [infoHash] as claimed by playback/library preparation and cancels any in-flight
     * temporary probe for it so the teardown `finally` block cannot destroy its engine store.
     */
    fun claimExternalOwnership(infoHash: String) {
        val norm = normalizeHashKey(infoHash) ?: return
        claimedHashes[norm] = clockMs() + EXTERNAL_CLAIM_TTL_MS
        activeProbeJobs[norm]?.cancel()
    }

    private fun isClaimedExternally(normHash: String): Boolean {
        val expires = claimedHashes[normHash] ?: return false
        if (clockMs() > expires) {
            claimedHashes.remove(normHash, expires)
            return false
        }
        return true
    }

    private fun normalizeHashKey(raw: String): String? {
        val clean = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        return SearchUtils.decodeInfoHashBytes(clean)?.first ?: clean.lowercase(Locale.ROOT)
    }

    fun getCached(infoHash: String): SwarmProbeResult? {
        val key = normalizeHashKey(infoHash) ?: return null
        val entry = cache[key] ?: return null
        if (clockMs() > entry.expiresAtMs) {
            cache.remove(key, entry)
            return null
        }
        return entry.result
    }

    suspend fun probeSingle(
        result: TorrentSearchResult,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false,
    ): SwarmProbeResult? {
        val client = engineClient ?: return null
        val hash = result.normalizedInfoHash ?: return null
        if (!forceRefresh) {
            getCached(hash)?.let { return it }
        }

        return withContext(ioDispatcher) {
            probeSemaphore.withPermit {
                if (!forceRefresh) {
                    getCached(hash)?.let { return@withPermit it }
                }

                val existingId = activeEngineIdByHash(hash)
                if (existingId == null && (isClaimedExternally(hash) || !canUseNetwork())) {
                    return@withPermit if (!canUseNetwork()) {
                        SwarmProbeResult(
                            infoHash = hash,
                            ttfmMs = 0L,
                            measuredDownloadBps = 0L,
                            activePeers = 0,
                            errorMessage = "Solo Wi-Fi activo (datos móviles bloqueados)",
                            measuredAtMs = clockMs(),
                        )
                    } else {
                        null
                    }
                }

                val currentJob = coroutineContext[Job]
                if (currentJob != null) {
                    activeProbeJobs[hash] = currentJob
                }
                var createdTempId: String? = null
                val targetId: String = try {
                    if (existingId != null) {
                        existingId
                    } else {
                        val added = client.add(result.magnetUri, prepare = true)
                        createdTempId = added.id
                        added.id
                    }
                } catch (ce: CancellationException) {
                    if (currentJob != null) activeProbeJobs.remove(hash, currentJob)
                    throw ce
                } catch (t: Throwable) {
                    if (currentJob != null) activeProbeJobs.remove(hash, currentJob)
                    val failed = SwarmProbeResult(
                        infoHash = hash,
                        ttfmMs = 0L,
                        measuredDownloadBps = 0L,
                        activePeers = 0,
                        errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "Error al iniciar diagnóstico",
                        measuredAtMs = clockMs(),
                    )
                    cacheResult(hash, failed, shortTtl = true)
                    return@withPermit failed
                }

                try {
                    runBoundedMeasurement(
                        client = client,
                        targetId = targetId,
                        hash = hash,
                        isTemporary = createdTempId != null,
                        season = season,
                        episode = episode,
                    )
                } finally {
                    if (currentJob != null) activeProbeJobs.remove(hash, currentJob)
                    if (createdTempId != null) {
                        withContext(NonCancellable) {
                            val nowOwnedExternally = activeEngineIdByHash(hash) != null || isClaimedExternally(hash)
                            if (!nowOwnedExternally) {
                                runCatching { client.remove(createdTempId, destroyStore = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun probeBatch(
        candidates: List<TorrentSearchResult>,
        maxCount: Int = 3,
        season: Int? = null,
        episode: Int? = null,
        onEachResult: (SwarmProbeResult) -> Unit = {},
    ): Map<String, SwarmProbeResult> {
        if (engineClient == null || maxCount <= 0) return emptyMap()
        val deduped = candidates
            .distinctBy { it.normalizedInfoHash ?: it.id }
            .filter { it.normalizedInfoHash != null }
            .take(maxCount)
        if (deduped.isEmpty()) return emptyMap()

        val out = LinkedHashMap<String, SwarmProbeResult>(deduped.size)
        for (candidate in deduped) {
            val probed = probeSingle(candidate, season = season, episode = episode) ?: continue
            out[probed.infoHash] = probed
            onEachResult(probed)
        }
        return out
    }

    private suspend fun runBoundedMeasurement(
        client: EngineClient,
        targetId: String,
        hash: String,
        isTemporary: Boolean,
        season: Int?,
        episode: Int?,
    ): SwarmProbeResult {
        val startedAt = clockMs()
        var firstReadyAtMs: Long? = null
        var peakSpeedBps = 0L
        var peakPeers = 0
        var peakConnectedSeeders: Int? = null
        var lastStatus: TorrentStatus? = null
        var targetFile: TorrentFile? = null
        var containerProbe: ContainerProbeResult? = null
        var containerProbed = false
        var terminalError: String? = null

        while (true) {
            val status = try {
                client.torrent(targetId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                terminalError = t.message?.takeIf { it.isNotBlank() } ?: "Error consultando torrent"
                break
            }

            lastStatus = status
            val now = clockMs()
            peakSpeedBps = maxOf(peakSpeedBps, status.downloadSpeed.coerceAtLeast(0L))
            peakPeers = maxOf(peakPeers, status.numPeers.coerceAtLeast(0))
            status.connectedSeeders?.let { cs ->
                peakConnectedSeeders = maxOf(peakConnectedSeeders ?: 0, cs.coerceAtLeast(0))
            }

            if ((status.ready || status.files.isNotEmpty()) && firstReadyAtMs == null) {
                firstReadyAtMs = now
                targetFile = selectTargetVideoFile(status.files, season, episode)
                if (isTemporary && targetFile != null) {
                    runCatching { client.focus(targetId, targetFile.index) }
                }
            }

            if (!containerProbed && targetFile != null) {
                containerProbed = true
                containerProbe = try {
                    containerProbeFn(targetId, targetFile.index, targetFile.length)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
            }

            if (!status.error.isNullOrBlank()) {
                terminalError = status.error
                break
            }

            val elapsed = (clockMs() - startedAt).coerceAtLeast(0L)
            if (isTemporary && status.downloaded >= maxByteBudget) break
            if (elapsed >= maxProbeDurationMs) break

            val readyAt = firstReadyAtMs
            val postReadyElapsed = if (readyAt != null) (clockMs() - readyAt).coerceAtLeast(0L) else 0L
            if (readyAt != null && containerProbed && postReadyElapsed >= postReadySampleWindowMs) {
                // Once we have header metadata and either observed speed or waited the post-ready window, finish early.
                break
            }

            delayFn(pollIntervalMs)
        }

        val endNow = clockMs()
        val ttfm = ((firstReadyAtMs ?: endNow) - startedAt).coerceAtLeast(1L)
        val effectiveError = terminalError ?: if (lastStatus?.files.isNullOrEmpty() && peakPeers == 0) {
            "Sin respuesta del swarm (${ttfm}ms)"
        } else {
            null
        }

        val verifiedSummary = containerProbe?.audioTracks
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.displayLabel }

        val probeResult = SwarmProbeResult(
            infoHash = hash,
            ttfmMs = ttfm,
            measuredDownloadBps = peakSpeedBps,
            activePeers = peakPeers,
            connectedSeeders = peakConnectedSeeders,
            swarmAvailability = lastStatus?.swarmAvailability,
            isFastStartMp4 = containerProbe?.isFastStartMp4,
            hasVerifiedLatinoAudio = containerProbe?.hasLatinoAudio == true,
            hasVerifiedSpanishAudio = containerProbe?.hasAnySpanishAudio == true,
            verifiedAudioSummary = verifiedSummary,
            targetFileSizeBytes = targetFile?.length,
            errorMessage = effectiveError,
            measuredAtMs = endNow,
        )
        cacheResult(hash, probeResult, shortTtl = effectiveError != null && peakPeers == 0)
        return probeResult
    }

    private fun cacheResult(hash: String, probe: SwarmProbeResult, shortTtl: Boolean) {
        val ttl = if (shortTtl) SHORT_ERROR_CACHE_TTL_MS else cacheTtlMs
        cache[hash] = CachedProbe(probe, clockMs() + ttl)
    }

    companion object {
        const val DEFAULT_MAX_PROBE_DURATION_MS = 4_500L
        const val DEFAULT_POST_READY_SAMPLE_MS = 1_200L
        const val DEFAULT_POLL_INTERVAL_MS = 220L
        const val DEFAULT_MAX_BYTE_BUDGET = 3L * 1024L * 1024L
        const val DEFAULT_CACHE_TTL_MS = 10L * 60_000L
        private const val EXTERNAL_CLAIM_TTL_MS = 60_000L
        private const val SHORT_ERROR_CACHE_TTL_MS = 90_000L

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts", "flv", "wmv", "mpg", "mpeg",
        )

        internal fun selectTargetVideoFile(
            files: List<TorrentFile>,
            season: Int?,
            episode: Int?,
        ): TorrentFile? {
            val videos = files.filter { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val pathExt = file.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                ext in VIDEO_EXTENSIONS || pathExt in VIDEO_EXTENSIONS || file.type.startsWith("video/", ignoreCase = true)
            }
            if (videos.isEmpty()) return null
            if (videos.size == 1) return videos.first()

            if (season != null && episode != null) {
                val matched = videos.filter { file ->
                    val parsed = MediaTitleParser.parse(file.path.ifBlank { file.name })
                    (parsed.season == season && parsed.episode == episode) ||
                        (season == 1 && parsed.season == null && parsed.episode == episode)
                }
                if (matched.isNotEmpty()) {
                    return matched.maxByOrNull { it.length }
                }
            }
            return videos.maxByOrNull { it.length }
        }
    }
}
