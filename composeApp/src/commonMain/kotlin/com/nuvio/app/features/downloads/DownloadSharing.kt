package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem

/*
 * Pure decisions behind sharing one downloaded file between profiles.
 *
 * Every profile keeps its own list of downloads, but all files live in one shared folder. Several
 * records (in different profiles) may therefore point to the same `fileName`. A file, its partial
 * file, its subtitles and its skip times may only be deleted when no other record points to it.
 *
 * Invariants that [DownloadsRepository] keeps:
 * - Identity: the content is `DownloadItem.logicalContentKey`, a physical file is its `fileName`
 *   (it contains a download id, so different downloads never collide). Watch progress and watched
 *   state are not part of the downloads and stay per profile.
 * - Deleting: every delete, replace or cancel first asks [isFileReferencedElsewhere]; when another
 *   record still points to the file only the record goes. Platform clean-ups that delete files on
 *   their own (orphan sweeps) only touch partial files, resume data and subtitle folders of file
 *   names that no profile knows.
 * - Transfers: at most one record per file name owns a transfer (the leader, `sharedFromDownloadId`
 *   null). Followers (`sharedFromDownloadId` set) have no handle, never call the platform downloader
 *   and are never restored on their own when a list is loaded. They are re-pointed together when
 *   leadership changes, so the platform's per-file-name bookkeeping (Android's transfer store, iOS'
 *   background tasks and resume data) only ever sees one active owner of a file name.
 * - Disk budget: a torrent only reserves space while it runs, and only leaders run.
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

/** A download record: the profile whose list it is in and its id. */
internal data class DownloadRef(val profileId: Int, val itemId: String)

/** [newLeader] takes over a running download; the [remainingFollowers] then follow it. */
internal data class LeaderPromotion(
    val newLeader: DownloadRef,
    val remainingFollowers: List<DownloadRef>,
)

/**
 * Who takes over a running download whose leader [removed] stops leading (it is deleted or
 * replaced): the first of the [followers], in the order they started following. The others keep
 * following, now the new leader. Null when nobody else is left, in which case the download simply
 * ends with its leader.
 */
internal fun planLeaderPromotion(followers: List<DownloadRef>, removed: DownloadRef): LeaderPromotion? {
    val candidates = followers.filter { it != removed }
    val newLeader = candidates.firstOrNull() ?: return null
    return LeaderPromotion(newLeader, candidates.drop(1))
}

/**
 * Whether the app has to re-attach to a running download before anyone can follow it: it claims to
 * be downloading ([leaderStatus]) but nothing in this app run drives it ([leaderHasLiveTransfer] is
 * false), e.g. after the app was restarted while another profile's download was running. The system
 * may still be transferring it, so it is re-attached (never started a second time); waiting for it
 * without that would wait forever.
 */
internal fun leaderNeedsReattach(leaderStatus: DownloadStatus, leaderHasLiveTransfer: Boolean): Boolean =
    leaderStatus == DownloadStatus.Downloading && !leaderHasLiveTransfer
