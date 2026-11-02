package com.glamsolo.phonehop.transfer

import android.content.Context
import com.glamsolo.phonehop.core.SendStatus
import com.glamsolo.phonehop.core.TransferException
import com.glamsolo.phonehop.core.TransferSender
import com.glamsolo.phonehop.data.AppIconLoader
import com.glamsolo.phonehop.data.AppInfo
import com.glamsolo.phonehop.data.DeviceSettings
import com.glamsolo.phonehop.data.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs one outgoing transfer at a time and exposes its state to the UI and the service. */
class SendController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: DeviceSettings,
    private val icons: AppIconLoader,
    private val startService: (Context) -> Unit = TransferService::start,
) {
    private val _state = MutableStateFlow<SendState>(SendState.Idle)
    val state: StateFlow<SendState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastRequest: Triple<PeerDevice, List<AppInfo>, List<PickedFile>>? = null

    fun send(target: PeerDevice, apps: List<AppInfo>, files: List<PickedFile>) {
        if (_state.value.isActive) return
        lastRequest = Triple(target, apps, files)
        _state.value = SendState.Preparing(target)
        startService(context)
        job = scope.launch {
            try {
                val outgoing = withContext(Dispatchers.IO) {
                    OutgoingBuilder.build(settings.deviceName.value, apps, files, context.contentResolver, icons::pngBytes)
                }
                val manifest = outgoing.manifest
                val result = TransferSender.send(target.host, target.port, manifest, outgoing.source) { status ->
                    _state.value = when (status) {
                        SendStatus.Connecting -> SendState.Connecting(target, manifest)
                        SendStatus.WaitingForAnswer -> SendState.WaitingForAnswer(target, manifest)
                        is SendStatus.Transferring ->
                            SendState.Transferring(target, status.receiverName, manifest, status.progress)
                    }
                }
                _state.value = SendState.Finished(target, result.receiverName, manifest, result.stats)
            } catch (e: CancellationException) {
                _state.value = SendState.Cancelled(target)
                throw e
            } catch (e: TransferException) {
                _state.value = SendState.Failed(target, e.reason, e.message)
            } catch (e: Exception) {
                _state.value = SendState.Failed(target, TransferException.Reason.SOURCE_ERROR, e.message)
            }
        }
    }

    /** Sends the same selection to the same phone again. */
    fun retry() {
        val (target, apps, files) = lastRequest ?: return
        send(target, apps, files)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Forgets a finished, failed or cancelled transfer. */
    fun reset() {
        if (!_state.value.isActive) _state.value = SendState.Idle
    }
}
