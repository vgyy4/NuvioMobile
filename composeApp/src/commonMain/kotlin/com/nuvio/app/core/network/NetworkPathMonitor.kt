package com.nuvio.app.core.network

/**
 * Tells the app, as it happens, when the device gains or loses a usable network path. It is driven
 * by the operating system's own change notifications, so nothing polls or probes in a loop.
 */
internal expect object NetworkPathMonitor {
    /**
     * Starts listening (once). [onChange] receives `true` when some network is available and
     * `false` when none is, normally once right away with the current state. Returns false when
     * the system listener could not be set up, so the caller can use a fallback.
     */
    fun start(onChange: (connected: Boolean) -> Unit): Boolean
}
