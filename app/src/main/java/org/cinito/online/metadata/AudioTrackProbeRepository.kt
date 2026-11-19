package org.cinito.online.metadata

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cinito.online.search.SearchUtils
import webtor.app.DownloadEntry
import webtor.app.EngineHost
import webtor.app.isVideoName
import webtor.core.EngineClient
import webtor.core.TorrentFile
import java.io.File
import java.util.Locale

fun interface ProbeSourceOpener {
    fun open(
        engineId: String?,
        fileIndex: Int,
        fileLength: Long,
        contentUri: String?,
    ): ProbeByteSource?
}

fun interface ProbeRunner {
    fun probe(source: ProbeByteSource): ContainerProbeResult?
}

data class MagnetProbeOutcome(
    val infoHash: String?,
    val fileIndex: Int,
    val fileName: String,
    val result: ContainerProbeResult,
)

data class MagnetProbeAttempt(
    val outcome: MagnetProbeOutcome? = null,
    val failureReason: String? = null,
)

interface MagnetProbeDriver {
    suspend fun probeCandidate(
        magnetUri: String,
        infoHashHint: String?,
        preferredFileIndex: Int? = null,
        timeoutMs: Long = 20_000L,
    ): MagnetProbeOutcome?

    suspend fun probeCandidateDetailed(
        magnetUri: String,
        infoHashHint: String?,
        preferredFileIndex: Int? = null,
        timeoutMs: Long = 20_000L,
    ): MagnetProbeAttempt {
        val outcome = probeCandidate(magnetUri, infoHashHint, preferredFileIndex, timeoutMs)
        return MagnetProbeAttempt(
            outcome = outcome,
            failureReason = if (outcome == null) "Sin respuesta del torrent" else null,
        )
    }
}

@Serializable
private data class PersistedTrackRecord(
    val kind: ProbeTrackKind,
    val languageCode: String? = null,
    val bcp47: String? = null,
    val name: String? = null,
    val codec: String? = null,
    val regionTag: AudioLanguageTag,
)

@Serializable
private data class PersistedProbeRecord(
    val containerFormat: String,
    val tracks: List<PersistedTrackRecord>,
)

@Serializable
private data class PersistedProbeStore(
    val version: Int = 1,
    val entries: Map<String, PersistedProbeRecord> = emptyMap(),
)

/**
 * Serializes container-header probing across the app and persists discovered audio/subtitle tracks
 * keyed by both library entry/file index and torrent infoHash so cards show real tracks pre-click.
 */
class AudioTrackProbeRepository(
    private val cacheDir: File,
    private val sourceOpener: ProbeSourceOpener,
    private val probeRunner: ProbeRunner = ProbeRunner { MediaContainerProbe.probe(it) },
    private val magnetDriver: MagnetProbeDriver? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeSemaphore = Semaphore(1)
    private val persistMutex = Mutex()
    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ContainerProbeResult?>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val storeFile = File(cacheDir, CACHE_FILE_NAME)

    private val _probes = MutableStateFlow(loadPersistedProbes())
    val probes: StateFlow<Map<String, ContainerProbeResult>> = _probes.asStateFlow()

    private val _probingKeys = MutableStateFlow<Set<String>>(emptySet())
    val probingKeys: StateFlow<Set<String>> = _probingKeys.asStateFlow()

    private val _unavailableKeys = MutableStateFlow<Set<String>>(emptySet())
    val unavailableKeys: StateFlow<Set<String>> = _unavailableKeys.asStateFlow()

    private val _failureReasons = MutableStateFlow<Map<String, String>>(emptyMap())
    val failureReasons: StateFlow<Map<String, String>> = _failureReasons.asStateFlow()

    private val unavailableLocalUris = MutableStateFlow<Set<String>>(emptySet())

    fun cachedForEntry(entryKey: String?, fileIndex: Int): ContainerProbeResult? =
        lookupFromMap(_probes.value, entryKey = entryKey, infoHash = null, fileIndex = fileIndex)

    fun cachedForHash(infoHash: String?, fileIndex: Int? = null): ContainerProbeResult? =
        lookupFromMap(_probes.value, entryKey = null, infoHash = infoHash, fileIndex = fileIndex)

    fun cachedPrimaryForHash(infoHash: String?): ContainerProbeResult? =
        infoHash?.takeIf { it.isNotBlank() }?.let { _probes.value[hashPrimaryKey(it)] }

    suspend fun probeFile(
        entryKey: String?,
        infoHash: String?,
        fileIndex: Int,
        fileName: String,
        fileLength: Long,
        engineId: String?,
        contentUri: String?,
        forceRefresh: Boolean = false,
    ): ContainerProbeResult? {
        if (!isInspectableContainerName(fileName) && !forceRefresh) return null
        val targetKeys = buildList {
            entryKey?.takeIf { it.isNotBlank() }?.let { add(entryKey(it, fileIndex)) }
            infoHash?.takeIf { it.isNotBlank() }?.let { add(hashKey(it, fileIndex)) }
        }
        val primaryKey = targetKeys.firstOrNull() ?: return null

        if (!forceRefresh) {
            val existing = lookupFromMap(_probes.value, entryKey, infoHash, fileIndex)
            if (existing != null) {
                storeProbeResult(targetKeys, existing, setHashPrimaryIfEmpty = infoHash)
                return existing
            }
        }
        if (contentUri.isNullOrBlank() && engineId.isNullOrBlank()) return null

        return runDeduplicated(primaryKey, targetKeys) {
            val probed = probeSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    val source = runCatching {
                        sourceOpener.open(engineId, fileIndex, fileLength, contentUri)
                    }.getOrNull() ?: return@withContext null
                    source.use { s ->
                        runCatching { probeRunner.probe(s) }.getOrNull()
                    }
                }
            }
            if (probed != null) {
                storeProbeResult(targetKeys, probed, setHashPrimaryIfEmpty = infoHash)
                _unavailableKeys.update { it - targetKeys.toSet() }
                if (!contentUri.isNullOrBlank()) {
                    unavailableLocalUris.update { it - contentUri }
                }
            } else {
                if (!contentUri.isNullOrBlank()) {
                    unavailableLocalUris.update { it + contentUri }
                } else {
                    _unavailableKeys.update { it + targetKeys }
                }
            }
            probed
        }
    }

    suspend fun probeCandidate(
        magnetUri: String,
        infoHash: String?,
        preferredFileIndex: Int? = null,
        forceRefresh: Boolean = false,
        timeoutMs: Long = 20_000L,
    ): ContainerProbeResult? {
        val resolvedHash = (infoHash?.takeIf { it.isNotBlank() } ?: SearchUtils.extractInfoHash(magnetUri))
            ?.lowercase(Locale.ROOT)
        val primaryHashKey = resolvedHash?.let(::hashPrimaryKey)
        val dedupeKey = primaryHashKey ?: "magnet:${magnetUri.hashCode()}"

        if (!forceRefresh && resolvedHash != null) {
            cachedForHash(resolvedHash, preferredFileIndex)?.let { return it }
        }
        val driver = magnetDriver ?: return null

        val trackKeys = listOfNotNull(primaryHashKey)
        return runDeduplicated(dedupeKey, trackKeys) {
            val attempt = probeSemaphore.withPermit {
                driver.probeCandidateDetailed(
                    magnetUri = magnetUri,
                    infoHashHint = resolvedHash,
                    preferredFileIndex = preferredFileIndex,
                    timeoutMs = timeoutMs,
                )
            }
            val outcome = attempt.outcome
            if (outcome != null) {
                val finalHash = outcome.infoHash?.takeIf { it.isNotBlank() } ?: resolvedHash
                val keys = buildList {
                    if (finalHash != null) {
                        add(hashKey(finalHash, outcome.fileIndex))
                        add(hashPrimaryKey(finalHash))
                    } else {
                        add(dedupeKey)
                    }
                }
                storeProbeResult(keys, outcome.result, setHashPrimaryIfEmpty = null)
                _unavailableKeys.update { it - keys.toSet() - dedupeKey }
                _failureReasons.update { it - keys.toSet() - dedupeKey }
                outcome.result
            } else {
                val reason = attempt.failureReason ?: "Sin respuesta del torrent"
                _unavailableKeys.update { it + trackKeys + dedupeKey }
                _failureReasons.update { map ->
                    map + (trackKeys + dedupeKey).associateWith { reason }
                }
                null
            }
        }
    }

    fun scheduleLibraryWarmup(entries: List<DownloadEntry>, maxNewProbes: Int = 24) {
        scope.launch {
            probeLibraryEntries(entries, maxNewProbes = maxNewProbes)
        }
    }

    suspend fun probeLibraryEntries(entries: List<DownloadEntry>, maxNewProbes: Int = 24) {
        data class Target(
            val entry: DownloadEntry,
            val fileIndex: Int,
            val fileName: String,
            val fileLength: Long,
            val contentUri: String?,
            val infoHash: String?,
            val priority: Int,
        )

        val candidates = mutableListOf<Target>()
        for (entry in entries) {
            val infoHash = extractEntryInfoHash(entry)
            val videos = entry.files.filter {
                (it.name.isVideoName() || isInspectableContainerName(it.name)) &&
                    isInspectableContainerName(it.name) &&
                    (!it.uri.isNullOrBlank() || !entry.engineId.isNullOrBlank())
            }
            videos.forEachIndexed { idx, file ->
                val eKey = entryKey(entry.key, file.index)
                val uri = file.uri?.takeIf { it.isNotBlank() }
                val hasLocal = uri != null
                if (eKey in _probes.value) return@forEachIndexed
                if (hasLocal && uri in unavailableLocalUris.value) return@forEachIndexed
                if (!hasLocal && eKey in _unavailableKeys.value) return@forEachIndexed
                val isPrimary = idx == 0 || file.index == entry.focusedFile
                val prio = when {
                    hasLocal && isPrimary -> 0
                    hasLocal -> 1
                    isPrimary -> 2
                    else -> 3
                }
                candidates += Target(entry, file.index, file.name, file.length, file.uri, infoHash, prio)
            }
        }

        candidates.sortedBy { it.priority }.take(maxNewProbes).forEach { target ->
            probeFile(
                entryKey = target.entry.key,
                infoHash = target.infoHash,
                fileIndex = target.fileIndex,
                fileName = target.fileName,
                fileLength = target.fileLength,
                engineId = target.entry.engineId,
                contentUri = target.contentUri,
            )
        }
    }

    private suspend fun runDeduplicated(
        dedupeKey: String,
        trackKeys: List<String>,
        block: suspend () -> ContainerProbeResult?,
    ): ContainerProbeResult? {
        var ownedDeferred: CompletableDeferred<ContainerProbeResult?>? = null
        val existing: Deferred<ContainerProbeResult?>? = inFlightMutex.withLock {
            val cur = inFlight[dedupeKey]
            if (cur != null && cur.isActive) {
                cur
            } else {
                CompletableDeferred<ContainerProbeResult?>().also { created ->
                    inFlight[dedupeKey] = created
                    ownedDeferred = created
                }
                null
            }
        }
        if (existing != null) return existing.await()

        val deferred = ownedDeferred ?: return block()
        val activeMarkers = (trackKeys + dedupeKey).toSet()
        _probingKeys.update { it + activeMarkers }
        _unavailableKeys.update { it - activeMarkers }
        return try {
            val res = block()
            deferred.complete(res)
            res
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            null
        } finally {
            _probingKeys.update { it - activeMarkers }
            inFlightMutex.withLock { inFlight.remove(dedupeKey, deferred) }
        }
    }

    private suspend fun storeProbeResult(
        keys: List<String>,
        result: ContainerProbeResult,
        setHashPrimaryIfEmpty: String?,
    ) {
        if (keys.isEmpty() && setHashPrimaryIfEmpty.isNullOrBlank()) return
        _probes.update { current ->
            val updated = current.toMutableMap()
            keys.forEach {
                updated.remove(it)
                updated[it] = result
            }
            val primaryHash = setHashPrimaryIfEmpty?.takeIf { it.isNotBlank() }?.let(::hashPrimaryKey)
            if (primaryHash != null && primaryHash !in updated) {
                updated[primaryHash] = result
            }
            updated
        }
        persistCurrentMap()
    }

    private suspend fun persistCurrentMap() {
        persistMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    cacheDir.mkdirs()
                    val snapshot = _probes.value
                    val encodedMap = snapshot.entries.toList().takeLast(MAX_PERSISTED_ENTRIES).associate { (k, v) ->
                        k to PersistedProbeRecord(
                            containerFormat = v.containerFormat,
                            tracks = v.tracks.map { t ->
                                PersistedTrackRecord(
                                    kind = t.kind,
                                    languageCode = t.languageCode,
                                    bcp47 = t.bcp47,
                                    name = t.name,
                                    codec = t.codec,
                                    regionTag = t.regionTag,
                                )
                            },
                        )
                    }
                    val tmp = File(cacheDir, "$CACHE_FILE_NAME.tmp")
                    tmp.writeText(json.encodeToString(PersistedProbeStore(entries = encodedMap)))
                    if (!tmp.renameTo(storeFile)) {
                        storeFile.writeText(tmp.readText())
                        tmp.delete()
                    }
                }
            }
        }
    }

    private fun loadPersistedProbes(): Map<String, ContainerProbeResult> {
        if (!storeFile.exists()) return emptyMap()
        return runCatching {
            val decoded = json.decodeFromString<PersistedProbeStore>(storeFile.readText())
            decoded.entries.mapValues { (_, record) ->
                ContainerProbeResult(
                    containerFormat = record.containerFormat,
                    tracks = record.tracks.map { t ->
                        ProbedTrack(
                            kind = t.kind,
                            languageCode = t.languageCode,
                            bcp47 = t.bcp47,
                            name = t.name,
                            codec = t.codec,
                            regionTag = t.regionTag,
                        )
                    },
                )
            }
        }.getOrElse { emptyMap() }
    }

    companion object {
        private const val CACHE_FILE_NAME = "audio_track_probes_v1.json"
        private const val MAX_PERSISTED_ENTRIES = 600
        private val INSPECTABLE_EXT = setOf("mkv", "mka", "webm", "mp4", "m4v", "mov")

        fun isInspectableContainerName(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in INSPECTABLE_EXT

        fun entryKey(entryKey: String, fileIndex: Int): String = "entry:$entryKey:$fileIndex"

        fun hashKey(infoHash: String, fileIndex: Int): String =
            "hash:${infoHash.trim().lowercase(Locale.ROOT)}:$fileIndex"

        fun hashPrimaryKey(infoHash: String): String =
            "hash:${infoHash.trim().lowercase(Locale.ROOT)}:primary"

        fun extractEntryInfoHash(entry: DownloadEntry): String? =
            entry.status?.infoHash?.takeIf { it.isNotBlank() }
                ?: entry.key.takeIf { it.length == 40 }
                ?: SearchUtils.extractInfoHash(entry.source)

        fun lookupFromMap(
            map: Map<String, ContainerProbeResult>,
            entryKey: String?,
            infoHash: String?,
            fileIndex: Int?,
        ): ContainerProbeResult? {
            if (!entryKey.isNullOrBlank() && fileIndex != null) {
                map[entryKey(entryKey, fileIndex)]?.let { return it }
            }
            if (!infoHash.isNullOrBlank()) {
                if (fileIndex != null) {
                    map[hashKey(infoHash, fileIndex)]?.let { return it }
                }
                map[hashPrimaryKey(infoHash)]?.let { return it }
            }
            return null
        }

        fun createDefault(
            appContext: Context,
            engine: EngineHost,
            engineClient: EngineClient,
            activeInfoHashesProvider: () -> Set<String>,
            canUseNetwork: () -> Boolean = { true },
            cacheDir: File = File(appContext.cacheDir, "audio_probes"),
        ): AudioTrackProbeRepository = AudioTrackProbeRepository(
            cacheDir = cacheDir,
            sourceOpener = { engineId, fileIndex, fileLength, contentUri ->
                val uri = contentUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
                if (uri != null) {
                    ContentUriByteSource(appContext, uri, fileLength)
                } else if (!engineId.isNullOrBlank()) {
                    EnginePreviewByteSource(engine, engineId, fileIndex, fileLength)
                } else {
                    null
                }
            },
            magnetDriver = EngineMagnetProbeDriver(
                engine = engine,
                engineClient = engineClient,
                activeInfoHashesProvider = activeInfoHashesProvider,
                canUseNetwork = canUseNetwork,
                logger = { android.util.Log.i("AudioProbe", it) },
            ),
        )
    }
}

internal class EngineMagnetProbeDriver(
    private val engineClient: EngineClient,
    private val activeInfoHashesProvider: () -> Set<String>,
    private val previewOpener: (engineId: String, fileIndex: Int, fileLength: Long) -> ProbeByteSource?,
    private val probeRunner: ProbeRunner = ProbeRunner { MediaContainerProbe.probe(it) },
    private val canUseNetwork: () -> Boolean = { true },
    private val logger: (String) -> Unit = {},
) : MagnetProbeDriver {
    constructor(
        engine: EngineHost,
        engineClient: EngineClient,
        activeInfoHashesProvider: () -> Set<String>,
        probeRunner: ProbeRunner = ProbeRunner { MediaContainerProbe.probe(it) },
        canUseNetwork: () -> Boolean = { true },
        logger: (String) -> Unit = {},
    ) : this(
        engineClient = engineClient,
        activeInfoHashesProvider = activeInfoHashesProvider,
        previewOpener = { id, idx, len ->
            EnginePreviewByteSource(engine, id, idx, len).takeIf { it.isOpen }
        },
        probeRunner = probeRunner,
        canUseNetwork = canUseNetwork,
        logger = logger,
    )

    override suspend fun probeCandidate(
        magnetUri: String,
        infoHashHint: String?,
        preferredFileIndex: Int?,
        timeoutMs: Long,
    ): MagnetProbeOutcome? = probeCandidateDetailed(
        magnetUri = magnetUri,
        infoHashHint = infoHashHint,
        preferredFileIndex = preferredFileIndex,
        timeoutMs = timeoutMs,
    ).outcome

    override suspend fun probeCandidateDetailed(
        magnetUri: String,
        infoHashHint: String?,
        preferredFileIndex: Int?,
        timeoutMs: Long,
    ): MagnetProbeAttempt {
        val enrichedMagnet = enrichMagnetTrackers(magnetUri)
        val normalized = runCatching { engineClient.normalize(enrichedMagnet) }.getOrNull()
        val resolvedHash = (normalized?.infoHash ?: infoHashHint ?: SearchUtils.extractInfoHash(enrichedMagnet))
            ?.lowercase(Locale.ROOT)
        val probeId = normalized?.id ?: resolvedHash

        val existingStatus = probeId?.let { runCatching { engineClient.torrent(it) }.getOrNull() }
        val wasAlreadyLoaded = existingStatus != null ||
            (resolvedHash != null && resolvedHash in activeInfoHashesProvider())

        if (!canUseNetwork() && existingStatus?.files?.none { it.verifiedBytes >= it.length && it.length > 0L } != false) {
            logger("Blocked probe hash=$resolvedHash: Solo Wi-Fi activo")
            return MagnetProbeAttempt(failureReason = "Solo Wi-Fi activo")
        }

        if (existingStatus?.paused == true &&
            existingStatus.files.none { it.verifiedBytes >= it.length && it.length > 0L }
        ) {
            logger("Paused library torrent hash=$resolvedHash: resume required for network probe")
            return MagnetProbeAttempt(failureReason = "En pausa en biblioteca")
        }

        var tempAddedId: String? = null
        val activeId: String
        if (existingStatus != null) {
            activeId = existingStatus.id
        } else {
            val added = withContext(Dispatchers.IO) {
                runCatching { engineClient.add(enrichedMagnet, prepare = true) }.getOrNull()
            } ?: return MagnetProbeAttempt(failureReason = "Error al abrir torrent")
            activeId = added.id
            if (!wasAlreadyLoaded) {
                tempAddedId = added.id
            }
        }

        val startTime = System.currentTimeMillis()
        try {
            var status = existingStatus
            while (status == null || status.files.isEmpty()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= timeoutMs) {
                    logger("Metadata timeout hash=$resolvedHash peers=${status?.numPeers ?: 0} after ${elapsed}ms")
                    return MagnetProbeAttempt(
                        failureReason = "Sin metadatos (${status?.numPeers ?: 0} peers)",
                    )
                }
                delay(300)
                status = withContext(Dispatchers.IO) {
                    runCatching { engineClient.torrent(activeId) }.getOrNull()
                }
                if (status?.error != null) {
                    logger("Torrent error hash=$resolvedHash: ${status.error}")
                    return MagnetProbeAttempt(failureReason = "Error en torrent")
                }
            }

            val targetFile = pickTargetVideoFile(status.files, preferredFileIndex)
            if (targetFile == null) {
                val names = status.files.joinToString { it.name }.take(80)
                logger("No inspectable MKV/MP4 file hash=$resolvedHash files=[$names]")
                return MagnetProbeAttempt(failureReason = "Sin soporte")
            }
            logger("Target file hash=$resolvedHash name=${targetFile.name} len=${targetFile.length} peers=${status.numPeers}")

            val probeStartTime = System.currentTimeMillis()
            val probeBudgetMs = maxOf(timeoutMs, 20_000L)
            var activePreview: ProbeByteSource? = null
            var lastOpenFailed = false
            try {
                repeat(4) { attempt ->
                    val elapsed = System.currentTimeMillis() - probeStartTime
                    if (attempt > 0 && elapsed >= probeBudgetMs) return@repeat
                    val probed = withContext(Dispatchers.IO) {
                        val src = activePreview ?: previewOpener(activeId, targetFile.index, targetFile.length)
                            ?.also { activePreview = it }
                        if (src == null) {
                            lastOpenFailed = true
                            return@withContext null
                        }
                        lastOpenFailed = false
                        runCatching { probeRunner.probe(src) }
                            .onFailure { logger("Probe exception hash=$resolvedHash: ${it.message}") }
                            .getOrNull()
                    }
                    if (probed != null) {
                        logger("Probe OK hash=$resolvedHash format=${probed.containerFormat} audio=${probed.audioTracks.size} subs=${probed.subtitleTracks.size}")
                        return MagnetProbeAttempt(
                            outcome = MagnetProbeOutcome(
                                infoHash = status.infoHash ?: resolvedHash,
                                fileIndex = targetFile.index,
                                fileName = targetFile.name,
                                result = probed,
                            ),
                        )
                    }
                    if (attempt < 3) delay(350)
                }
            } finally {
                withContext(Dispatchers.IO) {
                    runCatching { activePreview?.close() }
                }
            }
            val reason = if (lastOpenFailed) "Preview no disponible" else "Timeout cabecera"
            logger("Probe failed hash=$resolvedHash reason=$reason name=${targetFile.name}")
            return MagnetProbeAttempt(failureReason = reason)
        } finally {
            if (tempAddedId != null) {
                withContext(NonCancellable) {
                    val nowActive = resolvedHash != null && resolvedHash in activeInfoHashesProvider()
                    if (!nowActive) {
                        val currentStatus = withContext(Dispatchers.IO) {
                            runCatching { engineClient.torrent(tempAddedId) }.getOrNull()
                        }
                        val promotedToDownloadOrPlay = currentStatus != null &&
                            currentStatus.files.any { it.progress > 0.05 }
                        if (!promotedToDownloadOrPlay) {
                            runCatching { engineClient.remove(tempAddedId, destroyStore = true) }
                        }
                    }
                }
            }
        }
    }

    internal fun enrichMagnetTrackers(magnetUri: String): String {
        val trimmed = magnetUri.trim()
        if (!trimmed.startsWith("magnet:?", ignoreCase = true)) return trimmed
        val existingLower = trimmed.lowercase(Locale.ROOT)
        val missing = FALLBACK_PUBLIC_TRACKERS.filter { tracker ->
            val encoded = java.net.URLEncoder.encode(tracker, "UTF-8").lowercase(Locale.ROOT)
            !existingLower.contains(tracker.lowercase(Locale.ROOT)) && !existingLower.contains(encoded)
        }
        if (missing.isEmpty()) return trimmed
        val suffix = missing.joinToString("") { "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        return trimmed + suffix
    }

    companion object {
        private val FALLBACK_PUBLIC_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
        )
    }

    internal fun pickTargetVideoFile(files: List<TorrentFile>, preferredIndex: Int?): TorrentFile? {
        if (preferredIndex != null) {
            files.firstOrNull { it.index == preferredIndex && AudioTrackProbeRepository.isInspectableContainerName(it.name) }
                ?.let { return it }
        }
        val inspectable = files.filter { AudioTrackProbeRepository.isInspectableContainerName(it.name) && it.length > 0L }
        val nonSample = inspectable.filterNot { it.name.contains("sample", ignoreCase = true) }
        return (nonSample.ifEmpty { inspectable }).maxByOrNull { it.length }
    }
}
