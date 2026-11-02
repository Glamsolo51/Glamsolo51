package com.glamsolo.phonehop.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * PhoneHop wire protocol, version 1. Plain TCP, big-endian, strings in modified UTF-8.
 *
 * ```
 * sender -> receiver   HELLO     int MAGIC, int VERSION, manifest
 * receiver -> sender   ANSWER    int MAGIC, int VERSION, byte answer, utf receiverName
 * sender -> receiver   (accepted only) for every part of every item, in manifest order:
 *                      byte FRAME_PART, int itemId, int partIndex,
 *                      { int chunkLength > 0, bytes } *, int 0, long crc32
 *                      then byte FRAME_END
 *                      (a chunk length of CHUNK_CANCEL or a FRAME_CANCEL byte aborts the transfer)
 * receiver -> sender   RESULT    byte result, utf message
 * ```
 * The receiver may send its RESULT early (error or user cancel); the sender checks for
 * it between chunks and stops.
 */
object Protocol {
    const val MAGIC = 0x50484F50 // "PHOP"
    const val VERSION = 1

    /** DNS-SD service type used for discovery on the local network. */
    const val SERVICE_TYPE = "_phonehop._tcp"
    const val DEFAULT_PORT = 48484

    const val FRAME_PART: Int = 1
    const val FRAME_END: Int = 2
    const val FRAME_CANCEL: Int = 3
    const val CHUNK_CANCEL: Int = -1

    const val ANSWER_ACCEPT: Int = 1
    const val ANSWER_DECLINE: Int = 2
    const val ANSWER_BUSY: Int = 3
    const val ANSWER_INCOMPATIBLE: Int = 4

    const val RESULT_OK: Int = 1
    const val RESULT_FAILED: Int = 2
    const val RESULT_CANCELLED: Int = 3

    const val CHUNK_SIZE = 256 * 1024
    const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
    const val MAX_ITEMS = 100_000
    const val MAX_PARTS_PER_ITEM = 256
    const val MAX_ICON_BYTES = 512 * 1024
    const val SOCKET_BUFFER = 1024 * 1024

    const val CONNECT_TIMEOUT_MS = 7_000
    const val IO_TIMEOUT_MS = 45_000

    /** How long the receiver's user has to accept a request. */
    const val DECISION_TIMEOUT_MS = 120_000L
}

internal object WireFormat {

    fun writeHello(out: DataOutputStream, manifest: TransferManifest) {
        out.writeInt(Protocol.MAGIC)
        out.writeInt(Protocol.VERSION)
        out.writeUTF(manifest.senderName.take(200))
        out.writeInt(manifest.items.size)
        for (item in manifest.items) {
            out.writeInt(item.id)
            out.writeByte(item.kind.wireCode)
            out.writeUTF(item.title.take(1000))
            out.writeUTF(item.path.take(4000))
            out.writeUTF(item.mimeType.orEmpty().take(255))
            out.writeUTF(item.versionName.orEmpty().take(200))
            out.writeLong(item.versionCode)
            val icon = item.icon?.takeIf { it.size <= Protocol.MAX_ICON_BYTES }
            out.writeInt(icon?.size ?: 0)
            if (icon != null) out.write(icon)
            out.writeInt(item.parts.size)
            for (part in item.parts) {
                out.writeUTF(part.name.take(1000))
                out.writeLong(part.size)
            }
        }
    }

    /**
     * Reads the magic and version. Returns the version announced by the peer;
     * throws if the stream is not a PhoneHop stream at all.
     */
    fun readPreamble(input: DataInputStream): Int {
        val magic = input.readInt()
        if (magic != Protocol.MAGIC) {
            throw TransferException(TransferException.Reason.PROTOCOL_ERROR, "Not a PhoneHop peer")
        }
        return input.readInt()
    }

    fun readManifestBody(input: DataInputStream): TransferManifest {
        val senderName = input.readUTF()
        val count = input.readInt()
        if (count !in 0..Protocol.MAX_ITEMS) protocolError("Bad item count $count")
        val ids = HashSet<Int>()
        val items = ArrayList<ManifestItem>(minOf(count, 1024))
        repeat(count) {
            val id = input.readInt()
            if (!ids.add(id)) protocolError("Duplicate item id $id")
            val kind = ItemKind.fromWire(input.readUnsignedByte())
            val title = input.readUTF()
            val path = input.readUTF()
            val mime = input.readUTF().ifEmpty { null }
            val versionName = input.readUTF().ifEmpty { null }
            val versionCode = input.readLong()
            val iconSize = input.readInt()
            if (iconSize !in 0..Protocol.MAX_ICON_BYTES) protocolError("Bad icon size $iconSize")
            val icon = if (iconSize > 0) ByteArray(iconSize).also { input.readFully(it) } else null
            val partCount = input.readInt()
            if (partCount !in 1..Protocol.MAX_PARTS_PER_ITEM) protocolError("Bad part count $partCount")
            if (kind == ItemKind.FILE && partCount != 1) protocolError("A file must have exactly one part")
            val parts = List(partCount) {
                val name = input.readUTF()
                val size = input.readLong()
                if (size < -1) protocolError("Bad part size $size")
                PartInfo(name, size)
            }
            items += ManifestItem(id, kind, title, path, mime, versionName, versionCode, icon, parts)
        }
        return TransferManifest(senderName, items)
    }

    fun writeAnswer(out: DataOutputStream, answer: Int, receiverName: String) {
        out.writeInt(Protocol.MAGIC)
        out.writeInt(Protocol.VERSION)
        out.writeByte(answer)
        out.writeUTF(receiverName.take(200))
        out.flush()
    }

    fun writeResult(out: DataOutputStream, result: Int, message: String = "") {
        out.writeByte(result)
        out.writeUTF(message.take(1000))
        out.flush()
    }

    fun protocolError(message: String): Nothing =
        throw TransferException(TransferException.Reason.PROTOCOL_ERROR, message)
}

/** Reads one byte, turning end-of-stream into an [EOFException]. */
internal fun DataInputStream.readByteOrEof(): Int {
    val b = read()
    if (b < 0) throw EOFException()
    return b
}
