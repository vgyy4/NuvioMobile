package com.nuvio.app.features.downloads

import android.os.Environment
import android.os.StatFs

internal actual object P2pDownloadPlatform {
    actual val isSupported: Boolean = true

    // The scheduler's background job opens the torrent stream itself, so torrent downloads keep
    // going with the app in the background and resume after the process is restarted.
    actual val runsInPlatformDownloader: Boolean = true

    actual fun freeDiskSpaceBytes(): Long? =
        runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull()

    actual suspend fun awaitTorrentTransfersStopped() {
        AndroidTorrentStream.awaitIdle()
    }
}
