package com.glamsolo.phonehop.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file chosen by the user through the system picker. */
data class PickedFile(
    val uri: Uri,
    val name: String,
    /** Path on the receiving side, e.g. "Holiday 2025/IMG_001.jpg" for files picked from a folder. */
    val relativePath: String,
    /** Size in bytes, or -1 when the provider does not know. */
    val size: Long,
    val mimeType: String?,
)

object FileResolver {
    private const val MAX_FOLDER_FILES = 20_000

    suspend fun resolve(resolver: ContentResolver, uris: List<Uri>): List<PickedFile> =
        withContext(Dispatchers.IO) { uris.mapNotNull { resolveOne(resolver, it) } }

    private fun resolveOne(resolver: ContentResolver, uri: Uri): PickedFile? = runCatching {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = resolver.getType(uri) ?: guessMime(fileName)
        PickedFile(uri, fileName, fileName, size, mime)
    }.getOrNull()

    /** Lists every file inside a folder picked with ACTION_OPEN_DOCUMENT_TREE, keeping sub-folders. */
    suspend fun resolveTree(resolver: ContentResolver, treeUri: Uri): List<PickedFile> = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        var rootName = "Folder"
        resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) rootName = c.getString(0)
        }
        val result = ArrayList<PickedFile>()
        val pending = ArrayDeque<Pair<String, String>>().apply { add(rootId to rootName) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty() && result.size < MAX_FOLDER_FILES) {
            val (docId, prefix) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext() && result.size < MAX_FOLDER_FILES) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id to "$prefix/$name")
                    } else {
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        result += PickedFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name = name,
                            relativePath = "$prefix/$name",
                            size = size,
                            mimeType = mime ?: guessMime(name),
                        )
                    }
                }
            }
        }
        result
    }

    fun guessMime(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
