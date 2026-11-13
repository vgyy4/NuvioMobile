package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem

/*
 * Pure decisions behind sharing one downloaded file between profiles.
 *
 * Every profile keeps its own list of downloads, but all files live in one shared folder. Several
 * records (in different profiles) may therefore point to the same `fileName`. A file, its partial
 * file, its subtitles and its skip times may only be deleted when no other record points to it.
 */

/**
 * Whether a record other than the one identified by [ownerProfileId] / [ownerItemId] still points to
 * the file called [fileName], looking at the downloads of every profile in [itemsByProfile].
 *
 * Deleting is only allowed when this is false. A blank [fileName] cannot be matched to anything, so
 * it counts as referenced: when unsure, the file is kept.
 */
internal fun isFileReferencedElsewhere(
    fileName: String,
    ownerProfileId: Int,
    ownerItemId: String,
    itemsByProfile: Map<Int, List<DownloadItem>>,
): Boolean {
    val name = fileName.trim()
    if (name.isEmpty()) return true
    return itemsByProfile.any { (profileId, items) ->
        items.any { item ->
            item.fileName.trim() == name && !(profileId == ownerProfileId && item.id == ownerItemId)
        }
    }
}

/** How [DownloadsRepository.enqueueFromStream] treats a download of the same content in another profile. */
enum class DownloadSharingMode {
    /** Share the existing file only when it comes from the same source (the default). */
    IfSameSource,

    /** Share the existing file whatever its source is ("use the same file" was chosen). */
    Always,

    /** Download a separate file even if another profile has one ("download this source anyway"). */
    Never,
}

/**
 * Whether the download [existing] was made from the same source as [stream]:
 * - both are torrents: the same info hash and file index;
 * - both are HTTP links: the same link, otherwise the same add-on offering the same release (same
 *   name and description);
 * - one torrent and one HTTP link: only the add-on, name and description can tell, so they are
 *   compared as above.
 * An add-on id that is unknown never matches, because then the release cannot be identified.
 */
internal fun isSameDownloadSource(existing: DownloadItem, stream: StreamItem): Boolean {
    val directUrl = stream.playableDirectUrl?.trim()?.takeIf { it.isNotEmpty() }
    val streamInfoHash = if (directUrl == null && stream.isTorrentStream) {
        stream.p2pInfoHash?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    if (existing.isP2p && streamInfoHash != null) {
        return existing.p2pInfoHash.orEmpty().trim().equals(streamInfoHash, ignoreCase = true) &&
            existing.p2pFileIdx == stream.p2pFileIdx
    }
    if (!existing.isP2p && directUrl != null && existing.sourceUrl.trim() == directUrl) return true

    val existingAddonId = existing.providerAddonId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return existingAddonId == stream.addonId.trim() &&
        existing.streamTitle.trim() == stream.streamLabel.trim() &&
        existing.streamSubtitle.normalizedSourceText() == stream.streamSubtitle.normalizedSourceText()
}

private fun String?.normalizedSourceText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * A download of the same content that another profile made (or is making) and that can be shared.
 * [item] is a copy of the stored record; [isComplete] tells whether its file is there and finished,
 * [isSameSource] whether it came from the same source as the stream asked for.
 */
internal data class OtherProfileDownload(
    val profileId: Int,
    val item: DownloadItem,
    val isComplete: Boolean,
    val isSameSource: Boolean,
)

/**
 * Picks the download to share out of the [entries] (profile id and record) of the other profiles.
 * Only records of the content [logicalContentKey] count: finished ones whose file still exists
 * ([fileExists]) and, when [allowInProgress] is set, running or paused ones. Records that merely
 * follow another profile's running download are never picked, the one they follow is. Preferred, in
 * this order: the same source as [stream] (when given), then finished before running. The first of
 * equally good records wins.
 */
internal fun chooseOtherProfileDownload(
    logicalContentKey: String,
    stream: StreamItem?,
    entries: List<Pair<Int, DownloadItem>>,
    fileExists: (DownloadItem) -> Boolean,
    allowInProgress: Boolean,
): OtherProfileDownload? =
    entries
        .mapNotNull { (profileId, item) ->
            if (item.logicalContentKey != logicalContentKey) return@mapNotNull null
            val isComplete = when {
                item.status == DownloadStatus.Completed -> {
                    if (!fileExists(item)) return@mapNotNull null
                    true
                }
                allowInProgress &&
                    item.sharedFromDownloadId == null &&
                    (item.status == DownloadStatus.Downloading || item.status == DownloadStatus.Paused) -> false
                else -> return@mapNotNull null
            }
            OtherProfileDownload(
                profileId = profileId,
                item = item,
                isComplete = isComplete,
                isSameSource = stream != null && isSameDownloadSource(item, stream),
            )
        }
        .minWithOrNull(compareBy<OtherProfileDownload>({ !it.isSameSource }, { !it.isComplete }))
