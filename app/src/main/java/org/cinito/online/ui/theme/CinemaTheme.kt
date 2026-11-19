package org.cinito.online.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object CinemaColors {
    val Background = Color(0xFF090A0F)
    val Surface = Color(0xFF101219)
    val SurfaceElevated = Color(0xFF171A23)
    val SurfaceBright = Color(0xFF212533)
    val Primary = Color(0xFFD63B30)
    val PrimarySoft = Color(0xFFFF857A)
    val PrimaryMuted = Color(0xFF33181C)
    val Secondary = Color(0xFF34D399) // Swarm healthy / seeders
    val Gold = Color(0xFFF5B942)
    val Danger = Color(0xFFFF4D6D)
    val TextPrimary = Color(0xFFF5F6FA)
    val TextSecondary = Color(0xFFA0A7B8)
    val TextTertiary = Color(0xFF8A92A6)
    val HairlineBorder = Color(0x14FFFFFF)
    val HairlineBorderStrong = Color(0x2BFFFFFF)
    val CardScrim = Color(0xE0090A0F)
}

private val CinemaDarkColorScheme = darkColorScheme(
    primary = CinemaColors.Primary,
    onPrimary = Color.White,
    primaryContainer = CinemaColors.PrimaryMuted,
    onPrimaryContainer = CinemaColors.TextPrimary,
    secondary = CinemaColors.Secondary,
    onSecondary = Color(0xFF042214),
    tertiary = CinemaColors.Gold,
    background = CinemaColors.Background,
    onBackground = CinemaColors.TextPrimary,
    surface = CinemaColors.Surface,
    onSurface = CinemaColors.TextPrimary,
    surfaceVariant = CinemaColors.SurfaceElevated,
    onSurfaceVariant = CinemaColors.TextSecondary,
    outline = Color(0x32FFFFFF),
    outlineVariant = CinemaColors.HairlineBorder,
    error = CinemaColors.Danger,
    onError = Color.White,
)

private val CinemaTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.15).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

private val CinemaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
fun CiniToTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CinemaDarkColorScheme,
        typography = CinemaTypography,
        shapes = CinemaShapes,
        content = content,
    )
}
