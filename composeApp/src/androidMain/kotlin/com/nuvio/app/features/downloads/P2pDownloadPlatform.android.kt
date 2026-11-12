package com.nuvio.app.features.downloads

internal actual object P2pDownloadPlatform {
    // Android downloads run in a background job that persists the source URL and can be restarted
    // after the process dies, when the engine's local stream URL no longer exists.
    actual val isSupported: Boolean = false

    actual fun freeDiskSpaceBytes(): Long? = null
}
