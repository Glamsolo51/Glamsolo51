package com.glamsolo.phonehop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.core.Protocol
import com.glamsolo.phonehop.transfer.PeerDevice
import com.glamsolo.phonehop.ui.components.Chip
import com.glamsolo.phonehop.ui.components.GhostButton
import com.glamsolo.phonehop.ui.components.GlassCard
import com.glamsolo.phonehop.ui.components.IconBubble
import com.glamsolo.phonehop.ui.components.PulseRadar
import com.glamsolo.phonehop.ui.components.ScreenHeader
import com.glamsolo.phonehop.ui.theme.Brand

@Composable
fun DevicesScreen(
    devices: List<PeerDevice>,
    selectionText: String,
    onPick: (PeerDevice) -> Unit,
    onBack: () -> Unit,
) {
    var manual by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ScreenHeader(stringResource(R.string.devices_title), onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Chip(selectionText, leading = Icons.Rounded.Inventory2)
            PulseRadar(Brand.Send, Modifier.size(250.dp)) {
                Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = Color.White, modifier = Modifier.size(52.dp))
            }
            Text(stringResource(R.string.devices_searching), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.devices_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            if (devices.isNotEmpty()) {
                Text(
                    stringResource(R.string.devices_found),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
                )
                devices.forEach { device ->
                    DeviceRow(device) { onPick(device) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        GhostButton(
            text = stringResource(R.string.manual_address),
            onClick = { manual = true },
            icon = Icons.Rounded.Dialpad,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
    if (manual) {
        ManualAddressDialog(
            onDismiss = { manual = false },
            onConnect = {
                manual = false
                onPick(it)
            },
        )
    }
}

@Composable
private fun DeviceRow(device: PeerDevice, onClick: () -> Unit) {
    GlassCard(
        Modifier.fillMaxWidth().testTag("device_${device.name}"),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.Smartphone, Brand.Receive, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
}

/** Parses "192.168.1.23" or "192.168.1.23:48484" (host names work too). */
fun parseAddress(input: String): PeerDevice? {
    val text = input.trim()
    if (text.isEmpty() || text.contains(' ')) return null
    val host: String
    val port: Int
    val colon = text.lastIndexOf(':')
    if (colon > 0 && text.count { it == ':' } == 1) {
        host = text.substring(0, colon)
        port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    } else {
        host = text
        port = Protocol.DEFAULT_PORT
    }
    val ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    val hostname = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$")
    if (!ipv4.matches(host) && (host.all { it.isDigit() || it == '.' } || !hostname.matches(host))) return null
    return PeerDevice(name = host, host = host, port = port)
}

@Composable
private fun ManualAddressDialog(onDismiss: () -> Unit, onConnect: (PeerDevice) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun submit() {
        val device = parseAddress(text)
        if (device == null) error = true else onConnect(device)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.manual_address_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.manual_address_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.take(64)
                        error = false
                    },
                    singleLine = true,
                    isError = error,
                    placeholder = { Text("192.168.1.23") },
                    supportingText = if (error) {
                        { Text(stringResource(R.string.manual_address_invalid)) }
                    } else {
                        null
                    },
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("address_field"),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text(stringResource(R.string.connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
