package org.cinito.online.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import org.cinito.online.cast.CastSessionState
import org.cinito.online.ui.player.CastTopBarActionButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.cinito.online.metadata.AudioTrackProbeRepository
import org.cinito.online.metadata.ContainerProbeResult
import org.cinito.online.metadata.MediaKind
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.metadata.MediaTitleParser
import org.cinito.online.settings.LibraryViewMode
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular
import webtor.app.ContinueWatchingTarget
import webtor.app.DownloadEntry
import webtor.app.LibraryFilter
import webtor.app.LibrarySort
import webtor.app.SavedFile
import webtor.app.continueWatchingTargets
import webtor.app.formatBytes
import webtor.app.formatSpeed
import webtor.app.playbackTime
import webtor.app.rememberVideoPreviews

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryLibraryScreen(
    cards: List<LibraryMediaCardModel>,
    rawEntries: List<DownloadEntry>,
    metadataMap: Map<String, MediaMetadata>,
    viewMode: LibraryViewMode,
    filter: LibraryFilter,
    sort: LibrarySort,
    searchQuery: String,
    engineStatusLine: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    onOpenDetails: (LibraryMediaCardModel) -> Unit,
    onQuickPlay: (DownloadEntry, SavedFile) -> Unit,
    onPauseResume: (DownloadEntry) -> Unit,
    onOpenAddSheet: () -> Unit,
    onNavigateDiscover: () -> Unit,
    onNavigateSearch: () -> Unit,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as webtor.app.WebtorApp }
    val probeRepo = app.audioProbeRepository
    val probesMap by probeRepo.probes.collectAsState()
    val probingKeys by probeRepo.probingKeys.collectAsState()
    LaunchedEffect(rawEntries) {
        probeRepo.probeLibraryEntries(rawEntries)
    }

    val continueTargets = remember(rawEntries) { rawEntries.continueWatchingTargets(limit = 10) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(CinemaColors.Background)) {
        // Top header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Biblioteca",
                    style = MaterialTheme.typography.headlineLarge,
                    color = CinemaColors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = engineStatusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Ordenar", tint = CinemaColors.TextPrimary)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        LibrarySort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = when (option) {
                                            LibrarySort.RECENT -> "Recientes"
                                            LibrarySort.NAME -> "Nombre A-Z"
                                            LibrarySort.PROGRESS -> "Progreso"
                                            LibrarySort.STATUS -> "Estado"
                                        },
                                        fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    onSortChange(option)
                                    sortMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                IconButton(
                    onClick = {
                        val next = if (viewMode == LibraryViewMode.Gallery) LibraryViewMode.List else LibraryViewMode.Gallery
                        onViewModeChange(next)
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (viewMode == LibraryViewMode.Gallery) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = "Cambiar vista",
                        tint = CinemaColors.TextPrimary,
                    )
                }
                if (onOpenCast != null) {
                    CastTopBarActionButton(
                        castState = castState,
                        onClick = onOpenCast,
                        compact = true,
                    )
                }
                IconButton(onClick = onOpenAddSheet, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Agregar magnet o archivo", tint = CinemaColors.Primary)
                }
            }
        }

        // Filter chips
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { f ->
                val label = when (f) {
                    LibraryFilter.ALL -> "Todo"
                    LibraryFilter.ACTIVE -> "Activos"
                    LibraryFilter.READY -> "Listos"
                    LibraryFilter.NEEDS_ATTENTION -> "Alertas"
                }
                FilterChip(
                    selected = filter == f,
                    onClick = { onFilterChange(f) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CinemaColors.PrimaryMuted,
                        selectedLabelColor = CinemaColors.TextPrimary,
                    ),
                )
            }
        }

        if (cards.isEmpty() && continueTargets.isEmpty()) {
            EmptyGalleryState(
                onOpenAddSheet = onOpenAddSheet,
                onNavigateDiscover = onNavigateDiscover,
                onNavigateSearch = onNavigateSearch,
            )
            return@Column
        }

        if (viewMode == LibraryViewMode.Gallery) {
            val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (continueTargets.isNotEmpty() && searchQuery.isBlank() && filter == LibraryFilter.ALL) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue_section") {
                        ContinueWatchingCarousel(
                            targets = continueTargets,
                            metadataMap = metadataMap,
                            onPlay = onQuickPlay,
                        )
                    }
                }
                items(cards, key = { it.entry.key }) { card ->
                    val primaryFile = card.primaryPlayFile
                    val entryHash = AudioTrackProbeRepository.extractEntryInfoHash(card.entry)
                    val verifiedProbe = AudioTrackProbeRepository.lookupFromMap(probesMap, card.entry.key, entryHash, primaryFile?.index)
                    val isProbingAudio = primaryFile != null && AudioTrackProbeRepository.entryKey(card.entry.key, primaryFile.index) in probingKeys
                    PosterGalleryCard(
                        card = card,
                        verifiedProbe = verifiedProbe,
                        isProbingAudio = isProbingAudio,
                        onOpenDetails = { onOpenDetails(card) },
                        onQuickPlay = {
                            card.primaryPlayFile?.let { file -> onQuickPlay(card.entry, file) }
                        },
                    )
                }
            }
        } else {
            val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (continueTargets.isNotEmpty() && searchQuery.isBlank() && filter == LibraryFilter.ALL) {
                    item(key = "continue_section_list") {
                        ContinueWatchingCarousel(
                            targets = continueTargets,
                            metadataMap = metadataMap,
                            onPlay = onQuickPlay,
                        )
                    }
                }
                items(cards, key = { it.entry.key }) { card ->
                    val primaryFile = card.primaryPlayFile
                    val entryHash = AudioTrackProbeRepository.extractEntryInfoHash(card.entry)
                    val verifiedProbe = AudioTrackProbeRepository.lookupFromMap(probesMap, card.entry.key, entryHash, primaryFile?.index)
                    val isProbingAudio = primaryFile != null && AudioTrackProbeRepository.entryKey(card.entry.key, primaryFile.index) in probingKeys
                    LibraryListCard(
                        card = card,
                        verifiedProbe = verifiedProbe,
                        isProbingAudio = isProbingAudio,
                        onOpenDetails = { onOpenDetails(card) },
                        onQuickPlay = {
                            card.primaryPlayFile?.let { file -> onQuickPlay(card.entry, file) }
                        },
                        onPauseResume = { onPauseResume(card.entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCarousel(
    targets: List<ContinueWatchingTarget>,
    metadataMap: Map<String, MediaMetadata>,
    onPlay: (DownloadEntry, SavedFile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Seguir viendo",
            style = MaterialTheme.typography.titleLarge,
            color = CinemaColors.TextPrimary,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(targets, key = { "${it.entry.key}:${it.file.index}" }) { target ->
                val parsed = remember(target.entry.title) { MediaTitleParser.parse(target.entry.title) }
                val fileParsed = remember(target.file.name) { MediaTitleParser.parse(target.file.name) }
                val meta = metadataMap[parsed.lookupKey]
                val previewFrames = rememberVideoPreviews(target.entry, target.file.index)
                val frame = previewFrames?.lastOrNull()
                val progressFraction = if (target.file.playbackDurationMs > 0L) {
                    (target.file.playbackPositionMs.toFloat() / target.file.playbackDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Surface(
                    modifier = Modifier
                        .width(248.dp)
                        .clickable { onPlay(target.entry, target.file) },
                    shape = RoundedCornerShape(14.dp),
                    color = CinemaColors.Surface,
                    border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(CinemaColors.SurfaceElevated),
                        ) {
                            when {
                                frame != null -> Image(
                                    bitmap = frame.asImageBitmap(),
                                    contentDescription = parsed.cleanTitle,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                !meta?.backdropUrl.isNullOrBlank() || !meta?.posterUrl.isNullOrBlank() -> AsyncImage(
                                    model = meta?.backdropUrl ?: meta?.posterUrl,
                                    contentDescription = meta?.title ?: parsed.cleanTitle,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                else -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Movie, contentDescription = null, tint = CinemaColors.TextSecondary, modifier = Modifier.size(34.dp))
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, CinemaColors.CardScrim),
                                        ),
                                    ),
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(44.dp),
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Reanudar", tint = Color.White)
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(10.dp),
                            ) {
                                Text(
                                    text = meta?.title ?: parsed.cleanTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = listOfNotNull(
                                    fileParsed.episodeBadge,
                                    playbackTime(target.file.playbackPositionMs),
                                ).joinToString(" · ")
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = CinemaColors.TextSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = CinemaColors.Primary,
                            trackColor = CinemaColors.SurfaceBright,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tu biblioteca",
            style = MaterialTheme.typography.titleLarge,
            color = CinemaColors.TextPrimary,
        )
    }
}

@Composable
private fun PosterGalleryCard(
    card: LibraryMediaCardModel,
    verifiedProbe: ContainerProbeResult?,
    isProbingAudio: Boolean,
    onOpenDetails: () -> Unit,
    onQuickPlay: () -> Unit,
) {
    val previewFrames = rememberVideoPreviews(card.entry)
    val frame = previewFrames?.lastOrNull()
    val canPlay = card.flatVideos.any { it.canPlay }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(10.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(CinemaColors.SurfaceElevated),
            ) {
                when {
                    !card.posterUrl.isNullOrBlank() -> AsyncImage(
                        model = card.posterUrl,
                        contentDescription = card.displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    frame != null -> Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = card.displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    else -> PosterFallbackTile(title = card.displayTitle, kind = card.kind)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.25f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                        ),
                )

                // Top status badge
                val badgeText = when {
                    card.swarm.isError -> "Error"
                    card.swarm.isDirectPlay -> "Direct Play"
                    card.swarm.isCompleted -> "Listo"
                    else -> "${(card.entry.progress * 100).toInt()}%"
                }
                val badgeColor = when {
                    card.swarm.isError -> CinemaColors.Danger
                    card.swarm.isCompleted || card.swarm.isDirectPlay -> CinemaColors.Secondary
                    else -> CinemaColors.Primary
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }

                if (verifiedProbe != null || isProbingAudio) {
                    VerifiedAudioSummaryPill(
                        probe = verifiedProbe,
                        isProbing = isProbingAudio,
                        compactText = true,
                        overlayStyle = true,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 8.dp, bottom = 8.dp, end = if (canPlay) 52.dp else 8.dp),
                    )
                }

                if (canPlay) {
                    IconButton(
                        onClick = onQuickPlay,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(CinemaColors.Primary.copy(alpha = 0.92f)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Reproducir",
                            tint = Color.White,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = card.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaLine = buildList {
                    card.year?.let { add(it.toString()) }
                    if (card.flatVideos.size > 1) add("${card.flatVideos.size} ep.")
                    if (card.swarm.downloadSpeedBps > 0L) add("↓ ${formatSpeed(card.swarm.downloadSpeedBps)}")
                    else if (card.swarm.peersConnected > 0) add("${card.swarm.peersConnected} peers")
                }.joinToString(" · ")
                Text(
                    text = metaLine.ifBlank { card.swarm.statusLabel },
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = CinemaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LibraryListCard(
    card: LibraryMediaCardModel,
    verifiedProbe: ContainerProbeResult?,
    isProbingAudio: Boolean,
    onOpenDetails: () -> Unit,
    onQuickPlay: () -> Unit,
    onPauseResume: () -> Unit,
) {
    val canPlay = card.flatVideos.any { it.canPlay }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(10.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .height(102.dp)
                    .clip(RoundedCornerShape(10.dp))
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
                    PosterFallbackTile(title = card.displayTitle, kind = card.kind)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = card.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = CinemaColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val telemetry = buildList {
                    add(card.swarm.statusLabel)
                    if (card.swarm.downloadSpeedBps > 0L) add("↓ ${formatSpeed(card.swarm.downloadSpeedBps)}")
                    if (card.entry.total > 0L) add("${formatBytes(card.entry.downloaded)} / ${formatBytes(card.entry.total)}")
                }.joinToString(" · ")
                Text(
                    text = telemetry,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = CinemaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VerifiedAudioSummaryPill(
                    probe = verifiedProbe,
                    isProbing = isProbingAudio,
                )
                if (!card.swarm.isCompleted && !card.swarm.isDirectPlay) {
                    LinearProgressIndicator(
                        progress = { card.entry.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = CinemaColors.Primary,
                        trackColor = CinemaColors.SurfaceBright,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPauseResume) {
                    Icon(
                        imageVector = if (card.entry.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (card.entry.paused) "Reanudar descarga" else "Pausar descarga",
                        tint = CinemaColors.TextSecondary,
                    )
                }
                if (canPlay) {
                    IconButton(onClick = onQuickPlay) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Reproducir",
                            tint = CinemaColors.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PosterFallbackTile(title: String, kind: MediaKind) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(CinemaColors.SurfaceElevated, CinemaColors.PrimaryMuted.copy(alpha = 0.45f)),
                ),
            )
            .padding(12.dp),
    ) {
        Text(
            text = when (kind) {
                MediaKind.SERIES -> "SERIE"
                MediaKind.ANIME -> "ANIME"
                MediaKind.MOVIE -> "CINE"
                MediaKind.OTHER -> "MEDIA"
            },
            style = MaterialTheme.typography.labelSmall,
            color = CinemaColors.Primary,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CinemaColors.TextPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

@Composable
private fun EmptyGalleryState(
    onOpenAddSheet: () -> Unit,
    onNavigateDiscover: () -> Unit,
    onNavigateSearch: () -> Unit,
    castState: CastSessionState = CastSessionState(),
    onOpenCast: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CinemaColors.Surface,
            border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = CinemaColors.Primary,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = "Tu sala de cine está vacía",
                    style = MaterialTheme.typography.titleLarge,
                    color = CinemaColors.TextPrimary,
                )
                Text(
                    text = "Explora estrenos, busca torrents en múltiples indexadores HTTPS o pega un magnet link para reproducir al instante sin publicidad.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CinemaColors.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onNavigateDiscover,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Explore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Explorar catálogo")
                }
                OutlinedButton(
                    onClick = onNavigateSearch,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Buscar torrents")
                }
                OutlinedButton(
                    onClick = onOpenAddSheet,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pegar magnet o .torrent")
                }
            }
        }
    }
}
