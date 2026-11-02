package com.glamsolo.phonehop.transfer

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.glamsolo.phonehop.data.FileResolver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Where received regular files are saved. */
fun interface FileStore {
    /** [relativePath] is already sanitized, e.g. "Trip/IMG_1.jpg". */
    fun create(relativePath: String, mimeType: String?): PendingFile
}

interface PendingFile {
    val output: OutputStream
    fun commit(): SavedFile
    fun discard()
}

object FileStores {
    const val FOLDER = "PhoneHop"

    fun default(context: Context): FileStore =
        if (Build.VERSION.SDK_INT >= 29) MediaStoreFileStore(context) else DirectoryFileStore.publicFolders(context)

    /** Photos go to Pictures, videos to Movies, music to Music and everything else to Download. */
    fun standardDirectory(mime: String): String = when {
        mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    internal fun effectiveMime(name: String, mimeType: String?): String =
        mimeType?.takeIf { it.contains('/') && it != "application/octet-stream" }
            ?: FileResolver.guessMime(name)
            ?: "application/octet-stream"
}

/** Android 10+: scoped storage through MediaStore, no permission needed. */
@RequiresApi(29)
class MediaStoreFileStore(private val context: Context) : FileStore {
    private val resolver get() = context.contentResolver

    // The output stream is returned to the caller, which closes it before commit() / discard().
    @SuppressLint("Recycle")
    override fun create(relativePath: String, mimeType: String?): PendingFile {
        val name = relativePath.substringAfterLast('/')
        val subFolder = relativePath.substringBeforeLast('/', "")
        val mime = FileStores.effectiveMime(name, mimeType)
        val directory = FileStores.standardDirectory(mime)
        val uri = insert(collectionFor(directory), name, mime, directory, subFolder)
            ?: insert(collectionFor(Environment.DIRECTORY_DOWNLOADS), name, mime, Environment.DIRECTORY_DOWNLOADS, subFolder)
            ?: throw IOException("Could not create $name")
        val output = resolver.openOutputStream(uri, "w") ?: run {
            resolver.delete(uri, null, null)
            throw IOException("Could not open $name")
        }
        return object : PendingFile {
            override val output: OutputStream = output

            override fun commit(): SavedFile {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                var displayName = name
                var folder = listOf(directory, FileStores.FOLDER, subFolder).filter { it.isNotEmpty() }.joinToString("/")
                var size = -1L
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { displayName = it }
                        c.getString(1)?.let { folder = it.trimEnd('/') }
                        if (!c.isNull(2)) size = c.getLong(2)
                    }
                }
                return SavedFile(uri, displayName, folder, mime, size)
            }

            override fun discard() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun collectionFor(directory: String): Uri = when (directory) {
        Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    private fun insert(collection: Uri, name: String, mime: String, directory: String, subFolder: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                listOf(directory, FileStores.FOLDER, subFolder).filter { it.isNotEmpty() }.joinToString("/") + "/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return runCatching { resolver.insert(collection, values) }.getOrNull()
    }
}

/**
 * Writes into plain folders. Used on Android 8–9 (public folders, needs the storage
 * permission) and in tests. Files are written as hidden ".part" files first.
 */
class DirectoryFileStore(
    private val context: Context,
    private val rootFor: (directory: String) -> File,
    private val scan: Boolean = true,
) : FileStore {

    override fun create(relativePath: String, mimeType: String?): PendingFile {
        val name = relativePath.substringAfterLast('/')
        val subFolder = relativePath.substringBeforeLast('/', "")
        val mime = FileStores.effectiveMime(name, mimeType)
        val directory = FileStores.standardDirectory(mime)
        val folder = File(File(rootFor(directory), FileStores.FOLDER), subFolder)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create ${folder.path}")
        val temp = File.createTempFile(".phonehop-", ".part", folder)
        val output = FileOutputStream(temp)
        return object : PendingFile {
            override val output: OutputStream = output

            override fun commit(): SavedFile {
                val target = uniqueFile(folder, name)
                if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
                if (scan) MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
                val uri = runCatching {
                    FileProvider.getUriForFile(context, context.packageName + ".files", target)
                }.getOrElse { Uri.fromFile(target) }
                val label = listOf(directory, FileStores.FOLDER, subFolder).filter { it.isNotEmpty() }.joinToString("/")
                return SavedFile(uri, target.name, label, mime, target.length())
            }

            override fun discard() {
                runCatching { output.close() }
                temp.delete()
            }
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun publicFolders(context: Context) = DirectoryFileStore(context, { Environment.getExternalStoragePublicDirectory(it) })

        /** "photo.jpg" -> "photo (1).jpg" -> "photo (2).jpg" … */
        fun uniqueFile(folder: File, name: String): File {
            var candidate = File(folder, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (candidate.exists()) candidate = File(folder, "$base (${n++})$ext")
            return candidate
        }
    }
}
