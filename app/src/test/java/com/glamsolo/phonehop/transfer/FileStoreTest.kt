package com.glamsolo.phonehop.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store(root: File) = DirectoryFileStore(context, { File(root, it) }, scan = false)

    @Test
    fun `files are routed by type into PhoneHop folders`() {
        val root = tmp.newFolder()
        val store = store(root)
        fun save(path: String, mime: String?): SavedFile =
            store.create(path, mime).run { output.use { it.write(path.toByteArray()) }; commit() }

        val photo = save("Camera/IMG_1.jpg", "image/jpeg")
        val video = save("clip.mp4", null)
        val song = save("song.mp3", "audio/mpeg")
        val doc = save("Docs/report.pdf", "application/pdf")

        assertTrue(File(root, "Pictures/PhoneHop/Camera/IMG_1.jpg").isFile)
        assertTrue(File(root, "Movies/PhoneHop/clip.mp4").isFile)
        assertTrue(File(root, "Music/PhoneHop/song.mp3").isFile)
        assertTrue(File(root, "Download/PhoneHop/Docs/report.pdf").isFile)
        assertEquals("Pictures/PhoneHop/Camera", photo.folder)
        assertEquals("video/mp4", video.mimeType)
        assertEquals("song.mp3", song.displayName)
        assertEquals("Camera/IMG_1.jpg".length.toLong(), photo.size)
        assertEquals("Download/PhoneHop/Docs", doc.folder)
    }

    @Test
    fun `existing names get a counter and nothing is overwritten`() {
        val root = tmp.newFolder()
        val store = store(root)
        repeat(3) { i ->
            store.create("notes.txt", "text/plain").run {
                output.use { it.write("v$i".toByteArray()) }
                commit()
            }
        }
        val folder = File(root, "Download/PhoneHop")
        assertEquals("v0", File(folder, "notes.txt").readText())
        assertEquals("v1", File(folder, "notes (1).txt").readText())
        assertEquals("v2", File(folder, "notes (2).txt").readText())
    }

    @Test
    fun `discarded files leave nothing behind`() {
        val root = tmp.newFolder()
        val pending = store(root).create("broken.bin", null)
        pending.output.write(ByteArray(100))
        pending.discard()
        val folder = File(root, "Download/PhoneHop")
        assertFalse(folder.listFiles().orEmpty().any { it.isFile })
    }

    @Test
    fun `apk names are made unique and safe`() {
        assertEquals(
            listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.ru.apk"),
            OutgoingBuilder.apkNames(listOf("/data/app/x/base.apk", "/data/app/x/split_config.arm64_v8a.apk", "/data/app/x/split_config.ru.apk")),
        )
        assertEquals(
            listOf("base.apk", "split_1.apk"),
            OutgoingBuilder.apkNames(listOf("/a/whatever.apk", "/b/base.apk")),
        )
    }
}
