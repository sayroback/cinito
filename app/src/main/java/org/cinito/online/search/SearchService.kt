package org.cinito.online.search

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.network.HardenedNetworkClient
import org.cinito.online.network.NetworkSafety
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class SearchSortOrder(val label: String) {
    BestPlayability("Mejor para reproducir"),
    SeedersDesc("Más seeders"),
    NewestDesc("Más recientes"),
    SizeDesc("Mayor tamaño"),
    SizeAsc("Menor tamaño"),
    TitleAsc("Título A-Z"),
}

/** Timeout de proveedor; su mensaje es el texto visible para el usuario. */
class ProviderTimeoutException(message: String) : RuntimeException(message)

/** Respuesta de proveedor que no pudo interpretarse. */
class ProviderParseException(message: String, cause: Throwable?) : RuntimeException(message, cause)

/** Resumen visible de un fallo de proveedor: clase y mensaje reales, con URLs redactadas. */
internal fun failureSummary(e: Throwable): String =
    if (e is ProviderTimeoutException) {
        e.message.orEmpty()
    } else {
        NetworkSafety.redactUrl("${e::class.simpleName}: ${e.message}")
    }

/** Clase, mensaje y cadena de causas (hasta [maxLevels] niveles) para logs. */
internal fun describeThrowable(e: Throwable, maxLevels: Int = 3): String {
    val parts = ArrayList<String>(maxLevels)
    var cur: Throwable? = e
    while (cur != null && parts.size < maxLevels) {
        val t: Throwable = cur
        parts += "${t::class.simpleName}: ${NetworkSafety.redactUrl(t.message.toString())}"
        cur = t.cause
    }
    return parts.joinToString(" <- causa: ")
}

data class ProviderFailure(
    val providerId: String,
    val providerName: String,
    val message: String,
)

data class SearchBatchUpdate(
    val results: List<TorrentSearchResult>,
    val completedProviders: Int,
    val totalProviders: Int,
    val failures: List<ProviderFailure>,
    val isFinished: Boolean,
    val resolvedContext: SearchTitleResolution? = null,
)

class SearchService(
    private val builtinProviders: List<TorrentSearchProvider>,
    private val torznabFactory: (TorznabConfig) -> TorrentSearchProvider = {
        throw IllegalStateException("Torznab factory not provided")
    },
    private val titleResolver: (suspend (String, SearchCategory) -> SearchTitleResolution?)? = null,
    private val perProviderTimeoutMs: Long = 12_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class ProviderHealth(
        val consecutiveFailures: Int = 0,
        val openUntilMs: Long = 0L,
        val ewmaLatencyMs: Double = 0.0,
    )

    private val providerHealth = ConcurrentHashMap<String, ProviderHealth>()

    private fun isCircuitOpen(providerId: String): Boolean {
        val h = providerHealth[providerId] ?: return false
        return h.consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD && clockMs() < h.openUntilMs
    }

    private fun adaptiveTimeoutMs(providerId: String): Long {
        val ewma = providerHealth[providerId]?.ewmaLatencyMs ?: 0.0
        if (ewma <= 0.0) return perProviderTimeoutMs
        return (ewma * 2.5).toLong().coerceIn(MIN_ADAPTIVE_TIMEOUT_MS, perProviderTimeoutMs)
    }

    private fun recordProviderOutcome(providerId: String, success: Boolean, elapsedMs: Long) {
        var transition: String? = null
        providerHealth.compute(providerId) { _, prev ->
            val cur = prev ?: ProviderHealth()
            if (success) {
                if (cur.consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
                    transition = "circuit CLOSED provider=$providerId tras failures=${cur.consecutiveFailures}"
                }
                val nextEwma = if (cur.ewmaLatencyMs <= 0.0) {
                    elapsedMs.toDouble()
                } else {
                    0.35 * elapsedMs + 0.65 * cur.ewmaLatencyMs
                }
                ProviderHealth(consecutiveFailures = 0, openUntilMs = 0L, ewmaLatencyMs = nextEwma)
            } else {
                val failures = cur.consecutiveFailures + 1
                val openUntil = if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
                    transition = "circuit OPEN provider=$providerId failures=$failures cooldownMs=$CIRCUIT_COOLDOWN_MS"
                    clockMs() + CIRCUIT_COOLDOWN_MS
                } else {
                    0L
                }
                cur.copy(consecutiveFailures = failures, openUntilMs = openUntil)
            }
        }
        transition?.let { msg ->
            if (msg.startsWith("circuit OPEN")) Log.w(TAG, msg) else Log.d(TAG, msg)
        }
    }
    constructor(
        network: HardenedNetworkClient,
        titleResolver: (suspend (String, SearchCategory) -> SearchTitleResolution?)? = null,
    ) : this(
        builtinProviders = listOf(
            TorrentioProvider(network),
            KnabenProvider(network),
            ThePirateBayProvider(network),
            SolidTorrentsProvider(network),
            LimeTorrentsProvider(network),
            BitSearchProvider(network),
            TorrentsCsvProvider(network),
            YtsProvider(network),
            NyaaProvider(network),
            SukebeiProvider(network),
            TheRarBgProvider(network),
            InternetArchiveProvider(network),
            SubsPleaseProvider(network),
        ),
        torznabFactory = { cfg -> TorznabSearchProvider(cfg, network) },
        titleResolver = titleResolver,
    )

    private suspend fun searchQueries(
        provider: TorrentSearchProvider,
        queries: List<String>,
        category: SearchCategory,
        options: SearchRequestOptions,
    ): List<TorrentSearchResult> = coroutineScope {
        queries.map { q ->
            async { provider.search(q, category, options) }
        }.awaitAll().flatten()
    }

    fun allBuiltinInfos(): List<SearchProviderInfo> = builtinProviders.map { it.info }

    fun activeProviders(
        enabledProviderIds: Set<String>,
        torznabConfigs: List<TorznabConfig>,
    ): List<TorrentSearchProvider> {
        val enabledBuiltin = builtinProviders.filter { it.info.id in enabledProviderIds }
        val torznab = torznabConfigs.map(torznabFactory)
        return enabledBuiltin + torznab
    }

    suspend fun enrichResultDetails(
        result: TorrentSearchResult,
        torznabConfigs: List<TorznabConfig> = emptyList(),
    ): TorrentSearchResult {
        val provider = (builtinProviders + torznabConfigs.map(torznabFactory))
            .find { it.info.id == result.providerId }
            ?: return result
        return try {
            withTimeoutOrNull(6_000L) { provider.enrichResult(result) } ?: result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "enrich fallo provider=${provider.info.id} ${describeThrowable(e)}", e)
            result
        }
    }

    fun searchStreaming(
        query: String,
        category: SearchCategory,
        enabledProviderIds: Set<String>,
        torznabConfigs: List<TorznabConfig>,
        sortOrder: SearchSortOrder = SearchSortOrder.SeedersDesc,
        languageFilter: SearchLanguageFilter = SearchLanguageFilter.PrioritizeLatino,
        alternateQuery: String? = null,
        imdbId: String? = null,
        seasonHint: Int? = null,
        episodeHint: Int? = null,
        skipTitleResolution: Boolean = false,
        broadGeneralSearch: Boolean = false,
        hideAdultContent: Boolean = true,
        adultOnly: Boolean = false,
        disabledAdultProviderIds: Set<String> = emptySet(),
    ): Flow<SearchBatchUpdate> = channelFlow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            send(
                SearchBatchUpdate(
                    results = emptyList(),
                    completedProviders = 0,
                    totalProviders = 0,
                    failures = emptyList(),
                    isFinished = true,
                ),
            )
            return@channelFlow
        }

        val effectiveAdultOnly = !hideAdultContent && (adultOnly || category == SearchCategory.Adult)
        val effectiveSkipTitleResolution = skipTitleResolution || effectiveAdultOnly
        val parsedQuery = MediaTitleParser.parse(trimmed)
        val resolved = if (!effectiveSkipTitleResolution && (imdbId.isNullOrBlank() || alternateQuery.isNullOrBlank())) {
            withTimeoutOrNull(3_200L) {
                try {
                    titleResolver?.invoke(trimmed, category)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "resolución de título fallo ${describeThrowable(e)}", e)
                    null
                }
            }
        } else {
            null
        }

        val effectiveImdbId = imdbId?.trim()?.takeIf { it.startsWith("tt") }
            ?: resolved?.imdbId?.takeIf { it.startsWith("tt") }
            ?: trimmed.takeIf { it.startsWith("tt") && it.drop(2).all(Char::isDigit) }

        val effectiveAlternate = alternateQuery?.trim()?.takeIf { it.isNotBlank() }
            ?: resolved?.originalTitle?.takeIf { it.isNotBlank() }

        val resolutionBanner = resolved ?: if (effectiveAlternate != null || effectiveImdbId != null) {
            SearchTitleResolution(
                displayTitle = trimmed,
                originalTitle = effectiveAlternate,
                year = parsedQuery.year,
                imdbId = effectiveImdbId,
            )
        } else {
            null
        }

        val providers = activeProviders(enabledProviderIds, torznabConfigs)
            .filter {
                if (effectiveAdultOnly) {
                    it.info.supportsAdultContent && it.info.id !in disabledAdultProviderIds
                } else {
                    category == SearchCategory.All || category in it.info.categories
                }
            }
            .filter {
                effectiveAdultOnly ||
                    languageFilter != SearchLanguageFilter.OnlyLatino ||
                    it.info.supportsNonEnglishQueries ||
                    it.info.isTorznab
            }
            .filter { provider ->
                !provider.info.requiresImdbSearch || effectiveImdbId != null
            }

        if (providers.isEmpty()) {
            send(
                SearchBatchUpdate(
                    results = emptyList(),
                    completedProviders = 0,
                    totalProviders = 0,
                    failures = emptyList(),
                    isFinished = true,
                    resolvedContext = resolutionBanner,
                ),
            )
            return@channelFlow
        }

        val mutex = Mutex()
        val aggregated = mutableListOf<TorrentSearchResult>()
        val failures = mutableListOf<ProviderFailure>()
        var doneCount = 0

        val requestOptions = SearchRequestOptions(
            broadGeneralSearch = broadGeneralSearch,
            hideAdultContent = if (effectiveAdultOnly) false else hideAdultContent,
            adultOnly = effectiveAdultOnly,
        )
        val effectiveQueryLangFilter = if (effectiveAdultOnly) SearchLanguageFilter.AllLanguages else languageFilter

        coroutineScope {
            providers.map { provider ->
                async {
                    if (isCircuitOpen(provider.info.id)) {
                        Log.d(
                            TAG,
                            "skip provider=${provider.info.id} circuit=open failures=${providerHealth[provider.info.id]?.consecutiveFailures}",
                        )
                        mutex.withLock {
                            doneCount++
                            failures.add(
                                ProviderFailure(
                                    providerId = provider.info.id,
                                    providerName = provider.info.name,
                                    message = "Pausa temporal tras fallos repetidos",
                                ),
                            )
                            val snapshot = applyLanguageFilterAndSort(
                                results = deduplicateResults(aggregated),
                                sortOrder = sortOrder,
                                languageFilter = effectiveQueryLangFilter,
                            )
                            send(
                                SearchBatchUpdate(
                                    results = snapshot,
                                    completedProviders = doneCount,
                                    totalProviders = providers.size,
                                    failures = failures.toList(),
                                    isFinished = doneCount >= providers.size,
                                    resolvedContext = resolutionBanner,
                                ),
                            )
                        }
                        return@async
                    }
                    val startedAt = clockMs()
                    val timeoutMs = adaptiveTimeoutMs(provider.info.id)
                    val queriesForProvider = buildQueriesForProvider(
                        primary = trimmed,
                        alternate = effectiveAlternate,
                        languageFilter = effectiveQueryLangFilter,
                        supportsNonEnglish = provider.info.supportsNonEnglishQueries || provider.info.isTorznab,
                        maxQueries = provider.info.maxQueriesPerSearch,
                    )
                    Log.d(TAG, "start provider=${provider.info.id} queries=${queriesForProvider.size} timeoutMs=$timeoutMs")
                    var phase = "init"
                    val outcome: Result<List<TorrentSearchResult>> = try {
                        val hits = withTimeoutOrNull(timeoutMs) {
                            if (provider.info.supportsImdbSearch && effectiveImdbId != null && !effectiveAdultOnly) {
                                phase = "imdb"
                                val imdbHits = provider.searchByImdb(
                                    imdbId = effectiveImdbId,
                                    category = category,
                                    season = seasonHint ?: parsedQuery.season,
                                    episode = episodeHint ?: parsedQuery.episode,
                                    mediaTypeHint = resolved?.mediaType,
                                )
                                if (imdbHits.isNotEmpty() || provider.info.requiresImdbSearch) {
                                    imdbHits
                                } else {
                                    phase = "text"
                                    searchQueries(provider, queriesForProvider, category, requestOptions)
                                }
                            } else {
                                phase = "text"
                                searchQueries(provider, queriesForProvider, category, requestOptions)
                            }
                        } ?: throw ProviderTimeoutException("Timeout (${timeoutMs / 1000}s) en fase $phase")
                        Result.success(hits)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    val elapsedMs = (clockMs() - startedAt).coerceAtLeast(1L)
                    outcome.onSuccess { list ->
                        Log.d(TAG, "ok provider=${provider.info.id} results=${list.size} elapsedMs=$elapsedMs")
                    }.onFailure { err ->
                        Log.w(
                            TAG,
                            "fail provider=${provider.info.id} fase=$phase elapsedMs=$elapsedMs ${describeThrowable(err)}",
                            err,
                        )
                    }
                    recordProviderOutcome(
                        providerId = provider.info.id,
                        success = outcome.isSuccess,
                        elapsedMs = elapsedMs,
                    )
                    mutex.withLock {
                        doneCount++
                        outcome.onSuccess { list ->
                            val filteredList = when {
                                effectiveAdultOnly && !provider.info.hasStrictAdultCategory ->
                                    list.filter {
                                        it.category == SearchCategory.Adult ||
                                            SearchUtils.isAdultContent(it.title, it.description)
                                    }
                                effectiveAdultOnly -> list
                                hideAdultContent ->
                                    list.filterNot { SearchUtils.isAdultContent(it.title, it.description) }
                                else -> list
                            }
                            aggregated.addAll(filteredList)
                        }.onFailure { err ->
                            failures.add(
                                ProviderFailure(
                                    providerId = provider.info.id,
                                    providerName = provider.info.name,
                                    message = failureSummary(err),
                                ),
                            )
                        }
                        val snapshot = applyLanguageFilterAndSort(
                            results = deduplicateResults(aggregated),
                            sortOrder = sortOrder,
                            languageFilter = effectiveQueryLangFilter,
                        )
                        send(
                            SearchBatchUpdate(
                                results = snapshot,
                                completedProviders = doneCount,
                                totalProviders = providers.size,
                                failures = failures.toList(),
                                isFinished = doneCount >= providers.size,
                                resolvedContext = resolutionBanner,
                            ),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    companion object {
        private const val TAG = "SearchService"
        private const val CIRCUIT_FAILURE_THRESHOLD = 2
        private const val CIRCUIT_COOLDOWN_MS = 120_000L
        private const val MIN_ADAPTIVE_TIMEOUT_MS = 3_500L
        private val EXPLICIT_LANG_TOKEN = Regex("""(?i)\b(latino|lat|castellano|espa[nñ]ol|espanol|dual|vose|sub)\b""")
        private val TRAILING_YEAR_REGEX = Regex("""\s+(?:19|20)\d{2}$""")

        private fun stripYearAndColons(input: String): String =
            TRAILING_YEAR_REGEX.replace(input, "")
                .replace(':', ' ')
                .replace(Regex("""\s{2,}"""), " ")
                .trim()

        fun buildQueriesForProvider(
            primary: String,
            alternate: String?,
            languageFilter: SearchLanguageFilter,
            supportsNonEnglish: Boolean,
            maxQueries: Int = 3,
        ): List<String> {
            val cleanPrimary = primary.trim()
            val strippedBase = cleanPrimary.substringBefore(':').trim()
                .takeIf { it.isNotBlank() && !it.equals(cleanPrimary, ignoreCase = true) }
            val cleanAlt = alternate?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(cleanPrimary, ignoreCase = true) }

            val hasTrailingYear = TRAILING_YEAR_REGEX.containsMatchIn(cleanPrimary) ||
                (cleanAlt != null && TRAILING_YEAR_REGEX.containsMatchIn(cleanAlt))

            if (!supportsNonEnglish) {
                val base = cleanAlt ?: strippedBase ?: cleanPrimary
                val candidate = if (hasTrailingYear) stripYearAndColons(base).ifBlank { base } else base
                return listOf(candidate).take(1)
            }

            val bestGlobal = cleanAlt ?: strippedBase ?: cleanPrimary
            val cleanPrimaryNoYear = stripYearAndColons(cleanPrimary).ifBlank { cleanPrimary }
            val bestNoYear = stripYearAndColons(cleanAlt ?: strippedBase ?: cleanPrimary).ifBlank { bestGlobal }
            val hasLangKeyword = EXPLICIT_LANG_TOKEN.containsMatchIn(cleanPrimary)
            val ordered = LinkedHashSet<String>()

            if (hasTrailingYear) {
                when (languageFilter) {
                    SearchLanguageFilter.OnlyLatino -> {
                        if (hasLangKeyword) {
                            ordered += cleanPrimary
                            if (!cleanPrimaryNoYear.equals(cleanPrimary, ignoreCase = true)) {
                                ordered += cleanPrimaryNoYear
                            }
                            if (cleanAlt != null && EXPLICIT_LANG_TOKEN.containsMatchIn(cleanAlt)) {
                                ordered += cleanAlt
                            }
                        } else {
                            ordered += "$bestNoYear Latino"
                            if (!cleanPrimaryNoYear.equals(bestNoYear, ignoreCase = true)) {
                                ordered += "$cleanPrimaryNoYear Latino"
                            }
                            ordered += "$bestNoYear Dual"
                        }
                    }
                    SearchLanguageFilter.PrioritizeLatino -> {
                        if (!hasLangKeyword) {
                            ordered += "$bestNoYear Latino"
                        }
                        ordered += bestGlobal
                        if (!cleanPrimaryNoYear.equals(bestNoYear, ignoreCase = true)) {
                            ordered += "$cleanPrimaryNoYear Latino"
                        }
                        ordered += bestNoYear
                        if (!cleanPrimaryNoYear.equals(bestNoYear, ignoreCase = true)) {
                            ordered += cleanPrimaryNoYear
                        }
                    }
                    SearchLanguageFilter.SpanishAll -> {
                        if (!hasLangKeyword) {
                            ordered += "$bestNoYear Latino"
                            ordered += "$bestNoYear Español"
                        }
                        ordered += bestNoYear
                        ordered += bestGlobal
                    }
                    SearchLanguageFilter.AllLanguages -> {
                        ordered += bestGlobal
                        ordered += bestNoYear
                        if (!cleanPrimaryNoYear.equals(bestNoYear, ignoreCase = true)) {
                            ordered += cleanPrimaryNoYear
                        }
                    }
                }
                return ordered.take(maxQueries.coerceAtLeast(1))
            }

            when (languageFilter) {
                SearchLanguageFilter.OnlyLatino -> {
                    if (hasLangKeyword) {
                        ordered += cleanPrimary
                        if (cleanAlt != null) ordered += cleanAlt
                    } else {
                        ordered += "$bestGlobal Latino"
                        if (!cleanPrimary.equals(bestGlobal, ignoreCase = true)) ordered += "$cleanPrimary Latino"
                        ordered += "$bestGlobal Dual"
                    }
                }
                SearchLanguageFilter.PrioritizeLatino -> {
                    if (!hasLangKeyword) {
                        ordered += "$bestGlobal Latino"
                    }
                    ordered += bestGlobal
                    if (!cleanPrimary.equals(bestGlobal, ignoreCase = true)) {
                        ordered += "$cleanPrimary Latino"
                    }
                    if (strippedBase != null && !strippedBase.equals(bestGlobal, ignoreCase = true)) {
                        ordered += strippedBase
                    }
                    ordered += cleanPrimary
                }
                SearchLanguageFilter.SpanishAll -> {
                    if (!hasLangKeyword) {
                        ordered += "$bestGlobal Latino"
                        ordered += "$bestGlobal Español"
                    }
                    ordered += bestGlobal
                    ordered += cleanPrimary
                }
                SearchLanguageFilter.AllLanguages -> {
                    ordered += bestGlobal
                    if (!cleanPrimary.equals(bestGlobal, ignoreCase = true)) ordered += cleanPrimary
                    if (strippedBase != null) ordered += strippedBase
                }
            }
            return ordered.take(maxQueries.coerceAtLeast(1))
        }

        fun applyLanguageFilterAndSort(
            results: List<TorrentSearchResult>,
            sortOrder: SearchSortOrder,
            languageFilter: SearchLanguageFilter,
            playabilityContext: PlayabilityContext = PlayabilityContext(),
            scrapeStatsByHash: Map<String, TrackerSwarmStats> = emptyMap(),
            probeResultsByHash: Map<String, SwarmProbeResult> = emptyMap(),
        ): List<TorrentSearchResult> = evaluatePlayabilityAndSort(
            results = results,
            sortOrder = sortOrder,
            languageFilter = languageFilter,
            playabilityContext = playabilityContext,
            scrapeStatsByHash = scrapeStatsByHash,
            probeResultsByHash = probeResultsByHash,
        ).first

        fun evaluatePlayabilityAndSort(
            results: List<TorrentSearchResult>,
            sortOrder: SearchSortOrder,
            languageFilter: SearchLanguageFilter,
            playabilityContext: PlayabilityContext = PlayabilityContext(),
            scrapeStatsByHash: Map<String, TrackerSwarmStats> = emptyMap(),
            probeResultsByHash: Map<String, SwarmProbeResult> = emptyMap(),
        ): Pair<List<TorrentSearchResult>, Map<String, PlayabilityAssessment>> {
            val filtered = when (languageFilter) {
                SearchLanguageFilter.OnlyLatino -> results.filter {
                    it.languageTag == AudioLanguageTag.Latino ||
                        it.languageTag == AudioLanguageTag.SpanishGeneric
                }
                SearchLanguageFilter.SpanishAll -> results.filter {
                    it.languageTag != AudioLanguageTag.Other
                }
                SearchLanguageFilter.PrioritizeLatino, SearchLanguageFilter.AllLanguages -> results
            }
            val assessments = HashMap<String, PlayabilityAssessment>(filtered.size)
            for (item in filtered) {
                val hash = item.normalizedInfoHash
                assessments[item.id] = PlayabilityScorer.evaluate(
                    result = item,
                    context = playabilityContext,
                    scrape = hash?.let(scrapeStatsByHash::get),
                    probe = hash?.let(probeResultsByHash::get),
                )
            }
            val baseSorted = sortResults(filtered, sortOrder, assessments)
            if (languageFilter != SearchLanguageFilter.PrioritizeLatino) return baseSorted to assessments

            // When prioritizing Latino in BestPlayability, push Dead (0 live seeders) below playable releases,
            // then group by language tier, and rank the best options (verified flow, score) within each tier
            val finalSorted = if (sortOrder == SearchSortOrder.BestPlayability) {
                baseSorted.sortedWith(
                    compareBy<TorrentSearchResult> { assessments[it.id]?.verdict == PlayabilityVerdict.Dead }
                        .thenBy { tagPriority(it.languageTag) }
                        .thenBy { !hasVerifiedFlow(assessments[it.id]) }
                        .thenByDescending { assessments[it.id]?.score ?: 0 }
                        .thenByDescending { assessments[it.id]?.probe?.measuredDownloadBps ?: 0L }
                        .thenByDescending { assessments[it.id]?.effectiveSeeders ?: it.seeders },
                )
            } else {
                baseSorted.sortedBy { tagPriority(it.languageTag) }
            }
            return finalSorted to assessments
        }

        private fun hasVerifiedFlow(assessment: PlayabilityAssessment?): Boolean =
            (assessment?.probe?.measuredDownloadBps ?: 0L) > 0L

        private fun tagPriority(tag: AudioLanguageTag): Int = when (tag) {
            AudioLanguageTag.Latino -> 0
            AudioLanguageTag.SpanishGeneric -> 1
            AudioLanguageTag.SubSpanish -> 2
            AudioLanguageTag.Castellano -> 3
            AudioLanguageTag.Other -> 4
        }

        fun sortResults(
            results: List<TorrentSearchResult>,
            sortOrder: SearchSortOrder,
            assessments: Map<String, PlayabilityAssessment> = emptyMap(),
        ): List<TorrentSearchResult> = when (sortOrder) {
            SearchSortOrder.BestPlayability -> results.sortedWith(
                compareBy<TorrentSearchResult> {
                    (assessments[it.id] ?: PlayabilityScorer.evaluate(it)).verdict == PlayabilityVerdict.Dead
                }
                    .thenBy { !hasVerifiedFlow(assessments[it.id]) }
                    .thenByDescending {
                        assessments[it.id]?.score ?: PlayabilityScorer.evaluate(it).score
                    }
                    .thenByDescending { assessments[it.id]?.probe?.measuredDownloadBps ?: 0L }
                    .thenByDescending { assessments[it.id]?.effectiveSeeders ?: it.seeders }
                    .thenByDescending { it.uploadDateEpochSec ?: 0L },
            )
            SearchSortOrder.SeedersDesc -> results.sortedWith(
                compareByDescending<TorrentSearchResult> { it.seeders }
                    .thenByDescending { it.peers }
                    .thenByDescending { it.uploadDateEpochSec ?: 0L },
            )
            SearchSortOrder.NewestDesc -> results.sortedWith(
                compareByDescending<TorrentSearchResult> { it.uploadDateEpochSec ?: 0L }
                    .thenByDescending { it.seeders },
            )
            SearchSortOrder.SizeDesc -> results.sortedByDescending { it.sizeBytes ?: 0L }
            SearchSortOrder.SizeAsc -> results.sortedBy { it.sizeBytes ?: Long.MAX_VALUE }
            SearchSortOrder.TitleAsc -> results.sortedBy { it.title.lowercase(Locale.ROOT) }
        }

        fun deduplicateResults(items: List<TorrentSearchResult>): List<TorrentSearchResult> {
            val byKey = LinkedHashMap<String, TorrentSearchResult>()
            for (item in items) {
                val dedupeKey = item.infoHash?.takeIf { it.isNotBlank() }?.let { "hash:$it" }
                    ?: "norm:${item.parsed.lookupKey}:${item.sizeBytes ?: 0L}"
                val existing = byKey[dedupeKey]
                if (existing == null) {
                    byKey[dedupeKey] = item
                } else {
                    byKey[dedupeKey] = mergeDuplicates(existing, item)
                }
            }
            return byKey.values.toList()
        }

        private fun mergeDuplicates(a: TorrentSearchResult, b: TorrentSearchResult): TorrentSearchResult {
            val primary = if (a.seeders >= b.seeders) a else b
            val secondary = if (a.seeders >= b.seeders) b else a
            val bestLang = if (tagPriority(a.languageTag) <= tagPriority(b.languageTag)) a else b
            return primary.copy(
                posterUrl = primary.posterUrl ?: secondary.posterUrl,
                imdbId = primary.imdbId ?: secondary.imdbId,
                sizeBytes = primary.sizeBytes ?: secondary.sizeBytes,
                sizeText = primary.sizeText ?: secondary.sizeText,
                uploadDateEpochSec = primary.uploadDateEpochSec ?: secondary.uploadDateEpochSec,
                detailsUrl = primary.detailsUrl ?: secondary.detailsUrl,
                description = primary.description ?: secondary.description,
                sourceRecordId = primary.sourceRecordId ?: secondary.sourceRecordId,
                languageTag = bestLang.languageTag,
                languageEvidence = bestLang.languageEvidence,
            )
        }
    }
}
