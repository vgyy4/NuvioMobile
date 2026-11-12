package com.nuvio.app.core.ui

import platform.Foundation.NSProcessInfo

// Same cut-off the torrent engine uses: devices with <= 2.5 GiB of RAM (iPad Air 2, iPhone 6s/7/8 ...).
private const val LowEndRamThresholdBytes = 2_684_354_560uL

internal actual object DevicePerformance {
    actual val isLowEnd: Boolean = NSProcessInfo.processInfo.physicalMemory <= LowEndRamThresholdBytes
}
