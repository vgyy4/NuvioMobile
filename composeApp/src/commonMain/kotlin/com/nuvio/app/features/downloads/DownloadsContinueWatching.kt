package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/** A downloaded video that Home lists in Continue watching, with how far it got (0..1). */
internal class DownloadedContinueEntry(val item: DownloadItem, val progress: Float)

/**
 * Keeps the entries of Home's Continue watching list whose video is downloaded, in Home's order.
 * A next-up entry therefore only shows when the next episode itself is downloaded.
 */
internal fun downloadedContinueWatchingEntries(
    continueWatching: List<ContinueWatchingItem>,
    completedDownloads: List<DownloadItem>,
): List<DownloadedContinueEntry> {
    val playable = completedDownloads.filter(DownloadItem::isPlayable)
    return continueWatching.mapNotNull { entry ->
        val download = playable.downloadedVideoFor(entry) ?: return@mapNotNull null
        DownloadedContinueEntry(download, entry.progressFraction)
    }.distinctBy { it.item.id }
}

/** The downloaded file of the video a Continue watching entry points at (same rules as playback). */
internal fun List<DownloadItem>.downloadedVideoFor(entry: ContinueWatchingItem): DownloadItem? {
    val videoId = entry.videoId.trim()
    if (videoId.isNotBlank()) {
        firstOrNull { it.videoId == videoId }?.let { return it }
    }
    val parentMetaId = entry.parentMetaId.trim()
    return if (entry.seasonNumber != null && entry.episodeNumber != null) {
        firstOrNull {
            it.parentMetaId == parentMetaId &&
                it.seasonNumber == entry.seasonNumber &&
                it.episodeNumber == entry.episodeNumber
        }
    } else {
        firstOrNull {
            it.parentMetaId == parentMetaId && it.seasonNumber == null && it.episodeNumber == null
        }
    }
}

@Composable
internal fun DownloadsContinueWatchingRow(
    entries: List<DownloadedContinueEntry>,
    onOpen: (DownloadItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.item.id }) { entry ->
            val item = entry.item
            Column(
                modifier = Modifier
                    .width(190.dp)
                    .clickable { onOpen(item) },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    val image = item.episodeThumbnail ?: item.background ?: item.poster
                    if (image != null) {
                        AsyncImage(
                            model = image,
                            contentDescription = item.title,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { entry.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                if (item.isEpisode) {
                    Text(
                        text = localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
