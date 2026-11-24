package org.cinito.online.ui.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.search.PlayabilityAssessment
import org.cinito.online.search.PlayabilityVerdict
import org.cinito.online.search.SearchCategory
import org.cinito.online.search.SearchUtils
import org.cinito.online.search.TorrentSearchResult
import org.cinito.online.ui.library.AudioLanguageBadge
import org.cinito.online.ui.library.CandidateAudioProbeButton
import org.cinito.online.ui.library.InlineTrackBreakdownPanel
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.WebtorApp
import webtor.app.formatSpeed
import java.util.Locale

private val REDUNDANT_LANGUAGE_BADGES = setOf(
    "latino",
    "dual",
    "castellano",
    "español",
    "espanol",
    "sub",
    "subs",
    "multi",
)

/**
 * Clean 3-zone editorial release card:
 * 1. Top status bar: Audio/Subtitles on the left, Live Connections & Playability on the right.
 * 2. Full-width untruncated filename in the center with zero horizontal clutter.
 * 3. Unified bottom bar: File size + technical resolution/codec specs on the left, compact actions on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentReleaseCard(
    result: TorrentSearchResult,
    inLibrary: Boolean,
    isEnriching: Boolean,
    assessment: PlayabilityAssessment? = null,
    isTopRecommended: Boolean = false,
    isProbingSwarm: Boolean = false,
    isCastingToTv: Boolean = false,
    showCleanTitle: Boolean = true,
    onInspectDetails: (() -> Unit)?,
    onDiagnoseSwarm: (() -> Unit)? = null,
    onStream: () -> Unit,
    onChooseFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val probeRepo = remember(context) { (context.applicationContext as WebtorApp).audioProbeRepository }
    val probesMap by probeRepo.probes.collectAsState()
    val verifiedProbe = AudioTrackProbeRepository.lookupFromMap(probesMap, null, result.infoHash, null)
    var tracksExpanded by remember(result.id, result.infoHash) { mutableStateOf(false) }

    val shownSeeders = assessment?.effectiveSeeders ?: result.seeders
    val shownPeers = assessment?.effectivePeers ?: result.peers
    val isLiveScraped = assessment?.isLiveScraped == true

    val techSpecs = remember(result.parsed.episodeBadge, result.parsed.qualityBadges) {
        buildList {
            result.parsed.episodeBadge?.takeIf { it.isNotBlank() }?.let { add(it) }
            result.parsed.qualityBadges
                .filterNot { it.trim().lowercase(Locale.ROOT) in REDUNDANT_LANGUAGE_BADGES }
                .take(3)
                .forEach { add(it) }
        }
    }

    val extraFileLine = remember(result.description, result.title) {
        result.description
            ?.split(" · ")
            ?.firstOrNull { part ->
                !part.startsWith("Audio:", ignoreCase = true) &&
                    !part.startsWith("Subtítulos:", ignoreCase = true) &&
                    !part.startsWith("Fuente:", ignoreCase = true) &&
                    !part.startsWith("Pista/archivo", ignoreCase = true) &&
                    !part.equals(result.title, ignoreCase = true)
            }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    val isDead = assessment?.verdict == PlayabilityVerdict.Dead
    val probe = assessment?.probe
    val isVerifiedWorking = !isProbingSwarm && probe != null && probe.measuredDownloadBps > 0L
    val isFailedDead = !isProbingSwarm && !isVerifiedWorking && isDead

    Surface(
        onClick = onStream,
        modifier = modifier
            .fillMaxWidth()
            .then(if (isFailedDead) Modifier.alpha(0.58f) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(
            width = if (isProbingSwarm || isVerifiedWorking || isTopRecommended) 1.25.dp else 1.dp,
            color = when {
                isProbingSwarm -> CinemaColors.Gold.copy(alpha = 0.72f)
                isVerifiedWorking -> CinemaColors.Secondary.copy(alpha = 0.65f)
                inLibrary -> CinemaColors.Secondary.copy(alpha = 0.48f)
                isTopRecommended -> CinemaColors.Secondary.copy(alpha = 0.55f)
                else -> CinemaColors.HairlineBorder
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Zone 1: Semantic Header (Audio + Subtitles on Left | Playability & Seeders on Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AudioLanguageBadge(
                        tag = result.languageTag,
                        evidence = result.languageEvidence,
                        verifiedProbe = verifiedProbe,
                        expanded = if (verifiedProbe != null) tracksExpanded else null,
                        onClick = if (verifiedProbe != null) {
                            { tracksExpanded = !tracksExpanded }
                        } else {
                            null
                        },
                    )
                    SubtitleTrackBadge(
                        result = result,
                        verifiedProbe = verifiedProbe,
                        onClick = if (verifiedProbe != null) {
                            { tracksExpanded = !tracksExpanded }
                        } else {
                            null
                        },
                    )
                    if (result.category == SearchCategory.Adult || SearchUtils.isAdultContent(result.title, result.description)) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = CinemaColors.Danger.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.42f)),
                        ) {
                            Text(
                                text = "+18",
                                style = MaterialTheme.typography.labelSmall,
                                color = CinemaColors.Danger,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (inLibrary) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = CinemaColors.Secondary.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, CinemaColors.Secondary.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CinemaColors.Secondary,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = "En biblioteca",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaColors.Secondary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                SwarmTelemetryCluster(
                    verdict = assessment?.verdict,
                    seeders = shownSeeders,
                    peers = shownPeers,
                    isLiveScraped = isLiveScraped,
                    isTopRecommended = isTopRecommended,
                    isProbingSwarm = isProbingSwarm,
                    isVerifiedWorking = isVerifiedWorking,
                    isFailedDead = isFailedDead,
                    measuredDownloadBps = probe?.measuredDownloadBps,
                    connectedSeeders = probe?.connectedSeeders,
                    connectedPeersNoFlow = if (probe != null && !probe.isPolicyBlocked && probe.measuredDownloadBps <= 0L &&
                        probe.activePeers > 0 && probe.targetFileSizeBytes != null
                    ) probe.activePeers else null,
                    onDiagnoseSwarm = onDiagnoseSwarm,
                )
            }

            AnimatedVisibility(visible = tracksExpanded && verifiedProbe != null) {
                verifiedProbe?.let { probe ->
                    InlineTrackBreakdownPanel(probe = probe)
                }
            }

            // Zone 2: Full-width Untruncated Release / File Title (100% card width, zero side clutter)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (showCleanTitle && !result.parsed.cleanTitle.equals(result.title, ignoreCase = true)) {
                    Text(
                        text = result.parsed.cleanTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = CinemaColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 18.5.sp,
                    ),
                    color = if (showCleanTitle) CinemaColors.TextSecondary else CinemaColors.TextPrimary,
                    fontWeight = if (showCleanTitle) FontWeight.Normal else FontWeight.Medium,
                )
                if (extraFileLine != null) {
                    Text(
                        text = extraFileLine,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp),
                        color = CinemaColors.TextSecondary,
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = CinemaColors.HairlineBorder.copy(alpha = 0.65f),
            )

            // Zone 3: Unified Footer (Size + Tech Specs on Left | Compact Actions on Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    result.sizeText?.takeIf { it.isNotBlank() }?.let { sizeLabel ->
                        Text(
                            text = sizeLabel,
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = CinemaColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    val metaSummary = buildList {
                        addAll(techSpecs)
                        assessment?.probe?.let { p ->
                            if (p.measuredMbps > 0.05) {
                                add(String.format(Locale.US, "%.1f Mbps", p.measuredMbps))
                            }
                        }
                        if (isCastingToTv) add("Enviar a TV")
                    }.joinToString(" · ")

                    if (metaSummary.isNotEmpty()) {
                        Text(
                            text = if (!result.sizeText.isNullOrBlank()) "·  $metaSummary" else metaSummary,
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = if (isCastingToTv) CinemaColors.Secondary else CinemaColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CandidateAudioProbeButton(
                        magnetUri = result.magnetUri,
                        infoHash = result.infoHash,
                    )

                    Surface(
                        onClick = onChooseFiles,
                        shape = RoundedCornerShape(8.dp),
                        color = CinemaColors.SurfaceElevated,
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Descargar o elegir archivos",
                                tint = CinemaColors.PrimarySoft,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = "Guardar",
                                style = MaterialTheme.typography.labelSmall,
                                color = CinemaColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleTrackBadge(
    result: TorrentSearchResult,
    verifiedProbe: ContainerProbeResult?,
    onClick: (() -> Unit)?,
) {
    val label: String?
    val verified: Boolean
    val hasSpanishSub: Boolean

    if (verifiedProbe != null) {
        val subs = verifiedProbe.subtitleTracks
        if (subs.isEmpty()) {
            label = "Sin subs"
            verified = true
            hasSpanishSub = false
        } else {
            val esCount = subs.count {
                it.regionTag == AudioLanguageTag.Latino ||
                    it.regionTag == AudioLanguageTag.SpanishGeneric ||
                    it.regionTag == AudioLanguageTag.Castellano ||
                    it.regionTag == AudioLanguageTag.SubSpanish
            }
            label = when {
                esCount > 0 && subs.size > esCount -> "Subs: Esp +${subs.size - esCount}"
                esCount > 0 -> "Subs: Español"
                else -> "Subs: ${subs.size}"
            }
            verified = true
            hasSpanishSub = esCount > 0
        }
    } else if (result.languageTag != AudioLanguageTag.SubSpanish &&
        result.description?.contains("Subtítulos:", ignoreCase = true) == true
    ) {
        val desc = result.description
        val hasEsCue = desc.contains("🇲🇽") || desc.contains("🇪🇸") || desc.contains("esp", ignoreCase = true)
        label = if (hasEsCue) "Subs: Esp" else "Con subs"
        verified = false
        hasSpanishSub = hasEsCue
    } else {
        label = null
        verified = false
        hasSpanishSub = false
    }

    if (label == null) return

    val fg = if (hasSpanishSub) Color(0xFFCDB9FF) else CinemaColors.TextSecondary
    val bg = if (hasSpanishSub) Color(0xFF9C7BFF).copy(alpha = 0.18f) else CinemaColors.SurfaceBright
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(7.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = if (verified) 0.32f else 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Subtitles,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = if (hasSpanishSub) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SwarmTelemetryCluster(
    verdict: PlayabilityVerdict?,
    seeders: Int,
    peers: Int,
    isLiveScraped: Boolean,
    isTopRecommended: Boolean,
    isProbingSwarm: Boolean,
    isVerifiedWorking: Boolean,
    isFailedDead: Boolean,
    measuredDownloadBps: Long?,
    connectedSeeders: Int?,
    connectedPeersNoFlow: Int? = null,
    onDiagnoseSwarm: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when {
            isProbingSwarm -> {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = CinemaColors.Gold.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, CinemaColors.Gold.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.5.dp,
                            color = CinemaColors.Gold,
                        )
                        Text(
                            text = "Probando… · ↑$seeders",
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = CinemaColors.Gold,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }

            isVerifiedWorking && measuredDownloadBps != null -> {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = CinemaColors.Secondary.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, CinemaColors.Secondary.copy(alpha = 0.38f)),
                ) {
                    Text(
                        text = "✓ ↓ ${formatSpeed(measuredDownloadBps)} · ↑${connectedSeeders ?: seeders}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = CinemaColors.Secondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                if (onDiagnoseSwarm != null) {
                    Surface(
                        onClick = onDiagnoseSwarm,
                        shape = RoundedCornerShape(7.dp),
                        color = CinemaColors.SurfaceBright,
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    ) {
                        Text(
                            text = "↺",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            isFailedDead -> {
                val failLabel = if (measuredDownloadBps != null) {
                    "✗ 0 B/s · ↑$seeders"
                } else {
                    "Sin fuentes"
                }
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = CinemaColors.Danger.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.42f)),
                ) {
                    Text(
                        text = failLabel,
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = CinemaColors.Danger,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                if (onDiagnoseSwarm != null) {
                    Surface(
                        onClick = onDiagnoseSwarm,
                        shape = RoundedCornerShape(7.dp),
                        color = CinemaColors.SurfaceElevated,
                        border = BorderStroke(1.dp, CinemaColors.Danger.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = "Reintentar",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaColors.Danger,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            connectedPeersNoFlow != null && !isFailedDead -> {
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = CinemaColors.Gold.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, CinemaColors.Gold.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = "◐ $connectedPeersNoFlow peers · sin flujo aún",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = CinemaColors.Gold,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                if (onDiagnoseSwarm != null) {
                    Surface(
                        onClick = onDiagnoseSwarm,
                        shape = RoundedCornerShape(7.dp),
                        color = CinemaColors.SurfaceBright,
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                    ) {
                        Text(
                            text = "↺",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            else -> {
                PlayabilityConnectionsBadge(
                    verdict = verdict,
                    seeders = seeders,
                    peers = peers,
                    isLiveScraped = isLiveScraped,
                    isTopRecommended = isTopRecommended,
                )
                if (onDiagnoseSwarm != null) {
                    Surface(
                        onClick = onDiagnoseSwarm,
                        shape = RoundedCornerShape(7.dp),
                        color = CinemaColors.SurfaceElevated,
                        border = BorderStroke(1.dp, CinemaColors.HairlineBorderStrong),
                    ) {
                        Text(
                            text = "Probar",
                            style = MaterialTheme.typography.labelSmall,
                            color = CinemaColors.PrimarySoft,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayabilityConnectionsBadge(
    verdict: PlayabilityVerdict?,
    seeders: Int,
    peers: Int,
    isLiveScraped: Boolean,
    isTopRecommended: Boolean,
) {
    val effectiveVerdict = verdict ?: when {
        seeders >= 20 -> PlayabilityVerdict.Optimal
        seeders >= 5 -> PlayabilityVerdict.Good
        seeders > 0 -> PlayabilityVerdict.Risky
        else -> PlayabilityVerdict.Dead
    }
    val (bg, fg) = when (effectiveVerdict) {
        PlayabilityVerdict.Optimal -> CinemaColors.Secondary.copy(alpha = 0.18f) to CinemaColors.Secondary
        PlayabilityVerdict.Good -> CinemaColors.PrimaryMuted to CinemaColors.PrimarySoft
        PlayabilityVerdict.Risky -> CinemaColors.Gold.copy(alpha = 0.18f) to CinemaColors.Gold
        PlayabilityVerdict.Dead -> CinemaColors.Danger.copy(alpha = 0.18f) to CinemaColors.Danger
    }
    val prefix = if (isTopRecommended && effectiveVerdict != PlayabilityVerdict.Dead && effectiveVerdict != PlayabilityVerdict.Risky) {
        "Mejor"
    } else {
        effectiveVerdict.label
    }
    val peersPart = if (peers > 0 && seeders == 0) " · ↓$peers" else ""
    val liveDot = if (isLiveScraped) "✓" else ""
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (isTopRecommended && effectiveVerdict != PlayabilityVerdict.Dead) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = "$prefix · ↑$seeders$liveDot$peersPart",
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = fg,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
