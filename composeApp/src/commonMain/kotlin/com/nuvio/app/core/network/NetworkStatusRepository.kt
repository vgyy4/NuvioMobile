package com.nuvio.app.core.network

import androidx.compose.runtime.Composable
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_check_connection
import nuvio.composeapp.generated.resources.details_servers_unreachable
import nuvio.composeapp.generated.resources.network_cannot_reach_servers
import nuvio.composeapp.generated.resources.network_connection_issue
import nuvio.composeapp.generated.resources.network_no_internet_connection
import nuvio.composeapp.generated.resources.network_please_check_connection
import org.jetbrains.compose.resources.stringResource

enum class NetworkCondition {
    Unknown,
    Checking,
    Online,
    NoInternet,
    ServersUnreachable,
}

data class NetworkStatusUiState(
    val condition: NetworkCondition = NetworkCondition.Unknown,
) {
    val isOnline: Boolean
        get() = condition == NetworkCondition.Online

    val isOfflineLike: Boolean
        get() = condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

@Composable
fun NetworkCondition.titleForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.network_cannot_reach_servers)
        NetworkCondition.NoInternet -> stringResource(Res.string.network_no_internet_connection)
        else -> stringResource(Res.string.network_connection_issue)
    }

@Composable
fun NetworkCondition.messageForEmptyState(): String =
    when (this) {
        NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
        NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
        else -> stringResource(Res.string.network_please_check_connection)
    }

object NetworkStatusRepository {
    private const val REQUEST_TIMEOUT_MS = 4_500L
    private const val FOREGROUND_REFRESH_DELAY_MS = 6_000L
    private const val FOREGROUND_FAILURE_CONFIRM_DELAY_MS = 2_000L
    private const val NATIVE_FIRST_REPORT_TIMEOUT_MS = 5_000L
    private const val OFFLINE_VERIFY_DELAY_MS = 2_500L
    private const val FALLBACK_OFFLINE_BASE_DELAY_MS = 10_000L
    private const val FALLBACK_OFFLINE_MAX_DELAY_MS = 60_000L
    private const val FALLBACK_ONLINE_DELAY_MS = 90_000L
    private const val PUBLIC_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
    private const val PUBLIC_PROBE_FALLBACK = "https://cloudflare.com/cdn-cgi/trace"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(NetworkStatusUiState())
    val uiState: StateFlow<NetworkStatusUiState> = _uiState.asStateFlow()

    private var started = false
    private var probeInFlight = false
    private var pendingProbeAfterCurrent = false
    private var pendingProbeConfirmFailures = false
    private var foregroundRefreshJob: Job? = null

    fun ensureStarted() {
        if (started) return
        started = true
        // The system tells us when the connection comes or goes, so normally nothing polls. If
        // that listener cannot be set up, or never reports anything, a bounded fallback takes over.
        val listening = runCatching { NetworkPathMonitor.start(::onNetworkPathChanged) }.getOrDefault(false)
        if (listening) {
            // A working system listener reports the current state right away. Silence means it is
            // not working.
            scope.launch {
                delay(NATIVE_FIRST_REPORT_TIMEOUT_MS)
                if (!nativeReportSeen) startFallbackChecks()
            }
        } else {
            startFallbackChecks()
        }
        requestRefresh(force = true)
    }

    @kotlin.concurrent.Volatile
    private var nativeReportSeen = false
    private var offlineVerifyJob: Job? = null
    private var fallbackJob: Job? = null

    private fun onNetworkPathChanged(connected: Boolean) {
        nativeReportSeen = true
        // The system listener works after all: no fallback needed.
        fallbackJob?.cancel()
        fallbackJob = null

        if (!connected) {
            // No network at all: nothing to probe, switch immediately.
            _uiState.value = NetworkStatusUiState(condition = NetworkCondition.NoInternet)
            // One confirming check shortly after, in case the report was wrong (for example a
            // path that is "unsatisfied" while a VPN still works). It runs once, not repeatedly.
            offlineVerifyJob?.cancel()
            offlineVerifyJob = scope.launch {
                delay(OFFLINE_VERIFY_DELAY_MS)
                requestRefresh(force = true)
            }
        } else {
            offlineVerifyJob?.cancel()
            // A network is available again: confirm once that the internet is really reachable
            // (a captive portal or a dead router would still count as a "path").
            requestRefresh(force = true)
        }
    }

    /**
     * Only used when the system listener does not work. While offline it re-checks with growing
     * pauses (10 s up to 60 s) until the connection is back; while online it checks every 90 s so
     * a lost connection is still noticed. Foreground refreshes keep working as before.
     */
    private fun startFallbackChecks() {
        if (fallbackJob?.isActive == true) return
        fallbackJob = scope.launch {
            var offlineChecks = 0
            while (true) {
                val offline = _uiState.value.isOfflineLike
                val wait = if (offline) {
                    (FALLBACK_OFFLINE_BASE_DELAY_MS shl minOf(offlineChecks, 3)).coerceAtMost(FALLBACK_OFFLINE_MAX_DELAY_MS)
                } else {
                    FALLBACK_ONLINE_DELAY_MS
                }
                offlineChecks = if (offline) offlineChecks + 1 else 0
                delay(wait)
                requestRefresh(force = true)
            }
        }
    }

    fun requestForegroundRefresh() {
        ensureStarted()
        foregroundRefreshJob?.cancel()
        foregroundRefreshJob = scope.launch {
            delay(FOREGROUND_REFRESH_DELAY_MS)
            requestRefresh(force = true, confirmFailures = true)
        }
    }

    fun requestRefresh(force: Boolean = false, confirmFailures: Boolean = false) {
        if (!started) started = true
        if (probeInFlight) {
            if (force) {
                pendingProbeAfterCurrent = true
                pendingProbeConfirmFailures = pendingProbeConfirmFailures || confirmFailures
            }
            return
        }

        scope.launch {
            var nextConfirmFailures = confirmFailures
            do {
                val runConfirmFailures = nextConfirmFailures || pendingProbeConfirmFailures
                nextConfirmFailures = false
                pendingProbeAfterCurrent = false
                pendingProbeConfirmFailures = false
                probeInFlight = true
                runProbe(confirmFailures = runConfirmFailures)
                probeInFlight = false
            } while (pendingProbeAfterCurrent)
        }
    }

    private suspend fun runProbe(confirmFailures: Boolean) {
        if (_uiState.value.condition == NetworkCondition.Unknown) {
            _uiState.value = NetworkStatusUiState(condition = NetworkCondition.Checking)
        }

        val previousCondition = _uiState.value.condition
        var nextCondition = probeCondition()
        if (
            confirmFailures &&
            previousCondition == NetworkCondition.Online &&
            nextCondition.isOfflineLike()
        ) {
            delay(FOREGROUND_FAILURE_CONFIRM_DELAY_MS)
            nextCondition = probeCondition()
        }

        _uiState.value = NetworkStatusUiState(condition = nextCondition)
    }

    private suspend fun probeCondition(): NetworkCondition {
        val internetReachable = probePublicInternet()
        if (!internetReachable) {
            return NetworkCondition.NoInternet
        }

        val supabaseReachable = SupabaseEndpointConfig.restEndpointUrls().any { url ->
            probeReachable(
                url = url,
                headers = mapOf("apikey" to ServerConfigurationRepository.active.value.publishableKey),
            )
        }
        if (!supabaseReachable) {
            return NetworkCondition.ServersUnreachable
        }

        return NetworkCondition.Online
    }

    private suspend fun probePublicInternet(): Boolean =
        probeReachable(PUBLIC_PROBE_PRIMARY) || probeReachable(PUBLIC_PROBE_FALLBACK)

    private suspend fun probeReachable(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean {
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers,
                    body = "",
                )
            }.getOrNull()
        } ?: return false

        return response.status in 100..599
    }

    private fun NetworkCondition.isOfflineLike(): Boolean =
        this == NetworkCondition.NoInternet || this == NetworkCondition.ServersUnreachable
}
