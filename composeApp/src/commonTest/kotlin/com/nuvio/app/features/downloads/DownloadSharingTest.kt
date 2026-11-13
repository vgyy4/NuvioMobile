package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSharingTest {

    private fun item(
        id: String = "a",
        fileName: String = "Movie_a.mp4",
        status: DownloadStatus = DownloadStatus.Completed,
        sourceUrl: String = "https://cdn.example.com/a.mp4",
        p2pInfoHash: String? = null,
        p2pFileIdx: Int? = null,
        providerAddonId: String? = "addon:one",
        streamTitle: String = "1080p",
        streamSubtitle: String? = "Release A",
        parentMetaId: String = "tt1",
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        sharedFromDownloadId: String? = null,
    ) = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = parentMetaId,
        parentMetaType = "movie",
        videoId = parentMetaId,
        title = "Movie",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        streamTitle = streamTitle,
        streamSubtitle = streamSubtitle,
        providerName = "Addon One",
        providerAddonId = providerAddonId,
        sourceUrl = sourceUrl,
        p2pInfoHash = p2pInfoHash,
        p2pFileIdx = p2pFileIdx,
        fileName = fileName,
        status = status,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        sharedFromDownloadId = sharedFromDownloadId,
        sharedFromProfileId = if (sharedFromDownloadId != null) 1 else null,
    )

    private fun stream(
        url: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        name: String? = "1080p",
        description: String? = "Release A",
        addonId: String = "addon:one",
    ) = StreamItem(
        name = name,
        description = description,
        url = url,
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = "Addon One",
        addonId = addonId,
    )

    // ---- isSameDownloadSource ---------------------------------------------------------------

    @Test
    fun sameTorrentNeedsTheSameInfoHashAndFile() {
        val existing = item(p2pInfoHash = "ABCDEF0123", p2pFileIdx = 2)
        assertTrue(isSameDownloadSource(existing, stream(infoHash = "abcdef0123", fileIdx = 2)))
        assertFalse(isSameDownloadSource(existing, stream(infoHash = "abcdef0123", fileIdx = 3)))
        assertFalse(isSameDownloadSource(existing, stream(infoHash = "ffffff0000", fileIdx = 2)))
    }

    @Test
    fun twoDifferentTorrentsAreNeverTheSameSourceEvenWithTheSameLabel() {
        val existing = item(p2pInfoHash = "abcdef0123", p2pFileIdx = null)
        assertFalse(isSameDownloadSource(existing, stream(infoHash = "0123456789")))
    }

    @Test
    fun sameLinkIsTheSameSource() {
        val existing = item(sourceUrl = "https://cdn.example.com/a.mp4", streamTitle = "x", streamSubtitle = null)
        assertTrue(
            isSameDownloadSource(
                existing,
                stream(url = "https://cdn.example.com/a.mp4", name = "other", description = "other", addonId = "addon:two"),
            ),
        )
    }

    @Test
    fun differentLinkFallsBackToAddonNameAndDescription() {
        val existing = item(sourceUrl = "https://cdn.example.com/a.mp4")
        assertTrue(isSameDownloadSource(existing, stream(url = "https://cdn.example.com/b.mp4")))
        assertFalse(isSameDownloadSource(existing, stream(url = "https://cdn.example.com/b.mp4", description = "Release B")))
        assertFalse(isSameDownloadSource(existing, stream(url = "https://cdn.example.com/b.mp4", name = "720p")))
        assertFalse(isSameDownloadSource(existing, stream(url = "https://cdn.example.com/b.mp4", addonId = "addon:two")))
    }

    @Test
    fun descriptionsAreComparedWithoutSurroundingSpaceAndBlankEqualsMissing() {
        val existing = item(sourceUrl = "https://cdn.example.com/a.mp4", streamSubtitle = "Release A")
        assertTrue(isSameDownloadSource(existing, stream(url = "https://cdn.example.com/b.mp4", description = "  Release A ")))
        val noDescription = item(sourceUrl = "https://cdn.example.com/a.mp4", streamSubtitle = null)
        assertTrue(isSameDownloadSource(noDescription, stream(url = "https://cdn.example.com/b.mp4", description = "  ")))
    }

    @Test
    fun anUnknownAddonNeverMatchesByDescription() {
        val existing = item(sourceUrl = "https://cdn.example.com/a.mp4", providerAddonId = null)
        assertFalse(isSameDownloadSource(existing, stream(url = "https://cdn.example.com/b.mp4")))
    }

    @Test
    fun aTorrentAndALinkAreComparedByAddonNameAndDescription() {
        val torrent = item(p2pInfoHash = "abcdef0123", p2pFileIdx = 1)
        assertTrue(isSameDownloadSource(torrent, stream(url = "https://cdn.example.com/a.mp4")))
        assertFalse(isSameDownloadSource(torrent, stream(url = "https://cdn.example.com/a.mp4", name = "720p")))
        val link = item(sourceUrl = "https://cdn.example.com/a.mp4")
        assertTrue(isSameDownloadSource(link, stream(infoHash = "abcdef0123")))
        assertFalse(isSameDownloadSource(link, stream(infoHash = "abcdef0123", description = "Release B")))
    }

    // ---- isFileReferencedElsewhere ----------------------------------------------------------

    @Test
    fun aFileOnlyTheOwnerHasMayBeDeleted() {
        val owner = item(id = "a", fileName = "F.mp4")
        assertFalse(isFileReferencedElsewhere("F.mp4", 1, "a", mapOf(1 to listOf(owner))))
        assertFalse(isFileReferencedElsewhere("F.mp4", 1, "a", emptyMap()))
    }

    @Test
    fun aFileAnotherProfileStillHasIsKept() {
        val mine = item(id = "a", fileName = "F.mp4")
        val theirs = item(id = "b", fileName = "F.mp4")
        assertTrue(isFileReferencedElsewhere("F.mp4", 1, "a", mapOf(1 to listOf(mine), 2 to listOf(theirs))))
    }

    @Test
    fun withThreeProfilesTheFileGoesWithTheLastReference() {
        val all = mapOf(
            1 to listOf(item(id = "a", fileName = "F.mp4")),
            2 to listOf(item(id = "b", fileName = "F.mp4")),
            3 to listOf(item(id = "c", fileName = "F.mp4")),
        )
        assertTrue(isFileReferencedElsewhere("F.mp4", 1, "a", all))
        // Profiles 1 and 2 have deleted theirs: profile 3 holds the last reference.
        val onlyLast = mapOf(3 to all.getValue(3))
        assertFalse(isFileReferencedElsewhere("F.mp4", 3, "c", onlyLast))
        assertTrue(isFileReferencedElsewhere("F.mp4", 1, "a", onlyLast))
    }

    @Test
    fun anItemIdThatRepeatsInAnotherProfileIsStillAnotherRecord() {
        val all = mapOf(
            1 to listOf(item(id = "a", fileName = "F.mp4")),
            2 to listOf(item(id = "a", fileName = "F.mp4")),
        )
        assertTrue(isFileReferencedElsewhere("F.mp4", 1, "a", all))
    }

    @Test
    fun twoRecordsOfOneProfileCountAsTwoReferences() {
        val all = mapOf(1 to listOf(item(id = "a", fileName = "F.mp4"), item(id = "b", fileName = "F.mp4")))
        assertTrue(isFileReferencedElsewhere("F.mp4", 1, "a", all))
    }

    @Test
    fun otherFileNamesDoNotCount() {
        val all = mapOf(1 to listOf(item(id = "a", fileName = "F.mp4")), 2 to listOf(item(id = "b", fileName = "G.mp4")))
        assertFalse(isFileReferencedElsewhere("F.mp4", 1, "a", all))
    }

    @Test
    fun fileNamesAreComparedWithoutSurroundingSpace() {
        val all = mapOf(1 to listOf(item(id = "a", fileName = "F.mp4")), 2 to listOf(item(id = "b", fileName = " F.mp4 ")))
        assertTrue(isFileReferencedElsewhere("F.mp4", 1, "a", all))
    }

    @Test
    fun aBlankFileNameIsAlwaysKept() {
        assertTrue(isFileReferencedElsewhere("  ", 1, "a", emptyMap()))
    }

    // ---- planLeaderPromotion / leaderNeedsReattach ------------------------------------------

    @Test
    fun theFirstFollowerTakesOverAndTheRestFollowIt() {
        val removed = DownloadRef(1, "leader")
        val followers = listOf(DownloadRef(2, "f2"), DownloadRef(3, "f3"), DownloadRef(4, "f4"))
        val plan = planLeaderPromotion(followers, removed)
        assertEquals(DownloadRef(2, "f2"), plan?.newLeader)
        assertEquals(listOf(DownloadRef(3, "f3"), DownloadRef(4, "f4")), plan?.remainingFollowers)
    }

    @Test
    fun nobodyTakesOverWithoutFollowers() {
        assertNull(planLeaderPromotion(emptyList(), DownloadRef(1, "leader")))
    }

    @Test
    fun theRemovedLeaderNeverTakesOverItself() {
        val removed = DownloadRef(1, "leader")
        assertNull(planLeaderPromotion(listOf(removed), removed))
        val plan = planLeaderPromotion(listOf(removed, DownloadRef(2, "f")), removed)
        assertEquals(DownloadRef(2, "f"), plan?.newLeader)
        assertTrue(plan?.remainingFollowers.orEmpty().isEmpty())
    }

    @Test
    fun onlyADownloadingLeaderWithoutALiveTransferNeedsReattaching() {
        assertTrue(leaderNeedsReattach(DownloadStatus.Downloading, leaderHasLiveTransfer = false))
        assertFalse(leaderNeedsReattach(DownloadStatus.Downloading, leaderHasLiveTransfer = true))
        assertFalse(leaderNeedsReattach(DownloadStatus.Paused, leaderHasLiveTransfer = false))
        assertFalse(leaderNeedsReattach(DownloadStatus.Failed, leaderHasLiveTransfer = false))
        assertFalse(leaderNeedsReattach(DownloadStatus.Completed, leaderHasLiveTransfer = false))
    }

    // ---- chooseOtherProfileDownload ---------------------------------------------------------

    private val movieKey = "tt1|movie"
    private val always: (DownloadItem) -> Boolean = { true }

    @Test
    fun nothingIsChosenForOtherContentOrWithoutAFile() {
        val entries = listOf(2 to item(id = "b", parentMetaId = "tt2"))
        assertNull(chooseOtherProfileDownload(movieKey, null, entries, always, allowInProgress = true))
        val own = listOf(2 to item(id = "b"))
        assertNull(chooseOtherProfileDownload(movieKey, null, own, { false }, allowInProgress = true))
        assertEquals(2, chooseOtherProfileDownload(movieKey, null, own, always, allowInProgress = true)?.profileId)
    }

    @Test
    fun episodesAreMatchedBySeasonAndEpisode() {
        val entries = listOf(
            2 to item(id = "b", seasonNumber = 1, episodeNumber = 1),
            3 to item(id = "c", seasonNumber = 1, episodeNumber = 2),
        )
        val chosen = chooseOtherProfileDownload("tt1|1|2", null, entries, always, allowInProgress = true)
        assertEquals(3, chosen?.profileId)
        assertEquals("c", chosen?.item?.id)
    }

    @Test
    fun theSameSourceBeatsAFinishedDownloadOfAnotherSource() {
        val entries = listOf(
            2 to item(id = "b", sourceUrl = "https://cdn.example.com/other.mp4", streamTitle = "720p"),
            3 to item(id = "c", status = DownloadStatus.Downloading),
        )
        val chosen = chooseOtherProfileDownload(
            movieKey,
            stream(url = "https://cdn.example.com/a.mp4"),
            entries,
            always,
            allowInProgress = true,
        )
        assertEquals(3, chosen?.profileId)
        assertTrue(chosen?.isSameSource == true)
        assertFalse(chosen?.isComplete == true)
    }

    @Test
    fun finishedBeatsRunningWhenBothMatchTheSourceTheSame() {
        val entries = listOf(
            2 to item(id = "b", status = DownloadStatus.Downloading),
            3 to item(id = "c", status = DownloadStatus.Completed),
        )
        val chosen = chooseOtherProfileDownload(
            movieKey,
            stream(url = "https://cdn.example.com/a.mp4"),
            entries,
            always,
            allowInProgress = true,
        )
        assertEquals(3, chosen?.profileId)
        assertTrue(chosen?.isComplete == true)
    }

    @Test
    fun theFirstOfEquallyGoodDownloadsWins() {
        val entries = listOf(2 to item(id = "b"), 3 to item(id = "c"))
        assertEquals(2, chooseOtherProfileDownload(movieKey, null, entries, always, allowInProgress = true)?.profileId)
    }

    @Test
    fun runningDownloadsOnlyCountWhenAllowed() {
        val entries = listOf(2 to item(id = "b", status = DownloadStatus.Downloading))
        assertNull(chooseOtherProfileDownload(movieKey, null, entries, always, allowInProgress = false))
        assertEquals(2, chooseOtherProfileDownload(movieKey, null, entries, always, allowInProgress = true)?.profileId)
    }

    @Test
    fun followersAndFailedDownloadsAreNeverChosen() {
        val entries = listOf(
            2 to item(id = "b", status = DownloadStatus.Downloading, sharedFromDownloadId = "leader"),
            3 to item(id = "c", status = DownloadStatus.Failed),
        )
        assertNull(chooseOtherProfileDownload(movieKey, null, entries, always, allowInProgress = true))
        val paused = listOf(4 to item(id = "d", status = DownloadStatus.Paused))
        assertEquals(4, chooseOtherProfileDownload(movieKey, null, paused, always, allowInProgress = true)?.profileId)
    }

    @Test
    fun withoutAStreamNoSourceCountsAsTheSame() {
        val entries = listOf(2 to item(id = "b"))
        assertFalse(chooseOtherProfileDownload(movieKey, null, entries, always, allowInProgress = true)?.isSameSource == true)
    }
}
