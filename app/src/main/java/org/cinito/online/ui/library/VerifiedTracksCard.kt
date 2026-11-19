package org.cinito.online.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cinito.online.metadata.AudioLanguageTag
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.metadata.ProbedTrack
import org.cinito.online.player.TrackLanguageFormatter
import org.cinito.online.search.SearchUtils
import org.cinito.online.ui.theme.CinemaColors
import webtor.app.WebtorApp
import java.util.Locale

fun languageBadgeColors(tag: AudioLanguageTag): Pair<Color, Color> = when (tag) {
    AudioLanguageTag.Latino -> CinemaColors.Secondary.copy(alpha = 0.22f) to CinemaColors.Secondary
    AudioLanguageTag.SpanishGeneric -> CinemaColors.PrimaryMuted to CinemaColors.PrimarySoft
    AudioLanguageTag.Castellano -> CinemaColors.Gold.copy(alpha = 0.22f) to CinemaColors.Gold
    AudioLanguageTag.SubSpanish -> Color(0xFF9C7BFF).copy(alpha = 0.22f) to Color(0xFFCDB9FF)
    AudioLanguageTag.Other -> CinemaColors.SurfaceBright to CinemaColors.TextSecondary
}

@Composable
fun AudioLanguageBadge(
    tag: AudioLanguageTag,
    modifier: Modifier = Modifier,
    evidence: String? = null,
    verifiedProbe: ContainerProbeResult? = null,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val effectiveTag = verifiedProbe?.primaryAudioTag ?: tag
    val (bg, fg) = languageBadgeColors(effectiveTag)
    val label = if (verifiedProbe != null) {
        verifiedProbe.compactAudioBadgeLabel()
    } else if (!evidence.isNullOrBlank() && evidence.length <= 16 && tag != AudioLanguageTag.Other) {
        val cleanEv = when {
            evidence.contains("dual", ignoreCase = true) -> "Dual"
            evidence.equals(tag.shortLabel, ignoreCase = true) || evidence.equals("latino", ignoreCase = true) -> null
            else -> evidence.uppercase(Locale.ROOT)
        }
        if (cleanEv != null) "${tag.guessLabel} · $cleanEv" else tag.guessLabel
    } else {
        tag.guessLabel
    }
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(7.dp),
        color = bg,
        border = BorderStroke(
            width = 1.dp,
            color = fg.copy(alpha = if (verifiedProbe != null) 0.34f else 0.14f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (verifiedProbe != null) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Pistas verificadas",
                    tint = fg,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (effectiveTag == AudioLanguageTag.Latino || verifiedProbe != null) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                },
            )
            if (expanded != null && onClick != null) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Ocultar pistas" else "Ver pistas",
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
fun VerifiedAudioSummaryPill(
    probe: ContainerProbeResult?,
    isProbing: Boolean,
    modifier: Modifier = Modifier,
    compactText: Boolean = false,
    overlayStyle: Boolean = false,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    if (isProbing) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(7.dp),
            color = if (overlayStyle) Color.Black.copy(alpha = 0.74f) else CinemaColors.SurfaceBright,
            border = if (overlayStyle) BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)) else null,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = CinemaColors.PrimarySoft,
                )
                Text(
                    text = if (compactText) "Audio…" else "Analizando audio…",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overlayStyle) Color.White.copy(alpha = 0.9f) else CinemaColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
        return
    }
    if (probe == null) return
    val (bg, fg) = languageBadgeColors(probe.primaryAudioTag)
    val surfaceColor = if (overlayStyle) Color.Black.copy(alpha = 0.78f) else bg
    val textColor = if (overlayStyle) fg else fg
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(7.dp),
        color = surfaceColor,
        border = BorderStroke(
            width = 1.dp,
            color = fg.copy(alpha = if (overlayStyle) 0.38f else 0.28f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (overlayStyle) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(fg),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(11.dp),
                )
            }
            Text(
                text = probe.compactAudioBadgeLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (expanded != null && onClick != null) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Ocultar pistas" else "Ver pistas",
                    tint = fg,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
fun InlineTrackBreakdownPanel(
    probe: ContainerProbeResult,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = CinemaColors.SurfaceElevated,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        probe.isMultiAudioTrack -> "${probe.audioTrackCount} pistas de audio (${probe.containerFormat})"
                        probe.isSingleAudioTrack -> "1 sola pista de audio (${probe.containerFormat})"
                        else -> "Cabecera ${probe.containerFormat}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (probe.subtitleTracks.isNotEmpty()) {
                    Text(
                        text = "${probe.subtitleTracks.size} sub.",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }

            if (probe.audioTracks.isEmpty()) {
                Text(
                    text = "Sin pistas de audio detectadas en la cabecera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
            } else {
                probe.audioTracks.take(6).forEachIndexed { idx, track ->
                    AudioTrackDetailRow(index = idx + 1, track = track)
                }
                if (probe.audioTracks.size > 6) {
                    Text(
                        text = "+${probe.audioTracks.size - 6} pistas de audio adicionales",
                        style = MaterialTheme.typography.labelSmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
                if (probe.hasUntaggedSingleAudioTrack) {
                    Text(
                        text = "Sin idioma etiquetado en cabecera: al ser pista única no se puede confirmar doblaje sin reproducir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }

            if (probe.subtitleTracks.isNotEmpty()) {
                HorizontalDivider(color = CinemaColors.HairlineBorder)
                SubtitleSummaryRow(subtitleTracks = probe.subtitleTracks)
            }
        }
    }
}

@Composable
private fun AudioTrackDetailRow(
    index: Int,
    track: ProbedTrack,
) {
    val (_, fg) = languageBadgeColors(track.regionTag)
    val langLabel = when (track.regionTag) {
        AudioLanguageTag.Latino -> "Español Latino"
        AudioLanguageTag.Castellano -> "Castellano"
        AudioLanguageTag.SpanishGeneric -> "Español"
        AudioLanguageTag.SubSpanish, AudioLanguageTag.Other -> {
            if (track.hasExplicitLanguage) TrackLanguageFormatter.formatLanguage(track.bcp47 ?: track.languageCode)
            else "Sin etiqueta de idioma"
        }
    }
    val customName = track.name?.takeIf { it.isNotBlank() && !it.equals(langLabel, ignoreCase = true) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(fg),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Pista $index · $langLabel",
                style = MaterialTheme.typography.bodySmall,
                color = CinemaColors.TextPrimary,
                fontWeight = if (track.regionTag == AudioLanguageTag.Latino) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (customName != null) {
                Text(
                    text = customName,
                    style = MaterialTheme.typography.labelSmall,
                    color = CinemaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.codec?.takeIf { it.isNotBlank() }?.let { codec ->
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = CinemaColors.SurfaceBright,
            ) {
                Text(
                    text = codec.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CinemaColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SubtitleSummaryRow(subtitleTracks: List<ProbedTrack>) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Subtitles,
            contentDescription = null,
            tint = CinemaColors.TextSecondary,
            modifier = Modifier.size(14.dp).padding(top = 2.dp),
        )
        val subSummary = subtitleTracks.take(6).joinToString(", ") { it.displayLabel } +
            if (subtitleTracks.size > 6) " (+${subtitleTracks.size - 6})" else ""
        Text(
            text = "Subtítulos (${subtitleTracks.size}): $subSummary",
            style = MaterialTheme.typography.bodySmall,
            color = CinemaColors.TextSecondary,
        )
    }
}

@Composable
fun CandidateAudioProbeButton(
    magnetUri: String,
    infoHash: String?,
    preferredFileIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (magnetUri.isBlank()) return
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as WebtorApp }
    val probeRepo = app.audioProbeRepository
    val probes by probeRepo.probes.collectAsState()
    val probingKeys by probeRepo.probingKeys.collectAsState()
    val unavailableKeys by probeRepo.unavailableKeys.collectAsState()
    val failureReasons by probeRepo.failureReasons.collectAsState()
    val scope = rememberCoroutineScope()

    val resolvedHash = remember(magnetUri, infoHash) {
        (infoHash?.takeIf { it.isNotBlank() } ?: SearchUtils.extractInfoHash(magnetUri))
            ?.lowercase(Locale.ROOT)
    }
    val trackKey = resolvedHash?.let { AudioTrackProbeRepository.hashPrimaryKey(it) }
        ?: "magnet:${magnetUri.hashCode()}"
    val verified = AudioTrackProbeRepository.lookupFromMap(probes, null, resolvedHash, preferredFileIndex)
    if (verified != null) return

    val isProbing = trackKey in probingKeys
    val isUnavailable = trackKey in unavailableKeys
    val failureReason = failureReasons[trackKey]
    val buttonLabel = when {
        !isUnavailable -> "Audio"
        failureReason == "Solo Wi-Fi activo" -> "Solo Wi-Fi"
        failureReason == "En pausa en biblioteca" -> "En pausa"
        failureReason == "Sin soporte" -> "Sin soporte"
        else -> "Reintentar"
    }
    Surface(
        onClick = {
            if (!isProbing) {
                scope.launch {
                    probeRepo.probeCandidate(
                        magnetUri = magnetUri,
                        infoHash = resolvedHash,
                        preferredFileIndex = preferredFileIndex,
                        forceRefresh = isUnavailable,
                    )
                }
            }
        },
        enabled = !isProbing,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = CinemaColors.SurfaceElevated,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isProbing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = CinemaColors.PrimarySoft,
                )
                Text(
                    text = "Audio…",
                    style = MaterialTheme.typography.labelSmall,
                    color = CinemaColors.TextSecondary,
                    maxLines = 1,
                )
            } else {
                Icon(
                    imageVector = if (isUnavailable) Icons.Default.Refresh else Icons.Default.Audiotrack,
                    contentDescription = failureReason ?: "Verificar pistas reales de audio",
                    tint = if (isUnavailable && failureReason != null && buttonLabel != "Reintentar") {
                        CinemaColors.Gold
                    } else {
                        CinemaColors.PrimarySoft
                    },
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = CinemaColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

fun isInspectableContainerName(fileName: String): Boolean =
    AudioTrackProbeRepository.isInspectableContainerName(fileName)

private sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Probing : ProbeUiState
    data class Ready(val result: ContainerProbeResult) : ProbeUiState
    data object Unavailable : ProbeUiState
}

/**
 * Inspects real MKV/MP4 container track headers via local Content URI or BitTorrent Engine preview
 * and caches results in [AudioTrackProbeRepository] for instant visibility across the app.
 */
@Composable
fun VerifiedTracksCard(
    fileName: String,
    fileLength: Long,
    engineId: String?,
    fileIndex: Int?,
    modifier: Modifier = Modifier,
    contentUri: String? = null,
    autoProbe: Boolean = true,
    entryKey: String? = null,
    infoHash: String? = null,
) {
    if (!isInspectableContainerName(fileName) || fileLength <= 0L) return

    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as WebtorApp }
    val probeRepo = app.audioProbeRepository
    val probesMap by probeRepo.probes.collectAsState()
    val cachedProbe = AudioTrackProbeRepository.lookupFromMap(probesMap, entryKey, infoHash, fileIndex)

    val effectiveEntryKey = entryKey ?: engineId?.let { "engine:$it" } ?: fileName
    var probeTrigger by remember(effectiveEntryKey, infoHash, fileIndex, contentUri) {
        mutableIntStateOf(
            if (cachedProbe == null && autoProbe && (!contentUri.isNullOrBlank() || (!engineId.isNullOrBlank() && fileIndex != null))) 1 else 0,
        )
    }
    var localState by remember(effectiveEntryKey, infoHash, fileIndex, contentUri) {
        mutableStateOf<ProbeUiState>(
            if (cachedProbe != null) ProbeUiState.Ready(cachedProbe) else ProbeUiState.Idle,
        )
    }

    LaunchedEffect(cachedProbe) {
        if (cachedProbe != null) {
            localState = ProbeUiState.Ready(cachedProbe)
        }
    }

    LaunchedEffect(effectiveEntryKey, infoHash, engineId, fileIndex, contentUri, probeTrigger) {
        if (probeTrigger <= 0 || fileIndex == null) return@LaunchedEffect
        val hasLocal = !contentUri.isNullOrBlank()
        val hasEngine = !engineId.isNullOrBlank()
        if (!hasLocal && !hasEngine) {
            localState = ProbeUiState.Unavailable
            return@LaunchedEffect
        }

        localState = ProbeUiState.Probing
        var result: ContainerProbeResult? = null
        val maxAttempts = if (hasLocal && !hasEngine) 1 else 5
        for (attempt in 0 until maxAttempts) {
            result = probeRepo.probeFile(
                entryKey = effectiveEntryKey,
                infoHash = infoHash,
                fileIndex = fileIndex,
                fileName = fileName,
                fileLength = fileLength,
                engineId = engineId,
                contentUri = contentUri,
                forceRefresh = probeTrigger > 1 || attempt > 0,
            )?.takeIf { it.tracks.isNotEmpty() }
            if (result != null) break
            if (attempt < maxAttempts - 1) delay(1_500L)
        }
        localState = if (result != null) ProbeUiState.Ready(result) else ProbeUiState.Unavailable
    }

    val state = if (cachedProbe != null && localState !is ProbeUiState.Probing) {
        ProbeUiState.Ready(cachedProbe)
    } else {
        localState
    }
    val canProbeNow = fileIndex != null && (!contentUri.isNullOrBlank() || !engineId.isNullOrBlank())

    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val current = state) {
                ProbeUiState.Idle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CinemaColors.PrimaryMuted),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = CinemaColors.PrimarySoft,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Verificación de audio real",
                                style = MaterialTheme.typography.titleSmall,
                                color = CinemaColors.TextPrimary,
                            )
                            Text(
                                text = if (canProbeNow) {
                                    "Inspecciona la cabecera MKV/MP4 de $fileName sin reproducir."
                                } else {
                                    "Reanuda el torrent para analizar las pistas embebidas."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (canProbeNow) {
                        OutlinedButton(
                            onClick = { probeTrigger++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Analizar pistas reales de audio")
                        }
                    }
                }

                ProbeUiState.Probing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = CinemaColors.Primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Analizando cabecera multimedia…",
                                style = MaterialTheme.typography.titleSmall,
                                color = CinemaColors.TextPrimary,
                            )
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                is ProbeUiState.Ready -> {
                    val res = current.result
                    val (_, primaryFg) = languageBadgeColors(res.primaryAudioTag)
                    val titleText = when {
                        res.isMultiAudioTrack -> "Múltiples pistas de audio"
                        res.isSingleAudioTrack -> "Archivo de pista única"
                        else -> "Pistas detectadas"
                    }
                    val subtitleText = buildList {
                        add(res.containerFormat)
                        add(
                            if (res.audioTrackCount == 1) "1 pista de audio"
                            else "${res.audioTrackCount} pistas de audio",
                        )
                        if (res.subtitleTracks.isNotEmpty()) {
                            add("${res.subtitleTracks.size} sub.")
                        }
                    }.joinToString(" · ")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(primaryFg.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = primaryFg,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall,
                                color = CinemaColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AudioLanguageBadge(tag = res.primaryAudioTag, verifiedProbe = res)
                        if (canProbeNow) {
                            IconButton(
                                onClick = { probeTrigger++ },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Volver a analizar pistas",
                                    tint = CinemaColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    if (res.audioTracks.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinemaColors.SurfaceElevated)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            res.audioTracks.take(8).forEachIndexed { idx, track ->
                                AudioTrackDetailRow(index = idx + 1, track = track)
                            }
                            if (res.audioTracks.size > 8) {
                                Text(
                                    text = "+${res.audioTracks.size - 8} pistas de audio más",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaColors.TextSecondary,
                                )
                            }
                        }

                        if (res.hasUntaggedSingleAudioTrack) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = CinemaColors.SurfaceElevated,
                                border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = CinemaColors.TextSecondary,
                                        modifier = Modifier.size(15.dp).padding(top = 1.dp),
                                    )
                                    Text(
                                        text = "Tiene 1 sola pista de audio y la cabecera no indica idioma. No se puede confirmar doblaje sin reproducir.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CinemaColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Sin pistas de audio detectadas en la cabecera del archivo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                        )
                    }

                    if (res.subtitleTracks.isNotEmpty()) {
                        SubtitleSummaryRow(subtitleTracks = res.subtitleTracks)
                    }
                }

                ProbeUiState.Unavailable -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (canProbeNow) {
                                "Cabecera de pistas aún no descargada del enjambre."
                            } else {
                                "Reanuda el torrent para leer las pistas reales del video."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (canProbeNow) {
                            OutlinedButton(onClick = { probeTrigger++ }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reintentar")
                            }
                        }
                    }
                }
            }
        }
    }
}
