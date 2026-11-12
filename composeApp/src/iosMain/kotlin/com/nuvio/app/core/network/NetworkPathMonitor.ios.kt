package com.nuvio.app.core.network

private var listener: ((Boolean) -> Unit)? = null
private var lastReported: Boolean? = null

/**
 * Called from Swift (`NetworkPathBridge`) with the system's connectivity state. The Swift side
 * starts at app launch, before the shared code asks for updates, so the latest state is kept and
 * handed over when [NetworkPathMonitor.start] is called.
 */
fun reportNativeNetworkPath(connected: Boolean) {
    lastReported = connected
    listener?.invoke(connected)
}

internal actual object NetworkPathMonitor {
    actual fun start(onChange: (connected: Boolean) -> Unit): Boolean {
        listener = onChange
        lastReported?.let(onChange)
        // If the Swift side never reports, the repository's watchdog switches to the fallback.
        return true
    }
}
