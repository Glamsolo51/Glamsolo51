package com.glamsolo.phonehop.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.data.PickedFile
import com.glamsolo.phonehop.transfer.ReceiveEvent
import com.glamsolo.phonehop.transfer.ReceivedApp
import com.glamsolo.phonehop.transfer.SavedFile
import com.glamsolo.phonehop.ui.components.AuroraBackground
import com.glamsolo.phonehop.ui.screens.DevicesScreen
import com.glamsolo.phonehop.ui.screens.HomeScreen
import com.glamsolo.phonehop.ui.screens.ReceiveActions
import com.glamsolo.phonehop.ui.screens.ReceiveScreen
import com.glamsolo.phonehop.ui.screens.ReceiveUiState
import com.glamsolo.phonehop.ui.screens.SelectActions
import com.glamsolo.phonehop.ui.screens.SelectScreen
import com.glamsolo.phonehop.ui.screens.SendingScreen

/** Connects the view model to the stateless screens. */
@Composable
fun PhoneHopRoot(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    AuroraBackground {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith fadeOut(tween(160))
            },
            label = "screens",
        ) { current ->
            when (current) {
                Screen.Home -> HomeRoute(vm)
                Screen.Select -> SelectRoute(vm)
                Screen.Devices -> DevicesRoute(vm)
                Screen.Sending -> SendingRoute(vm)
                Screen.Receive -> ReceiveRoute(vm)
            }
        }
    }
}

private fun runtimePermissions(forReceiving: Boolean): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    if (forReceiving && Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

@Composable
private fun HomeRoute(vm: MainViewModel) {
    val context = LocalContext.current
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    var afterPermissions by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val storageDenied = result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false
        if (storageDenied) {
            Toast.makeText(context, R.string.storage_permission_needed, Toast.LENGTH_LONG).show()
        } else {
            afterPermissions?.invoke()
        }
        afterPermissions = null
    }

    fun withPermissions(forReceiving: Boolean, action: () -> Unit) {
        val missing = runtimePermissions(forReceiving).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermissions = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    HomeScreen(
        deviceName = deviceName,
        network = network,
        onSend = { withPermissions(false) { vm.navigate(Screen.Select) } },
        onReceive = { withPermissions(true) { vm.openReceive() } },
        onRename = vm::rename,
    )
}

@Composable
private fun SelectRoute(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadApps() }
    val state by vm.selectState.collectAsStateWithLifecycle()
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addFiles(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.addFolder(it) }
    BackHandler { vm.back() }

    SelectScreen(
        state = state,
        actions = object : SelectActions {
            override fun onTab(tab: Int) = vm.setTab(tab)
            override fun onQuery(query: String) = vm.setQuery(query)
            override fun onToggleApp(packageName: String) = vm.toggleApp(packageName)
            override fun onSelectAll(select: Boolean) = vm.selectAllVisible(select)
            override fun onPickMedia() = mediaPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )

            override fun onPickFiles() = filePicker.launch(arrayOf("*/*"))
            override fun onPickFolder() = folderPicker.launch(null)
            override fun onRemoveFile(file: PickedFile) = vm.removeFile(file)
            override fun onNext() = vm.navigate(Screen.Devices)
            override fun onBack() {
                vm.back()
            }
        },
    )
}

@Composable
private fun DevicesRoute(vm: MainViewModel) {
    val context = LocalContext.current
    val devicesFlow = remember { vm.discoverDevices() }
    val devices by devicesFlow.collectAsStateWithLifecycle(emptyList())
    val selection by vm.selectState.collectAsStateWithLifecycle()
    BackHandler { vm.back() }
    DevicesScreen(
        devices = devices,
        selectionText = stringResource(
            R.string.dot_join,
            Format.counts(context, selection.summary.apps, selection.summary.files),
            Format.bytes(context, selection.summary.bytes),
        ),
        onPick = vm::sendTo,
        onBack = { vm.back() },
    )
}

@Composable
private fun SendingRoute(vm: MainViewModel) {
    val state by vm.sendState.collectAsStateWithLifecycle()
    SendingScreen(
        state = state,
        onCancel = vm::cancelSending,
        onRetry = vm::retrySending,
        onDone = vm::finishSending,
    )
}

@Composable
private fun ReceiveRoute(vm: MainViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by vm.receiveState.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val received by vm.received.collectAsStateWithLifecycle()
    // Re-read install states whenever the installer reports progress.
    val statuses by vm.installStatuses.collectAsStateWithLifecycle()
    var pendingInstall by remember { mutableStateOf<List<ReceivedApp>?>(null) }
    var askPermission by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apps = pendingInstall
        pendingInstall = null
        if (apps != null && vm.canInstall()) vm.install(apps)
    }

    LaunchedEffect(Unit) {
        vm.receiveEvents.collect { event ->
            when (event) {
                is ReceiveEvent.RequestWithdrawn -> Toast.makeText(
                    context,
                    resources.getString(R.string.request_withdrawn, event.senderName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    ReceiveScreen(
        ui = ReceiveUiState(
            state = state,
            deviceName = deviceName,
            addresses = network.addresses,
            received = received,
            installStatus = { app -> statuses[app.packageName] ?: vm.installStatus(app) },
        ),
        actions = object : ReceiveActions {
            override fun onAccept() = vm.accept()
            override fun onDecline() = vm.decline()
            override fun onCancelTransfer() = vm.cancelReceiving()
            override fun onReceiveMore() = vm.receiveMore()
            override fun onClose() = vm.closeReceive()
            override fun onInstall(apps: List<ReceivedApp>) {
                if (vm.canInstall()) {
                    vm.install(apps)
                } else {
                    pendingInstall = apps
                    askPermission = true
                }
            }

            override fun onOpenFile(file: SavedFile) {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(file.uri, file.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                }
            }
        },
    )

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.install_permission_title)) },
            text = { Text(stringResource(R.string.install_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, ("package:" + context.packageName).toUri())
                    runCatching { settingsLauncher.launch(intent) }
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { askPermission = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
