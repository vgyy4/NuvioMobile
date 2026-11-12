package com.nuvio.app.features.downloads

import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory

internal actual object P2pDownloadPlatform {
    actual val isSupported: Boolean = true

    actual fun freeDiskSpaceBytes(): Long? {
        val attributes = NSFileManager.defaultManager
            .attributesOfFileSystemForPath(NSHomeDirectory(), error = null)
        return when (val value = attributes?.get("NSFileSystemFreeSize")) {
            is Long -> value
            is Number -> value.toLong()
            else -> null
        }
    }
}
