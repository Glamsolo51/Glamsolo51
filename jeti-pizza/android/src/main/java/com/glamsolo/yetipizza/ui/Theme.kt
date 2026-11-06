package com.glamsolo.yetipizza.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.Fastfood
import androidx.compose.material.icons.outlined.Icecream
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalPizza
import androidx.compose.material.icons.outlined.RiceBowl
import androidx.compose.material.icons.outlined.SportsBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.yetipizza.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colours of the pizzeria: chalkboard walls, the red sign, wood panels and the green LED strip. */
object Yeti {
    val Charcoal = Color(0xFF141212)
    val Panel = Color(0xFF1D1A19)
    val Panel2 = Color(0xFF272322)
    val Cream = Color(0xFFF6EFE6)
    val Muted = Color(0xFFB5AAA0)
    val Sign = Color(0xFFD63C28)
    val Wood = Color(0xFFD29C62)
    val Led = Color(0xFF7AD36B)
    val Warn = Color(0xFFF2B544)
    val Line = Color(0x24F6EFE6)
    val LineStrong = Color(0x47F6EFE6)
}

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)
val Unbounded = FontFamily(Font(R.font.unbounded_bold, FontWeight.Bold))

private val colors = darkColorScheme(
    primary = Yeti.Sign,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1A12),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Yeti.Wood,
    onSecondary = Yeti.Charcoal,
    // Segmented buttons, badges and selected chips: the panel tone, not Material's default violet.
    secondaryContainer = Yeti.Panel2,
    onSecondaryContainer = Yeti.Cream,
    tertiary = Yeti.Led,
    onTertiary = Color(0xFF10200D),
    background = Yeti.Charcoal,
    onBackground = Yeti.Cream,
    surface = Yeti.Charcoal,
    onSurface = Yeti.Cream,
    surfaceVariant = Yeti.Panel2,
    onSurfaceVariant = Yeti.Muted,
    surfaceContainerLowest = Yeti.Charcoal,
    surfaceContainerLow = Yeti.Panel,
    surfaceContainer = Yeti.Panel,
    surfaceContainerHigh = Yeti.Panel2,
    surfaceContainerHighest = Color(0xFF312C2A),
    outline = Yeti.LineStrong,
    outlineVariant = Yeti.Line,
    error = Color(0xFFFF8A7A),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF4A1A12),
    onErrorContainer = Color(0xFFFFC2B8),
)

private val base = Typography()
private fun TextStyle.body() = copy(fontFamily = Manrope)
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = base.titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge.body(),
    bodyMedium = base.bodyMedium.body(),
    bodySmall = base.bodySmall.body(),
    labelLarge = base.labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
    labelSmall = base.labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
)

@Composable
fun YetiTheme(content: @Composable () -> Unit) {
    // A touch smaller on phones, so more of the menu fits on the screen; tablets keep the normal size.
    val density = LocalDensity.current
    val phone = LocalConfiguration.current.smallestScreenWidthDp < 600
    CompositionLocalProvider(LocalDensity provides if (phone) Density(density.density * PHONE_SCALE, density.fontScale) else density) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(18.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}

private const val PHONE_SCALE = 0.9f

/** Same icon per category as the line drawings over the pizzeria's door. */
fun categoryIcon(id: String): ImageVector = when (id) {
    "pizza" -> Icons.Outlined.LocalPizza
    "sweet" -> Icons.Outlined.Cake
    "snacks" -> Icons.Outlined.Fastfood
    "salads" -> Icons.Outlined.RiceBowl
    "hot" -> Icons.Outlined.LocalCafe
    "shakes" -> Icons.Outlined.EmojiFoodBeverage
    "drinks" -> Icons.Outlined.LocalDrink
    "beer" -> Icons.Outlined.SportsBar
    "icecream" -> Icons.Outlined.Icecream
    "chips" -> Icons.Outlined.BakeryDining
    else -> Icons.Outlined.Inventory2
}

val Moscow: ZoneId = ZoneId.of("Europe/Moscow")
private val rubFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU"))
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

fun rub(n: Int): String = "${rubFormat.format(n)} ₽"
fun timeText(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(Moscow).format(hhmm)
fun phoneText(p: String): String = Regex("""^\+7(\d{3})(\d{3})(\d{2})(\d{2})$""").replace(p, "+7 $1 $2-$3-$4")

val STATUS_CUSTOMER = mapOf("new" to "Принят", "cooking" to "Готовится", "ready" to "Готов, ждём вас", "done" to "Выдан", "cancelled" to "Отменён")
val STATUS_STAFF = mapOf("new" to "Новый", "cooking" to "Готовится", "ready" to "Готов", "done" to "Выдан", "cancelled" to "Отменён")
