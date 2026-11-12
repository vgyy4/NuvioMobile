package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerStreams
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun AddonManifest.supportsStream(type: String, videoId: String): Boolean =
    supportsResource("stream", type, videoId)

internal fun hasCompatiblePlaybackSource(
    addons: List<ManagedAddon>,
    plugins: PluginsUiState,
    type: String,
    videoId: String,
): Boolean = addons.any { it.enabled && it.manifest?.supportsStream(type, videoId) == true } ||
    (plugins.pluginsEnabled && plugins.scrapers.any { it.enabled && it.supportsType(type) })

internal class PlaybackAvailability(
    private val addons: List<ManagedAddon>,
    private val plugins: PluginsUiState,
) {
    fun canStream(type: String, videoId: String): Boolean =
        hasCompatiblePlaybackSource(addons, plugins, type, videoId) ||
            MetaDetailsRepository.findEmbeddedStreams(videoId).isNotEmpty() ||
            ServerStreams.canServe(type, videoId)

    fun canPlay(
        type: String,
        videoId: String,
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): Boolean = canStream(type, videoId) || DownloadsRepository.findPlayableDownload(
        parentMetaId = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        videoId = videoId,
    ) != null

    companion object {
        fun current(): PlaybackAvailability = PlaybackAvailability(
            addons = AddonRepository.uiState.value.addons,
            plugins = if (AppFeaturePolicy.pluginsEnabled) {
                PluginRepository.uiState.value
            } else {
                PluginsUiState(pluginsEnabled = false)
            },
        )
    }
}

@Composable
internal fun rememberPlaybackAvailability(): PlaybackAvailability {
    val addons by remember {
        AddonRepository.initialize()
        AddonRepository.uiState
    }.collectAsStateWithLifecycle()
    val plugins = if (AppFeaturePolicy.pluginsEnabled) {
        val state by remember {
            PluginRepository.initialize()
            PluginRepository.uiState
        }.collectAsStateWithLifecycle()
        state
    } else {
        PluginsUiState(pluginsEnabled = false)
    }
    // Playback only depends on which downloads are complete; progress ticks must not rebuild this.
    val completedDownloadsFlow = remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
            .map { state -> state.items.count { it.status == DownloadStatus.Completed } }
            .distinctUntilChanged()
    }
    val completedDownloadCount by completedDownloadsFlow.collectAsStateWithLifecycle(
        initialValue = remember(completedDownloadsFlow) {
            DownloadsRepository.uiState.value.items.count { it.status == DownloadStatus.Completed }
        },
    )
    val servers by remember {
        ServerRepository.ensureLoaded()
        ServerRepository.uiState
    }.collectAsStateWithLifecycle()
    return remember(addons, plugins, completedDownloadCount, servers.revision) {
        PlaybackAvailability(addons.addons, plugins)
    }
}
