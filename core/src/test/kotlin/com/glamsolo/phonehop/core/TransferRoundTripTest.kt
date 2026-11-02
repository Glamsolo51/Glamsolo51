package com.glamsolo.phonehop.core

import com.glamsolo.phonehop.core.TransferException.Reason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransferRoundTripTest {

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(60_000) { block() }
    }

    @Test
    fun `files and a split app arrive byte for byte`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler, name = "New Phone")
        val payload = samplePayload()
        val statuses = mutableListOf<SendStatus>()

        val result = TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source) { statuses += it }

        assertEquals("New Phone", result.receiverName)
        assertEquals(payload.manifest.totalBytes, result.stats.bytes)
        assertEquals(4, result.stats.items)
        val stats = handler.completed.await()
        assertEquals(payload.manifest.totalBytes, stats.bytes)

        val sink = handler.sinkRef
        assertEquals(payload.data.keys, sink.committed.keys)
        for ((key, bytes) in payload.data) assertContentEquals(bytes, sink.committed[key], "part $key")
        assertEquals(listOf(1, 2, 3, 4), sink.receivedItems.toList())
        assertTrue(sink.discarded.isEmpty())

        // The receiver saw exactly what the sender announced, icon included.
        val announced = handler.requests.single().manifest
        assertEquals(payload.manifest, announced)
        assertEquals("Old Phone", announced.senderName)

        // Progress ends at 100% on both sides.
        assertEquals(1f, handler.progress.last().fraction)
        val lastSent = statuses.filterIsInstance<SendStatus.Transferring>().last().progress
        assertEquals(1f, lastSent.fraction)
        assertEquals(4, lastSent.itemsDone)
        assertTrue(statuses.first() is SendStatus.Connecting)
        assertTrue(statuses.contains(SendStatus.WaitingForAnswer))
        running.job.cancelAndJoin()
    }

    @Test
    fun `large file keeps integrity`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val big = randomBytes(48 * 1024 * 1024, seed = 42)
        val manifest = TransferManifest(
            "Sender",
            listOf(ManifestItem(1, ItemKind.FILE, "video.mp4", "video.mp4", "video/mp4", parts = listOf(PartInfo("video.mp4", big.size.toLong())))),
        )
        val started = System.nanoTime()
        TransferSender.send("127.0.0.1", running.port, manifest, { _, _ -> big.inputStream() })
        val seconds = (System.nanoTime() - started) / 1e9
        assertContentEquals(big, handler.sinkRef.committed[1 to 0])
        println("48 MiB over loopback in %.2f s (%.0f MiB/s)".format(seconds, 48 / seconds))
        running.job.cancelAndJoin()
    }

    @Test
    fun `unknown or wrong announced sizes still transfer everything`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val a = randomBytes(1_000_000, 5)
        val b = randomBytes(10_000, 6)
        val manifest = TransferManifest(
            "Sender",
            listOf(
                ManifestItem(1, ItemKind.FILE, "unknown.bin", "unknown.bin", parts = listOf(PartInfo("unknown.bin", -1))),
                ManifestItem(2, ItemKind.FILE, "grew.bin", "grew.bin", parts = listOf(PartInfo("grew.bin", 10))),
            ),
        )
        val result = TransferSender.send("127.0.0.1", running.port, manifest, { item, _ -> if (item.id == 1) a.inputStream() else b.inputStream() })
        assertEquals((a.size + b.size).toLong(), result.stats.bytes)
        assertContentEquals(a, handler.sinkRef.committed[1 to 0])
        assertContentEquals(b, handler.sinkRef.committed[2 to 0])
        assertEquals(1f, handler.progress.last().fraction)
        running.job.cancelAndJoin()
    }

    @Test
    fun `declined request is reported to the sender`() = test {
        val handler = RecordingHandler(decide = { false })
        val running = startServer(handler)
        val payload = samplePayload()
        val error = assertFailsWith<TransferException> {
            TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source)
        }
        assertEquals(Reason.DECLINED, error.reason)
        assertTrue(handler.sinkRef.committed.isEmpty())
        running.job.cancelAndJoin()
    }

    @Test
    fun `second sender gets busy while the first waits for an answer`() = test {
        val gate = CompletableDeferred<Boolean>()
        val handler = RecordingHandler(decide = { gate.await() })
        val running = startServer(handler)
        val payload = samplePayload()

        val first = async { TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source) }
        while (handler.requests.isEmpty()) delay(10)
        assertTrue(running.server.isBusy)

        val error = assertFailsWith<TransferException> {
            TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source)
        }
        assertEquals(Reason.BUSY, error.reason)

        gate.complete(true)
        first.await()
        handler.completed.await()
        assertTrue(!running.server.isBusy)
        running.job.cancelAndJoin()
    }

    @Test
    fun `sender withdrawing before the answer dismisses the request`() = test {
        val handler = RecordingHandler(decide = { CompletableDeferred<Boolean>().await() })
        val running = startServer(handler)
        val payload = samplePayload()
        val sending = async { TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source) }
        while (handler.requests.isEmpty()) delay(10)
        sending.cancelAndJoin()
        handler.withdrawn.await()
        // The server is free again.
        while (running.server.isBusy) delay(10)
        running.job.cancelAndJoin()
    }

    @Test
    fun `sender cancelling mid transfer is reported as a cancel and partial data is discarded`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val manifest = TransferManifest(
            "Sender",
            listOf(ManifestItem(1, ItemKind.FILE, "endless.bin", "endless.bin", parts = listOf(PartInfo("endless.bin", Long.MAX_VALUE / 4)))),
        )
        val sending = async { TransferSender.send("127.0.0.1", running.port, manifest, { _, _ -> EndlessStream() }) }
        handler.firstProgress.await()
        sending.cancelAndJoin()
        val error = handler.failed.await()
        assertEquals(Reason.PEER_CANCELLED, error.reason)
        assertEquals(listOf(1 to 0), handler.sinkRef.discarded.toList())
        assertTrue(handler.sinkRef.committed.isEmpty())
        running.job.cancelAndJoin()
    }

    @Test
    fun `receiver cancelling mid transfer is reported to the sender`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val manifest = TransferManifest(
            "Sender",
            listOf(ManifestItem(1, ItemKind.FILE, "endless.bin", "endless.bin", parts = listOf(PartInfo("endless.bin", -1)))),
        )
        val sending = async {
            assertFailsWith<TransferException> {
                TransferSender.send("127.0.0.1", running.port, manifest, { _, _ -> EndlessStream() })
            }
        }
        handler.firstProgress.await()
        running.server.cancelActiveSession()
        assertEquals(Reason.PEER_CANCELLED, sending.await().reason)
        handler.cancelled.await()
        assertEquals(listOf(1 to 0), handler.sinkRef.discarded.toList())
        running.job.cancelAndJoin()
    }

    @Test
    fun `storage failure on the receiver is explained to the sender`() = test {
        val handler = RecordingHandler(sink = MemorySink(failOnItemId = 3))
        val running = startServer(handler)
        val payload = samplePayload()
        val error = assertFailsWith<TransferException> {
            TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source)
        }
        assertEquals(Reason.REMOTE_ERROR, error.reason)
        assertEquals("STORAGE_ERROR", error.message)
        assertEquals(Reason.STORAGE_ERROR, handler.failed.await().reason)
        // Items before the failure were kept.
        assertEquals(listOf(1, 2), handler.sinkRef.receivedItems.toList())
        running.job.cancelAndJoin()
    }

    @Test
    fun `unreadable source aborts cleanly on both sides`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val payload = samplePayload()
        val error = assertFailsWith<TransferException> {
            TransferSender.send("127.0.0.1", running.port, payload.manifest, { item, part ->
                if (item.id == 3) throw IOException("Permission denied") else payload.source.open(item, part)
            })
        }
        assertEquals(Reason.SOURCE_ERROR, error.reason)
        assertEquals(Reason.PEER_CANCELLED, handler.failed.await().reason)
        running.job.cancelAndJoin()
    }

    @Test
    fun `nobody listening means connection failed`() = test {
        val port = ServerSocket(0).use { it.localPort }
        val error = assertFailsWith<TransferException> {
            TransferSender.send("127.0.0.1", port, samplePayload().manifest, samplePayload().source)
        }
        assertEquals(Reason.CONNECTION_FAILED, error.reason)
    }

    @Test
    fun `garbage connections do not take the server down`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        Socket("127.0.0.1", running.port).use { it.getOutputStream().write(randomBytes(5000)) }
        Socket("127.0.0.1", running.port).use { it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) }
        Socket("127.0.0.1", running.port).close()
        val payload = samplePayload()
        TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source)
        handler.completed.await()
        assertEquals(1, handler.requests.size)
        running.job.cancelAndJoin()
    }

    @Test
    fun `corrupted data is detected by the checksum`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val manifest = TransferManifest(
            "Evil",
            listOf(ManifestItem(1, ItemKind.FILE, "a.bin", "a.bin", parts = listOf(PartInfo("a.bin", 4)))),
        )
        Socket("127.0.0.1", running.port).use { socket ->
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(socket.getInputStream())
            WireFormat.writeHello(out, manifest)
            out.flush()
            WireFormat.readPreamble(input)
            assertEquals(Protocol.ANSWER_ACCEPT, input.readUnsignedByte())
            input.readUTF()
            val data = byteArrayOf(1, 2, 3, 4)
            out.writeByte(Protocol.FRAME_PART); out.writeInt(1); out.writeInt(0)
            out.writeInt(4); out.write(data); out.writeInt(0)
            out.writeLong(CRC32().apply { update(byteArrayOf(9, 9, 9, 9)) }.value)
            out.flush()
            assertEquals(Protocol.RESULT_FAILED, input.readUnsignedByte())
            assertEquals("CHECKSUM_MISMATCH", input.readUTF())
        }
        assertEquals(Reason.CHECKSUM_MISMATCH, handler.failed.await().reason)
        assertTrue(handler.sinkRef.committed.isEmpty())
        assertEquals(listOf(1 to 0), handler.sinkRef.discarded.toList())
        running.job.cancelAndJoin()
    }

    @Test
    fun `newer protocol version gets an incompatible answer`() = test {
        val running = startServer(RecordingHandler())
        Socket("127.0.0.1", running.port).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.writeInt(Protocol.MAGIC); out.writeInt(Protocol.VERSION + 1); out.flush()
            WireFormat.readPreamble(input)
            assertEquals(Protocol.ANSWER_INCOMPATIBLE, input.readUnsignedByte())
        }
        running.job.cancelAndJoin()
    }

    @Test
    fun `server keeps serving sequential transfers`() = test {
        val handler = RecordingHandler()
        val running = startServer(handler)
        val payload = samplePayload()
        repeat(3) { TransferSender.send("127.0.0.1", running.port, payload.manifest, payload.source) }
        assertEquals(3, handler.requests.size)
        running.job.cancelAndJoin()
    }

    /** Produces data forever, a bit slowly, so the test can cancel in the middle. */
    private class EndlessStream : InputStream() {
        override fun read(): Int = 7
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(2)
            val n = minOf(len, 32 * 1024)
            b.fill(7, off, off + n)
            return n
        }
    }
}
