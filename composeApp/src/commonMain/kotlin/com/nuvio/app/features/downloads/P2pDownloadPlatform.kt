package com.nuvio.app.features.downloads

/**
 * Platform capabilities needed to download torrents through the P2P engine.
 */
internal expect object P2pDownloadPlatform {
    /**
     * Whether torrent downloads are safe on this platform. Torrent downloads copy the engine's
     * local stream to a file, which only works while the app (and so the engine) is running.
     * Platforms whose downloader keeps running or restarts independently of the app cannot
     * guarantee that, so they report false.
     */
    val isSupported: Boolean

    /** Free bytes on the volume that holds downloads and the engine cache, or null if unknown. */
    fun freeDiskSpaceBytes(): Long?
}
