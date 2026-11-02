package com.glamsolo.phonehop.core

import java.io.IOException

/** A transfer failure with a machine readable [reason] the UI can translate. */
class TransferException(
    val reason: Reason,
    message: String? = null,
    cause: Throwable? = null,
) : IOException(message ?: reason.name, cause) {

    enum class Reason {
        /** Could not reach the other device. */
        CONNECTION_FAILED,

        /** The connection dropped in the middle of the transfer. */
        CONNECTION_LOST,

        /** The receiver declined the request. */
        DECLINED,

        /** The receiver is already busy with another transfer. */
        BUSY,

        /** The other device runs an incompatible protocol version. */
        INCOMPATIBLE,

        /** The other side cancelled the transfer. */
        PEER_CANCELLED,

        /** The other side reported an error (e.g. it ran out of storage). */
        REMOTE_ERROR,

        /** Data arrived corrupted. */
        CHECKSUM_MISMATCH,

        /** The peer sent something that does not follow the protocol. */
        PROTOCOL_ERROR,

        /** A file to send could not be read. */
        SOURCE_ERROR,

        /** A received file could not be saved. */
        STORAGE_ERROR,

        /** The receiver did not answer in time. */
        TIMEOUT,
    }
}
