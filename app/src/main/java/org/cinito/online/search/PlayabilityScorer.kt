package org.cinito.online.search

import org.cinito.online.cast.WebOsCastMode
import org.cinito.online.metadata.MediaTitleParser
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

enum class PlayabilityVerdict(val label: String) {
    Optimal("Fluido"),
    Good("Bueno"),
    Risky("Riesgo de pausas"),
    Dead("Sin fuentes vivas"),
}

data class TrackerSwarmStats(
    val seeders: Int,
    val leechers: Int,
    val completed: Int = 0,
    val updatedAtMs: Long = 0L,
)

data class SwarmProbeResult(
    val infoHash: String,
    val ttfmMs: Long,
    val measuredDownloadBps: Long,
    val activePeers: Int,
    val connectedSeeders: Int? = null,
    val swarmAvailability: Double? = null,
    val isFastStartMp4: Boolean? = null,
    val hasVerifiedLatinoAudio: Boolean = false,
    val hasVerifiedSpanishAudio: Boolean = false,
    val verifiedAudioSummary: String? = null,
    val targetFileSizeBytes: Long? = null,
    val errorMessage: String? = null,
    val measuredAtMs: Long = 0L,
) {
    val measuredMbps: Double
        get() = (measuredDownloadBps.coerceAtLeast(0L) * 8.0) / 1_000_000.0
}

data class PlayabilityContext(
    val expectedDurationMinutes: Int? = null,
    val requestedSeason: Int? = null,
    val requestedEpisode: Int? = null,
    val isCastingToTv: Boolean = false,
    val activeWebOsMode: WebOsCastMode? = null,
    val inLibraryHashes: Set<String> = emptySet(),
) {
    companion object {
        private val HOURS_MINUTES_REGEX = Regex("""(?i)(\d+)\s*h(?:\s*(\d+)\s*m)?""")
        private val MINUTES_REGEX = Regex("""(?i)(\d+)\s*min""")

        fun parseRuntimeMinutes(runtime: String?): Int? {
            val text = runtime?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
            HOURS_MINUTES_REGEX.find(text)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toIntOrNull() ?: 0
                val total = h * 60 + min
                if (total in 5..600) return total
            }
            MINUTES_REGEX.find(text)?.let { m ->
                val total = m.groupValues[1].toIntOrNull()
                if (total != null && total in 5..600) return total
            }
            val plain = text.toIntOrNull()
            return plain?.takeIf { it in 5..600 }
        }
    }
}

data class PlayabilityAssessment(
    val score: Int,
    val verdict: PlayabilityVerdict,
    val estimatedBitrateMbps: Double?,
    val effectiveSeeders: Int,
    val effectivePeers: Int,
    val isLiveScraped: Boolean = false,
    val headroomRatio: Double? = null,
    val reasons: List<String> = emptyList(),
    val probe: SwarmProbeResult? = null,
)

/**
 * Pure domain evaluator computing a 0..100 streaming playability score and verdict
 * from Tier 0 heuristics (bitrate vs seeds, contention, codec/Cast compatibility),
 * Tier 1 live tracker scrape metrics, and Tier 2 bounded engine probes.
 */
object PlayabilityScorer {
    private val CAM_REGEX = Regex("""(?i)\b(hdcam|camrip|cam|hdts|telesync|telecine|workprint|wp|scr|screener|ts)\b""")
    private val HEAVY_AUDIO_REGEX = Regex("""(?i)\b(truehd|dts(?:-hd)?|dts-ma|pcm)\b""")
    private val LEGACY_CONTAINER_REGEX = Regex("""(?i)\.(avi|divx|xvid|iso|rar)\b|\b(xvid|divx)\b""")
    private val BROWSER_FRAGILE_VIDEO_REGEX = Regex("""(?i)\b(x265|h\.?265|hevc|av1|10bit|dolby\.?vision|\bdv\b)\b""")

    fun evaluate(
        result: TorrentSearchResult,
        context: PlayabilityContext = PlayabilityContext(),
        scrape: TrackerSwarmStats? = null,
        probe: SwarmProbeResult? = null,
    ): PlayabilityAssessment {
        val normalizedHash = result.normalizedInfoHash
        val inLibrary = normalizedHash != null && normalizedHash in context.inLibraryHashes

        val isLiveScraped = scrape != null
        val effSeeders = scrape?.seeders ?: result.seeders
        val effPeers = scrape?.leechers ?: result.peers
        val estimatedBitrateMbps = estimateBitrateMbps(result, context, probe)
        val reasons = mutableListOf<String>()

        if (estimatedBitrateMbps != null) {
            reasons += "~${formatOneDecimal(estimatedBitrateMbps)} Mbps req."
        }

        if (inLibrary) {
            reasons.add(0, "Ya en biblioteca")
            val headroom = computeHeadroom(probe, estimatedBitrateMbps)
            return PlayabilityAssessment(
                score = 98,
                verdict = PlayabilityVerdict.Optimal,
                estimatedBitrateMbps = estimatedBitrateMbps,
                effectiveSeeders = effSeeders,
                effectivePeers = effPeers,
                isLiveScraped = isLiveScraped,
                headroomRatio = headroom,
                reasons = reasons,
                probe = probe,
            )
        }

        val liveSeedersFromProbe = probe?.connectedSeeders ?: 0
        val hasMeasuredFlow = (probe?.measuredDownloadBps ?: 0L) > 0L
        if (effSeeders <= 0 && liveSeedersFromProbe <= 0 && !hasMeasuredFlow) {
            val deadReason = if (isLiveScraped) {
                "Trackers vivos reportan 0 semillas"
            } else {
                "0 semillas reportadas"
            }
            return PlayabilityAssessment(
                score = if (effPeers > 0) 8 else 2,
                verdict = PlayabilityVerdict.Dead,
                estimatedBitrateMbps = estimatedBitrateMbps,
                effectiveSeeders = 0,
                effectivePeers = effPeers,
                isLiveScraped = isLiveScraped,
                reasons = listOf(deadReason) + reasons,
                probe = probe,
            )
        }

        var score = 20.0

        // 1. Supply points on log scale (0..42)
        val supplyRef = maxOf(effSeeders, liveSeedersFromProbe)
        val supplyPts = (ln(1.0 + supplyRef.toDouble()) / ln(80.0)).coerceIn(0.0, 1.0) * 42.0
        score += supplyPts

        // 2. Bitrate-vs-seeder viability
        var forceRisky = false
        if (estimatedBitrateMbps != null) {
            when {
                estimatedBitrateMbps <= 10.0 -> {
                    score += 12.0
                }
                estimatedBitrateMbps <= 22.0 -> {
                    score += if (supplyRef >= 10) 8.0 else -6.0
                }
                estimatedBitrateMbps <= 38.0 -> {
                    if (supplyRef < 15) {
                        score -= 16.0
                        forceRisky = true
                        reasons += "Bitrate exigente para $supplyRef semillas"
                    } else {
                        score += 4.0
                    }
                }
                else -> {
                    if (supplyRef < 30) {
                        score -= 26.0
                        forceRisky = true
                        reasons += "Remux pesado (${formatOneDecimal(estimatedBitrateMbps)} Mbps) con pocas semillas"
                    } else {
                        score -= 2.0
                    }
                }
            }
        }

        // 3. Seeder / Leecher contention
        if (effPeers > 0) {
            val ratio = supplyRef.toDouble() / effPeers.toDouble()
            when {
                effPeers >= 20 && ratio < 0.25 -> {
                    score -= 10.0
                    reasons += "Mucha congestión de leechers"
                }
                ratio >= 1.5 && supplyRef >= 6 -> score += 8.0
            }
        }

        // 4. Quality & format heuristics
        val badges = result.parsed.qualityBadges
        when {
            CAM_REGEX.containsMatchIn(result.title) -> {
                score -= 25.0
                forceRisky = true
                reasons += "Calidad CAM / grabada"
            }
            "1080p" in badges -> score += 12.0
            "720p" in badges -> score += 9.0
            "4K" in badges -> score += 7.0
        }

        if (context.requestedEpisode != null && result.parsed.isSeasonPack && probe?.targetFileSizeBytes == null) {
            score -= 10.0
            reasons += "Pack de temporada (tarda más en preparar)"
        }

        if (LEGACY_CONTAINER_REGEX.containsMatchIn(result.title)) {
            score -= 18.0
            forceRisky = true
            reasons += "Formato antiguo / comprimido"
        }

        // 5. Cast / device compatibility hints
        if (context.isCastingToTv) {
            if (context.activeWebOsMode == WebOsCastMode.Browser &&
                (BROWSER_FRAGILE_VIDEO_REGEX.containsMatchIn(result.title) || HEAVY_AUDIO_REGEX.containsMatchIn(result.title))
            ) {
                score -= 16.0
                reasons += "Códec arriesgado en navegador TV (usa DLNA)"
            } else if (HEAVY_AUDIO_REGEX.containsMatchIn(result.title)) {
                score -= 6.0
                reasons += "Audio sin pérdida pesado para Cast"
            }
        }

        // 6. Active swarm probe (Tier 2 & Rust availability)
        var headroom: Double? = null
        var incompleteSwarm = false
        if (probe != null) {
            if (probe.swarmAvailability != null && probe.swarmAvailability < 1.0) {
                incompleteSwarm = true
                forceRisky = true
                score -= 35.0
                reasons.add(0, "Enjambre incompleto (${(probe.swarmAvailability * 100).roundToInt()}%)")
            }
            if (probe.errorMessage != null && probe.measuredDownloadBps <= 0L) {
                forceRisky = true
                score -= 28.0
                reasons.add(0, probe.errorMessage)
            } else {
                headroom = computeHeadroom(probe, estimatedBitrateMbps)
                if (headroom != null) {
                    when {
                        headroom >= 2.5 -> {
                            score += 22.0
                            reasons.add(0, "${formatOneDecimal(probe.measuredMbps)} Mbps reales (${formatOneDecimal(headroom)}× margen)")
                        }
                        headroom >= 1.35 -> {
                            score += 12.0
                            reasons.add(0, "${formatOneDecimal(probe.measuredMbps)} Mbps reales (${formatOneDecimal(headroom)}× margen)")
                        }
                        else -> {
                            forceRisky = true
                            score -= 22.0
                            reasons.add(0, "Velocidad justa (${formatOneDecimal(probe.measuredMbps)} Mbps · ${formatOneDecimal(headroom)}×)")
                        }
                    }
                } else if (probe.measuredDownloadBps > 0L) {
                    reasons.add(0, "${formatOneDecimal(probe.measuredMbps)} Mbps medidos")
                }

                when {
                    probe.ttfmMs in 1L..1_500L -> score += 6.0
                    probe.ttfmMs > 3_500L -> score -= 8.0
                }
                if (probe.isFastStartMp4 == false) {
                    score -= 6.0
                    reasons += "MP4 índice al final"
                } else if (probe.isFastStartMp4 == true) {
                    score += 3.0
                }
                probe.verifiedAudioSummary?.let { reasons += it }
            }
        }

        val finalScore = score.roundToInt().coerceIn(1, 100)
        val verdict = when {
            incompleteSwarm -> PlayabilityVerdict.Risky
            headroom != null -> when {
                !forceRisky && headroom >= 2.0 && finalScore >= 65 -> PlayabilityVerdict.Optimal
                !forceRisky && headroom >= 1.25 && finalScore >= 45 -> PlayabilityVerdict.Good
                else -> PlayabilityVerdict.Risky
            }
            forceRisky -> PlayabilityVerdict.Risky
            finalScore >= 70 && supplyRef >= 8 -> PlayabilityVerdict.Optimal
            finalScore >= 45 && supplyRef >= 3 -> PlayabilityVerdict.Good
            else -> PlayabilityVerdict.Risky
        }

        return PlayabilityAssessment(
            score = finalScore,
            verdict = verdict,
            estimatedBitrateMbps = estimatedBitrateMbps,
            effectiveSeeders = supplyRef,
            effectivePeers = effPeers,
            isLiveScraped = isLiveScraped,
            headroomRatio = headroom,
            reasons = reasons.distinct().take(3),
            probe = probe,
        )
    }

    private fun estimateBitrateMbps(
        result: TorrentSearchResult,
        context: PlayabilityContext,
        probe: SwarmProbeResult?,
    ): Double? {
        val rawBytes = probe?.targetFileSizeBytes ?: result.sizeBytes
        if (rawBytes == null || rawBytes <= 0L) return null
        val adjustedBytes = if (probe?.targetFileSizeBytes == null && result.parsed.isSeasonPack) {
            rawBytes.toDouble() / 10.0
        } else {
            rawBytes.toDouble()
        }
        val durationMin = context.expectedDurationMinutes
            ?: when {
                context.requestedEpisode != null -> 45
                result.category == SearchCategory.Anime -> 24
                result.category == SearchCategory.Series -> 45
                else -> 110
            }
        val durationSec = (durationMin * 60.0).coerceAtLeast(60.0)
        return (adjustedBytes * 8.0) / (durationSec * 1_000_000.0)
    }

    private fun computeHeadroom(probe: SwarmProbeResult?, requiredMbps: Double?): Double? {
        if (probe == null || probe.measuredDownloadBps <= 0L || requiredMbps == null || requiredMbps <= 0.1) {
            return null
        }
        return probe.measuredMbps / requiredMbps
    }

    private fun formatOneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
}
