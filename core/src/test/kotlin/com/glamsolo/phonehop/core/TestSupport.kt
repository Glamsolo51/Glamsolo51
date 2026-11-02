package com.glamsolo.phonehop.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Keeps received parts in memory, recording commits and discards. */
class MemorySink(private val failOnItemId: Int? = null) : ReceiveSink {
    val committed = Collections.synchronizedMap(LinkedHashMap<Pair<Int, Int>, ByteArray>())
    val discarded = CopyOnWriteArrayList<Pair<Int, Int>>()
    val receivedItems = CopyOnWriteArrayList<Int>()

    override fun openPart(item: ManifestItem, partIndex: Int): PartWriter {
        if (item.id == failOnItemId) throw java.io.IOException("No space left on device")
        val buffer = ByteArrayOutputStream()
        return object : PartWriter {
            override val output: OutputStream = buffer
            override fun commit() {
                committed[item.id to partIndex] = buffer.toByteArray()
            }

            override fun discard() {
                discarded += item.id to partIndex
            }
        }
    }

    override fun onItemReceived(item: ManifestItem) {
        receivedItems += item.id
    }
}

/** Records every receiver callback; the decision is pluggable. */
class RecordingHandler(
    private val sink: MemorySink = MemorySink(),
    private val decide: suspend (IncomingRequest) -> Boolean = { true },
) : ReceiveHandler {
    val requests = CopyOnWriteArrayList<IncomingRequest>()
    val progress = CopyOnWriteArrayList<TransferProgress>()
    val completed = CompletableDeferred<TransferStats>()
    val failed = CompletableDeferred<TransferException>()
    val cancelled = CompletableDeferred<Unit>()
    val withdrawn = CompletableDeferred<IncomingRequest>()
    val firstProgress = CompletableDeferred<Unit>()
    val sinkRef get() = sink

    override suspend fun onRequest(request: IncomingRequest): Boolean {
        requests += request
        return decide(request)
    }

    override fun onRequestWithdrawn(request: IncomingRequest) {
        withdrawn.complete(request)
    }

    override fun createSink(request: IncomingRequest): ReceiveSink = sink
    override fun onProgress(request: IncomingRequest, progress: TransferProgress) {
        this.progress += progress
        if (progress.bytesDone > 0) firstProgress.complete(Unit)
    }

    override fun onCompleted(request: IncomingRequest, stats: TransferStats) {
        completed.complete(stats)
    }

    override fun onFailed(request: IncomingRequest, error: TransferException) {
        failed.complete(error)
    }

    override fun onCancelled(request: IncomingRequest) {
        cancelled.complete(Unit)
    }
}

class RunningServer(val server: TransferServer, val port: Int, val job: Job)

suspend fun CoroutineScope.startServer(handler: ReceiveHandler, name: String = "Pixel Receiver"): RunningServer {
    val server = TransferServer({ name }, handler)
    val port = CompletableDeferred<Int>()
    val job = launch { server.run(port = 0) { port.complete(it) } }
    return RunningServer(server, port.await(), job)
}

/** A manifest plus the bytes behind it. */
class Payload(val manifest: TransferManifest, val data: Map<Pair<Int, Int>, ByteArray>) {
    val source = PartSource { item, part -> data.getValue(item.id to part).inputStream() }
}

fun randomBytes(size: Int, seed: Int = size): ByteArray = Random(seed).nextBytes(size)

fun samplePayload(): Payload {
    val photo = randomBytes(3 * 1024 * 1024 + 17)
    val empty = ByteArray(0)
    val doc = "Привет, PhoneHop! ✨".toByteArray()
    val base = randomBytes(2 * 1024 * 1024, seed = 1)
    val splitAbi = randomBytes(700 * 1024, seed = 2)
    val splitLang = randomBytes(1234, seed = 3)
    val items = listOf(
        ManifestItem(1, ItemKind.FILE, "IMG_0001.jpg", "Camera/IMG_0001.jpg", "image/jpeg", parts = listOf(PartInfo("IMG_0001.jpg", photo.size.toLong()))),
        ManifestItem(2, ItemKind.FILE, "пустой.txt", "пустой.txt", "text/plain", parts = listOf(PartInfo("пустой.txt", 0))),
        ManifestItem(3, ItemKind.FILE, "Заметка.md", "Docs/Заметка.md", "text/markdown", parts = listOf(PartInfo("Заметка.md", doc.size.toLong()))),
        ManifestItem(
            4, ItemKind.APP, "Weather", "com.example.weather", versionName = "2.4.1", versionCode = 241,
            icon = byteArrayOf(1, 2, 3),
            parts = listOf(
                PartInfo("base.apk", base.size.toLong()),
                PartInfo("split_config.arm64_v8a.apk", splitAbi.size.toLong()),
                PartInfo("split_config.ru.apk", splitLang.size.toLong()),
            ),
        ),
    )
    val data = mapOf(
        (1 to 0) to photo, (2 to 0) to empty, (3 to 0) to doc,
        (4 to 0) to base, (4 to 1) to splitAbi, (4 to 2) to splitLang,
    )
    return Payload(TransferManifest("Old Phone", items), data)
}
