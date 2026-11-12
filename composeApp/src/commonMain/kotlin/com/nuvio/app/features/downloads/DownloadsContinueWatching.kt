package com.nuvio.app.features.downloads

import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/**
 * An entry of Home's Continue watching list whose video is downloaded. [item] is what Home shows
 * for it (with artwork filled in from the download when Home has none), [download] is the file.
 */
internal class DownloadedContinueEntry(val item: ContinueWatchingItem, val download: DownloadItem)

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
        DownloadedContinueEntry(entry.withDownloadedFallbacks(download), download)
    }.distinctBy { it.download.id }
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

/** Offline, Home's cache can be missing artwork or a title that the download still carries. */
private fun ContinueWatchingItem.withDownloadedFallbacks(download: DownloadItem): ContinueWatchingItem {
    val hasTitle = title.isNotBlank() && !title.equals(parentMetaId, ignoreCase = true)
    return copy(
        title = if (hasTitle) title else download.title,
        imageUrl = imageUrl.orFallback(download.episodeThumbnail ?: download.background ?: download.poster),
        logo = logo.orFallback(download.logo),
        poster = poster.orFallback(download.poster),
        background = background.orFallback(download.background),
        episodeThumbnail = episodeThumbnail.orFallback(download.episodeThumbnail),
    )
}

private fun String?.orFallback(fallback: String?): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
