package com.glamsolo.phonehop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.data.DeviceSettings
import com.glamsolo.phonehop.data.NetworkStatus
import com.glamsolo.phonehop.ui.components.ActionTile
import com.glamsolo.phonehop.ui.components.Chip
import com.glamsolo.phonehop.ui.components.GlassCard
import com.glamsolo.phonehop.ui.components.IconBubble
import com.glamsolo.phonehop.ui.theme.Brand
import com.glamsolo.phonehop.ui.theme.Unbounded

@Composable
fun HomeScreen(
    deviceName: String,
    network: NetworkStatus,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onRename: (String) -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(Brand.Aurora)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SyncAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = Unbounded, fontSize = 18.sp),
            )
            Spacer(Modifier.weight(1f))
            Chip(
                text = deviceName,
                leading = Icons.Rounded.Smartphone,
                modifier = Modifier.widthIn(max = 190.dp),
                onClick = { renaming = true },
            )
        }

        Spacer(Modifier.height(34.dp))
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActionTile(
                title = stringResource(R.string.action_send),
                subtitle = stringResource(R.string.action_send_hint),
                icon = Icons.Rounded.NorthEast,
                gradient = Brand.Send,
                onClick = onSend,
                modifier = Modifier.weight(1f).height(224.dp),
            )
            ActionTile(
                title = stringResource(R.string.action_receive),
                subtitle = stringResource(R.string.action_receive_hint),
                icon = Icons.Rounded.SouthWest,
                gradient = Brand.Receive,
                onClick = onReceive,
                modifier = Modifier.weight(1f).height(224.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        NetworkCard(network)
        Spacer(Modifier.height(14.dp))
        StepsCard()
        Spacer(Modifier.height(24.dp))
    }

    if (renaming) {
        RenameDialog(
            current = deviceName,
            onDismiss = { renaming = false },
            onSave = {
                onRename(it)
                renaming = false
            },
        )
    }
}

@Composable
private fun NetworkCard(network: NetworkStatus) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (network.isConnected) {
                IconBubble(Icons.Rounded.Wifi, Brand.Receive)
            } else {
                IconBubble(Icons.Rounded.WifiOff, listOf(Brand.Amber, Brand.Coral))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (network.isConnected) R.string.network_ready else R.string.network_missing_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (network.isConnected) network.addresses.joinToString(" · ") else stringResource(R.string.network_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepsCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.steps_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        val steps = listOf(R.string.step_1, R.string.step_2, R.string.step_3)
        val gradients = listOf(Brand.Aurora, Brand.Receive, Brand.Send)
        steps.forEachIndexed { index, text ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(gradients[index])),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.rename_device_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.rename_device_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(DeviceSettings.MAX_LENGTH) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
