package com.glamsolo.phonehop.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.core.TransferProgress
import com.glamsolo.phonehop.ui.Format
import com.glamsolo.phonehop.ui.components.GlassCard
import com.glamsolo.phonehop.ui.components.GradientBar
import com.glamsolo.phonehop.ui.components.IconBubble
import com.glamsolo.phonehop.ui.components.ProgressRing
import com.glamsolo.phonehop.ui.components.PulseRadar

/** Big ring with percent, speed, the current item and remaining time. */
@Composable
fun TransferProgressView(title: String, progress: TransferProgress, gradient: List<Color>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(26.dp))
        ProgressRing(progress.fraction, gradient, Modifier.size(244.dp).testTag("progress_ring")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${(progress.fraction * 100).toInt()}",
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Text(
                        "%",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 8.dp, start = 2.dp),
                    )
                }
                if (progress.bytesPerSecond > 0) {
                    Text(
                        Format.speed(context, progress.bytesPerSecond),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progress.currentItemTitle.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        R.string.items_progress,
                        (progress.itemsDone + 1).coerceAtMost(progress.totalItems),
                        progress.totalItems,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            GradientBar(progress.fraction, gradient)
            Spacer(Modifier.height(10.dp))
            Row {
                Text(
                    "${Format.bytes(context, progress.bytesDone)} / ${Format.bytes(context, progress.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                progress.etaSeconds?.let {
                    Text(
                        stringResource(R.string.time_left, Format.duration(context, it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Radar with a message: connecting, waiting for an answer, listening. */
@Composable
fun WaitingView(
    icon: ImageVector,
    gradient: List<Color>,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PulseRadar(gradient, Modifier.size(270.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(54.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        extra()
    }
}

/** Success / failure / cancelled headline with a springy icon. */
@Composable
fun ResultView(icon: ImageVector, gradient: List<Color>, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        IconBubble(icon, gradient, size = 116.dp, modifier = Modifier.scale(scale.value))
        Spacer(Modifier.height(22.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp), textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun CancelTransferDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.cancel_confirm_title)) },
        text = { Text(stringResource(R.string.cancel_confirm_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.stop)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.keep_going)) } },
    )
}
