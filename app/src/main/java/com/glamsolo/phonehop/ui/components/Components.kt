package com.glamsolo.phonehop.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.ui.theme.Brand
import com.glamsolo.phonehop.ui.theme.LocalGlass

/** Loads app icons; screens get it from the composition so previews and tests can swap it. */
fun interface IconSource {
    suspend fun load(packageName: String): ImageBitmap?
}

val LocalIconSource = staticCompositionLocalOf { IconSource { null } }

/** Soft, slowly drifting colour blobs behind every screen. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = Brand.Aurora,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlass.current
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxSize()
            .background(background)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun blob(color: Color, center: Offset, radius: Float) = drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = glass.blobAlpha), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
                blob(colors[0], Offset(w * (0.05f + 0.2f * t), h * 0.06f), w * 0.85f)
                blob(colors[1 % colors.size], Offset(w * (1.0f - 0.15f * t), h * (0.30f + 0.06f * t)), w * 0.75f)
                blob(colors.last(), Offset(w * (0.25f + 0.25f * t), h * 1.02f), w * 0.95f)
            },
        content = content,
    )
}

/** Frosted translucent card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current
    Column(
        modifier
            .clip(shape)
            .background(glass.fill)
            .border(1.dp, glass.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Big gradient tile used for the main actions. */
@Composable
fun ActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(gradient))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 48.dp, y = (-48).dp)
                .size(160.dp)
                .background(Color.White.copy(alpha = 0.13f), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-30).dp, y = 40.dp)
                .size(110.dp)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
        )
        Column(Modifier.padding(20.dp)) {
            Box(
                Modifier
                    .size(54.dp)
                    .background(Color.White.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.weight(1f))
            // Wide display font: shrink long words ("Отправить") instead of cutting them.
            BasicText(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(color = Color.White),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 20.sp, stepSize = 0.5.sp),
            )
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f), maxLines = 2)
        }
    }
}

/** Filled pill button with a gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: List<Color> = Brand.Send,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val disabled = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier
            .height(58.dp)
            .clip(CircleShape)
            .background(if (enabled) Brush.linearGradient(gradient) else SolidColor(disabled))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            val color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
            if (icon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Translucent pill button for secondary actions. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val glass = LocalGlass.current
    Box(
        modifier
            .height(58.dp)
            .clip(CircleShape)
            .background(glass.fill)
            .border(1.dp, glass.border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
        }
    }
}

/** Screen title row with an optional round back button. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back), onBack)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(glass.fill)
            .border(1.dp, glass.border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
    }
}

/** Pill-shaped segmented control with a sliding, inverted indicator. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    val inverse = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(glass.fill)
            .border(1.dp, glass.border, CircleShape)
            .padding(4.dp),
    ) {
        val width = maxWidth / options.size
        val offset by animateDpAsState(width * selected, label = "tab")
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(width)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(inverse),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(role = Role.Tab) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (index == selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Round check mark that fills with the gradient when selected. */
@Composable
fun SelectionDot(selected: Boolean, modifier: Modifier = Modifier, gradient: List<Color> = Brand.Send) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (selected) Brush.linearGradient(gradient) else SolidColor(Color.Transparent))
            .border(2.dp, if (selected) Color.Transparent else outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(selected, enter = scaleIn(), exit = scaleOut()) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Expanding rings around a gradient disc: "looking for / waiting for" phones. */
@Composable
fun PulseRadar(
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    discSize: Dp = 124.dp,
    center: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing)),
        label = "phase",
    )
    Box(
        modifier.drawBehind {
            val maxRadius = size.minDimension / 2
            val ringBrush = Brush.linearGradient(gradient)
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    brush = ringBrush,
                    radius = maxRadius * (0.42f + 0.58f * p),
                    alpha = (1f - p) * 0.55f,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(gradient.first().copy(alpha = 0.28f), Color.Transparent)),
                radius = maxRadius * 0.75f,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(discSize)
                .clip(CircleShape)
                .background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center,
            content = center,
        )
    }
}

/** Thick circular progress with a gradient sweep. */
@Composable
fun ProgressRing(
    progress: Float,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    stroke: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(450), label = "ring")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Box(
        modifier.drawBehind {
            val width = stroke.toPx()
            val inset = width / 2
            val arc = Size(size.width - width, size.height - width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
            rotate(-90f) {
                drawArc(
                    brush = Brush.sweepGradient(gradient + gradient.first()),
                    startAngle = 0f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arc,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Gradient circle with a white icon. */
@Composable
fun IconBubble(icon: ImageVector, gradient: List<Color>, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Icon of an installed app, loaded asynchronously. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val source = LocalIconSource.current
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) { bitmap = source.load(packageName) }
    IconFrame(bitmap, modifier, size)
}

/** Icon sent along with an app (PNG bytes). */
@Composable
fun PngIcon(bytes: ByteArray?, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    IconFrame(bitmap, modifier, size)
}

@Composable
private fun IconFrame(bitmap: ImageBitmap?, modifier: Modifier, size: Dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Small rounded label. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, leading: ImageVector? = null, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null) {
    val glass = LocalGlass.current
    Row(
        modifier
            .clip(CircleShape)
            .background(glass.fill)
            .border(1.dp, glass.border, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Gradient text-free bar used as a thin linear progress indicator. */
@Composable
fun GradientBar(progress: Float, gradient: List<Color>, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "bar")
    Box(
        modifier
            .height(6.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(CircleShape)
                .background(Brush.linearGradient(gradient)),
        )
    }
}
