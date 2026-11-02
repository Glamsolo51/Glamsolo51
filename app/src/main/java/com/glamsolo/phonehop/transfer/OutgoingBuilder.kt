package com.glamsolo.phonehop.transfer

import android.content.ContentResolver
import com.glamsolo.phonehop.core.ItemKind
import com.glamsolo.phonehop.core.ManifestItem
import com.glamsolo.phonehop.core.PartInfo
import com.glamsolo.phonehop.core.PartSource
import com.glamsolo.phonehop.core.PathSanitizer
import com.glamsolo.phonehop.core.TransferManifest
import com.glamsolo.phonehop.data.AppInfo
import com.glamsolo.phonehop.data.PickedFile
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/** What is about to be sent: the manifest plus a way to read every part of it. */
class Outgoing(val manifest: TransferManifest, val source: PartSource)

object OutgoingBuilder {
    const val APK_MIME = "application/vnd.android.package-archive"

    fun build(
        senderName: String,
        apps: List<AppInfo>,
        files: List<PickedFile>,
        resolver: ContentResolver,
        iconPng: (String) -> ByteArray?,
    ): Outgoing {
        val items = ArrayList<ManifestItem>(apps.size + files.size)
        val openers = HashMap<Int, (Int) -> InputStream>()
        var nextId = 1

        for (app in apps) {
            val id = nextId++
            val names = apkNames(app.apkPaths)
            items += ManifestItem(
                id = id,
                kind = ItemKind.APP,
                title = app.label,
                path = app.packageName,
                mimeType = APK_MIME,
                versionName = app.versionName,
                versionCode = app.versionCode,
                icon = iconPng(app.packageName),
                parts = app.apkPaths.mapIndexed { i, path -> PartInfo(names[i], File(path).length()) },
            )
            openers[id] = { part -> FileInputStream(app.apkPaths[part]) }
        }
        for (file in files) {
            val id = nextId++
            items += ManifestItem(
                id = id,
                kind = ItemKind.FILE,
                title = file.name,
                path = file.relativePath,
                mimeType = file.mimeType,
                parts = listOf(PartInfo(file.name, file.size)),
            )
            openers[id] = { _ -> resolver.openInputStream(file.uri) ?: throw FileNotFoundException(file.name) }
        }
        val source = PartSource { item, part -> openers.getValue(item.id)(part) }
        return Outgoing(TransferManifest(senderName, items), source)
    }

    /** "base.apk", "split_config.arm64_v8a.apk", … made unique and safe. */
    internal fun apkNames(paths: List<String>): List<String> {
        val used = HashSet<String>()
        return paths.mapIndexed { index, path ->
            val raw = if (index == 0) "base.apk" else File(path).name
            var name = PathSanitizer.sanitizeApkName(raw, index)
            if (!used.add(name)) name = "split_$index.apk".also { used.add(it) }
            name
        }
    }
}
