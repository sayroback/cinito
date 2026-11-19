package org.cinito.online.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.cinito.online.cast.CastSessionState
import org.cinito.online.ui.player.CastTopBarActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.ui.explore.formatSeasonChipLabel
import org.cinito.online.ui.explore.sortSeasonNumbers
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.CinemaCompactPill
import org.cinito.online.ui.theme.tabular
import webtor.app.DownloadEntry
import webtor.app.SavedFile
import webtor.app.WebtorApp
import webtor.app.formatBytes
import webtor.app.formatEta
import webtor.app.formatSpeed
import webtor.app.playbackTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaDetailsScreen(
    card: LibraryMediaCardModel,
    showRawFilenames: Boolean,
    onBack: () -> Unit,
    onPlayFile: (DownloadEntry, SavedFile, Boolean) -> Unit,
    onPauseResume: (DownloadEntry) -> Unit,
    onOpenFiles: (DownloadEntry) -> Unit,
    onDelete: (DownloadEntry) -> Unit,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)

    val seasons = remember(card.episodesBySeason) { sortSeasonNumbers(card.episodesBySeason.keys) }
    val initialSeason = remember(card.entry.key, seasons, card.primaryEpisode?.season) {
        card.primaryEpisode?.season?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
    }
    var selectedSeason by rememberSaveable(card.entry.key) {
        mutableIntStateOf(initialSeason)
    }
    val effectiveSeason = if (selectedSeason in seasons) selectedSeason else initialSeason
    var synopsisExpanded by remember(card.entry.key) { mutableStateOf(false) }

    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as WebtorApp }
    val probeRepo = app.audioProbeRepository
    val probesMap by probeRepo.probes.collectAsState()
    val probingKeys by probeRepo.probingKeys.collectAsState()
    val scope = rememberCoroutineScope()
    val entryInfoHash = remember(card.entry) { AudioTrackProbeRepository.extractEntryInfoHash(card.entry) }

    val primaryFile = card.primaryPlayFile
    val hasResume = (primaryFile?.playbackPositionMs ?: 0L) > 5_000L

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaColors.Background),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp),
            ) {
                val heroImg = card.backdropUrl ?: card.posterUrl
                if (!heroImg.isNullOrBlank()) {
                    AsyncImage(
                        model = heroImg,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(CinemaColors.PrimaryMuted, CinemaColors.Background),
                                ),
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.25f),
                                    CinemaColors.Background,
                                ),
                            ),
                        ),
                )

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .align(Alignment.TopStart),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = Color.White,
                    )
                }

                if (onOpenCast != null) {
                    CastTopBarActionButton(
                        castState = castState,
                        onClick = onOpenCast,
                        overHeroScrim = true,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(8.dp)
                            .align(Alignment.TopEnd),
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CinemaColors.SurfaceElevated),
                    ) {
                        if (!card.posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = card.posterUrl,
                                contentDescription = card.displayTitle,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            PosterFallbackTile(card.displayTitle, card.kind)
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = card.displayTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = CinemaColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val infoBits = buildList {
                            card.year?.let { add(it.toString()) }
                            card.metadata?.runtime?.let { add(it) }
                            card.rating?.let { add("★ $it") }
                        }.joinToString(" · ")
                        if (infoBits.isNotEmpty()) {
                            Text(
                                text = infoBits,
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = CinemaColors.Gold,
                            )
                        }
                        if (card.genres.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                card.genres.take(3).forEach { g ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = CinemaColors.SurfaceBright,
                                    ) {
                                        Text(
                                            text = g,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CinemaColors.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "actions") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (primaryFile != null) {
                    val primaryItem = card.flatVideos.find { it.fileIndex == primaryFile.index }
                    val canPlayPrimary = primaryItem?.canPlay == true
                    Button(
                        onClick = { onPlayFile(card.entry, primaryFile, false) },
                        enabled = canPlayPrimary,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        val isCasting = castState.isConnected
                        val label = when {
                            isCasting && hasResume && card.isSeriesLayout -> "Enviar ${primaryItem?.season?.let { s -> "T$s E${primaryItem.episode ?: 1}" } ?: ""} a TV (${playbackTime(primaryFile.playbackPositionMs)})"
                            isCasting && hasResume -> "Enviar a TV desde ${playbackTime(primaryFile.playbackPositionMs)}"
                            isCasting && card.isSeriesLayout -> "Enviar Episodio ${primaryItem?.episode ?: 1} a TV"
                            isCasting -> "Enviar a TV ahora"
                            hasResume && card.isSeriesLayout -> "Continuar ${primaryItem?.season?.let { s -> "T$s E${primaryItem.episode ?: 1}" } ?: ""} (${playbackTime(primaryFile.playbackPositionMs)})"
                            hasResume -> "Continuar desde ${playbackTime(primaryFile.playbackPositionMs)}"
                            card.isSeriesLayout -> "Reproducir Episodio ${primaryItem?.episode ?: 1}"
                            else -> "Reproducir ahora"
                        }
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hasResume && canPlayPrimary) {
                        OutlinedButton(
                            onClick = { onPlayFile(card.entry, primaryFile, true) },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Ver desde el inicio")
                        }
                    }
                }

                // Swarm Telemetry & Secondary Buttons
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CinemaColors.Surface,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = card.swarm.statusLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (card.swarm.isError) CinemaColors.Danger else CinemaColors.Secondary,
                            )
                            Text(
                                text = "${card.swarm.peersConnected} peers",
                                style = MaterialTheme.typography.labelMedium.tabular(),
                                color = CinemaColors.TextSecondary,
                            )
                        }
                        val speedLine = buildList {
                            if (card.swarm.downloadSpeedBps > 0L) add("↓ ${formatSpeed(card.swarm.downloadSpeedBps)}")
                            if (card.swarm.uploadSpeedBps > 0L) add("↑ ${formatSpeed(card.swarm.uploadSpeedBps)}")
                            formatEta(card.swarm.etaMs)?.let { add(it) }
                            if (card.entry.total > 0L) add("${formatBytes(card.entry.downloaded)} / ${formatBytes(card.entry.total)}")
                        }.joinToString(" · ")
                        if (speedLine.isNotEmpty()) {
                            Text(
                                text = speedLine,
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = CinemaColors.TextSecondary,
                            )
                        }
                        if (!card.swarm.isCompleted && !card.swarm.isDirectPlay) {
                            LinearProgressIndicator(
                                progress = { card.entry.progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = CinemaColors.Primary,
                                trackColor = CinemaColors.SurfaceBright,
                            )
                        }
                        if (card.subtitleFiles.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp), tint = CinemaColors.Primary)
                                Text(
                                    text = "${card.subtitleFiles.size} archivo(s) de subtítulos detectados en el torrent",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CinemaColors.TextSecondary,
                                )
                            }
                        }
                    }
                }

                if (primaryFile != null) {
                    VerifiedTracksCard(
                        fileName = primaryFile.name,
                        fileLength = primaryFile.length,
                        engineId = card.entry.engineId,
                        fileIndex = primaryFile.index,
                        contentUri = primaryFile.uri,
                        autoProbe = primaryFile.uri != null || !card.entry.engineId.isNullOrBlank(),
                        entryKey = card.entry.key,
                        infoHash = entryInfoHash,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onPauseResume(card.entry) },
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(
                            imageVector = if (card.entry.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (card.entry.paused) "Reanudar" else "Pausar")
                    }
                    OutlinedButton(
                        onClick = { onOpenFiles(card.entry) },
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Archivos (${card.entry.files.size})")
                    }
                    IconButton(
                        onClick = { onDelete(card.entry) },
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = CinemaColors.Danger)
                    }
                }

                if (!card.description.isNullOrBlank()) {
                    Text(
                        text = card.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaColors.TextSecondary,
                        maxLines = if (synopsisExpanded) 14 else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { synopsisExpanded = !synopsisExpanded },
                    )
                }

                if (showRawFilenames) {
                    Text(
                        text = "Torrent: ${card.entry.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                }
            }
        }

        if (card.flatVideos.size > 1 || seasons.size > 1) {
            item(key = "seasons_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Episodios",
                        style = MaterialTheme.typography.titleLarge,
                        color = CinemaColors.TextPrimary,
                    )
                    if (seasons.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(seasons, key = { it }) { seasonNum ->
                                CinemaCompactPill(
                                    label = formatSeasonChipLabel(seasonNum, card.episodesBySeason[seasonNum]?.size),
                                    selected = effectiveSeason == seasonNum,
                                    onClick = { selectedSeason = seasonNum },
                                )
                            }
                        }
                    }
                }
            }

            val episodesToShow = if (card.episodesBySeason.isNotEmpty()) {
                card.episodesBySeason[effectiveSeason] ?: card.flatVideos
            } else {
                card.flatVideos
            }

            item(key = "episodes_probe_warmup_$effectiveSeason") {
                LaunchedEffect(card.entry.key, effectiveSeason, card.entry.engineId, episodesToShow.size) {
                    episodesToShow
                        .filter { isInspectableContainerName(it.rawFileName) && (!it.file.uri.isNullOrBlank() || !card.entry.engineId.isNullOrBlank()) }
                        .take(10)
                        .forEach { ep ->
                            if (AudioTrackProbeRepository.lookupFromMap(probesMap, card.entry.key, entryInfoHash, ep.fileIndex) == null) {
                                probeRepo.probeFile(
                                    entryKey = card.entry.key,
                                    infoHash = entryInfoHash,
                                    fileIndex = ep.fileIndex,
                                    fileName = ep.rawFileName,
                                    fileLength = ep.sizeBytes,
                                    engineId = card.entry.engineId,
                                    contentUri = ep.file.uri,
                                )
                            }
                        }
                }
            }

            items(episodesToShow, key = { it.fileIndex }) { ep ->
                val isResumeTarget = card.flatVideos.size > 1 && ep.fileIndex == card.primaryPlayFile?.index
                val epProbe = AudioTrackProbeRepository.lookupFromMap(probesMap, card.entry.key, entryInfoHash, ep.fileIndex)
                val epProbing = AudioTrackProbeRepository.entryKey(card.entry.key, ep.fileIndex) in probingKeys
                val canInspectEp = isInspectableContainerName(ep.rawFileName) && (!ep.file.uri.isNullOrBlank() || !card.entry.engineId.isNullOrBlank())
                EpisodeRowCard(
                    item = ep,
                    showRawFilename = showRawFilenames,
                    isResumeTarget = isResumeTarget,
                    verifiedProbe = epProbe,
                    isProbingAudio = epProbing,
                    onInspectAudio = if (canInspectEp && epProbe == null && !epProbing) {
                        {
                            scope.launch {
                                probeRepo.probeFile(
                                    entryKey = card.entry.key,
                                    infoHash = entryInfoHash,
                                    fileIndex = ep.fileIndex,
                                    fileName = ep.rawFileName,
                                    fileLength = ep.sizeBytes,
                                    engineId = card.entry.engineId,
                                    contentUri = ep.file.uri,
                                    forceRefresh = true,
                                )
                            }
                        }
                    } else {
                        null
                    },
                    onClick = { onPlayFile(card.entry, ep.file, false) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRowCard(
    item: EpisodeUiItem,
    showRawFilename: Boolean,
    isResumeTarget: Boolean,
    verifiedProbe: ContainerProbeResult? = null,
    isProbingAudio: Boolean = false,
    onInspectAudio: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var tracksExpanded by remember(item.fileIndex) { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(enabled = item.canPlay, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isResumeTarget) CinemaColors.PrimaryMuted.copy(alpha = 0.28f) else CinemaColors.Surface,
        border = BorderStroke(
            width = if (isResumeTarget) 1.5.dp else 1.dp,
            color = if (isResumeTarget) CinemaColors.Primary else CinemaColors.HairlineBorder,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(104.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(CinemaColors.SurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!item.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.displayTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(
                            text = item.episode?.let { "E%02d".format(it) } ?: "▶",
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            color = CinemaColors.Primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (item.episode != null) "${item.episode}. ${item.displayTitle}" else item.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = CinemaColors.TextPrimary,
                    )
                    if (showRawFilename || !item.rawFileName.equals(item.displayTitle, ignoreCase = true)) {
                        Text(
                            text = item.rawFileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = CinemaColors.TextSecondary,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = formatBytes(item.sizeBytes),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = CinemaColors.TextSecondary,
                        )
                        if (item.isVerifiedComplete) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Completo",
                                tint = CinemaColors.Secondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        if (item.isWatched) {
                            Text(
                                text = "· Visto",
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.Secondary,
                                fontWeight = FontWeight.Medium,
                            )
                        } else if (item.playbackPositionMs > 5_000L) {
                            Text(
                                text = "· ${playbackTime(item.playbackPositionMs)}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = CinemaColors.PrimarySoft,
                            )
                        } else if (isResumeTarget) {
                            Text(
                                text = "· Siguiente",
                                style = MaterialTheme.typography.bodySmall,
                                color = CinemaColors.PrimarySoft,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (verifiedProbe != null || isProbingAudio) {
                        VerifiedAudioSummaryPill(
                            probe = verifiedProbe,
                            isProbing = isProbingAudio,
                            expanded = if (verifiedProbe != null) tracksExpanded else null,
                            onClick = if (verifiedProbe != null) {
                                { tracksExpanded = !tracksExpanded }
                            } else {
                                null
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else if (onInspectAudio != null) {
                        Surface(
                            modifier = Modifier.padding(top = 2.dp).clickable(onClick = onInspectAudio),
                            shape = RoundedCornerShape(6.dp),
                            color = CinemaColors.SurfaceBright,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = "Analizar pistas de audio",
                                    tint = CinemaColors.PrimarySoft,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = "Ver pistas audio",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CinemaColors.PrimarySoft,
                                )
                            }
                        }
                    }
                }
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Reproducir episodio",
                    tint = if (item.canPlay) CinemaColors.Primary else CinemaColors.TextSecondary,
                )
            }
            AnimatedVisibility(visible = tracksExpanded && verifiedProbe != null) {
                verifiedProbe?.let { probe ->
                    InlineTrackBreakdownPanel(probe = probe)
                }
            }
            if (item.playbackDurationMs > 0L && item.playbackPositionMs > 0L) {
                val frac = (item.playbackPositionMs.toFloat() / item.playbackDurationMs.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = CinemaColors.Primary,
                    trackColor = CinemaColors.SurfaceBright,
                )
            }
            if (!item.overview.isNullOrBlank()) {
                Text(
                    text = item.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
