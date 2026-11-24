package org.cinito.online.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import android.util.Log
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.metadata.MediaTitleParser
import webtor.core.EngineClient
import webtor.core.TorrentFile
import webtor.core.TorrentStatus
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ProbePriority { USER_CLICK, BACKGROUND_SCAN }

enum class ProbeProfile(
    val maxDurationMs: Long,
    val postReadySampleWindowMs: Long,
    val earlyDeadAbortMs: Long?,
    val unchokeGraceMs: Long = 0L,
) {
    USER_CLICK(6_500L, 2_400L, 4_000L, 1_400L),
    BACKGROUND_SCAN(4_500L, 1_800L, 4_000L, 1_000L),
}

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
    private val priorityGate = PriorityProbeGate()
    private val cache = ConcurrentHashMap<String, CachedProbe>()
    private val claimedHashes = ConcurrentHashMap<String, Long>()
    private val activeProbeJobs = ConcurrentHashMap<String, Job>()

    private class PriorityProbeGate {
        private val semaphore = Semaphore(1)
        private val stateLock = Any()
        private var activeJob: Job? = null
        private var activePriority: ProbePriority? = null
        private val pendingUserClicks = AtomicInteger(0)

        suspend fun <T> withPriority(
            priority: ProbePriority,
            block: suspend () -> T,
        ): T {
            if (priority == ProbePriority.USER_CLICK) {
                pendingUserClicks.incrementAndGet()
                try {
                    val jobToCancel = synchronized(stateLock) {
                        if (activePriority == ProbePriority.BACKGROUND_SCAN) activeJob else null
                    }
                    jobToCancel?.cancel()
                    return semaphore.withPermit {
                        runGuarded(priority, block)
                    }
                } finally {
                    pendingUserClicks.decrementAndGet()
                }
            }

            while (true) {
                while (pendingUserClicks.get() > 0) {
                    delay(50)
                }
                var yielded = false
                val result = semaphore.withPermit {
                    if (pendingUserClicks.get() > 0) {
                        yielded = true
                        @Suppress("UNCHECKED_CAST")
                        return@withPermit null as T
                    }
                    runGuarded(priority, block)
                }
                if (!yielded) return result
            }
        }

        private suspend fun <T> runGuarded(
            priority: ProbePriority,
            block: suspend () -> T,
        ): T {
            val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            synchronized(stateLock) {
                activeJob = currentJob
                activePriority = priority
            }
            return try {
                block()
            } finally {
                synchronized(stateLock) {
                    if (activeJob === currentJob) {
                        activeJob = null
                        activePriority = null
                    }
                }
            }
        }
    }

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
        priority: ProbePriority = ProbePriority.USER_CLICK,
        profile: ProbeProfile = ProbeProfile.USER_CLICK,
    ): SwarmProbeResult? {
        val client = engineClient ?: run {
            Log.w(TAG, "skip reason=no-engine-client")
            return null
        }
        val hash = result.normalizedInfoHash ?: run {
            Log.w(TAG, "skip reason=no-info-hash id=${result.id}")
            return null
        }
        Log.i(TAG, "start hash=${hash.take(8)} priority=$priority profile=$profile force=$forceRefresh season=$season episode=$episode")
        if (!forceRefresh) {
            getCached(hash)?.let {
                Log.i(TAG, "cache-hit hash=${hash.take(8)} verdict=${if (it.isVerifiedLive) "OK" else "FAIL"}")
                return it
            }
        }

        return withContext(ioDispatcher) {
            priorityGate.withPriority(priority) {
                if (!forceRefresh) {
                    getCached(hash)?.let { return@withPriority it }
                }

                val existingId = activeEngineIdByHash(hash)
                if (existingId == null && (isClaimedExternally(hash) || !canUseNetwork())) {
                    Log.w(
                        TAG,
                        "skip hash=${hash.take(8)} reason=${if (canUseNetwork()) "claimed-externally" else "network-policy"} existingId=null",
                    )
                    return@withPriority if (!canUseNetwork()) {
                        SwarmProbeResult(
                            infoHash = hash,
                            ttfmMs = 0L,
                            measuredDownloadBps = 0L,
                            activePeers = 0,
                            errorMessage = "Solo Wi-Fi activo (datos móviles bloqueados)",
                            measuredAtMs = clockMs(),
                            isPolicyBlocked = true,
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
                        Log.i(TAG, "reuse hash=${hash.take(8)} engineId=$existingId temp=false")
                        existingId
                    } else {
                        val added = client.add(result.magnetUri, prepare = true)
                        createdTempId = added.id
                        Log.i(TAG, "added hash=${hash.take(8)} engineId=${added.id} temp=true")
                        added.id
                    }
                } catch (ce: CancellationException) {
                    if (currentJob != null) activeProbeJobs.remove(hash, currentJob)
                    Log.w(TAG, "add cancelled hash=${hash.take(8)}")
                    throw ce
                } catch (t: Throwable) {
                    if (currentJob != null) activeProbeJobs.remove(hash, currentJob)
                    Log.e(TAG, "add failed hash=${hash.take(8)} error=${t.message}", t)
                    val failed = SwarmProbeResult(
                        infoHash = hash,
                        ttfmMs = 0L,
                        measuredDownloadBps = 0L,
                        activePeers = 0,
                        errorMessage = t.message?.takeIf { it.isNotBlank() } ?: "Error al iniciar diagnóstico",
                        measuredAtMs = clockMs(),
                    )
                    cacheResult(hash, failed, shortTtl = true)
                    return@withPriority failed
                }

                try {
                    runBoundedMeasurement(
                        client = client,
                        targetId = targetId,
                        hash = hash,
                        isTemporary = createdTempId != null,
                        season = season,
                        episode = episode,
                        profile = profile,
                    )
                } finally {
                    if (currentJob != null) activeProbeJobs.remove(hash, currentJob)
                    if (createdTempId != null) {
                        withContext(NonCancellable) {
                            val nowOwnedExternally = activeEngineIdByHash(hash) != null || isClaimedExternally(hash)
                            if (!nowOwnedExternally) {
                                runCatching { client.remove(createdTempId, destroyStore = true) }
                                    .onSuccess { Log.i(TAG, "removed hash=${hash.take(8)} engineId=$createdTempId destroyStore=true") }
                                    .onFailure { Log.e(TAG, "remove FAILED hash=${hash.take(8)} engineId=$createdTempId", it) }
                            } else {
                                Log.w(TAG, "remove skipped hash=${hash.take(8)} engineId=$createdTempId reason=owned-externally")
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
        profile: ProbeProfile,
    ): SwarmProbeResult {
        val effectiveMaxDurationMs = if (maxProbeDurationMs != DEFAULT_MAX_PROBE_DURATION_MS) {
            maxProbeDurationMs
        } else {
            profile.maxDurationMs
        }
        val effectivePostReadyWindowMs = if (postReadySampleWindowMs != DEFAULT_POST_READY_SAMPLE_MS) {
            postReadySampleWindowMs
        } else {
            profile.postReadySampleWindowMs
        }
        val hasCustomMaxDuration = maxProbeDurationMs != DEFAULT_MAX_PROBE_DURATION_MS
        val isCustomTestConfig = hasCustomMaxDuration || postReadySampleWindowMs != DEFAULT_POST_READY_SAMPLE_MS
        val startedAt = clockMs()
        var firstReadyAtMs: Long? = null
        var streamConfiguredAtMs: Long? = null
        var baselineDownloaded = 0L
        var peakSpeedBps = 0L
        var peakReceivedBytes = 0L
        var peakPeers = 0
        var peakConnectedSeeders: Int? = null
        var lastStatus: TorrentStatus? = null
        var targetFile: TorrentFile? = null
        var containerProbe: ContainerProbeResult? = null
        var containerProbeJob: Deferred<ContainerProbeResult?>? = null
        var terminalError: String? = null
        var stopReason = "unknown"

        coroutineScope {
            while (true) {
                val status = try {
                    client.torrent(targetId)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    terminalError = t.message?.takeIf { it.isNotBlank() } ?: "Error consultando torrent"
                    stopReason = "status-exception"
                    Log.w(TAG, "poll exception hash=${hash.take(8)} error=$terminalError")
                    break
                }

                lastStatus = status
                val now = clockMs()
                Log.d(
                    TAG,
                    "poll hash=${hash.take(8)} t=${now - startedAt}ms ready=${status.ready} files=${status.files.size} " +
                        "peers=${status.numPeers} seeders=${status.connectedSeeders ?: -1} " +
                        "speedBps=${status.downloadSpeed} downloaded=${status.downloaded} " +
                        "avail=${status.swarmAvailability ?: -1.0} done=${status.done} paused=${status.paused} " +
                        "error=${status.error ?: "-"} phase=${if (firstReadyAtMs == null) "meta" else "payload"}",
                )
                peakSpeedBps = maxOf(peakSpeedBps, status.downloadSpeed.coerceAtLeast(0L))
                peakReceivedBytes = maxOf(peakReceivedBytes, (status.downloaded - baselineDownloaded).coerceAtLeast(0L))
                val cfgAt = streamConfiguredAtMs
                if (cfgAt != null && now > cfgAt) {
                    val deltaBytes = (status.downloaded - baselineDownloaded).coerceAtLeast(0L)
                    val deltaMs = (now - cfgAt).coerceAtLeast(100L)
                    if (deltaBytes > 0L) {
                        peakSpeedBps = maxOf(peakSpeedBps, (deltaBytes * 1_000L) / deltaMs)
                    }
                }
                peakPeers = maxOf(peakPeers, status.numPeers.coerceAtLeast(0))
                status.connectedSeeders?.let { cs ->
                    peakConnectedSeeders = maxOf(peakConnectedSeeders ?: 0, cs.coerceAtLeast(0))
                }

                if ((status.ready || status.files.isNotEmpty()) && firstReadyAtMs == null) {
                    firstReadyAtMs = now
                    val selectedFile = selectTargetVideoFile(status.files, season, episode)
                    targetFile = selectedFile
                    if (isTemporary && selectedFile != null) {
                        baselineDownloaded = status.downloaded.coerceAtLeast(0L)
                        streamConfiguredAtMs = now
                        runCatching {
                            client.configure(
                                id = targetId,
                                descriptors = List(status.files.size) { null },
                                selected = setOf(selectedFile.index),
                                storageMode = "stream",
                                streamCacheBytes = 32L * 1024 * 1024,
                            )
                            client.play(id = targetId, fileIndex = selectedFile.index, offset = 0L, boost = true)
                        }.onFailure {
                            runCatching { client.focus(targetId, selectedFile.index) }
                        }
                    }
                }

                val sc = streamConfiguredAtMs
                val downloadedDelta = if (sc != null) (status.downloaded - baselineDownloaded).coerceAtLeast(0L) else 0L
                if (isTemporary && sc != null && peakSpeedBps <= 0L && !isCustomTestConfig) {
                    // Status speed is an EWMA sampled once per second; per-peer byte counters show flow sooner.
                    val peers = runCatching { client.connections(targetId).peers }.getOrNull().orEmpty()
                    val connBytes = peers.sumOf { it.downloaded.coerceAtLeast(0L) }
                    if (connBytes > 0L) {
                        val dMs = (now - sc).coerceAtLeast(300L)
                        peakSpeedBps = maxOf(peakSpeedBps, (connBytes * 1_000L) / dMs)
                        peakPeers = maxOf(peakPeers, peers.size)
                    }
                }

                // open_preview must not compete with piece 0 while nothing has arrived yet.
                val probeAllowed = !isTemporary || isCustomTestConfig ||
                    downloadedDelta >= 32L * 1024L || peakSpeedBps > 0L
                if (containerProbeJob == null && targetFile != null && probeAllowed) {
                    val fileForProbe = targetFile
                    // Run container header probe concurrently so slow open_preview I/O never starves status polling.
                    Log.i(TAG, "container-probe start hash=${hash.take(8)} file=${fileForProbe.name} index=${fileForProbe.index}")
                    containerProbeJob = async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            containerProbeFn(targetId, fileForProbe.index, fileForProbe.length)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            Log.w(TAG, "container-probe failed hash=${hash.take(8)} error=${t.message}")
                            null
                        }
                    }
                }

                if (!status.error.isNullOrBlank()) {
                    terminalError = status.error
                    stopReason = "engine-error"
                    break
                }

                val elapsed = (clockMs() - startedAt).coerceAtLeast(0L)
                if (isTemporary && status.downloaded >= maxByteBudget) {
                    stopReason = "byte-budget"
                    break
                }
                // Tope global: ninguna prueba sin config custom dura más de maxDuration + 2,5 s.
                if (!isCustomTestConfig && elapsed >= effectiveMaxDurationMs + HARD_CAP_EXTRA_MS) {
                    stopReason = "hard-cap"
                    break
                }
                val readyAt = firstReadyAtMs
                if (readyAt == null) {
                    // Phase 1: waiting for magnet metadata.
                    if (profile.earlyDeadAbortMs != null &&
                        elapsed >= profile.earlyDeadAbortMs &&
                        peakPeers == 0 &&
                        peakSpeedBps == 0L &&
                        peakReceivedBytes == 0L
                    ) {
                        stopReason = "early-dead-meta"
                        break
                    }
                    if (elapsed >= effectiveMaxDurationMs) {
                        stopReason = "meta-timeout"
                        break
                    }
                } else {
                    // Phase 2: payload window counts from metadata, not from probe start.
                    if (hasCustomMaxDuration && elapsed >= effectiveMaxDurationMs) {
                        stopReason = "custom-timeout"
                        break
                    }
                    val postReadyElapsed = (clockMs() - readyAt).coerceAtLeast(0L)
                    val probeDone = containerProbeJob?.isCompleted != false
                    if (!isCustomTestConfig && postReadyElapsed >= 1_000L &&
                        peakSpeedBps >= 300_000L && downloadedDelta >= 64L * 1024L && probeDone
                    ) {
                        stopReason = "success-speed"
                        break
                    }
                    val grace = if (!isCustomTestConfig && peakPeers > 0 && peakSpeedBps == 0L) profile.unchokeGraceMs else 0L
                    val target = effectivePostReadyWindowMs + grace
                    if (postReadyElapsed >= target && (probeDone || postReadyElapsed >= target + 400L)) {
                        stopReason = "payload-window"
                        break
                    }
                    // Hard cap so a stalled stream cannot hold the single probe slot.
                    if (!isCustomTestConfig && postReadyElapsed >= target + 2_500L) {
                        stopReason = "payload-stall-cap"
                        break
                    }
                }

                delayFn(pollIntervalMs)
            }

            val job = containerProbeJob
            if (job != null) {
                if (job.isCompleted && !job.isCancelled) {
                    containerProbe = runCatching { job.await() }.getOrNull()
                } else {
                    job.cancel()
                }
            }
        }

        if (streamConfiguredAtMs != null && terminalError == null) {
            runCatching { client.torrent(targetId) }.getOrNull()?.let { finalStatus ->
                lastStatus = finalStatus
                val finalNow = clockMs()
                peakSpeedBps = maxOf(peakSpeedBps, finalStatus.downloadSpeed.coerceAtLeast(0L))
                val cfgAt = streamConfiguredAtMs
                if (cfgAt != null && finalNow > cfgAt) {
                    val deltaBytes = (finalStatus.downloaded - baselineDownloaded).coerceAtLeast(0L)
                    val deltaMs = (finalNow - cfgAt).coerceAtLeast(100L)
                    if (deltaBytes > 0L) {
                        peakSpeedBps = maxOf(peakSpeedBps, (deltaBytes * 1_000L) / deltaMs)
                    }
                }
                peakReceivedBytes = maxOf(peakReceivedBytes, (finalStatus.downloaded - baselineDownloaded).coerceAtLeast(0L))
                peakPeers = maxOf(peakPeers, finalStatus.numPeers.coerceAtLeast(0))
                finalStatus.connectedSeeders?.let { cs ->
                    peakConnectedSeeders = maxOf(peakConnectedSeeders ?: 0, cs.coerceAtLeast(0))
                }
            }
        }

        val endNow = clockMs()
        val ttfm = ((firstReadyAtMs ?: endNow) - startedAt).coerceAtLeast(1L)
        val noFlow = peakSpeedBps <= 0L && peakReceivedBytes <= 0L
        val effectiveError = terminalError ?: when {
            noFlow && (peakPeers == 0 || lastStatus?.files.isNullOrEmpty()) ->
                "Sin respuesta del swarm (${ttfm}ms)"
            noFlow -> "Peers sin datos: $peakPeers peers, 0 B recibidos"
            else -> null
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
        val isZeroYield = probeResult.measuredDownloadBps <= 0L && (probeResult.connectedSeeders ?: 0) <= 0
        Log.i(
            TAG,
            "probe hash=${hash.take(8)} profile=$profile temp=$isTemporary stop=$stopReason " +
                "ttfm=${probeResult.ttfmMs}ms peers=$peakPeers seeders=${peakConnectedSeeders ?: -1} " +
                "speedBps=$peakSpeedBps recvBytes=$peakReceivedBytes " +
                "file=${targetFile?.name ?: "-"} containerProbe=${containerProbe != null} " +
                "verdict=${if (probeResult.isVerifiedLive) "OK" else "FAIL"} " +
                "error=${effectiveError ?: "-"}",
        )
        cacheResult(
            hash = hash,
            probe = probeResult,
            shortTtl = (effectiveError != null && peakPeers == 0) || isZeroYield,
        )
        return probeResult
    }

    private fun cacheResult(hash: String, probe: SwarmProbeResult, shortTtl: Boolean) {
        val ttl = if (shortTtl) SHORT_ERROR_CACHE_TTL_MS else cacheTtlMs
        cache[hash] = CachedProbe(probe, clockMs() + ttl)
    }

    companion object {
        private const val TAG = "SwarmProbe"
        const val DEFAULT_MAX_PROBE_DURATION_MS = 4_500L
        const val DEFAULT_POST_READY_SAMPLE_MS = 1_200L
        const val DEFAULT_POLL_INTERVAL_MS = 220L
        const val DEFAULT_MAX_BYTE_BUDGET = 3L * 1024L * 1024L
        const val DEFAULT_CACHE_TTL_MS = 10L * 60_000L
        private const val EXTERNAL_CLAIM_TTL_MS = 60_000L
        private const val HARD_CAP_EXTRA_MS = 2_500L
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
