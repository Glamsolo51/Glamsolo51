package com.glamsolo.phonehop.core

/**
 * Accumulates byte counts and emits throttled [TransferProgress] snapshots with a
 * smoothed speed estimate. Not thread safe: driven by the single transfer loop.
 */
class ProgressTracker(
    private val manifest: TransferManifest,
    private val emitIntervalNanos: Long = 150_000_000L,
    private val clock: () -> Long = System::nanoTime,
    private val listener: (TransferProgress) -> Unit,
) {
    private var totalBytes = manifest.totalBytes
    private var bytesDone = 0L
    private var itemsDone = 0
    private var currentIndex = 0
    private val startedAt = clock()

    private var lastEmitAt = startedAt
    private var lastSampleAt = startedAt
    private var lastSampleBytes = 0L
    private var speed = 0.0

    fun startItem(index: Int) {
        currentIndex = index
        emit(force = true)
    }

    fun addBytes(count: Long) {
        bytesDone += count
        // The announced size may have been wrong (e.g. unknown size or a growing file).
        if (bytesDone > totalBytes) totalBytes = bytesDone
        emit(force = false)
    }

    /** Fixes the total once a part is complete and its real size is known. */
    fun partFinished(announcedSize: Long, actualSize: Long) {
        val announced = announcedSize.coerceAtLeast(0)
        if (announced != actualSize) {
            totalBytes = (totalBytes - announced + actualSize).coerceAtLeast(bytesDone)
        }
    }

    fun finishItem() {
        itemsDone++
        emit(force = true)
    }

    fun complete(): TransferStats {
        totalBytes = bytesDone
        emit(force = true)
        return TransferStats(bytesDone, itemsDone, (clock() - startedAt) / 1_000_000)
    }

    fun snapshot(): TransferProgress {
        val item = manifest.items.getOrNull(currentIndex)
        return TransferProgress(
            bytesDone = bytesDone,
            totalBytes = totalBytes,
            itemsDone = itemsDone,
            totalItems = manifest.items.size,
            currentItemId = item?.id,
            currentItemTitle = item?.title,
            bytesPerSecond = speed.toLong(),
        )
    }

    private fun emit(force: Boolean) {
        val now = clock()
        val sampleElapsed = now - lastSampleAt
        if (sampleElapsed >= emitIntervalNanos) {
            val instant = (bytesDone - lastSampleBytes) * 1_000_000_000.0 / sampleElapsed
            // Exponential smoothing keeps the number readable without hiding real changes.
            speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
            lastSampleAt = now
            lastSampleBytes = bytesDone
        }
        if (!force && now - lastEmitAt < emitIntervalNanos) return
        lastEmitAt = now
        listener(snapshot())
    }
}
