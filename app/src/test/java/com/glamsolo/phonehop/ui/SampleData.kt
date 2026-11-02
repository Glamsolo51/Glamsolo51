package com.glamsolo.phonehop.ui

import android.net.Uri
import com.glamsolo.phonehop.core.IncomingRequest
import com.glamsolo.phonehop.core.ItemKind
import com.glamsolo.phonehop.core.ManifestItem
import com.glamsolo.phonehop.core.PartInfo
import com.glamsolo.phonehop.core.TransferManifest
import com.glamsolo.phonehop.core.TransferProgress
import com.glamsolo.phonehop.data.AppInfo
import com.glamsolo.phonehop.data.PickedFile
import com.glamsolo.phonehop.install.InstallStatus
import com.glamsolo.phonehop.transfer.ReceivedApp
import com.glamsolo.phonehop.transfer.ReceivedFile
import com.glamsolo.phonehop.transfer.SavedFile
import java.io.File

object SampleData {
    private const val MB = 1024L * 1024

    val apps = listOf(
        AppInfo("org.telegram.messenger", "Telegram", "11.4.2", 1, listOf("/a.apk"), 78 * MB),
        AppInfo("com.spotify.music", "Spotify", "9.0.12", 1, listOf("/b.apk"), 112 * MB),
        AppInfo("com.duolingo", "Duolingo", "6.8.3", 1, listOf("/c.apk"), 64 * MB),
        AppInfo("ru.yandex.taxi", "Yandex Go", "5.12", 1, listOf("/d.apk"), 140 * MB),
        AppInfo("com.whatsapp", "WhatsApp", "2.25.3", 1, listOf("/e.apk"), 96 * MB),
        AppInfo("com.instagram.android", "Instagram", "390.0", 1, listOf("/f.apk"), 88 * MB),
        AppInfo("com.notion.id", "Notion", "0.6.2", 1, listOf("/g.apk"), 41 * MB),
    )

    val files = listOf(
        PickedFile(Uri.parse("content://x/1"), "IMG_20260814_1932.jpg", "Camera/IMG_20260814_1932.jpg", 4_200_000, "image/jpeg"),
        PickedFile(Uri.parse("content://x/2"), "Отпуск в горах.mp4", "Отпуск в горах.mp4", 486_000_000, "video/mp4"),
        PickedFile(Uri.parse("content://x/3"), "Договор аренды.pdf", "Docs/Договор аренды.pdf", 830_000, "application/pdf"),
    )

    private fun item(id: Int, kind: ItemKind, title: String, size: Long, mime: String?) = ManifestItem(
        id = id, kind = kind, title = title, path = if (kind == ItemKind.APP) "com.example.app$id" else title,
        mimeType = mime, versionName = "1.0", parts = listOf(PartInfo("base.apk", size)),
    )

    val manifest = TransferManifest(
        "Galaxy S10",
        listOf(
            item(1, ItemKind.APP, "Telegram", 78 * MB, null),
            item(2, ItemKind.APP, "Spotify", 112 * MB, null),
            item(3, ItemKind.FILE, "IMG_20260814_1932.jpg", 4 * MB, "image/jpeg"),
            item(4, ItemKind.FILE, "Отпуск в горах.mp4", 486 * MB, "video/mp4"),
            item(5, ItemKind.FILE, "Договор аренды.pdf", 1 * MB, "application/pdf"),
            item(6, ItemKind.FILE, "Заметки.txt", 20_000, "text/plain"),
        ),
    )

    val request = IncomingRequest(1, "192.168.1.40", manifest)

    val progress = TransferProgress(
        bytesDone = 412 * MB,
        totalBytes = manifest.totalBytes,
        itemsDone = 3,
        totalItems = 6,
        currentItemId = 4,
        currentItemTitle = "Отпуск в горах.mp4",
        bytesPerSecond = 23 * MB,
    )

    val receivedApps = listOf(
        ReceivedApp("org.telegram.messenger", "Telegram", "11.4.2", 1, null, listOf(File("/x/base.apk")), 78 * MB),
        ReceivedApp("com.spotify.music", "Spotify", "9.0.12", 1, null, listOf(File("/y/base.apk")), 112 * MB),
    )

    val receivedFiles = listOf(
        ReceivedFile(SavedFile(Uri.parse("content://media/1"), "IMG_20260814_1932.jpg", "Pictures/PhoneHop/Camera", "image/jpeg", 4 * MB)),
        ReceivedFile(SavedFile(Uri.parse("content://media/2"), "Отпуск в горах.mp4", "Movies/PhoneHop", "video/mp4", 486 * MB)),
        ReceivedFile(SavedFile(Uri.parse("content://media/3"), "Договор аренды.pdf", "Download/PhoneHop/Docs", "application/pdf", 1 * MB)),
    )

    val installStatus: (ReceivedApp) -> InstallStatus = { if (it.label == "Telegram") InstallStatus.Installed else InstallStatus.NotInstalled }
}
