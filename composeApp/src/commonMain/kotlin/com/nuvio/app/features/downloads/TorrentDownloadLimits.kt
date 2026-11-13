package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.DevicePerformance
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * How many torrents download at the same time. More wait their turn in the order they were
 * started. Fewer on low-end devices, where several torrents at once would starve everything else.
 */
internal fun maxConcurrentTorrentDownloads(): Int = if (DevicePerformance.isLowEnd) 2 else 3

internal class TorrentDiskShortfall(val neededBytes: Long, val freeBytes: Long)

/**
 * Keeps concurrent torrent downloads from all counting on the same free space. While a torrent
 * downloads, the engine caches the file as well, so about twice the not-yet-written size is
 * spoken for. A new download only starts if its need fits on top of what the running ones still
 * have to write.
 */
internal object TorrentDiskBudget {
    // Headroom so the device is never filled up completely.
    const val RESERVE_BYTES = 256L * 1024L * 1024L

    private val lock = SynchronizedObject()
    private val unwrittenBytes = mutableMapOf<String, Long>()

    /** Registers [downloadId] if it fits in [freeBytes]; otherwise returns what is missing. */
    fun reserve(downloadId: String, totalBytes: Long, downloadedBytes: Long, freeBytes: Long?): TorrentDiskShortfall? =
        synchronized(lock) {
            val own = (totalBytes - downloadedBytes).coerceAtLeast(0L) * 2L
            if (freeBytes != null) {
                val others = unwrittenBytes.entries.filter { it.key != downloadId }.sumOf { it.value }
                val needed = own + others + RESERVE_BYTES
                if (freeBytes < needed) return@synchronized TorrentDiskShortfall(needed, freeBytes)
            }
            unwrittenBytes[downloadId] = own
            null
        }

    fun update(downloadId: String, totalBytes: Long, downloadedBytes: Long) {
        synchronized(lock) {
            if (downloadId in unwrittenBytes) {
                unwrittenBytes[downloadId] = (totalBytes - downloadedBytes).coerceAtLeast(0L) * 2L
            }
        }
    }

    fun release(downloadId: String) {
        synchronized(lock) { unwrittenBytes.remove(downloadId) }
    }
}
