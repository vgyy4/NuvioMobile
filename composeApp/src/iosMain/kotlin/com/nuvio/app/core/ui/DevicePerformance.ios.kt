package com.nuvio.app.core.ui

import platform.Foundation.NSProcessInfo

// Same cut-off the torrent engine uses: devices with <= 2.5 GiB of RAM (iPad Air 2, iPhone 6s/7/8 ...).
private const val LowEndRamThresholdBytes = 2_684_354_560uL
// 1 GB devices (iPad mini 2): <= 1.25 GiB.
private const val VeryLowEndRamThresholdBytes = 1_342_177_280uL

internal actual object DevicePerformance {
    actual val isLowEnd: Boolean = NSProcessInfo.processInfo.physicalMemory <= LowEndRamThresholdBytes
    actual val isVeryLowEnd: Boolean = NSProcessInfo.processInfo.physicalMemory <= VeryLowEndRamThresholdBytes
}
