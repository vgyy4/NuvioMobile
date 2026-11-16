package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_cd_downloaded
import nuvio.composeapp.generated.resources.episodes_cd_downloading
import org.jetbrains.compose.resources.stringResource

/** What the episode cards show about an episode's download. */
internal enum class EpisodeDownloadState { None, InProgress, Done }

/**
 * The download state of every episode of [parentMetaId] that has a download in the active profile,
 * keyed like [stateFor]. Recomposes only when the downloads list changes.
 */
@Composable
internal fun rememberEpisodeDownloadStates(parentMetaId: String): Map<String, EpisodeDownloadState> {
    remember { DownloadsRepository.ensureLoaded() }
    val uiState by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    return remember(uiState.items, parentMetaId) {
        val wanted = parentMetaId.trim()
        buildMap {
            uiState.items.forEach { item ->
                val season = item.seasonNumber ?: return@forEach
                val episode = item.episodeNumber ?: return@forEach
                if (item.parentMetaId.trim() != wanted) return@forEach
                val state = when {
                    item.isPlayable -> EpisodeDownloadState.Done
                    item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused ->
                        EpisodeDownloadState.InProgress
                    else -> EpisodeDownloadState.None
                }
                if (state == EpisodeDownloadState.None) return@forEach
                val key = "$season:$episode"
                // A finished copy wins over a second, unfinished one of the same episode.
                if (this[key] != EpisodeDownloadState.Done) put(key, state)
            }
        }
    }
}

internal fun Map<String, EpisodeDownloadState>.stateFor(video: MetaVideo): EpisodeDownloadState =
    this["${video.season}:${video.episode}"] ?: EpisodeDownloadState.None

/** Small round badge: a "downloaded" tick-arrow for finished downloads, an arrow while one runs. */
@Composable
internal fun EpisodeDownloadBadge(
    state: EpisodeDownloadState,
    modifier: Modifier = Modifier,
) {
    if (state == EpisodeDownloadState.None) return
    val done = state == EpisodeDownloadState.Done
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (done) Icons.Filled.DownloadDone else Icons.Filled.Downloading,
            contentDescription = stringResource(
                if (done) Res.string.episodes_cd_downloaded else Res.string.episodes_cd_downloading,
            ),
            tint = if (done) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(14.dp),
        )
    }
}
