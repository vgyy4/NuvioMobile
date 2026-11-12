package com.nuvio.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

internal actual object NetworkPathMonitor {
    private var appContext: Context? = null
    private var started = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun start(onChange: (connected: Boolean) -> Unit): Boolean {
        if (started) return true
        val manager = appContext?.getSystemService(ConnectivityManager::class.java) ?: return false

        // Asking the system what is active now (instead of trusting the order of the events)
        // keeps a switch from Wi-Fi to mobile data from looking like a loss of connection.
        fun hasConnection(): Boolean {
            val network = manager.activeNetwork ?: return false
            return manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }

        return try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChange(hasConnection())
                override fun onLost(network: Network) = onChange(hasConnection())
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    onChange(hasConnection())
            })
            started = true
            onChange(hasConnection())
            true
        } catch (_: Exception) {
            // Some devices refuse the registration (for example when the limit of callbacks is hit).
            false
        }
    }
}
