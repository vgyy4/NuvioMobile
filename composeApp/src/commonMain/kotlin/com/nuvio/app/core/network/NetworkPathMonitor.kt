package com.nuvio.app.core.network

/**
 * Tells the app, as it happens, when the device gains or loses a usable network path. It is driven
 * by the operating system's own change notifications, so nothing polls or probes in a loop.
 */
internal expect object NetworkPathMonitor {
    /**
     * Starts listening (once). [onChange] receives `true` when some network is available and
     * `false` when none is. It is called once with the current state right after starting.
     */
    fun start(onChange: (connected: Boolean) -> Unit)
}
