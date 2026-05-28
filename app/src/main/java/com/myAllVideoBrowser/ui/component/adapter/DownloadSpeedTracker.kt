package com.myAllVideoBrowser.ui.component.adapter

import android.os.SystemClock
import com.myAllVideoBrowser.util.FileUtil

/**
 * Lightweight in-memory speed tracker for the progress list.
 *
 * The repository / workers do not surface an instantaneous download speed,
 * so we approximate it from the rate of change of [progressDownloaded] across
 * adapter rebinds. The tracker is intentionally process-local and recovers
 * gracefully across rotations (the UI just shows "—" until two samples land).
 */
internal object DownloadSpeedTracker {

    private data class Sample(val bytes: Long, val timestampMs: Long, val speedBps: Double)

    /** Smoothing factor for the exponential moving average. */
    private const val ALPHA = 0.35

    /** Minimum elapsed window between samples to avoid divide-by-zero noise. */
    private const val MIN_DELTA_MS = 250L

    /** Window after which a stale sample is discarded (e.g. paused for a long time). */
    private const val STALE_AFTER_MS = 30_000L

    private val samples = HashMap<Long, Sample>()

    /**
     * Record a fresh observation for [downloadId] and return the current
     * smoothed download speed in bytes / second. Returns `null` while the
     * tracker has not yet collected enough data points.
     */
    @Synchronized
    fun update(downloadId: Long, currentBytes: Long): Double? {
        val now = SystemClock.elapsedRealtime()
        val previous = samples[downloadId]

        if (previous == null) {
            samples[downloadId] = Sample(currentBytes, now, 0.0)
            return null
        }

        val deltaMs = now - previous.timestampMs
        val deltaBytes = currentBytes - previous.bytes

        // No measurable progress yet — keep the previous sample so the next
        // bind can still produce a reading.
        if (deltaMs < MIN_DELTA_MS) {
            return previous.speedBps.takeIf { it > 0 }
        }

        // Bytes counter rolled back (cancel + restart) — reset baseline.
        if (deltaBytes < 0) {
            samples[downloadId] = Sample(currentBytes, now, 0.0)
            return null
        }

        // Stale sample (e.g. paused for a while). Reset the baseline rather
        // than report a misleadingly tiny number.
        if (deltaMs > STALE_AFTER_MS) {
            samples[downloadId] = Sample(currentBytes, now, 0.0)
            return null
        }

        val instantaneous = deltaBytes * 1000.0 / deltaMs
        val smoothed = if (previous.speedBps <= 0.0) {
            instantaneous
        } else {
            ALPHA * instantaneous + (1 - ALPHA) * previous.speedBps
        }

        samples[downloadId] = Sample(currentBytes, now, smoothed)
        return smoothed
    }

    /** Reset state for a download (call when it is removed). */
    @Synchronized
    fun forget(downloadId: Long) {
        samples.remove(downloadId)
    }

    /** Format a bytes-per-second number for the UI. Returns `null` for unknown. */
    fun format(bytesPerSec: Double?): String? {
        if (bytesPerSec == null || bytesPerSec <= 0.0) return null
        return FileUtil.getFileSizeReadable(bytesPerSec) + "/s"
    }
}
