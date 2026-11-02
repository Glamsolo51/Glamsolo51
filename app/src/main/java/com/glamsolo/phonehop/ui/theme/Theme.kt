package com.glamsolo.phonehop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.phonehop.R

object Brand {
    val Violet = Color(0xFF7C5CFF)
    val Pink = Color(0xFFFF5FCB)
    val Cyan = Color(0xFF22C3FF)
    val Mint = Color(0xFF2EE6A6)
    val Amber = Color(0xFFFFB547)
    val Coral = Color(0xFFFF5C7A)

    val Send = listOf(Violet, Pink)
    val Receive = listOf(Cyan, Mint)
    val Aurora = listOf(Cyan, Violet, Pink)

    fun linear(colors: List<Color>) = Brush.linearGradient(colors)
}

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/** Wide geometric display face for headlines and big numbers. */
val Unbounded = FontFamily(Font(R.font.unbounded_bold, FontWeight.Bold))

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAE9BFF),
    onPrimary = Color(0xFF1A0B5E),
    primaryContainer = Color(0xFF2C2366),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF6FE3C1),
    onSecondary = Color(0xFF00382A),
    tertiary = Color(0xFFFF8AD8),
    background = Color(0xFF0B0A14),
    onBackground = Color(0xFFF3F1FA),
    surface = Color(0xFF13121D),
    onSurface = Color(0xFFF3F1FA),
    surfaceVariant = Color(0xFF1F1D2C),
    onSurfaceVariant = Color(0xFFB3AEC8),
    surfaceContainer = Color(0xFF181723),
    surfaceContainerHigh = Color(0xFF211F2E),
    surfaceContainerHighest = Color(0xFF2A2839),
    outline = Color(0xFF4A4660),
    outlineVariant = Color(0xFF2C2A3B),
    error = Color(0xFFFF7A90),
    onError = Color(0xFF4A0013),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3DF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1B0B66),
    secondary = Color(0xFF00876A),
    onSecondary = Color.White,
    tertiary = Color(0xFFC2189A),
    background = Color(0xFFF5F4FB),
    onBackground = Color(0xFF15131F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15131F),
    surfaceVariant = Color(0xFFECEAF5),
    onSurfaceVariant = Color(0xFF5D5872),
    surfaceContainer = Color(0xFFF0EEF8),
    surfaceContainerHigh = Color(0xFFEAE7F4),
    surfaceContainerHighest = Color(0xFFE3E0EE),
    outline = Color(0xFFC9C5D9),
    outlineVariant = Color(0xFFE3E0EE),
    error = Color(0xFFD3264A),
    onError = Color.White,
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.manrope(weight: FontWeight) = copy(fontFamily = Manrope, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1).sp),
        displayMedium = base.displayMedium.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp),
        displaySmall = base.displaySmall.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = base.titleLarge.manrope(FontWeight.ExtraBold).copy(fontSize = 21.sp),
        titleMedium = base.titleMedium.manrope(FontWeight.Bold),
        titleSmall = base.titleSmall.manrope(FontWeight.Bold),
        bodyLarge = base.bodyLarge.manrope(FontWeight.Medium),
        bodyMedium = base.bodyMedium.manrope(FontWeight.Medium),
        bodySmall = base.bodySmall.manrope(FontWeight.Medium),
        labelLarge = base.labelLarge.manrope(FontWeight.Bold).copy(fontSize = 15.sp),
        labelMedium = base.labelMedium.manrope(FontWeight.Bold),
        labelSmall = base.labelSmall.manrope(FontWeight.Bold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Extra colors that Material does not have slots for. */
@Immutable
data class GlassColors(
    val fill: Color,
    val border: Color,
    val blobAlpha: Float,
    val isDark: Boolean,
)

val LocalGlass = staticCompositionLocalOf {
    GlassColors(Color.White.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.06f), 0.22f, false)
}

@Composable
fun PhoneHopTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    val glass = if (darkTheme) {
        GlassColors(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.09f), 0.30f, true)
    } else {
        GlassColors(Color.White.copy(alpha = 0.72f), Color(0xFF15131F).copy(alpha = 0.06f), 0.20f, false)
    }
    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(colorScheme = colors, typography = typography(), shapes = AppShapes) {
            // Screens draw on our own background instead of a Surface, so set the text colour here.
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}
