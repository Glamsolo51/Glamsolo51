package com.glamsolo.phonehop.transfer

import android.net.Uri
import com.glamsolo.phonehop.core.IncomingRequest
import com.glamsolo.phonehop.core.TransferException
import com.glamsolo.phonehop.core.TransferManifest
import com.glamsolo.phonehop.core.TransferProgress
import com.glamsolo.phonehop.core.TransferStats
import java.io.File

/** A phone that can receive, found on the network or typed in by hand. */
data class PeerDevice(val name: String, val host: String, val port: Int) {
    val key: String get() = "$host:$port"
}

/** A file that was saved on this phone. */
data class SavedFile(
    val uri: Uri,
    val displayName: String,
    /** Human readable folder, e.g. "Download/PhoneHop". */
    val folder: String,
    val mimeType: String?,
    val size: Long,
)

sealed interface ReceivedItem {
    val title: String
}

data class ReceivedFile(val file: SavedFile) : ReceivedItem {
    override val title: String get() = file.displayName
}

data class ReceivedApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val icon: ByteArray?,
    val apkFiles: List<File>,
    /** Size at the time of receiving (the APKs are deleted after installation). */
    val sizeBytes: Long = apkFiles.sumOf { it.length() },
) : ReceivedItem {
    override val title: String get() = label

    override fun equals(other: Any?): Boolean =
        other is ReceivedApp && other.packageName == packageName && other.apkFiles == apkFiles

    override fun hashCode(): Int = packageName.hashCode() * 31 + apkFiles.hashCode()
}

sealed interface SendState {
    val target: PeerDevice?

    data object Idle : SendState {
        override val target: PeerDevice? = null
    }

    data class Preparing(override val target: PeerDevice) : SendState
    data class Connecting(override val target: PeerDevice, val manifest: TransferManifest) : SendState
    data class WaitingForAnswer(override val target: PeerDevice, val manifest: TransferManifest) : SendState
    data class Transferring(
        override val target: PeerDevice,
        val receiverName: String,
        val manifest: TransferManifest,
        val progress: TransferProgress,
    ) : SendState

    data class Finished(
        override val target: PeerDevice,
        val receiverName: String,
        val manifest: TransferManifest,
        val stats: TransferStats,
    ) : SendState

    data class Failed(
        override val target: PeerDevice,
        val reason: TransferException.Reason,
        val detail: String?,
    ) : SendState

    data class Cancelled(override val target: PeerDevice) : SendState

    val isActive: Boolean
        get() = this is Preparing || this is Connecting || this is WaitingForAnswer || this is Transferring
}

sealed interface ReceiveState {
    data object Idle : ReceiveState
    data object Starting : ReceiveState
    data class Listening(val port: Int) : ReceiveState
    data class Incoming(val port: Int, val request: IncomingRequest) : ReceiveState
    data class Transferring(val port: Int, val request: IncomingRequest, val progress: TransferProgress) : ReceiveState
    data class Finished(val port: Int, val request: IncomingRequest, val stats: TransferStats) : ReceiveState
    data class Failed(
        val port: Int,
        val request: IncomingRequest,
        val reason: TransferException.Reason,
        val detail: String?,
    ) : ReceiveState

    data class Cancelled(val port: Int, val request: IncomingRequest) : ReceiveState

    /** The server could not start (e.g. no permission to open a socket). */
    data class Error(val message: String?) : ReceiveState

    /** True while the server is up and other phones can connect. */
    val isListening: Boolean
        get() = this !is Idle && this !is Starting && this !is Error

    val isTransferring: Boolean get() = this is Transferring
}

/** One-off notices for the UI. */
sealed interface ReceiveEvent {
    data class RequestWithdrawn(val senderName: String) : ReceiveEvent
}
