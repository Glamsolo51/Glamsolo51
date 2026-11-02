package com.glamsolo.phonehop.transfer

import android.content.Context
import com.glamsolo.phonehop.core.IncomingRequest
import com.glamsolo.phonehop.core.Protocol
import com.glamsolo.phonehop.core.ReceiveHandler
import com.glamsolo.phonehop.core.ReceiveSink
import com.glamsolo.phonehop.core.TransferException
import com.glamsolo.phonehop.core.TransferProgress
import com.glamsolo.phonehop.core.TransferServer
import com.glamsolo.phonehop.core.TransferStats
import com.glamsolo.phonehop.data.DeviceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Receive mode: runs the transfer server, advertises it on the network, asks the user
 * about incoming requests and keeps the list of everything received in this session.
 */
class ReceiveController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: DeviceSettings,
    private val discovery: Discovery,
    private val fileStoreFactory: () -> FileStore,
    private val apkBaseDir: File = File(context.filesDir, "received_apks"),
    private val port: Int = Protocol.DEFAULT_PORT,
    private val startService: (Context) -> Unit = TransferService::start,
    private val notifier: (IncomingRequest?) -> Unit = { TransferNotifications.showIncoming(context, it) },
) : ReceiveHandler {

    private val _state = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    val state: StateFlow<ReceiveState> = _state.asStateFlow()

    /** Everything received since receive mode was opened, newest transfer last. */
    private val _received = MutableStateFlow<List<ReceivedItem>>(emptyList())
    val received: StateFlow<List<ReceivedItem>> = _received.asStateFlow()

    private val _events = MutableSharedFlow<ReceiveEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ReceiveEvent> = _events.asSharedFlow()

    @Volatile private var server: TransferServer? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var pendingDecision: CompletableDeferred<Boolean>? = null
    @Volatile private var currentSink: AndroidReceiveSink? = null
    @Volatile private var boundPort: Int = 0

    fun start() {
        if (serverJob?.isActive == true) return
        // APKs of a previous session can no longer be installed from the UI: free the space.
        apkBaseDir.deleteRecursively()
        _received.value = emptyList()
        _state.value = ReceiveState.Starting
        val newServer = TransferServer({ settings.deviceName.value }, this)
        server = newServer
        startService(context)
        serverJob = scope.launch(Dispatchers.IO) {
            var advertisement: AutoCloseable? = null
            try {
                newServer.run(port) { actualPort ->
                    boundPort = actualPort
                    advertisement = discovery.advertise(settings.deviceName.value, actualPort)
                    _state.value = ReceiveState.Listening(actualPort)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ReceiveState.Error(e.message)
            } finally {
                runCatching { advertisement?.close() }
            }
        }
    }

    fun stop() {
        pendingDecision?.complete(false)
        serverJob?.cancel()
        serverJob = null
        server = null
        notifier(null)
        _state.value = ReceiveState.Idle
    }

    fun accept() = decide(true)
    fun decline() = decide(false)

    private fun decide(accept: Boolean) {
        val incoming = _state.value as? ReceiveState.Incoming ?: return
        if (accept) {
            _state.value = ReceiveState.Transferring(
                incoming.port, incoming.request, TransferProgress.initial(incoming.request.manifest),
            )
        } else {
            _state.value = ReceiveState.Listening(incoming.port)
        }
        pendingDecision?.complete(accept)
    }

    fun cancelTransfer() {
        server?.cancelActiveSession()
    }

    /** Leaves the result screen and waits for the next sender. */
    fun dismissResult() {
        _state.update { current ->
            when (current) {
                is ReceiveState.Finished, is ReceiveState.Failed, is ReceiveState.Cancelled -> ReceiveState.Listening(boundPort)
                else -> current
            }
        }
    }

    // ---- ReceiveHandler, called by the server on background threads ----

    override suspend fun onRequest(request: IncomingRequest): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _state.value = ReceiveState.Incoming(boundPort, request)
        notifier(request)
        try {
            return decision.await()
        } finally {
            pendingDecision = null
            notifier(null)
            // Timed out or the server stopped while the question was on screen.
            _state.update { current ->
                if (current is ReceiveState.Incoming && current.request.id == request.id) {
                    ReceiveState.Listening(boundPort)
                } else {
                    current
                }
            }
        }
    }

    override fun onRequestWithdrawn(request: IncomingRequest) {
        _events.tryEmit(ReceiveEvent.RequestWithdrawn(request.manifest.senderName))
    }

    override fun createSink(request: IncomingRequest): ReceiveSink {
        val sink = AndroidReceiveSink(File(apkBaseDir, "session-${request.id}"), fileStoreFactory())
        currentSink = sink
        return sink
    }

    override fun onProgress(request: IncomingRequest, progress: TransferProgress) {
        _state.update { current ->
            if (current is ReceiveState.Transferring || current is ReceiveState.Incoming) {
                ReceiveState.Transferring(boundPort, request, progress)
            } else {
                current
            }
        }
    }

    override fun onCompleted(request: IncomingRequest, stats: TransferStats) {
        collectReceived()
        _state.value = ReceiveState.Finished(boundPort, request, stats)
    }

    override fun onFailed(request: IncomingRequest, error: TransferException) {
        collectReceived()
        _state.value = ReceiveState.Failed(boundPort, request, error.reason, error.message)
    }

    override fun onCancelled(request: IncomingRequest) {
        collectReceived()
        if (serverJob?.isActive == true) _state.value = ReceiveState.Cancelled(boundPort, request)
    }

    private fun collectReceived() {
        val sink = currentSink ?: return
        currentSink = null
        val items = sink.received.toList()
        if (items.isNotEmpty()) _received.update { it + items }
    }
}
