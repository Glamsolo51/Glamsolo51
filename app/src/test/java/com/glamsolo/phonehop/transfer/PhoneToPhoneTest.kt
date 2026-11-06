package com.glamsolo.phonehop.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glamsolo.phonehop.FakeDiscovery
import com.glamsolo.phonehop.TestFilesProvider
import com.glamsolo.phonehop.core.TransferException
import com.glamsolo.phonehop.data.AppIconLoader
import com.glamsolo.phonehop.data.DeviceSettings
import com.glamsolo.phonehop.data.FileResolver
import com.glamsolo.phonehop.data.InstalledAppsRepository
import com.glamsolo.phonehop.installFakeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Two "phones" in one process: the real SendController talks to the real
 * ReceiveController over TCP, exactly as two devices on a Wi-Fi network would.
 */
@RunWith(AndroidJUnit4::class)
class PhoneToPhoneTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var storage: File
    private lateinit var apkDir: File
    private lateinit var discovery: FakeDiscovery
    private lateinit var receiver: ReceiveController
    private lateinit var sender: SendController

    /** Open by default; a test can close it to hold the receiver's disk writes. */
    private val writeGate = WriteGate()

    @Before
    fun setUp() {
        TestFilesProvider.install()
        storage = tmp.newFolder("storage")
        apkDir = tmp.newFolder("apks")
        discovery = FakeDiscovery()
        val receiverSettings = DeviceSettings(context).apply { rename("New Phone") }
        val senderSettings = DeviceSettings(context).apply { rename("Old Phone") }
        receiver = ReceiveController(
            context = context,
            scope = scope,
            settings = receiverSettings,
            discovery = discovery,
            fileStoreFactory = { HeldFileStore(DirectoryFileStore(context, { File(storage, it) }, scan = false), writeGate) },
            apkBaseDir = apkDir,
            port = 0,
            startService = {},
            notifier = {},
        )
        sender = SendController(context, scope, senderSettings, AppIconLoader(context), startService = {})
    }

    @After
    fun tearDown() {
        writeGate.open()
        receiver.stop()
        scope.cancel()
    }

    private fun threadDump(): String = Thread.getAllStackTraces().entries
        .filter { (_, stack) -> stack.any { it.className.startsWith("com.glamsolo") } }
        .joinToString("\n\n") { (thread, stack) -> thread.name + "\n  " + stack.take(25).joinToString("\n  ") }

    private fun randomFile(name: String, size: Int, seed: Int): File =
        File(tmp.root, name).apply { writeBytes(Random(seed).nextBytes(size)) }

    @Test
    fun `apps and files travel from the old phone to the new one`() = runBlocking {
        withTimeout(60_000) {
            // Old phone: one split app and two files.
            val base = randomFile("base.apk", 3_000_000, 1)
            val split = randomFile("split_config.xxhdpi.apk", 400_000, 2)
            installFakeApp(context, "org.example.maps", "Maps", listOf(base, split))
            val photo = randomFile("IMG_0042.jpg", 2_500_000, 3)
            val doc = randomFile("Договор.pdf", 120_000, 4)
            val app = InstalledAppsRepository(context).load().first { it.packageName == "org.example.maps" }
            val files = FileResolver.resolve(
                context.contentResolver,
                listOf(TestFilesProvider.register(photo, "image/jpeg"), TestFilesProvider.register(doc, "application/pdf")),
            )

            // New phone: receive mode, visible on the network.
            receiver.start()
            val target = discovery.discover().first { it.isNotEmpty() }.single()
            assertEquals("New Phone", target.name)

            sender.send(target, listOf(app), files)
            val incoming = receiver.state.first { it is ReceiveState.Incoming } as ReceiveState.Incoming
            assertEquals("Old Phone", incoming.request.manifest.senderName)
            assertEquals(1, incoming.request.manifest.appCount)
            assertEquals(2, incoming.request.manifest.fileCount)
            assertTrue(sender.state.value is SendState.WaitingForAnswer)

            receiver.accept()
            val sent = sender.state.first { !it.isActive } as SendState.Finished
            val received = receiver.state.first { it is ReceiveState.Finished } as ReceiveState.Finished
            assertEquals("New Phone", sent.receiverName)
            val total = base.length() + split.length() + photo.length() + doc.length()
            assertEquals(total, sent.stats.bytes)
            assertEquals(total, received.stats.bytes)

            val items = receiver.received.value
            val receivedApp = items.filterIsInstance<ReceivedApp>().single()
            assertEquals("org.example.maps", receivedApp.packageName)
            assertEquals("Maps", receivedApp.label)
            assertEquals("3.2.1", receivedApp.versionName)
            assertEquals(listOf("base.apk", "split_config.xxhdpi.apk"), receivedApp.apkFiles.map { it.name })
            assertArrayEquals(base.readBytes(), receivedApp.apkFiles[0].readBytes())
            assertArrayEquals(split.readBytes(), receivedApp.apkFiles[1].readBytes())

            val receivedFiles = items.filterIsInstance<ReceivedFile>().map { it.file }
            assertEquals(listOf("IMG_0042.jpg", "Договор.pdf"), receivedFiles.map { it.displayName })
            assertArrayEquals(photo.readBytes(), File(storage, "Pictures/PhoneHop/IMG_0042.jpg").readBytes())
            assertArrayEquals(doc.readBytes(), File(storage, "Download/PhoneHop/Договор.pdf").readBytes())

            // The receiver is ready for the next sender.
            receiver.dismissResult()
            assertTrue(receiver.state.value is ReceiveState.Listening)
        }
    }

    @Test
    fun `declining on the new phone is shown on the old one`() = runBlocking {
        withTimeout(30_000) {
            val doc = randomFile("notes.txt", 1000, 5)
            val files = FileResolver.resolve(context.contentResolver, listOf(TestFilesProvider.register(doc, "text/plain")))
            receiver.start()
            val target = discovery.discover().first { it.isNotEmpty() }.single()
            sender.send(target, emptyList(), files)
            receiver.state.first { it is ReceiveState.Incoming }
            receiver.decline()
            val failed = sender.state.first { !it.isActive } as SendState.Failed
            assertEquals(TransferException.Reason.DECLINED, failed.reason)
            assertTrue(receiver.state.value is ReceiveState.Listening)
            assertTrue(receiver.received.value.isEmpty())
        }
    }

    @Test
    fun `cancelling on the old phone stops the new one cleanly`() = runBlocking {
        withTimeout(30_000) {
            val big = randomFile("movie.mp4", 60_000_000, 6)
            val files = FileResolver.resolve(context.contentResolver, listOf(TestFilesProvider.register(big, "video/mp4")))
            receiver.start()
            val target = discovery.discover().first { it.isNotEmpty() }.single()
            // Over loopback 60 MB can arrive in a fraction of a second, i.e. before the test
            // gets to press "cancel". Hold the receiver's first disk write until it has: the
            // socket buffers fill up and the sender is guaranteed to still be mid transfer.
            writeGate.close()
            sender.send(target, emptyList(), files)
            receiver.state.first { it is ReceiveState.Incoming }
            receiver.accept()
            // The receiver writing data means the transfer is under way. (Don't wait for the
            // sender's progress here: with the receiver held, the sender soon blocks on a full
            // socket and may not have published a progress update yet.)
            assertTrue("Receiver never started writing", runInterruptible(Dispatchers.IO) { writeGate.awaitWriter(15_000) })
            sender.cancel()
            // cancel() marks the whole send job cancelled before it returns, so once the
            // receiver drains the socket the sender stops at the next chunk and says why.
            writeGate.open()
            assertTrue(sender.state.first { !it.isActive } is SendState.Cancelled)
            val failed = withTimeoutOrNull(15_000) { receiver.state.first { it is ReceiveState.Failed } } as? ReceiveState.Failed
                ?: error("Receiver did not fail: ${receiver.state.value}\n" + threadDump())
            assertEquals(TransferException.Reason.PEER_CANCELLED, failed.reason)
            // No half-written movie is left in the gallery.
            val movies = File(storage, "Movies/PhoneHop").listFiles().orEmpty()
            assertTrue(movies.none { it.name == "movie.mp4" })
        }
    }

    @Test
    fun `unreachable receiver gives a clear error`() = runBlocking {
        withTimeout(30_000) {
            val doc = randomFile("a.txt", 10, 7)
            val files = FileResolver.resolve(context.contentResolver, listOf(TestFilesProvider.register(doc, "text/plain")))
            val port = java.net.ServerSocket(0).use { it.localPort }
            sender.send(PeerDevice("Ghost", "127.0.0.1", port), emptyList(), files)
            val failed = sender.state.first { !it.isActive } as SendState.Failed
            assertEquals(TransferException.Reason.CONNECTION_FAILED, failed.reason)
        }
    }
}

/** Lets a test hold every write to received files until it opens the gate again. */
private class WriteGate {
    @Volatile private var released = CountDownLatch(0)
    private val writerArrived = CountDownLatch(1)

    fun close() {
        released = CountDownLatch(1)
    }

    fun open() = released.countDown()

    /** Called by the receiver before each write. */
    fun pass() {
        writerArrived.countDown()
        released.await()
    }

    fun awaitWriter(millis: Long): Boolean = writerArrived.await(millis, TimeUnit.MILLISECONDS)
}

private class HeldFileStore(private val delegate: FileStore, private val gate: WriteGate) : FileStore {
    override fun create(relativePath: String, mimeType: String?): PendingFile {
        val pending = delegate.create(relativePath, mimeType)
        return object : PendingFile by pending {
            override val output: OutputStream = object : FilterOutputStream(pending.output) {
                override fun write(b: Int) {
                    gate.pass()
                    out.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    gate.pass()
                    out.write(b, off, len)
                }
            }
        }
    }
}
