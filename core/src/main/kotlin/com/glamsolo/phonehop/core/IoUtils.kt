package com.glamsolo.phonehop.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

/**
 * Runs blocking socket code so that coroutine cancellation actually stops it:
 * blocking reads and writes do not react to cancellation, so a watchdog closes the
 * resource when the scope is cancelled. [graceMillis] gives the blocking code a moment
 * to notice the cancellation itself (e.g. to tell the peer) before the hard close.
 *
 * An exception that [block] throws because the watchdog closed the resource (typically
 * "Socket closed") is rethrown as a [CancellationException]. Without this, a coroutine
 * that is merely being cancelled would *fail* with that IOException: the watchdog runs on
 * another thread and can close the resource before cancellation has reached the nested
 * scopes inside [block], so checks like `ensureActive()` there may still pass.
 */
internal suspend fun <T> Closeable.closeOnCancel(graceMillis: Long = 0, block: suspend () -> T): T =
    coroutineScope {
        val blockDone = CompletableDeferred<Unit>()
        val closedByCancel = AtomicBoolean(false)
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!blockDone.isCompleted) {
                    if (graceMillis > 0) {
                        withContext(NonCancellable) { withTimeoutOrNull(graceMillis) { blockDone.await() } }
                    }
                    closedByCancel.set(true)
                    closeQuietly()
                }
            }
        }
        try {
            block()
        } catch (e: Throwable) {
            // This scope is the watchdog's parent, so it is already marked cancelled
            // before the watchdog can run: `!isActive` is reliable here.
            if (e !is CancellationException && (closedByCancel.get() || !isActive)) {
                throw CancellationException("Closed because the coroutine was cancelled").apply { initCause(e) }
            }
            throw e
        } finally {
            blockDone.complete(Unit)
            watchdog.cancel()
            closeQuietly()
        }
    }

/** Reads until [buffer] is full or the stream ends. Returns the number of bytes read (0 at end). */
internal fun InputStream.readUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/**
 * After sending an early result, keep reading (and dropping) what the peer is still
 * sending for a short while. Closing a socket with unread input makes the kernel send
 * a reset, which could destroy our result message before the peer reads it.
 */
internal fun Socket.drainBriefly(input: InputStream, maxMillis: Long = 3_000) {
    try {
        shutdownOutput()
        soTimeout = 250
        val deadline = System.currentTimeMillis() + maxMillis
        val scratch = ByteArray(64 * 1024)
        while (System.currentTimeMillis() < deadline) {
            try {
                if (input.read(scratch) < 0) return
            } catch (_: SocketTimeoutException) {
            }
        }
    } catch (_: IOException) {
    }
}
