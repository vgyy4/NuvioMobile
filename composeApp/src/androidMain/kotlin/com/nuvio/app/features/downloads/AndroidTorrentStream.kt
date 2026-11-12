package com.nuvio.app.features.downloads

import android.content.Context
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.P2pStreamingState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_p2p_no_peers
import nuvio.composeapp.generated.resources.downloads_p2p_playback_active
import nuvio.composeapp.generated.resources.downloads_p2p_unavailable
import org.jetbrains.compose.resources.getString

/** A torrent download cannot run. Not an IOException, so it is never retried automatically. */
internal class TorrentTransferException(message: String) : Exception(message)

/** The disk cannot hold the file twice (engine cache + the downloaded copy). Never retried. */
internal class NotEnoughSpaceException(val neededBytes: Long, val freeBytes: Long) :
    Exception("Not enough free space")

private const val TORRENT_START_TIMEOUT_MS = 3L * 60L * 1_000L
private const val TORRENT_STOP_TIMEOUT_MS = 10_000L

/**
 * An open P2P engine stream for one torrent download. The engine serves one torrent at a time, so
 * [open] waits its turn: queued torrent downloads simply run one after another. The stream's
 * local HTTP URL is downloaded by the normal transfer code (resume, retry, progress and all).
 *
 * [close] stops the stream and clears the engine's cache, so a finished, paused or cancelled
 * download does not leave a second copy of the file on the device.
 */
internal class AndroidTorrentStream private constructor(val localUrl: String) {
    suspend fun close() = withContext(NonCancellable) {
        try {
            releaseEngine()
        } finally {
            engineLock.unlock()
        }
    }

    companion object {
        private val engineLock = Mutex()

        suspend fun open(context: Context, item: DownloadItem): AndroidTorrentStream {
            val infoHash = item.p2pInfoHash ?: throw TorrentTransferException(getString(Res.string.download_failed))
            engineLock.lock()
            var engineStarted = false
            var opened = false
            try {
                P2pStreamingEngine.initialize(context.applicationContext)
                P2pSettingsStorage.initialize(context.applicationContext)
                P2pSettingsRepository.ensureLoaded()
                if (!AppFeaturePolicy.p2pEnabled || !P2pSettingsRepository.uiState.value.p2pEnabled) {
                    throw TorrentTransferException(getString(Res.string.downloads_p2p_unavailable))
                }
                val state = P2pStreamingEngine.state.value
                if (state is P2pStreamingState.Connecting || state is P2pStreamingState.Streaming) {
                    // Playback owns the engine. Never stop its stream.
                    throw TorrentTransferException(getString(Res.string.downloads_p2p_playback_active))
                }
                engineStarted = true
                val localUrl = withTimeoutOrNull(TORRENT_START_TIMEOUT_MS) {
                    P2pStreamingEngine.startStream(
                        P2pStreamRequest(
                            infoHash = infoHash,
                            fileIdx = item.p2pFileIdx,
                            filename = item.p2pFilename,
                            trackers = item.p2pTrackers,
                        ),
                    )
                } ?: throw TorrentTransferException(getString(Res.string.downloads_p2p_no_peers))
                opened = true
                return AndroidTorrentStream(localUrl)
            } finally {
                if (!opened) {
                    withContext(NonCancellable) {
                        try {
                            if (engineStarted) releaseEngine()
                        } finally {
                            engineLock.unlock()
                        }
                    }
                }
            }
        }

        /** Suspends until no torrent transfer holds the engine. */
        suspend fun awaitIdle() {
            engineLock.withLock { }
        }

        private suspend fun releaseEngine() {
            P2pStreamingEngine.stopStream()
            withTimeoutOrNull(TORRENT_STOP_TIMEOUT_MS) {
                P2pStreamingEngine.state.first {
                    it is P2pStreamingState.Idle || it is P2pStreamingState.Error
                }
            }
            runCatching { P2pStreamingEngine.clearCache() }
        }
    }
}
