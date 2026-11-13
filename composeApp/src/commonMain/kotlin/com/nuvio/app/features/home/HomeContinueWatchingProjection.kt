package com.nuvio.app.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesUiState
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.isSeriesTypeForContinueWatching
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import com.nuvio.app.features.watchprogress.resolvedProgressKey

/*
 * Pure stages of the Home "Continue watching" pipeline that need no network. HomeScreen and the
 * Downloads screen both call these, so the two lists are built by the same rules.
 */

/** Progress rows that may appear in Continue watching: not hidden/dropped, inside the days window. */
internal fun homeEffectiveProgressEntries(
    entries: List<WatchProgressEntry>,
    hiddenContentIds: Set<String>,
    cutoffEpochMs: Long?,
): List<WatchProgressEntry> {
    val visibleProviderEntries = entries.filterNot { entry ->
        entry.parentMetaId in hiddenContentIds ||
            WatchProgressRepository.isDroppedShow(entry.parentMetaId)
    }
    return filterEntriesForContinueWatchingWindow(
        entries = visibleProviderEntries,
        cutoffEpochMs = cutoffEpochMs,
    )
}

/** Series that are actively in progress and therefore do not also get a next-up card. */
internal fun homeNextUpSuppressedSeriesIds(
    visibleEntries: List<WatchProgressEntry>,
    latestCompletedAtBySeries: Map<String, Long>,
): Set<String> =
    visibleEntries
        .asSequence()
        .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
        .filter { entry ->
            shouldTreatAsActiveInProgressForNextUpSuppression(
                progress = entry,
                latestCompletedAt = latestCompletedAtBySeries[entry.parentMetaId],
            )
        }
        .map { entry -> entry.parentMetaId }
        .filter(String::isNotBlank)
        .toSet()

/** Next-up cards restored from the enrichment cache, after dismissal/seed/hidden/aired checks. */
internal fun homeCachedNextUpItems(
    cachedNextUpReleases: List<CachedNextUpRelease>,
    shouldValidateMissingNextUpSeeds: Boolean,
    activeNextUpSeedContentIds: Set<String>,
    currentNextUpSeedByContentId: Map<String, Pair<Int, Int>>,
    progressProviderOwnsCompletedHistory: Boolean,
    hasLoadedRemoteProgress: Boolean,
    processedNextUpContentIds: Set<String>,
    resolvedNextUpContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>,
    showUnairedNextUp: Boolean,
    hiddenContentIds: Set<String>,
): Map<String, Pair<Long, ContinueWatchingItem>> {
    val nowEpochMs = WatchProgressClock.nowEpochMs()
    return cachedNextUpReleases.mapNotNull { cachedRelease ->
        val cached = cachedRelease.item
        if (
            shouldValidateMissingNextUpSeeds &&
            cached.contentId !in activeNextUpSeedContentIds
        ) {
            return@mapNotNull null
        }
        val currentSeed = currentNextUpSeedByContentId[cached.contentId]
        if (currentSeed != null) {
            val (currentSeason, currentEpisode) = currentSeed
            if (
                hasHomeNextUpSeedChangedFromCache(
                    currentSeason = currentSeason,
                    currentEpisode = currentEpisode,
                    cachedSeason = cached.seedSeason,
                    cachedEpisode = cached.seedEpisode,
                )
            ) {
                return@mapNotNull null
            }
        }
        if (
            progressProviderOwnsCompletedHistory &&
            hasLoadedRemoteProgress &&
            cached.contentId in processedNextUpContentIds &&
            cached.contentId !in resolvedNextUpContentIds
        ) {
            return@mapNotNull null
        }
        if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in dismissedNextUpKeys) {
            return@mapNotNull null
        }
        val releaseEpochMs = cachedRelease.epochMs()
        if (
            !cachedNextUpHasAired(cached, nowEpochMs, releaseEpochMs) &&
            !showUnairedNextUp
        ) {
            return@mapNotNull null
        }
        if (
            cached.contentId in hiddenContentIds ||
            WatchProgressRepository.isDroppedShow(cached.contentId)
        ) {
            return@mapNotNull null
        }
        val item = cached.toContinueWatchingItem(releaseEpochMs, nowEpochMs)
        val sortTimestamp = if (item.isReleaseAlert) {
            releaseEpochMs ?: cached.lastWatched
        } else {
            cached.lastWatched
        }
        cached.contentId to (sortTimestamp to item)
    }.toMap()
}

/** In-progress items restored from the enrichment cache, keyed by progress key. */
internal fun homeCachedInProgressItems(
    cachedInProgress: List<CachedInProgressItem>,
    hiddenContentIds: Set<String>,
): Map<String, ContinueWatchingItem> =
    cachedInProgress.mapNotNull { cached ->
        if (
            cached.contentId in hiddenContentIds ||
            WatchProgressRepository.isDroppedShow(cached.contentId)
        ) {
            return@mapNotNull null
        }
        cached.resolvedProgressKey() to cached.toContinueWatchingItem()
    }.toMap()

/**
 * The Continue watching list Home shows before its network resolution of new next-up episodes
 * finishes: live progress rows plus the cached next-up cards, built by [buildHomeContinueWatchingItems]
 * with the same filters and sort mode. This is the whole list for a device that is offline.
 */
internal fun buildCachedHomeContinueWatchingItems(
    watchProgress: WatchProgressUiState,
    watchedItems: List<WatchedItem>,
    hasLoadedWatchedItems: Boolean,
    hasLoadedRemoteWatchedItems: Boolean,
    preferences: ContinueWatchingPreferencesUiState,
    cutoffEpochMs: Long?,
    providerOwnsCompletedHistory: Boolean,
    cachedNextUp: List<CachedNextUpItem>,
    cachedInProgress: List<CachedInProgressItem>,
): List<ContinueWatchingItem> {
    val nowEpochMs = WatchProgressClock.nowEpochMs()
    val hiddenContentIds = watchProgress.hiddenContentIds
    val nextUpWatchedItems = if (providerOwnsCompletedHistory) emptyList() else watchedItems

    val effectiveEntries = homeEffectiveProgressEntries(
        entries = watchProgress.entries,
        hiddenContentIds = hiddenContentIds,
        cutoffEpochMs = cutoffEpochMs,
    )
    val seedCandidates = buildHomeNextUpSeedCandidates(
        progressEntries = watchProgress.entries,
        watchedItems = nextUpWatchedItems,
        providerOwnsCompletedHistory = providerOwnsCompletedHistory,
        preferFurthestEpisode = preferences.upNextFromFurthestEpisode,
        nowEpochMs = nowEpochMs,
        shouldUseProgressSeed = WatchProgressRepository::shouldUseAsNextUpSeed,
        isContentHidden = { contentId ->
            contentId in hiddenContentIds || WatchProgressRepository.isDroppedShow(contentId)
        },
    )
    val activeSeedContentIds = seedCandidates.mapTo(mutableSetOf()) { candidate -> candidate.content.id }
    val currentSeedByContentId = seedCandidates.associate { candidate ->
        candidate.content.id to (candidate.seasonNumber to candidate.episodeNumber)
    }
    val latestCompletedAtBySeries = seedCandidates
        .groupBy { candidate -> candidate.content.id }
        .mapValues { (_, candidates) -> candidates.maxOfOrNull { candidate -> candidate.markedAtEpochMs } ?: Long.MIN_VALUE }

    val visibleEntries = effectiveEntries.continueWatchingEntries(limit = HomeContinueWatchingMaxRecentProgressItems)
    val suppressedSeriesIds = homeNextUpSuppressedSeriesIds(
        visibleEntries = visibleEntries,
        latestCompletedAtBySeries = latestCompletedAtBySeries,
    )

    val nextUpItems = homeCachedNextUpItems(
        cachedNextUpReleases = cachedNextUp.map(::CachedNextUpRelease),
        shouldValidateMissingNextUpSeeds = isHomeNextUpSeedSourceLoaded(
            providerOwnsCompletedHistory = providerOwnsCompletedHistory,
            hasLoadedRemoteProgress = watchProgress.hasLoadedRemoteProgress,
            hasLoadedWatchedItems = hasLoadedWatchedItems,
            hasLoadedRemoteWatchedItems = hasLoadedRemoteWatchedItems,
        ),
        activeNextUpSeedContentIds = activeSeedContentIds,
        currentNextUpSeedByContentId = currentSeedByContentId,
        progressProviderOwnsCompletedHistory = providerOwnsCompletedHistory,
        hasLoadedRemoteProgress = watchProgress.hasLoadedRemoteProgress,
        processedNextUpContentIds = emptySet(),
        resolvedNextUpContentIds = emptySet(),
        dismissedNextUpKeys = preferences.dismissedNextUpKeys,
        showUnairedNextUp = preferences.showUnairedNextUp,
        hiddenContentIds = hiddenContentIds,
    )
    val inProgressItems = homeCachedInProgressItems(
        cachedInProgress = cachedInProgress,
        hiddenContentIds = hiddenContentIds,
    )

    val items = buildHomeContinueWatchingItems(
        visibleEntries = visibleEntries,
        cachedInProgressByProgressKey = inProgressItems,
        nextUpItemsBySeries = nextUpItems,
        nextUpSuppressedSeriesIds = suppressedSeriesIds,
        sortMode = preferences.sortMode,
        todayIsoDate = CurrentDateProvider.todayIsoDate(),
        cloudLibraryUiState = null,
    )
    // The Upcoming row of the split sort mode is for episodes that have not aired yet.
    return splitUpcomingItems(items = items, mode = preferences.sortMode, nowEpochMs = nowEpochMs).first
}

/** [buildCachedHomeContinueWatchingItems] fed from the same repositories HomeScreen reads. */
@Composable
internal fun rememberCachedHomeContinueWatchingItems(): List<ContinueWatchingItem> {
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val preferences by remember {
        ContinueWatchingPreferencesRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.uiState
    }.collectAsStateWithLifecycle()
    val trackingSettingsUiState by remember {
        TrackingSettingsRepository.ensureLoaded()
        TrackingSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val cacheGeneration by ContinueWatchingEnrichmentCache.generation.collectAsStateWithLifecycle()
    val activeProfileId = profileState.activeProfile?.profileIndex ?: 1
    val source = watchProgressUiState.source

    val cachedSnapshots = remember(activeProfileId, source, cacheGeneration) {
        ContinueWatchingEnrichmentCache.getSnapshots(profileId = activeProfileId, source = source)
    }
    val providerOwnsCompletedHistory = remember(source) {
        WatchProgressRepository.activeProviderOwnsCompletedHistoryProjection()
    }
    val cutoffEpochMs = remember(source, trackingSettingsUiState.continueWatchingDaysCap) {
        WatchProgressRepository.activeProviderContinueWatchingCutoffEpochMs(
            daysCap = trackingSettingsUiState.continueWatchingDaysCap,
            nowEpochMs = WatchProgressClock.nowEpochMs(),
        )
    }

    return remember(
        watchProgressUiState,
        watchedUiState,
        preferences,
        cachedSnapshots,
        providerOwnsCompletedHistory,
        cutoffEpochMs,
    ) {
        buildCachedHomeContinueWatchingItems(
            watchProgress = watchProgressUiState,
            watchedItems = watchedUiState.items,
            hasLoadedWatchedItems = watchedUiState.isLoaded,
            hasLoadedRemoteWatchedItems = watchedUiState.hasLoadedRemoteItems,
            preferences = preferences,
            cutoffEpochMs = cutoffEpochMs,
            providerOwnsCompletedHistory = providerOwnsCompletedHistory,
            cachedNextUp = cachedSnapshots.first,
            cachedInProgress = cachedSnapshots.second,
        )
    }
}
