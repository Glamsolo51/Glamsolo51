package com.glamsolo.phonehop.core

/** What a transferred item represents on the receiving side. */
enum class ItemKind(internal val wireCode: Int) {
    /** An installed application: one or more APK files (base + splits). */
    APP(1),

    /** A regular file (document, photo, video, …). */
    FILE(2);

    companion object {
        fun fromWire(code: Int): ItemKind =
            entries.firstOrNull { it.wireCode == code }
                ?: throw TransferException(TransferException.Reason.PROTOCOL_ERROR, "Unknown item kind $code")
    }
}

/**
 * One physical stream inside an item. Files always have exactly one part;
 * apps have one part per APK (base.apk plus split APKs).
 *
 * [size] is the size announced by the sender. It is used for progress only:
 * the real length is determined by the data stream itself, so files whose size
 * is unknown (or that change while being sent) still transfer correctly.
 */
data class PartInfo(val name: String, val size: Long)

/** An entry of the transfer manifest. */
data class ManifestItem(
    val id: Int,
    val kind: ItemKind,
    /** Human readable name: app label or file name. */
    val title: String,
    /** For files: relative path ("Folder/photo.jpg"). For apps: the package name. */
    val path: String,
    val mimeType: String? = null,
    val versionName: String? = null,
    val versionCode: Long = 0,
    /** Small PNG icon for apps, shown on the receiving side. */
    val icon: ByteArray? = null,
    val parts: List<PartInfo>,
) {
    val size: Long get() = parts.sumOf { it.size.coerceAtLeast(0) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManifestItem) return false
        return id == other.id && kind == other.kind && title == other.title && path == other.path &&
            mimeType == other.mimeType && versionName == other.versionName &&
            versionCode == other.versionCode && parts == other.parts &&
            (icon === other.icon || (icon != null && other.icon != null && icon.contentEquals(other.icon)))
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + kind.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + parts.hashCode()
        return result
    }
}

/** Everything the sender is going to transfer, announced before the data starts. */
data class TransferManifest(
    val senderName: String,
    val items: List<ManifestItem>,
) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val appCount: Int get() = items.count { it.kind == ItemKind.APP }
    val fileCount: Int get() = items.count { it.kind == ItemKind.FILE }
}

/** Snapshot of a running transfer. */
data class TransferProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val itemsDone: Int,
    val totalItems: Int,
    val currentItemId: Int?,
    val currentItemTitle: String?,
    val bytesPerSecond: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) (if (totalItems == 0 || itemsDone >= totalItems) 1f else 0f)
        else (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    /** Estimated remaining time in seconds, or null when the speed is not known yet. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond <= 0) null else ((totalBytes - bytesDone).coerceAtLeast(0) / bytesPerSecond)

    companion object {
        fun initial(manifest: TransferManifest) = TransferProgress(
            bytesDone = 0,
            totalBytes = manifest.totalBytes,
            itemsDone = 0,
            totalItems = manifest.items.size,
            currentItemId = manifest.items.firstOrNull()?.id,
            currentItemTitle = manifest.items.firstOrNull()?.title,
            bytesPerSecond = 0,
        )
    }
}

/** Final numbers of a finished transfer. */
data class TransferStats(
    val bytes: Long,
    val items: Int,
    val durationMillis: Long,
) {
    val averageBytesPerSecond: Long
        get() = if (durationMillis <= 0) bytes else bytes * 1000 / durationMillis
}
