package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.PosterZoomAnchorHolder
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSectionBottomPadding
import com.nuvio.app.features.home.rememberCachedHomeContinueWatchingItems
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.continueWatchingItemKey
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
    onOpenDetails: ((type: String, id: String, title: String) -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.completedItems.size) {
        DownloadsRepository.markCompletedSeen()
    }

    // The Continue watching list of Home (same builder, filters, dismissals and sort), narrowed to
    // what is downloaded on this device.
    val homeContinueWatching = rememberCachedHomeContinueWatchingItems()
    val continueWatchingPreferences by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()
    val continueEntries = remember(uiState.completedItems, homeContinueWatching, continueWatchingPreferences.isVisible) {
        if (continueWatchingPreferences.isVisible) {
            downloadedContinueWatchingEntries(homeContinueWatching, uiState.completedItems)
        } else {
            emptyList()
        }
    }
    val continueByKey = remember(continueEntries) {
        continueEntries.associateBy { entry -> continueWatchingItemKey(entry.item) }
    }

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var downloadPendingDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteDownloadsRequest?>(null) }
    var menuTarget by remember { mutableStateOf<DownloadsMenuTarget?>(null) }
    var menuAnchor by remember { mutableStateOf<PosterZoomAnchor?>(null) }
    val menuHazeState = rememberHazeState()
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    // Same hand-off as Home: the long-pressed card stashes its geometry, the menu zooms from it.
    val openMenu: (DownloadsMenuTarget) -> Unit = { target ->
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        menuAnchor = PosterZoomAnchorHolder.consume()
        menuTarget = target
    }

    // Progress ticks replace `uiState.items` several times a second; sorting only depends on the
    // completed downloads, which do not change while others are still downloading.
    val completedItems = uiState.completedItems
    val completedEpisodes = remember(completedItems) {
        completedItems
            .filter { it.isEpisode }
            .sortedForSeriesDownloads()
    }
    val downloadedMovies = remember(completedItems) { completedItems.toDownloadedMovies() }
    val downloadedShows = remember(completedItems) { completedItems.toDownloadedShows() }
    // Downloaded movies and episodes that were watched to the end, most recent download first.
    val finishedDownloads = remember(completedItems, watchedUiState.watchedKeys, watchProgressUiState) {
        completedItems
            .filter { item ->
                watchedItemKeys(
                    type = item.parentMetaType,
                    id = item.parentMetaId,
                    season = item.seasonNumber,
                    episode = item.episodeNumber,
                ).any(watchedUiState.watchedKeys::contains) ||
                    watchProgressUiState.progressForVideo(
                        videoId = item.videoId,
                        parentMetaId = item.parentMetaId,
                        seasonNumber = item.seasonNumber,
                        episodeNumber = item.episodeNumber,
                    )?.isCompleted == true
            }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    val selectedShowTitle = remember(selectedShowId, completedEpisodes) {
        selectedShowId?.let { showId ->
            completedEpisodes.firstOrNull { it.parentMetaId == showId }?.title
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (menuTarget != null) {
                        Modifier.hazeSource(state = menuHazeState)
                    } else {
                        Modifier
                    },
                ),
        ) {
            NuvioScreen(horizontalPadding = 0.dp) {
                stickyHeader {
                    NuvioScreenHeader(
                        title = if (selectedShowId == null) {
                            stringResource(Res.string.compose_settings_root_downloads_title)
                        } else {
                            selectedShowTitle ?: stringResource(Res.string.downloads_show_downloads)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onBack = if (selectedShowId == null && !showBack) {
                            null
                        } else {
                            {
                                if (selectedShowId != null) {
                                    onBackFromShow?.invoke() ?: run { selectedShowId = null }
                                } else {
                                    onBack()
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                        NuvioToastController.show(openDownloadsDirectoryFailedText)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = stringResource(Res.string.downloads_open_directory),
                                )
                            }
                        },
                    )
                }

                if (selectedShowId == null) {
                    downloadsRootContent(
                        uiState = uiState,
                        continueItems = continueEntries.map(DownloadedContinueEntry::item),
                        continuePreferences = continueWatchingPreferences,
                        continueDataSourceKey = watchProgressUiState.source,
                        movies = downloadedMovies,
                        shows = downloadedShows,
                        finished = finishedDownloads,
                        onFinishedLongPress = { item ->
                            if (item.isEpisode) {
                                openMenu(DownloadsMenuTarget.Episode(item))
                            } else {
                                listOf(item).toDownloadedMovies().firstOrNull()?.let { movie ->
                                    openMenu(DownloadsMenuTarget.Title(title = movie, playTarget = item, resumes = false))
                                }
                            }
                        },
                        onFinishedTrash = { item ->
                            deleteRequest = DeleteDownloadsRequest(
                                if (item.isEpisode) DeleteDownloadsKind.Episode else DeleteDownloadsKind.Movie,
                                listOf(item.id),
                            )
                        },
                        onDeleteAllFinished = {
                            deleteRequest = DeleteDownloadsRequest(
                                DeleteDownloadsKind.Watched,
                                finishedDownloads.map(DownloadItem::id),
                            )
                        },
                        watchedKeys = watchedUiState.watchedKeys,
                        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                        onContinueClick = { item ->
                            continueByKey[continueWatchingItemKey(item)]?.let { onOpenDownload(it.download) }
                        },
                        onContinueLongPress = { item ->
                            continueByKey[continueWatchingItemKey(item)]?.let { entry ->
                                openMenu(DownloadsMenuTarget.ContinueWatching(entry))
                            }
                        },
                        onTitleClick = { downloaded ->
                            if (downloaded.isShow) {
                                onNavigateToShow?.invoke(downloaded.parentMetaId, downloaded.title)
                                    ?: run { selectedShowId = downloaded.parentMetaId }
                            } else {
                                downloaded.downloads.firstOrNull()?.let(onOpenDownload)
                            }
                        },
                        onTitleLongPress = { downloaded ->
                            // Play continues what Home's Continue watching points at, else starts at the first file.
                            val resumeEntry = continueEntries.firstOrNull { entry ->
                                entry.download.parentMetaId == downloaded.parentMetaId
                            }
                            val playTarget = resumeEntry?.download ?: downloaded.downloads.firstOrNull()
                            if (playTarget != null) {
                                openMenu(
                                    DownloadsMenuTarget.Title(
                                        title = downloaded,
                                        playTarget = playTarget,
                                        resumes = resumeEntry != null && !resumeEntry.item.isNextUp,
                                    ),
                                )
                            }
                        },
                        onOpenDownload = onOpenDownload,
                        onDeleteDownload = { downloadPendingDeletionId = it },
                    )
                } else {
                    downloadsShowContent(
                        showId = selectedShowId.orEmpty(),
                        episodes = completedEpisodes,
                        onOpenDownload = onOpenDownload,
                        onEpisodeLongPress = { episode -> openMenu(DownloadsMenuTarget.Episode(episode)) },
                        onDeleteDownload = { downloadPendingDeletionId = it },
                    )
                }
            }
        }

        menuTarget?.let { target ->
            key(target) {
                DownloadsActionOverlay(
                    target = target,
                    anchor = menuAnchor,
                    hazeState = menuHazeState,
                    isOnline = networkStatusUiState.isOnline,
                    continueWatchingPreferences = continueWatchingPreferences,
                    watchedKeys = watchedUiState.watchedKeys,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    scope = coroutineScope,
                    onPlay = onOpenDownload,
                    onOpenDetails = onOpenDetails,
                    onDelete = { request -> deleteRequest = request },
                    onDismissed = {
                        menuTarget = null
                        menuAnchor = null
                    },
                )
            }
        }
    }

    DeleteDownloadsHost(deleteRequest) { deleteRequest = null }

    val pendingDeletionId = downloadPendingDeletionId
    if (pendingDeletionId != null) {
        NuvioStatusModal(
            title = stringResource(Res.string.action_delete_confirm_title),
            message = stringResource(Res.string.action_delete_confirm_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                DownloadsRepository.cancelDownload(pendingDeletionId)
                downloadPendingDeletionId = null
            },
            onDismiss = { downloadPendingDeletionId = null },
        )
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    continueItems: List<ContinueWatchingItem>,
    continuePreferences: ContinueWatchingPreferencesUiState,
    continueDataSourceKey: WatchProgressSource,
    movies: List<DownloadedTitle>,
    shows: List<DownloadedTitle>,
    finished: List<DownloadItem>,
    onFinishedLongPress: (DownloadItem) -> Unit,
    onFinishedTrash: (DownloadItem) -> Unit,
    onDeleteAllFinished: () -> Unit,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onContinueClick: (ContinueWatchingItem) -> Unit,
    onContinueLongPress: (ContinueWatchingItem) -> Unit,
    onTitleClick: (DownloadedTitle) -> Unit,
    onTitleLongPress: (DownloadedTitle) -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    val activeItems = uiState.activeItems

    if (continueItems.isNotEmpty()) {
        item(key = "downloads-continue-watching") {
            // Home's own section: Card, Wide or Poster style and the thumbnail/blur options are the user's.
            HomeContinueWatchingSection(
                items = continueItems,
                style = continuePreferences.style,
                dataSourceKey = continueDataSourceKey,
                useEpisodeThumbnails = continuePreferences.useEpisodeThumbnails,
                blurNextUp = continuePreferences.blurNextUp,
                modifier = Modifier.padding(bottom = HomeContinueWatchingSectionBottomPadding),
                title = stringResource(Res.string.downloads_section_continue_watching),
                onItemClick = onContinueClick,
                onItemLongPress = onContinueLongPress,
            )
        }
    }

    if (shows.isNotEmpty()) {
        item(key = "downloads-shows") {
            DownloadsPosterShelf(
                title = stringResource(Res.string.downloads_section_shows),
                entries = shows,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                onClick = onTitleClick,
                onLongClick = onTitleLongPress,
            )
        }
    }

    if (movies.isNotEmpty()) {
        item(key = "downloads-movies") {
            DownloadsPosterShelf(
                title = stringResource(Res.string.downloads_section_movies),
                entries = movies,
                watchedKeys = watchedKeys,
                fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                onClick = onTitleClick,
                onLongClick = onTitleLongPress,
            )
        }
    }

    if (finished.isNotEmpty()) {
        item(key = "downloads-finished-header") {
            FinishedWatchingHeader(onDeleteAll = onDeleteAllFinished)
        }
        items(
            items = finished,
            key = { "finished-${it.id}" },
        ) { item ->
            FinishedDownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onLongPress = { onFinishedLongPress(item) },
                onTrash = { onFinishedTrash(item) },
            )
        }
    }

    if (activeItems.isNotEmpty()) {
        item {
            SectionTitle(stringResource(Res.string.downloads_section_active))
        }
        items(
            items = activeItems,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
            )
        }
    }

    if (uiState.items.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.downloadsShowContent(
    showId: String,
    episodes: List<DownloadItem>,
    onOpenDownload: (DownloadItem) -> Unit,
    onEpisodeLongPress: (DownloadItem) -> Unit,
    onDeleteDownload: (String) -> Unit,
) {
    val showEpisodes = episodes
        .filter { it.parentMetaId == showId }
        .sortedForSeriesDownloads()

    val seasons = showEpisodes
        .groupBy { it.seasonNumber ?: 0 }
        .toList()
        .sortedWith(
            compareBy<Pair<Int, List<DownloadItem>>> { (season, _) ->
                if (season == 0) 0 else 1
            }.thenBy { (season, _) -> if (season == 0) 0 else season },
        )

    if (seasons.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    seasons.forEach { (seasonNumber, entries) ->
        item {
            SectionTitle(
                if (seasonNumber == 0) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, seasonNumber)
                },
            )
        }

        val sortedEpisodes = entries.sortedForSeriesDownloads()

        items(
            items = sortedEpisodes,
            key = { it.id },
        ) { item ->
            DownloadRow(
                item = item,
                onOpen = { onOpenDownload(item) },
                onPause = { DownloadsRepository.pauseDownload(item.id) },
                onResume = { DownloadsRepository.resumeDownload(item.id) },
                onRetry = { DownloadsRepository.retryDownload(item.id) },
                onDelete = { onDeleteDownload(item.id) },
                onLongPress = { onEpisodeLongPress(item) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(
        item = item,
        displayTitle = displayTitle,
    )

    // The padding sits outside the long-press target, so the lifted copy is exactly the card.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .posterCardClickable(
                    onClick = onOpen.takeIf { item.isPlayable },
                    onLongClick = onLongPress,
                    zoomImageUrl = item.episodeThumbnail ?: item.background ?: item.poster,
                    zoomCornerRadius = 12.dp,
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displaySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusText(item),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (item.status) {
                            DownloadStatus.Downloading -> {
                                IconButton(onClick = onPause) {
                                    Icon(
                                        imageVector = Icons.Rounded.Pause,
                                        contentDescription = stringResource(Res.string.compose_action_pause),
                                    )
                                }
                            }
                            DownloadStatus.Paused -> {
                                IconButton(onClick = onResume) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(Res.string.action_resume),
                                    )
                                }
                            }
                            DownloadStatus.Failed -> {
                                IconButton(onClick = onRetry) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(Res.string.action_retry),
                                    )
                                }
                            }
                            DownloadStatus.Completed -> {
                                IconButton(onClick = onOpen) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(Res.string.action_play),
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(Res.string.action_delete),
                            )
                        }
                    }
                }

                if (item.status == DownloadStatus.Downloading) {
                    if (item.totalBytes != null && item.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = item.progressFraction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun FinishedWatchingHeader(onDeleteAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.downloads_section_finished),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onDeleteAll) {
            Text(text = stringResource(Res.string.downloads_delete_finished))
        }
    }
}

/** A downloaded movie or episode that was watched to the end: tap plays, long-press opens the menu, the trash deletes it. */
@Composable
private fun FinishedDownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onTrash: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(item = item, displayTitle = displayTitle)
    val image = item.episodeThumbnail ?: item.background ?: item.poster

    // The padding sits outside the long-press target, so the lifted copy is exactly the card.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .posterCardClickable(
                    onClick = onOpen.takeIf { item.isPlayable },
                    onLongClick = onLongPress,
                    zoomImageUrl = image,
                    zoomCornerRadius = 12.dp,
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        if (image != null) {
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = displaySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusText(item),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onTrash,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(Res.string.action_delete),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun statusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        formatBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
