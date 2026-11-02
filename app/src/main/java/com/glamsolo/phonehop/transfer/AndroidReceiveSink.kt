package com.glamsolo.phonehop.transfer

import com.glamsolo.phonehop.core.ItemKind
import com.glamsolo.phonehop.core.ManifestItem
import com.glamsolo.phonehop.core.PartWriter
import com.glamsolo.phonehop.core.PathSanitizer
import com.glamsolo.phonehop.core.ReceiveSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Saves incoming data on the phone: files go to shared storage through [fileStore],
 * APKs go to app-private storage under [apkRoot] until the user installs them.
 */
class AndroidReceiveSink(
    private val apkRoot: File,
    private val fileStore: FileStore,
) : ReceiveSink {
    private val savedFiles = ConcurrentHashMap<Int, SavedFile>()

    /** Items fully received so far, in arrival order. */
    val received = CopyOnWriteArrayList<ReceivedItem>()

    override fun openPart(item: ManifestItem, partIndex: Int): PartWriter = when (item.kind) {
        ItemKind.APP -> openApkPart(item, partIndex)
        ItemKind.FILE -> openFile(item)
    }

    private fun openApkPart(item: ManifestItem, partIndex: Int): PartWriter {
        if (!PathSanitizer.isValidPackageName(item.path)) throw IOException("Invalid package name")
        val file = apkFiles(item)[partIndex]
        file.parentFile?.mkdirs()
        val output = FileOutputStream(file)
        return object : PartWriter {
            override val output: OutputStream = output
            override fun commit() = Unit
            override fun discard() {
                file.delete()
            }
        }
    }

    private fun openFile(item: ManifestItem): PartWriter {
        val path = PathSanitizer.sanitizeRelativePath(item.path, fallbackName = item.title)
        val pending = fileStore.create(path, item.mimeType)
        return object : PartWriter {
            override val output: OutputStream = pending.output
            override fun commit() {
                savedFiles[item.id] = pending.commit()
            }

            override fun discard() = pending.discard()
        }
    }

    override fun onItemReceived(item: ManifestItem) {
        received += when (item.kind) {
            ItemKind.APP -> ReceivedApp(
                packageName = item.path,
                label = item.title.ifBlank { item.path },
                versionName = item.versionName,
                versionCode = item.versionCode,
                icon = item.icon,
                apkFiles = apkFiles(item),
            )
            ItemKind.FILE -> ReceivedFile(savedFiles.remove(item.id) ?: throw IOException("File was not saved"))
        }
    }

    /** Safe, unique file names for the APKs of an app. */
    private fun apkFiles(item: ManifestItem): List<File> {
        val folder = File(apkRoot, item.path)
        val used = HashSet<String>()
        return item.parts.mapIndexed { index, part ->
            var name = PathSanitizer.sanitizeApkName(part.name, index)
            if (!used.add(name)) name = "part_$index.apk".also { used.add(it) }
            File(folder, name)
        }
    }
}
