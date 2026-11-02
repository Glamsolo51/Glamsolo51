package com.glamsolo.phonehop.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glamsolo.phonehop.AppGraph
import com.glamsolo.phonehop.data.AppInfo
import com.glamsolo.phonehop.data.FileResolver
import com.glamsolo.phonehop.data.PickedFile
import com.glamsolo.phonehop.graph
import com.glamsolo.phonehop.transfer.PeerDevice
import com.glamsolo.phonehop.transfer.ReceiveState
import com.glamsolo.phonehop.transfer.ReceivedApp
import com.glamsolo.phonehop.transfer.SendState
import com.glamsolo.phonehop.ui.screens.SelectUiState
import com.glamsolo.phonehop.ui.screens.SelectionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { Home, Select, Devices, Sending, Receive }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph: AppGraph = application.graph
    private val resolver = application.contentResolver

    val deviceName = graph.settings.deviceName
    val network = graph.network.status
    val sendState: StateFlow<SendState> = graph.sender.state
    val receiveState: StateFlow<ReceiveState> = graph.receiver.state
    val received = graph.receiver.received
    val receiveEvents = graph.receiver.events
    val installStatuses = graph.installer.statuses

    // ---- navigation ----
    private val _backStack = MutableStateFlow(initialStack())
    val screen: StateFlow<Screen> = _backStack.map { it.last() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _backStack.value.last())

    private fun initialStack(): List<Screen> = when {
        // The activity was recreated while a transfer keeps running in the service.
        graph.sender.state.value != SendState.Idle -> listOf(Screen.Home, Screen.Sending)
        graph.receiver.state.value != ReceiveState.Idle -> listOf(Screen.Home, Screen.Receive)
        else -> listOf(Screen.Home)
    }

    fun navigate(screen: Screen) {
        _backStack.update { it + screen }
    }

    /** Returns false when already on the home screen. */
    fun back(): Boolean {
        val stack = _backStack.value
        if (stack.size <= 1) return false
        _backStack.value = stack.dropLast(1)
        return true
    }

    // ---- selection ----
    private val _tab = MutableStateFlow(0)
    private val _apps = MutableStateFlow<List<AppInfo>?>(null)
    private val _query = MutableStateFlow("")
    private val _selectedApps = MutableStateFlow<Set<String>>(emptySet())
    private val _files = MutableStateFlow<List<PickedFile>>(emptyList())
    private val _readingFolder = MutableStateFlow(false)
    private var appsLoading = false

    val selectState: StateFlow<SelectUiState> = combine(
        combine(_tab, _apps, _query) { tab, apps, query -> Triple(tab, apps, query) },
        _selectedApps,
        _files,
        _readingFolder,
    ) { (tab, apps, query), selected, files, reading ->
        val visible = apps?.filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        val chosenApps = apps.orEmpty().filter { it.packageName in selected }
        SelectUiState(
            tab = tab,
            apps = visible,
            query = query,
            selectedApps = selected,
            files = files,
            readingFolder = reading,
            summary = SelectionSummary(
                apps = chosenApps.size,
                files = files.size,
                bytes = chosenApps.sumOf { it.sizeBytes } + files.sumOf { it.size.coerceAtLeast(0) },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SelectUiState())

    fun loadApps() {
        if (_apps.value != null || appsLoading) return
        appsLoading = true
        viewModelScope.launch {
            _apps.value = runCatching { graph.apps.load() }.getOrDefault(emptyList())
            appsLoading = false
        }
    }

    fun setTab(tab: Int) {
        _tab.value = tab
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun toggleApp(packageName: String) {
        _selectedApps.update { if (packageName in it) it - packageName else it + packageName }
    }

    fun selectAllVisible(select: Boolean) {
        val visible = selectState.value.apps.orEmpty().map { it.packageName }.toSet()
        _selectedApps.update { if (select) it + visible else it - visible }
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val picked = FileResolver.resolve(resolver, uris)
            _files.update { (it + picked).distinctBy { file -> file.uri } }
        }
    }

    fun addFolder(treeUri: Uri?) {
        treeUri ?: return
        _readingFolder.value = true
        viewModelScope.launch {
            val picked = runCatching { FileResolver.resolveTree(resolver, treeUri) }.getOrDefault(emptyList())
            _files.update { (it + picked).distinctBy { file -> file.uri } }
            _readingFolder.value = false
        }
    }

    fun removeFile(file: PickedFile) {
        _files.update { list -> list.filterNot { it.uri == file.uri } }
    }

    private fun clearSelection() {
        _selectedApps.value = emptySet()
        _files.value = emptyList()
        _query.value = ""
    }

    // ---- sending ----
    fun discoverDevices(): Flow<List<PeerDevice>> = graph.discovery.discover()

    fun sendTo(device: PeerDevice) {
        val selected = _selectedApps.value
        val apps = _apps.value.orEmpty().filter { it.packageName in selected }
        graph.sender.send(device, apps, _files.value)
        _backStack.value = listOf(Screen.Home, Screen.Sending)
    }

    fun cancelSending() = graph.sender.cancel()
    fun retrySending() = graph.sender.retry()

    fun finishSending() {
        if (sendState.value is SendState.Finished) clearSelection()
        graph.sender.reset()
        _backStack.value = listOf(Screen.Home)
    }

    // ---- receiving ----
    fun openReceive() {
        graph.receiver.start()
        navigate(Screen.Receive)
    }

    fun closeReceive() {
        graph.receiver.stop()
        _backStack.value = listOf(Screen.Home)
    }

    fun accept() = graph.receiver.accept()
    fun decline() = graph.receiver.decline()
    fun cancelReceiving() = graph.receiver.cancelTransfer()
    fun receiveMore() = graph.receiver.dismissResult()

    fun canInstall(): Boolean = graph.installer.canInstall()
    fun install(apps: List<ReceivedApp>) = graph.installer.install(apps)
    fun installStatus(app: ReceivedApp) = graph.installer.statusOf(app)

    fun rename(name: String) = graph.settings.rename(name)
}
