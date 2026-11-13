package com.nuvio.app.core.ui

/**
 * Coarse device-capability flag used to swap the most GPU-expensive effects for cheaper ones on
 * old hardware (for example an iPad Air 2). Capable devices are never affected.
 */
internal expect object DevicePerformance {
    val isLowEnd: Boolean
}
