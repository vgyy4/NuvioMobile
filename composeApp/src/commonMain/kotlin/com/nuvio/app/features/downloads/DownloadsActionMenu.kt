package com.nuvio.app.features.downloads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterZoomActionOverlay
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.PosterZoomOverlayExitAnimation
import com.nuvio.app.core.ui.localizedContinueWatchingSubtitle
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.components.shouldBlurContinueWatchingArtwork
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.application.canMarkWatched
import com.nuvio.app.features.watching.application.toWatchedItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** What was long-pressed on the Downloads screen. */
internal sealed interface DownloadsMenuTarget {
    class ContinueWatching(val entry: DownloadedContinueEntry) : DownloadsMenuTarget

    /** A movie or show card. [playTarget] is the file Play starts; [resumes] says it continues one. */
    class Title(
        val title: DownloadedTitle,
        val playTarget: DownloadItem,
        val resumes: Boolean,
    ) : DownloadsMenuTarget

    class Episode(val item: DownloadItem) : DownloadsMenuTarget
}

/**
 * The long-press menu of the Downloads screen: the same zoom overlay as Home posters and Continue
 * watching cards. Details and anything that needs the network are left out while offline.
 */
@Composable
internal fun DownloadsActionOverlay(
    target: DownloadsMenuTarget,
    anchor: PosterZoomAnchor?,
    hazeState: HazeState,
    isOnline: Boolean,
    continueWatchingPreferences: ContinueWatchingPreferencesUiState,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    scope: CoroutineScope,
    onPlay: (DownloadItem) -> Unit,
    onOpenDetails: ((type: String, id: String, title: String) -> Unit)?,
    onDelete: (DeleteDownloadsRequest) -> Unit,
    onDismissed: () -> Unit,
) {
    when (target) {
        is DownloadsMenuTarget.ContinueWatching -> ContinueWatchingMenu(
            entry = target.entry,
            anchor = anchor,
            hazeState = hazeState,
            isOnline = isOnline,
            preferences = continueWatchingPreferences,
            onPlay = onPlay,
            onOpenDetails = onOpenDetails,
            onDelete = onDelete,
            onDismissed = onDismissed,
        )

        is DownloadsMenuTarget.Title -> TitleMenu(
            target = target,
            anchor = anchor,
            hazeState = hazeState,
            isOnline = isOnline,
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            scope = scope,
            onPlay = onPlay,
            onOpenDetails = onOpenDetails,
            onDelete = onDelete,
            onDismissed = onDismissed,
        )

        is DownloadsMenuTarget.Episode -> EpisodeMenu(
            item = target.item,
            anchor = anchor,
            hazeState = hazeState,
            isOnline = isOnline,
            watchedKeys = watchedKeys,
            onPlay = onPlay,
            onOpenDetails = onOpenDetails,
            onDelete = onDelete,
            onDismissed = onDismissed,
        )
    }
}

@Composable
private fun ContinueWatchingMenu(
    entry: DownloadedContinueEntry,
    anchor: PosterZoomAnchor?,
    hazeState: HazeState,
    isOnline: Boolean,
    preferences: ContinueWatchingPreferencesUiState,
    onPlay: (DownloadItem) -> Unit,
    onOpenDetails: ((type: String, id: String, title: String) -> Unit)?,
    onDelete: (DeleteDownloadsRequest) -> Unit,
    onDismissed: () -> Unit,
) {
    val item = entry.item
    val download = entry.download
    val artworkUrl = anchor?.imageUrl ?: item.poster ?: item.imageUrl
    NuvioPosterZoomActionOverlay(
        imageUrl = artworkUrl,
        title = item.title,
        subtitle = localizedContinueWatchingSubtitle(item),
        blurred = item.shouldBlurContinueWatchingArtwork(
            blurUnwatchedEpisodes = preferences.blurNextUp,
            useEpisodeThumbnails = preferences.useEpisodeThumbnails,
            artworkUrl = artworkUrl,
        ),
        depthSurface = NuvioCardDepthSurface.ContinueWatching,
        anchor = anchor,
        actions = buildList {
            add(playAction(download = download, resume = !item.isNextUp && item.progressFraction > 0f, onPlay = onPlay))
            if (isOnline && onOpenDetails != null) {
                add(detailsAction(item.parentMetaType, item.parentMetaId, item.title, onOpenDetails))
            }
            if (item.canMarkWatched()) {
                add(
                    PosterZoomOverlayAction(
                        icon = Icons.Default.CheckCircle,
                        label = stringResource(Res.string.hero_mark_watched),
                        onSelected = { WatchingActions.markContinueWatchingWatched(item) },
                    ),
                )
            }
            add(
                deleteAction(
                    kind = if (download.isEpisode) DeleteDownloadsKind.Episode else DeleteDownloadsKind.Movie,
                    downloadIds = listOf(download.id),
                    onDelete = onDelete,
                ),
            )
            add(
                PosterZoomOverlayAction(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(Res.string.cw_action_remove),
                    isDestructive = true,
                    onSelected = { removeFromContinueWatching(item) },
                ),
            )
        },
        hazeState = hazeState,
        onDismissed = onDismissed,
    )
}

@Composable
private fun TitleMenu(
    target: DownloadsMenuTarget.Title,
    anchor: PosterZoomAnchor?,
    hazeState: HazeState,
    isOnline: Boolean,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    scope: CoroutineScope,
    onPlay: (DownloadItem) -> Unit,
    onOpenDetails: ((type: String, id: String, title: String) -> Unit)?,
    onDelete: (DeleteDownloadsRequest) -> Unit,
    onDismissed: () -> Unit,
) {
    val downloaded = target.title
    val preview = remember(downloaded) { downloaded.toMetaPreview() }
    val isWatched = WatchingState.isPosterWatched(
        watchedKeys = watchedKeys,
        item = preview,
        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
    )
    NuvioPosterZoomActionOverlay(
        imageUrl = anchor?.imageUrl ?: downloaded.poster ?: downloaded.background,
        title = downloaded.title,
        subtitle = if (downloaded.isShow) {
            stringResource(Res.string.downloads_episode_count, downloaded.downloads.size)
        } else {
            stringResource(Res.string.media_movie)
        },
        isWatched = isWatched,
        anchor = anchor,
        actions = buildList {
            add(playAction(download = target.playTarget, resume = target.resumes, onPlay = onPlay))
            if (isOnline && onOpenDetails != null) {
                add(detailsAction(downloaded.parentMetaType, downloaded.parentMetaId, downloaded.title, onOpenDetails))
            }
            // Marking a whole show watched needs its episode list, which only exists online.
            if (!downloaded.isShow || isOnline) {
                add(
                    PosterZoomOverlayAction(
                        icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                        label = stringResource(
                            if (isWatched) Res.string.hero_mark_unwatched else Res.string.hero_mark_watched,
                        ),
                        onSelected = {
                            scope.launch { WatchingActions.togglePosterWatched(preview) }
                        },
                    ),
                )
            }
            add(
                deleteAction(
                    kind = if (downloaded.isShow) DeleteDownloadsKind.Show else DeleteDownloadsKind.Movie,
                    downloadIds = downloaded.downloads.map(DownloadItem::id),
                    onDelete = onDelete,
                ),
            )
        },
        hazeState = hazeState,
        onDismissed = onDismissed,
    )
}

@Composable
private fun EpisodeMenu(
    item: DownloadItem,
    anchor: PosterZoomAnchor?,
    hazeState: HazeState,
    isOnline: Boolean,
    watchedKeys: Set<String>,
    onPlay: (DownloadItem) -> Unit,
    onOpenDetails: ((type: String, id: String, title: String) -> Unit)?,
    onDelete: (DeleteDownloadsRequest) -> Unit,
    onDismissed: () -> Unit,
) {
    val resumes = remember(item) {
        WatchProgressRepository.progressForVideo(
            videoId = item.videoId,
            parentMetaId = item.parentMetaId,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
        )?.let { progress -> progress.isResumable && progress.lastPositionMs > 0L } == true
    }
    val seasonDownloadIds = remember(item) { wholeSeasonDownloadIds(item) }
    val watchable = remember(item) { item.toContinueWatchingItem() }
    val isWatched = watchedItemKeys(
        type = item.parentMetaType,
        id = item.parentMetaId,
        season = item.seasonNumber,
        episode = item.episodeNumber,
    ).any(watchedKeys::contains)

    NuvioPosterZoomActionOverlay(
        imageUrl = anchor?.imageUrl ?: item.episodeThumbnail ?: item.background ?: item.poster,
        title = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: item.title,
        subtitle = localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber) ?: item.title,
        isWatched = isWatched,
        depthSurface = NuvioCardDepthSurface.EpisodeCards,
        anchor = anchor,
        actions = buildList {
            add(playAction(download = item, resume = resumes, onPlay = onPlay))
            if (isOnline && onOpenDetails != null) {
                add(detailsAction(item.parentMetaType, item.parentMetaId, item.title, onOpenDetails))
            }
            add(
                PosterZoomOverlayAction(
                    icon = if (isWatched) Icons.Default.CheckCircleOutline else Icons.Default.CheckCircle,
                    label = stringResource(
                        if (isWatched) Res.string.episode_mark_unwatched else Res.string.episode_mark_watched,
                    ),
                    onSelected = {
                        if (isWatched) {
                            WatchedRepository.unmarkWatched(watchable.toWatchedItem())
                        } else {
                            WatchingActions.markContinueWatchingWatched(watchable)
                        }
                    },
                ),
            )
            add(
                deleteAction(
                    kind = DeleteDownloadsKind.Episode,
                    downloadIds = listOf(item.id),
                    onDelete = onDelete,
                ),
            )
            // Only when every released episode of the season is on this device, as on the details screen.
            if (seasonDownloadIds.isNotEmpty()) {
                add(
                    deleteAction(
                        kind = DeleteDownloadsKind.Season,
                        downloadIds = seasonDownloadIds,
                        onDelete = onDelete,
                    ),
                )
            }
        },
        hazeState = hazeState,
        onDismissed = onDismissed,
    )
}

@Composable
private fun playAction(
    download: DownloadItem,
    resume: Boolean,
    onPlay: (DownloadItem) -> Unit,
): PosterZoomOverlayAction = PosterZoomOverlayAction(
    icon = Icons.Default.PlayArrow,
    label = stringResource(if (resume) Res.string.action_resume else Res.string.action_play),
    onSelected = { onPlay(download) },
)

@Composable
private fun detailsAction(
    type: String,
    id: String,
    title: String,
    onOpenDetails: (type: String, id: String, title: String) -> Unit,
): PosterZoomOverlayAction = PosterZoomOverlayAction(
    icon = Icons.Default.Info,
    label = stringResource(Res.string.cw_action_go_to_details),
    onSelected = { onOpenDetails(type, id, title) },
)

/** Confirmation comes first ([DeleteDownloadsHost]), so the poster collapses instead of burning away. */
@Composable
private fun deleteAction(
    kind: DeleteDownloadsKind,
    downloadIds: List<String>,
    onDelete: (DeleteDownloadsRequest) -> Unit,
): PosterZoomOverlayAction = PosterZoomOverlayAction(
    icon = Icons.Default.DeleteOutline,
    label = deleteDownloadLabel(kind),
    isDestructive = true,
    exitAnimation = PosterZoomOverlayExitAnimation.COLLAPSE,
    onSelected = { onDelete(DeleteDownloadsRequest(kind, downloadIds)) },
)

/** Same effect as the Remove action of Home's Continue watching menu. */
private fun removeFromContinueWatching(item: ContinueWatchingItem) {
    if (item.isNextUp) {
        ContinueWatchingPreferencesRepository.addDismissedNextUpKey(
            nextUpDismissKey(
                item.parentMetaId,
                item.nextUpSeedSeasonNumber,
                item.nextUpSeedEpisodeNumber,
            ),
        )
    } else {
        WatchProgressRepository.removeProgress(contentId = item.parentMetaId)
    }
}

/** Ids of the downloaded episodes of this episode's season, only when all its released episodes are downloaded. */
private fun wholeSeasonDownloadIds(item: DownloadItem): List<String> {
    val meta = MetaDetailsRepository.peek(item.parentMetaType, item.parentMetaId) ?: return emptyList()
    val seasonEpisodes = meta.releasedEpisodesForSeason(
        seasonNumber = item.seasonNumber,
        todayIsoDate = CurrentDateProvider.todayIsoDate(),
    )
    if (seasonEpisodes.isEmpty()) return emptyList()
    val downloaded = seasonEpisodes.mapNotNull { episode ->
        DownloadsRepository.findPlayableDownload(
            parentMetaId = meta.id,
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            videoId = episode.id,
        )
    }
    return if (downloaded.size == seasonEpisodes.size) downloaded.map(DownloadItem::id) else emptyList()
}

/** A watch-state view of a downloaded episode, so it can be marked like a Continue watching entry. */
private fun DownloadItem.toContinueWatchingItem(): ContinueWatchingItem = ContinueWatchingItem(
    parentMetaId = parentMetaId,
    parentMetaType = parentMetaType,
    videoId = videoId,
    title = title,
    subtitle = "",
    imageUrl = episodeThumbnail ?: background ?: poster,
    logo = logo,
    poster = poster,
    background = background,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    episodeTitle = episodeTitle,
    episodeThumbnail = episodeThumbnail,
    resumePositionMs = 0L,
    durationMs = 0L,
    progressFraction = 0f,
)
