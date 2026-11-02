package com.glamsolo.phonehop.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathSanitizerTest {
    @Test
    fun `traversal and absolute paths stay inside the target folder`() {
        assertEquals("etc/passwd", PathSanitizer.sanitizeRelativePath("../../etc/passwd"))
        assertEquals("etc/passwd", PathSanitizer.sanitizeRelativePath("/etc/passwd"))
        assertEquals("a/b.txt", PathSanitizer.sanitizeRelativePath("a\\..\\b.txt"))
        assertEquals("C_/Windows/x.txt", PathSanitizer.sanitizeRelativePath("C:\\Windows\\x.txt"))
    }

    @Test
    fun `hidden names and illegal characters are neutralised`() {
        assertEquals("bashrc", PathSanitizer.sanitizeRelativePath(".bashrc"))
        assertEquals("a_b_c.txt", PathSanitizer.sanitizeSegment("a:b*c.txt"))
        assertEquals("file", PathSanitizer.sanitizeRelativePath("../.."))
        assertEquals("photo.jpg", PathSanitizer.sanitizeRelativePath("", fallbackName = "photo.jpg"))
        assertNull(PathSanitizer.sanitizeSegment("..."))
        assertEquals("Фото отпуска/IMG 1.jpg", PathSanitizer.sanitizeRelativePath("Фото отпуска/IMG 1.jpg"))
    }

    @Test
    fun `very long names keep their extension`() {
        val name = "x".repeat(500) + ".jpeg"
        val sanitized = PathSanitizer.sanitizeSegment(name)!!
        assertTrue(sanitized.length <= 120)
        assertTrue(sanitized.endsWith(".jpeg"))
    }

    @Test
    fun `deep paths are trimmed from the top`() {
        val deep = (1..40).joinToString("/") { "d$it" } + "/f.txt"
        val sanitized = PathSanitizer.sanitizeRelativePath(deep)
        assertEquals(16, sanitized.split('/').size)
        assertTrue(sanitized.endsWith("d40/f.txt"))
    }

    @Test
    fun `package and apk names are validated`() {
        assertTrue(PathSanitizer.isValidPackageName("com.whatsapp"))
        assertTrue(PathSanitizer.isValidPackageName("org.telegram.messenger_web"))
        assertFalse(PathSanitizer.isValidPackageName("../evil"))
        assertFalse(PathSanitizer.isValidPackageName("single"))
        assertEquals("split_config.arm64_v8a.apk", PathSanitizer.sanitizeApkName("split_config.arm64_v8a.apk", 1))
        assertEquals("part_2.apk", PathSanitizer.sanitizeApkName("../../base.apk", 2))
        assertEquals("part_0.apk", PathSanitizer.sanitizeApkName("base.so", 0))
    }
}

class WireFormatTest {
    @Test
    fun `manifest survives the round trip`() {
        val manifest = samplePayload().manifest
        val bytes = ByteArrayOutputStream().also { WireFormat.writeHello(DataOutputStream(it), manifest) }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(Protocol.VERSION, WireFormat.readPreamble(input))
        assertEquals(manifest, WireFormat.readManifestBody(input))
    }

    @Test
    fun `hostile manifests are rejected`() {
        fun parse(write: DataOutputStream.() -> Unit) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).write() }.toByteArray()
            WireFormat.readManifestBody(DataInputStream(ByteArrayInputStream(bytes)))
        }
        val tooMany = assertFailsWith<TransferException> { parse { writeUTF("x"); writeInt(Int.MAX_VALUE) } }
        assertEquals(TransferException.Reason.PROTOCOL_ERROR, tooMany.reason)
        assertFailsWith<TransferException> {
            parse {
                writeUTF("x"); writeInt(1)
                writeInt(1); writeByte(2); writeUTF("t"); writeUTF("p"); writeUTF(""); writeUTF(""); writeLong(0)
                writeInt(Int.MAX_VALUE) // icon size
            }
        }
        assertFailsWith<TransferException> {
            parse {
                writeUTF("x"); writeInt(1)
                writeInt(1); writeByte(2); writeUTF("t"); writeUTF("p"); writeUTF(""); writeUTF(""); writeLong(0)
                writeInt(0); writeInt(3) // a file with three parts
            }
        }
        assertFailsWith<TransferException> { WireFormat.readPreamble(DataInputStream(ByteArrayInputStream(ByteArray(8)))) }
    }
}

class ProgressTrackerTest {
    @Test
    fun `speed eta and totals are computed`() {
        var now = 0L
        val emitted = mutableListOf<TransferProgress>()
        val manifest = TransferManifest(
            "s",
            listOf(
                ManifestItem(1, ItemKind.FILE, "a", "a", parts = listOf(PartInfo("a", 10_000_000))),
                ManifestItem(2, ItemKind.FILE, "b", "b", parts = listOf(PartInfo("b", 10_000_000))),
            ),
        )
        val tracker = ProgressTracker(manifest, emitIntervalNanos = 100_000_000, clock = { now }) { emitted += it }
        tracker.startItem(0)
        repeat(10) {
            now += 100_000_000 // 100 ms
            tracker.addBytes(1_000_000) // 10 MB/s
        }
        val mid = emitted.last()
        assertEquals(10_000_000, mid.bytesDone)
        assertEquals(10_000_000, mid.bytesPerSecond)
        assertEquals(1L, mid.etaSeconds)
        assertEquals(0.5f, mid.fraction)
        assertEquals("a", mid.currentItemTitle)
        tracker.partFinished(10_000_000, 10_000_000)
        tracker.finishItem()
        tracker.startItem(1)
        assertEquals("b", emitted.last().currentItemTitle)
        now += 500_000_000
        tracker.addBytes(4_000_000)
        tracker.partFinished(10_000_000, 4_000_000) // the file was shorter than announced
        tracker.finishItem()
        val stats = tracker.complete()
        assertEquals(14_000_000, stats.bytes)
        assertEquals(2, stats.items)
        assertEquals(1f, emitted.last().fraction)
        assertEquals(14_000_000, emitted.last().totalBytes)
    }

    @Test
    fun `empty transfer is complete`() {
        val p = TransferProgress.initial(TransferManifest("s", emptyList()))
        assertEquals(1f, p.fraction)
        assertNull(p.etaSeconds)
    }
}
