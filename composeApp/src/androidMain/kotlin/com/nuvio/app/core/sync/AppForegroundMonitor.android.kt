package com.nuvio.app.core.sync

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal actual object AppForegroundMonitor {
    private val mainHandler = Handler(Looper.getMainLooper())

    actual fun events(): Flow<AppVisibility> = callbackFlow {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> trySend(AppVisibility.Foreground)
                Lifecycle.Event.ON_STOP -> trySend(AppVisibility.Background)
                else -> Unit
            }
        }
        trySend(
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                AppVisibility.Foreground
            } else {
                AppVisibility.Background
            },
        )
        // Lifecycle.addObserver/removeObserver must run on the main thread. callbackFlow runs its
        // block (and awaitClose's cleanup) on whatever dispatcher first collects it - e.g.
        // DownloadsRepository.observeAppForeground() collects this on a plain Dispatchers.Default
        // scope - so that isn't guaranteed without posting explicitly. Reading currentState above
        // is fine off-main; only add/removeObserver enforce the check.
        runOnMainThread { lifecycle.addObserver(observer) }
        awaitClose {
            runOnMainThread { lifecycle.removeObserver(observer) }
        }
    }.distinctUntilChanged()

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
