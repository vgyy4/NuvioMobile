@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.network

import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

internal actual object NetworkPathMonitor {
    // Kept so the system object stays alive for as long as the app runs.
    private var monitor: Any? = null

    actual fun start(onChange: (connected: Boolean) -> Unit) {
        if (monitor != null) return
        val created = nw_path_monitor_create()
        monitor = created
        nw_path_monitor_set_queue(created, dispatch_queue_create("com.nuvio.app.network-path", null))
        nw_path_monitor_set_update_handler(created) { path ->
            onChange(path != null && nw_path_get_status(path) == nw_path_status_satisfied)
        }
        nw_path_monitor_start(created)
    }
}
