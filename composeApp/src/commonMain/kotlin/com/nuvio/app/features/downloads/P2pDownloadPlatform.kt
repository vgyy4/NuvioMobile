package com.nuvio.app.features.downloads

/**
 * Platform capabilities needed to download torrents through the P2P engine.
 */
internal expect object P2pDownloadPlatform {
    /** Whether this platform can download torrents at all. */
    val isSupported: Boolean

    /**
     * True when the platform downloader runs the torrent stream itself (Android, inside its
     * background job). False when [DownloadsRepository] has to run it while the app is open (iOS).
     */
    val runsInPlatformDownloader: Boolean

    /** Free bytes on the volume that holds downloads and the engine cache, or null if unknown. */
    fun freeDiskSpaceBytes(): Long?
}
