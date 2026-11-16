package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.p2p.P2pDownloadStream
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_p2p_no_peers
import nuvio.composeapp.generated.resources.downloads_p2p_unavailable
import org.jetbrains.compose.resources.getString
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A torrent download cannot run. Not an IOException, so it is never retried automatically. */
internal class TorrentTransferException(message: String) : Exception(message)

/** The disk cannot hold the file twice (engine cache + the downloaded copy). Never retried. */
internal class NotEnoughSpaceException(val neededBytes: Long, val freeBytes: Long) :
    Exception("Not enough free space")

/** No peers answered in time. An IOException on purpose: the scheduler retries those. */
internal class TorrentNoPeersException(message: String) : IOException(message)

private const val TORRENT_START_TIMEOUT_MS = 3L * 60L * 1_000L

/**
 * An open P2P engine stream for one torrent download. Several can be open at once; beyond
 * [maxConcurrentTorrentDownloads] they wait their turn in [open]. The stream's local HTTP URL is
 * downloaded by the normal transfer code (resume, retry, progress and all).
 *
 * [close] stops the stream and gives the engine's cache back, so a finished, paused or cancelled
 * download does not leave a second copy of the file on the device.
 */
internal class AndroidTorrentStream private constructor(
    private val downloadId: String,
    private val heldLock: Mutex?,
    private val stream: P2pDownloadStream,
) {
    val localUrl: String get() = stream.localUrl

    suspend fun close() = withContext(NonCancellable) {
        try {
            runCatching { P2pStreamingEngine.closeDownloadStream(stream) }
        } finally {
            TorrentDiskBudget.release(downloadId)
            slots.release()
            heldLock?.unlock()
        }
    }

    companion object {
        private val slots = Semaphore(maxConcurrentTorrentDownloads())

        // Only used while SAME_TORRENT_DOWNLOADS_IN_PARALLEL is off: then downloads of one torrent
        // (two episodes of a season pack) take turns; other torrents always run in parallel.
        private val torrentLocks = ConcurrentHashMap<String, Mutex>()
        private fun torrentLock(infoHash: String): Mutex =
            torrentLocks.getOrPut(infoHash.lowercase()) { Mutex() }

        suspend fun open(context: Context, item: DownloadItem): AndroidTorrentStream {
            val infoHash = item.p2pInfoHash ?: throw TorrentTransferException(getString(Res.string.download_failed))
            P2pStreamingEngine.initialize(context.applicationContext)
            P2pSettingsStorage.initialize(context.applicationContext)
            P2pSettingsRepository.ensureLoaded()
            if (!AppFeaturePolicy.p2pEnabled || !P2pSettingsRepository.uiState.value.p2pEnabled) {
                throw TorrentTransferException(getString(Res.string.downloads_p2p_unavailable))
            }
            // Wait for the torrent first, then for a free slot, so a queued episode never holds a
            // slot that another torrent could be using.
            val lock = if (SAME_TORRENT_DOWNLOADS_IN_PARALLEL) null else torrentLock(infoHash)
            lock?.lock()
            var slotTaken = false
            var opened = false
            try {
                slots.acquire()
                slotTaken = true
                val stream = withTimeoutOrNull(TORRENT_START_TIMEOUT_MS) {
                    P2pStreamingEngine.openDownloadStream(
                        P2pStreamRequest(
                            infoHash = infoHash,
                            fileIdx = item.p2pFileIdx,
                            filename = item.p2pFilename,
                            trackers = item.p2pTrackers,
                        ),
                    )
                } ?: throw TorrentNoPeersException(getString(Res.string.downloads_p2p_no_peers))
                opened = true
                return AndroidTorrentStream(item.id, lock, stream)
            } finally {
                if (!opened) {
                    if (slotTaken) slots.release()
                    lock?.unlock()
                }
            }
        }
    }
}
