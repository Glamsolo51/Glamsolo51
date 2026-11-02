package com.glamsolo.phonehop.core

import com.glamsolo.phonehop.core.TransferException.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException

/** Provides the bytes of the items being sent. */
fun interface PartSource {
    /** Opens one part of [item] for reading. The stream is closed by the sender. */
    fun open(item: ManifestItem, partIndex: Int): InputStream
}

sealed interface SendStatus {
    data object Connecting : SendStatus
    data object WaitingForAnswer : SendStatus
    data class Transferring(val receiverName: String, val progress: TransferProgress) : SendStatus
}

data class SendResult(val receiverName: String, val stats: TransferStats)

/** The sending side of a transfer: connects to a receiver and streams the manifest items. */
object TransferSender {

    /**
     * Sends [manifest] to the receiver at [host]:[port]. Suspends until the transfer is
     * complete. Cancelling the calling coroutine aborts the transfer and notifies the receiver.
     *
     * @throws TransferException with a [Reason] describing what went wrong.
     */
    suspend fun send(
        host: String,
        port: Int,
        manifest: TransferManifest,
        source: PartSource,
        onStatus: (SendStatus) -> Unit = {},
    ): SendResult = withContext(Dispatchers.IO) {
        onStatus(SendStatus.Connecting)
        val socket = Socket()
        socket.closeOnCancel(graceMillis = 1_500) {
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = Protocol.SOCKET_BUFFER
                socket.receiveBufferSize = Protocol.SOCKET_BUFFER
                socket.connect(InetSocketAddress(host, port), Protocol.CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw TransferException(Reason.CONNECTION_FAILED, e.message, e)
            }
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 16 * 1024))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            try {
                val receiverName = handshake(socket, input, out, manifest, onStatus)
                socket.soTimeout = Protocol.IO_TIMEOUT_MS
                val stats = streamItems(socket, input, out, manifest, source) {
                    onStatus(SendStatus.Transferring(receiverName, it))
                }
                SendResult(receiverName, stats)
            } catch (e: TransferException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                // The receiver may have explained why it closed the connection.
                throw readEarlyResult(socket, input) ?: TransferException(Reason.CONNECTION_LOST, e.message, e)
            }
        }
    }

    private fun handshake(
        socket: Socket,
        input: DataInputStream,
        out: DataOutputStream,
        manifest: TransferManifest,
        onStatus: (SendStatus) -> Unit,
    ): String {
        WireFormat.writeHello(out, manifest)
        out.flush()
        onStatus(SendStatus.WaitingForAnswer)
        // The receiver's user needs time to look at the request.
        socket.soTimeout = (Protocol.DECISION_TIMEOUT_MS + 15_000).toInt()
        try {
            WireFormat.readPreamble(input)
            val answer = input.readUnsignedByte()
            val receiverName = input.readUTF()
            when (answer) {
                Protocol.ANSWER_ACCEPT -> return receiverName
                Protocol.ANSWER_DECLINE -> throw TransferException(Reason.DECLINED)
                Protocol.ANSWER_BUSY -> throw TransferException(Reason.BUSY)
                Protocol.ANSWER_INCOMPATIBLE -> throw TransferException(Reason.INCOMPATIBLE)
                else -> WireFormat.protocolError("Unknown answer $answer")
            }
        } catch (e: SocketTimeoutException) {
            throw TransferException(Reason.TIMEOUT, e.message, e)
        } catch (e: EOFException) {
            throw TransferException(Reason.CONNECTION_LOST, "Receiver closed the connection", e)
        }
    }

    private suspend fun streamItems(
        socket: Socket,
        input: DataInputStream,
        out: DataOutputStream,
        manifest: TransferManifest,
        source: PartSource,
        onProgress: (TransferProgress) -> Unit,
    ): TransferStats {
        val tracker = ProgressTracker(manifest, listener = onProgress)
        val buffer = ByteArray(Protocol.CHUNK_SIZE)
        var insidePart = false
        try {
            for ((index, item) in manifest.items.withIndex()) {
                tracker.startItem(index)
                for ((partIndex, part) in item.parts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val stream = try {
                        source.open(item, partIndex)
                    } catch (e: Exception) {
                        throw TransferException(Reason.SOURCE_ERROR, "${item.title}: ${e.message}", e)
                    }
                    stream.use { ins ->
                        out.writeByte(Protocol.FRAME_PART)
                        out.writeInt(item.id)
                        out.writeInt(partIndex)
                        insidePart = true
                        val crc = CRC32()
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = try {
                                ins.readUpTo(buffer)
                            } catch (e: IOException) {
                                throw TransferException(Reason.SOURCE_ERROR, "${item.title}: ${e.message}", e)
                            }
                            if (n <= 0) break
                            out.writeInt(n)
                            out.write(buffer, 0, n)
                            crc.update(buffer, 0, n)
                            total += n
                            tracker.addBytes(n.toLong())
                            checkForEarlyResult(socket, input)
                        }
                        out.writeInt(0)
                        out.writeLong(crc.value)
                        insidePart = false
                        tracker.partFinished(part.size, total)
                    }
                }
                tracker.finishItem()
            }
            out.writeByte(Protocol.FRAME_END)
            out.flush()
        } catch (e: Exception) {
            if (e is CancellationException || (e is TransferException && e.reason == Reason.SOURCE_ERROR)) {
                // Tell the receiver this is a deliberate stop, not a network failure.
                try {
                    if (insidePart) out.writeInt(Protocol.CHUNK_CANCEL) else out.writeByte(Protocol.FRAME_CANCEL)
                    out.flush()
                } catch (_: IOException) {
                }
            }
            throw e
        }

        val result = input.readUnsignedByte()
        val message = input.readUTF()
        return when (result) {
            Protocol.RESULT_OK -> tracker.complete()
            Protocol.RESULT_CANCELLED -> throw TransferException(Reason.PEER_CANCELLED, message)
            Protocol.RESULT_FAILED -> throw TransferException(Reason.REMOTE_ERROR, message)
            else -> WireFormat.protocolError("Unknown result $result")
        }
    }

    /** The receiver only talks during the data phase when it wants to stop the transfer. */
    private fun checkForEarlyResult(socket: Socket, input: DataInputStream) {
        if (input.available() > 0) {
            throw readEarlyResult(socket, input)
                ?: WireFormat.protocolError("Unexpected data from receiver")
        }
    }

    private fun readEarlyResult(socket: Socket, input: DataInputStream): TransferException? = try {
        socket.soTimeout = 1_000
        when (val result = input.read()) {
            Protocol.RESULT_CANCELLED -> TransferException(Reason.PEER_CANCELLED, input.readUTF())
            Protocol.RESULT_FAILED -> TransferException(Reason.REMOTE_ERROR, input.readUTF())
            -1 -> null
            else -> TransferException(Reason.PROTOCOL_ERROR, "Unexpected result $result")
        }
    } catch (_: IOException) {
        null
    }
}
