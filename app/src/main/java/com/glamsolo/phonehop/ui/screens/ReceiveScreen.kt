package com.glamsolo.phonehop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.core.IncomingRequest
import com.glamsolo.phonehop.core.ItemKind
import com.glamsolo.phonehop.core.Protocol
import com.glamsolo.phonehop.install.InstallStatus
import com.glamsolo.phonehop.transfer.ReceiveState
import com.glamsolo.phonehop.transfer.ReceivedApp
import com.glamsolo.phonehop.transfer.ReceivedFile
import com.glamsolo.phonehop.transfer.ReceivedItem
import com.glamsolo.phonehop.transfer.SavedFile
import com.glamsolo.phonehop.ui.Format
import com.glamsolo.phonehop.ui.components.Chip
import com.glamsolo.phonehop.ui.components.GhostButton
import com.glamsolo.phonehop.ui.components.GlassCard
import com.glamsolo.phonehop.ui.components.GradientButton
import com.glamsolo.phonehop.ui.components.PngIcon
import com.glamsolo.phonehop.ui.components.ScreenHeader
import com.glamsolo.phonehop.ui.theme.Brand
import com.glamsolo.phonehop.ui.theme.LocalGlass

data class ReceiveUiState(
    val state: ReceiveState,
    val deviceName: String,
    val addresses: List<String>,
    val received: List<ReceivedItem>,
    val installStatus: (ReceivedApp) -> InstallStatus,
)

interface ReceiveActions {
    fun onAccept()
    fun onDecline()
    fun onCancelTransfer()
    fun onReceiveMore()
    fun onInstall(apps: List<ReceivedApp>)
    fun onOpenFile(file: SavedFile)
    fun onClose()
}

@Composable
fun ReceiveScreen(ui: ReceiveUiState, actions: ReceiveActions) {
    val context = LocalContext.current
    val state = ui.state
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (state.isTransferring) confirmCancel = true else actions.onClose() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            ScreenHeader(stringResource(R.string.receive_title), onBack = {
                if (state.isTransferring) confirmCancel = true else actions.onClose()
            })
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "main") {
                    when (state) {
                        ReceiveState.Idle, ReceiveState.Starting -> WaitingView(
                            Icons.Rounded.SouthWest, Brand.Receive, stringResource(R.string.receive_starting), null,
                        )
                        is ReceiveState.Error -> ResultView(
                            Icons.Rounded.ErrorOutline, listOf(Brand.Amber, Brand.Coral),
                            stringResource(R.string.error_server), state.message,
                        )
                        is ReceiveState.Listening, is ReceiveState.Incoming -> ListeningView(ui)
                        is ReceiveState.Transferring -> TransferProgressView(
                            stringResource(R.string.receiving_from, state.request.manifest.senderName),
                            state.progress,
                            Brand.Receive,
                        )
                        is ReceiveState.Finished -> ResultView(
                            Icons.Rounded.Check, Brand.Receive,
                            stringResource(R.string.received_title),
                            stringResource(
                                R.string.done_summary,
                                Format.counts(context, state.request.manifest.appCount, state.request.manifest.fileCount),
                                Format.bytes(context, state.stats.bytes),
                                Format.duration(context, state.stats.durationMillis / 1000),
                            ),
                        )
                        is ReceiveState.Failed -> ResultView(
                            Icons.Rounded.ErrorOutline, listOf(Brand.Amber, Brand.Coral),
                            stringResource(R.string.failed_title), Format.error(context, state.reason),
                        )
                        is ReceiveState.Cancelled -> ResultView(
                            Icons.Rounded.Close, listOf(Brand.Amber, Brand.Coral),
                            stringResource(R.string.cancelled_title), null,
                        )
                    }
                }
                if (!state.isTransferring) receivedItems(ui, actions)
                item(key = "bottom_space") { Spacer(Modifier.height(16.dp)) }
            }
            BottomButtons(state, actions, onCancel = { confirmCancel = true })
        }

        val incoming = state as? ReceiveState.Incoming
        AnimatedVisibility(
            visible = incoming != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.fillMaxSize(),
        ) {
            // Keep showing the last request while the sheet slides away.
            var last by remember { mutableStateOf(incoming?.request) }
            if (incoming != null) last = incoming.request
            last?.let { IncomingSheet(it, actions) }
        }
    }

    if (confirmCancel) {
        CancelTransferDialog(
            onConfirm = {
                confirmCancel = false
                actions.onCancelTransfer()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun ListeningView(ui: ReceiveUiState) {
    val port = (ui.state as? ReceiveState.Listening)?.port ?: (ui.state as? ReceiveState.Incoming)?.port
    WaitingView(
        icon = Icons.Rounded.SouthWest,
        gradient = Brand.Receive,
        title = stringResource(R.string.receive_ready),
        subtitle = stringResource(R.string.receive_hint),
    ) {
        Spacer(Modifier.height(16.dp))
        Chip(stringResource(R.string.receive_visible_as, ui.deviceName), leading = Icons.Rounded.Visibility)
        Spacer(Modifier.height(18.dp))
        if (ui.addresses.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Text(
                    stringResource(R.string.receive_address),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ui.addresses.forEach { address ->
                    val text = if (port == null || port == Protocol.DEFAULT_PORT) address else "$address:$port"
                    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("address"))
                }
            }
        } else {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = Brand.Coral)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.network_missing), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.receivedItems(ui: ReceiveUiState, actions: ReceiveActions) {
    val apps = ui.received.filterIsInstance<ReceivedApp>()
    val files = ui.received.filterIsInstance<ReceivedFile>()
    if (apps.isNotEmpty()) {
        item(key = "apps_header") {
            val pending = apps.filter { ui.installStatus(it).let { s -> s is InstallStatus.NotInstalled || s is InstallStatus.Failed } }
            SectionHeader(stringResource(R.string.received_apps)) {
                if (pending.size > 1) {
                    TextButton(onClick = { actions.onInstall(pending) }) { Text(stringResource(R.string.install_all)) }
                }
            }
        }
        items(apps, key = { "app_" + it.packageName + it.apkFiles.firstOrNull()?.path }) { app ->
            ReceivedAppRow(app, ui.installStatus(app)) { actions.onInstall(listOf(app)) }
        }
    }
    if (files.isNotEmpty()) {
        item(key = "files_header") {
            SectionHeader(stringResource(R.string.received_files)) {}
            val folders = files.map { it.file.folder }.distinct().take(3).joinToString(", ")
            Text(
                stringResource(R.string.files_saved_to, folders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
            )
        }
        items(files.take(200), key = { "file_" + it.file.uri }) { file ->
            ReceivedFileRow(file.file) { actions.onOpenFile(file.file) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun ReceivedAppRow(app: ReceivedApp, status: InstallStatus, onInstall: () -> Unit) {
    val context = LocalContext.current
    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), shape = RoundedCornerShape(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PngIcon(app.icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = Format.install(context, status)
                    ?: listOfNotNull(app.versionName?.let { "v$it" }, Format.bytes(context, app.sizeBytes)).joinToString(" · ")
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is InstallStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            when (status) {
                InstallStatus.Installed -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Brand.Mint)
                InstallStatus.Installing, InstallStatus.Queued -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                else -> FilledTonalButton(onClick = onInstall, modifier = Modifier.testTag("install_${app.packageName}")) {
                    Text(stringResource(R.string.install))
                }
            }
        }
    }
}

@Composable
private fun ReceivedFileRow(file: SavedFile, onOpen: () -> Unit) {
    val context = LocalContext.current
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glass.fill)
            .border(1.dp, glass.border, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brand.Cyan.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(mimeIcon(file.mimeType), contentDescription = null, tint = Brand.Cyan)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                listOfNotNull(file.folder, file.size.takeIf { it >= 0 }?.let { Format.bytes(context, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.open), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BottomButtons(state: ReceiveState, actions: ReceiveActions, onCancel: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        when (state) {
            is ReceiveState.Transferring -> GhostButton(
                stringResource(R.string.cancel_transfer), onCancel, Modifier.fillMaxWidth(), icon = Icons.Rounded.Close,
            )
            is ReceiveState.Finished, is ReceiveState.Failed, is ReceiveState.Cancelled -> {
                GhostButton(stringResource(R.string.receive_more), actions::onReceiveMore, Modifier.weight(1f))
                GradientButton(stringResource(R.string.done), actions::onClose, Modifier.weight(1f), gradient = Brand.Receive)
            }
            else -> Unit
        }
    }
}

/** "Galaxy S10 wants to send you…" with Accept / Decline. */
@Composable
private fun IncomingSheet(request: IncomingRequest, actions: ReceiveActions) {
    val context = LocalContext.current
    val manifest = request.manifest
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .navigationBarsPadding()
                .padding(24.dp)
                .testTag("incoming_sheet"),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.incoming_title, manifest.senderName),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                Format.manifestSummary(context, manifest),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val preview = manifest.items.take(4)
            preview.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                    if (item.kind == ItemKind.APP) {
                        PngIcon(item.icon, size = 36.dp)
                    } else {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Brand.Cyan.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(mimeIcon(item.mimeType), contentDescription = null, tint = Brand.Cyan, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(Format.bytes(context, item.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (manifest.items.size > preview.size) {
                val more = manifest.items.size - preview.size
                Text(
                    "+ " + pluralStringResource(R.plurals.items_count, more, more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 48.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton(stringResource(R.string.decline), actions::onDecline, Modifier.weight(1f))
                GradientButton(stringResource(R.string.accept), actions::onAccept, Modifier.weight(1f), gradient = Brand.Receive)
            }
        }
    }
}
