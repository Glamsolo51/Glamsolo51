package com.glamsolo.phonehop.core

import com.glamsolo.phonehop.core.TransferException.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32

/** Where received data goes. Implemented by the platform (MediaStore, app storage, test folders…). */
interface ReceiveSink {
    /** Opens a destination for one part of [item]. */
    fun openPart(item: ManifestItem, partIndex: Int): PartWriter

    /** All parts of [item] were received, verified and committed. */
    fun onItemReceived(item: ManifestItem) {}
}

/** Destination of a single part. The receiver closes [output] before calling [commit]. */
interface PartWriter {
    val output: OutputStream

    /** The data is complete and verified: make it visible. */
    fun commit()

    /** The transfer of this part failed: remove whatever was written. */
    fun discard()
}

data class IncomingRequest(
    val id: Long,
    val peerAddress: String,
    val manifest: TransferManifest,
)

/** Callbacks of the receiving side. Called from background threads. */
interface ReceiveHandler {
    /** Ask the user whether to accept. May suspend until they decide; `true` accepts. */
    suspend fun onRequest(request: IncomingRequest): Boolean

    /** The sender went away before the user decided. */
    fun onRequestWithdrawn(request: IncomingRequest) {}

    fun createSink(request: IncomingRequest): ReceiveSink
    fun onProgress(request: IncomingRequest, progress: TransferProgress) {}
    fun onCompleted(request: IncomingRequest, stats: TransferStats) {}
    fun onFailed(request: IncomingRequest, error: TransferException) {}

    /** The local user cancelled the running transfer. */
    fun onCancelled(request: IncomingRequest) {}
}

/**
 * The receiving side: a small TCP server that accepts one transfer at a time.
 * Run it with [run]; cancel the coroutine to stop listening.
 */
class TransferServer(
    private val receiverName: () -> String,
    private val handler: ReceiveHandler,
) {
    private val nextRequestId = AtomicLong(1)
    private val activeSession = AtomicReference<Job?>(null)

    /** True while a request is being decided or a transfer is running. */
    val isBusy: Boolean get() = activeSession.get() != null

    /**
     * Binds to [port] (or any free port if it is taken), reports the real port through
     * [onListening] and serves connections until cancelled.
     */
    suspend fun run(port: Int = Protocol.DEFAULT_PORT, onListening: (Int) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            val server = bind(port)
            server.closeOnCancel {
                onListening(server.localPort)
                coroutineScope {
                    while (isActive) {
                        val socket = try {
                            server.accept()
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw e
                        }
                        launch {
                            try {
                                handleConnection(socket)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // One broken connection must never take the server down.
                            }
                        }
                    }
                }
            }
        }
    }

    /** Stops the transfer that is currently running (or the pending request). */
    fun cancelActiveSession() {
        activeSession.get()?.cancel(CancellationException("Cancelled by the receiver"))
    }

    private fun bind(port: Int): ServerSocket {
        fun open(p: Int) = ServerSocket().apply {
            reuseAddress = true
            receiveBufferSize = Protocol.SOCKET_BUFFER
            bind(InetSocketAddress(p))
        }
        return try {
            open(port)
        } catch (_: BindException) {
            open(0)
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.closeOnCancel(graceMillis = 1_000) {
            socket.tcpNoDelay = true
            socket.sendBufferSize = Protocol.SOCKET_BUFFER
            socket.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))

            val manifest = try {
                val version = WireFormat.readPreamble(input)
                if (version != Protocol.VERSION) {
                    WireFormat.writeAnswer(out, Protocol.ANSWER_INCOMPATIBLE, receiverName())
                    return@closeOnCancel
                }
                WireFormat.readManifestBody(input)
            } catch (_: IOException) {
                // Port scanners, garbage or a peer that vanished: nothing to tell the user.
                return@closeOnCancel
            }

            val sessionJob = currentCoroutineContext()[Job]
            if (!activeSession.compareAndSet(null, sessionJob)) {
                runCatching { WireFormat.writeAnswer(out, Protocol.ANSWER_BUSY, receiverName()) }
                return@closeOnCancel
            }
            val request = IncomingRequest(
                id = nextRequestId.getAndIncrement(),
                peerAddress = socket.inetAddress?.hostAddress.orEmpty(),
                manifest = manifest,
            )
            try {
                serveSession(socket, input, out, request)
            } finally {
                activeSession.compareAndSet(sessionJob, null)
            }
        }
    }

    private suspend fun serveSession(
        socket: Socket,
        input: DataInputStream,
        out: DataOutputStream,
        request: IncomingRequest,
    ) {
        val accepted = awaitDecision(socket, input, request) ?: return
        try {
            if (!accepted) {
                WireFormat.writeAnswer(out, Protocol.ANSWER_DECLINE, receiverName())
                return
            }
            WireFormat.writeAnswer(out, Protocol.ANSWER_ACCEPT, receiverName())
        } catch (_: IOException) {
            return
        }

        socket.soTimeout = Protocol.IO_TIMEOUT_MS
        try {
            val stats = receiveItems(input, request)
            WireFormat.writeResult(out, Protocol.RESULT_OK)
            handler.onCompleted(request, stats)
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                // Our own user pressed "cancel" (or left the screen): tell the sender why.
                runCatching {
                    WireFormat.writeResult(out, Protocol.RESULT_CANCELLED, "Cancelled by the receiver")
                    socket.drainBriefly(input, 500)
                }
                handler.onCancelled(request)
                throw e as? CancellationException ?: CancellationException("Cancelled").apply { initCause(e) }
            }
            val error = when (e) {
                is TransferException -> e
                is SocketTimeoutException, is EOFException, is SocketException ->
                    TransferException(Reason.CONNECTION_LOST, e.message, e)
                else -> TransferException(Reason.STORAGE_ERROR, e.message, e)
            }
            if (error.reason in REPORTABLE) {
                runCatching {
                    WireFormat.writeResult(out, Protocol.RESULT_FAILED, error.reason.name)
                    socket.drainBriefly(input)
                }
            }
            handler.onFailed(request, error)
        }
    }

    /**
     * Asks the user while watching the socket: if the sender disconnects in the meantime,
     * the request is withdrawn. Returns null when there is nobody left to answer.
     */
    private suspend fun awaitDecision(
        socket: Socket,
        input: DataInputStream,
        request: IncomingRequest,
    ): Boolean? = coroutineScope {
        val decision = async {
            withTimeoutOrNull(Protocol.DECISION_TIMEOUT_MS) { handler.onRequest(request) } ?: false
        }
        val watcher = launch(Dispatchers.IO) {
            socket.soTimeout = 300
            while (isActive && !decision.isCompleted) {
                try {
                    // The sender must stay silent until it gets an answer.
                    input.read()
                    decision.cancel()
                    return@launch
                } catch (_: SocketTimeoutException) {
                } catch (_: IOException) {
                    decision.cancel()
                    return@launch
                }
            }
        }
        val result = try {
            decision.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
        watcher.cancelAndJoin()
        if (result == null) handler.onRequestWithdrawn(request)
        result
    }

    private suspend fun receiveItems(input: DataInputStream, request: IncomingRequest): TransferStats {
        val manifest = request.manifest
        val sink = try {
            handler.createSink(request)
        } catch (e: Exception) {
            throw TransferException(Reason.STORAGE_ERROR, e.message, e)
        }
        val tracker = ProgressTracker(manifest) { handler.onProgress(request, it) }
        val buffer = ByteArray(Protocol.CHUNK_SIZE)

        for ((index, item) in manifest.items.withIndex()) {
            tracker.startItem(index)
            for ((partIndex, part) in item.parts.withIndex()) {
                when (input.readByteOrEof()) {
                    Protocol.FRAME_PART -> Unit
                    Protocol.FRAME_CANCEL -> throw TransferException(Reason.PEER_CANCELLED)
                    else -> WireFormat.protocolError("Expected a part frame")
                }
                val itemId = input.readInt()
                val receivedPartIndex = input.readInt()
                if (itemId != item.id || receivedPartIndex != partIndex) {
                    WireFormat.protocolError("Unexpected part $itemId/$receivedPartIndex")
                }
                val size = receivePart(input, sink, item, partIndex, buffer, tracker)
                tracker.partFinished(part.size, size)
            }
            try {
                sink.onItemReceived(item)
            } catch (e: Exception) {
                throw TransferException(Reason.STORAGE_ERROR, e.message, e)
            }
            tracker.finishItem()
        }
        when (input.readByteOrEof()) {
            Protocol.FRAME_END -> Unit
            Protocol.FRAME_CANCEL -> throw TransferException(Reason.PEER_CANCELLED)
            else -> WireFormat.protocolError("Expected the end frame")
        }
        return tracker.complete()
    }

    private suspend fun receivePart(
        input: DataInputStream,
        sink: ReceiveSink,
        item: ManifestItem,
        partIndex: Int,
        buffer: ByteArray,
        tracker: ProgressTracker,
    ): Long {
        val writer = try {
            sink.openPart(item, partIndex)
        } catch (e: Exception) {
            throw TransferException(Reason.STORAGE_ERROR, e.message, e)
        }
        var committed = false
        try {
            val crc = CRC32()
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val length = input.readInt()
                if (length == 0) break
                if (length == Protocol.CHUNK_CANCEL) throw TransferException(Reason.PEER_CANCELLED)
                if (length < 0 || length > Protocol.MAX_CHUNK_SIZE) WireFormat.protocolError("Bad chunk $length")
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(remaining, buffer.size))
                    if (n < 0) throw EOFException()
                    crc.update(buffer, 0, n)
                    try {
                        writer.output.write(buffer, 0, n)
                    } catch (e: IOException) {
                        throw TransferException(Reason.STORAGE_ERROR, e.message, e)
                    }
                    remaining -= n
                    total += n
                    tracker.addBytes(n.toLong())
                }
            }
            val expectedCrc = input.readLong()
            if (expectedCrc != crc.value) {
                throw TransferException(Reason.CHECKSUM_MISMATCH, "${item.title}: data corrupted")
            }
            try {
                writer.output.close()
                writer.commit()
            } catch (e: Exception) {
                throw TransferException(Reason.STORAGE_ERROR, e.message, e)
            }
            committed = true
            return total
        } finally {
            if (!committed) {
                runCatching { writer.output.close() }
                runCatching { writer.discard() }
            }
        }
    }

    private companion object {
        /** Failures that are worth explaining to the sender. */
        val REPORTABLE = setOf(Reason.STORAGE_ERROR, Reason.CHECKSUM_MISMATCH, Reason.PROTOCOL_ERROR)
    }
}
