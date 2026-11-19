package org.cinito.online.ui.discover

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.cinito.online.metadata.MediaKind
import org.cinito.online.metadata.MediaMetadata
import org.cinito.online.ui.explore.CatalogLibraryIndex
import org.cinito.online.ui.library.PosterFallbackTile
import org.cinito.online.ui.theme.CinemaColors
import org.cinito.online.ui.theme.tabular

@Composable
internal fun FeaturedHeroPager(
    items: List<MediaMetadata>,
    libraryIndex: CatalogLibraryIndex,
    onOpenMedia: (MediaMetadata) -> Unit,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
        ) { page ->
            val item = items[page]
            val inLib = remember(item.id, libraryIndex) { libraryIndex.contains(item) }
            FeaturedHeroCard(
                item = item,
                inLibrary = inLib,
                onClick = { onOpenMedia(item) },
            )
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { idx ->
                    val selected = pagerState.currentPage == idx
                    val width by animateDpAsState(
                        targetValue = if (selected) 18.dp else 6.dp,
                        animationSpec = tween(durationMillis = 180),
                        label = "hero_dot_width",
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(4.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(
                                if (selected) CinemaColors.Primary else CinemaColors.HairlineBorderStrong,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun FeaturedHeroCard(
    item: MediaMetadata,
    inLibrary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(1.dp, CinemaColors.HairlineBorderStrong),
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 216.dp)) {
            AsyncImage(
                model = item.backdropUrl ?: item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.22f),
                                0.42f to Color.Black.copy(alpha = 0.36f),
                                1.0f to Color(0xEE090A0F),
                            ),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CinemaColors.Primary,
                ) {
                    Text(
                        text = "HOY",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (inLibrary) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CinemaColors.Secondary.copy(alpha = 0.92f),
                    ) {
                        Text(
                            text = "EN BIBLIOTECA",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF042214),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 48.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaParts = listOfNotNull(
                    item.year?.toString(),
                    if (item.type == "series") "Serie" else "Película",
                    item.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                )
                val metaLine = buildAnnotatedString {
                    item.rating?.let { rating ->
                        withStyle(SpanStyle(color = CinemaColors.Gold, fontWeight = FontWeight.SemiBold)) {
                            append("★ $rating")
                        }
                        if (metaParts.isNotEmpty()) append(" · ")
                    }
                    withStyle(SpanStyle(color = CinemaColors.TextSecondary)) {
                        append(metaParts.joinToString(" · "))
                    }
                }
                if (metaLine.isNotEmpty()) {
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!item.description.isNullOrBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextPrimary.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CinemaColors.Primary,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 42.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Explorar fuentes", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
internal fun CatalogPosterRail(
    title: String,
    items: List<MediaMetadata>,
    libraryIndex: CatalogLibraryIndex,
    showTypeTag: Boolean,
    onSeeAll: () -> Unit,
    onOpenMedia: (MediaMetadata) -> Unit,
) {
    if (items.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = CinemaColors.TextPrimary,
            )
            TextButton(
                onClick = onSeeAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    text = "Ver todo",
                    style = MaterialTheme.typography.labelMedium,
                    color = CinemaColors.PrimarySoft,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CinemaColors.PrimarySoft,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { "rail_${title}_${it.type}_${it.id}" }) { meta ->
                val inLib = remember(meta.id, libraryIndex) { libraryIndex.contains(meta) }
                CatalogPosterCard(
                    item = meta,
                    inLibrary = inLib,
                    showTypeTag = showTypeTag,
                    modifier = Modifier.width(114.dp),
                    onClick = { onOpenMedia(meta) },
                )
            }
        }
    }
}

@Composable
internal fun CatalogPosterCard(
    item: MediaMetadata,
    inLibrary: Boolean,
    showTypeTag: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = CinemaColors.Surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (inLibrary) CinemaColors.Secondary.copy(alpha = 0.55f) else CinemaColors.HairlineBorder,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(CinemaColors.SurfaceElevated),
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    PosterFallbackTile(
                        title = item.title,
                        kind = if (item.type == "series") MediaKind.SERIES else MediaKind.MOVIE,
                    )
                }

                if (inLibrary) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        shape = CircleShape,
                        color = Color(0xCC090A0F),
                        border = BorderStroke(1.dp, CinemaColors.Secondary.copy(alpha = 0.8f)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "En biblioteca",
                            tint = CinemaColors.Secondary,
                            modifier = Modifier.padding(3.dp).size(13.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = CinemaColors.TextPrimary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaText = buildAnnotatedString {
                    val baseParts = listOfNotNull(
                        item.year?.toString(),
                        if (showTypeTag) if (item.type == "series") "Serie" else "Cine" else null,
                    )
                    if (baseParts.isNotEmpty()) {
                        withStyle(SpanStyle(color = CinemaColors.TextSecondary)) {
                            append(baseParts.joinToString(" · "))
                        }
                    }
                    item.rating?.let { rating ->
                        if (baseParts.isNotEmpty()) {
                            withStyle(SpanStyle(color = CinemaColors.TextTertiary)) {
                                append(" · ")
                            }
                        }
                        withStyle(SpanStyle(color = CinemaColors.Gold, fontWeight = FontWeight.Medium)) {
                            append("★$rating")
                        }
                    }
                    if (length == 0) append(" ")
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun DiscoverLoadMoreFooter(
    isLoadingMore: Boolean,
    loadMoreError: Boolean,
    hasMore: Boolean,
    loadedCount: Int,
    onRetry: () -> Unit,
) {
    when {
        isLoadingMore -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = CinemaColors.Primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Cargando más títulos…",
                    style = MaterialTheme.typography.bodySmall,
                    color = CinemaColors.TextSecondary,
                )
            }
        }
        loadMoreError -> {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = CinemaColors.Surface,
                border = BorderStroke(1.dp, CinemaColors.HairlineBorder),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "No se pudieron cargar más títulos",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaColors.TextSecondary,
                    )
                    OutlinedButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) {
                        Text("Reintentar", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        !hasMore && loadedCount > 0 -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$loadedCount títulos en este listado",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = CinemaColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
internal fun DiscoverSkeletonGrid() {
    val transition = rememberInfiniteTransition(label = "catalog_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton_alpha",
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "sk_hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(216.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CinemaColors.SurfaceElevated.copy(alpha = alpha)),
            )
        }
        items(9) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3.45f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CinemaColors.SurfaceElevated.copy(alpha = alpha)),
            )
        }
    }
}
