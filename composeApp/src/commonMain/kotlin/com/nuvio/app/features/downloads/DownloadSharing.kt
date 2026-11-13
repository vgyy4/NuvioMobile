package com.nuvio.app.features.downloads

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
