package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pDownloadStream
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.buildP2pMagnetUri
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.player.computeVideoHash
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    // Subtitle languages (normalized codes) the user had ticked in the download options last time.
    // null = nothing saved yet: every language is ticked by default. Once a choice is saved, a
    // language is ticked only if it is in this set, so languages never offered before start unticked.
    private val _selectedSubtitleLanguages = MutableStateFlow<Set<String>?>(null)
    val selectedSubtitleLanguages: StateFlow<Set<String>?> = _selectedSubtitleLanguages.asStateFlow()

    // Fallback for data written by older app versions, which remembered the UNticked languages.
    // Only read while no selection is saved; kept unchanged in the local payload until the user
    // saves a new selection (then it is cleared) and never written to the cloud.
    private var legacyDeselectedSubtitleLanguages: Set<String> = emptySet()

    // Whether the home screen turns into the downloads view while the device is offline.
    private val _offlineDownloadsHome = MutableStateFlow(true)
    val offlineDownloadsHome: StateFlow<Boolean> = _offlineDownloadsHome.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    // Subtitles found by the last previews (newest last), so the download uses exactly the list
    // the user chose from instead of asking the add-ons a second time. Updated atomically because
    // season downloads queue episodes from another thread.
    private val previewedSubtitles = MutableStateFlow<Map<String, PreviewedSubtitles>>(emptyMap())

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()

    /**
     * The profile each running download belongs to. A download keeps running when another profile
     * is selected; its progress and result are then written to its own profile's list instead of
     * the active one.
     */
    private val handleOwners = mutableMapOf<String, Int>()
    private val p2pScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Limits how many torrents download at once; the rest wait their turn (first come, first served).
    private val torrentSlots = Semaphore(maxConcurrentTorrentDownloads())
    private val pausedForBackground = mutableSetOf<String>()
    private var foregroundJob: Job? = null
    private var lastProgressPersistEpochMs = 0L
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _selectedSubtitleLanguages.value = null
        legacyDeselectedSubtitleLanguages = emptySet()
        _offlineDownloadsHome.value = true
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun setOfflineDownloadsHome(enabled: Boolean) {
        ensureLoaded()
        if (_offlineDownloadsHome.value == enabled) return
        _offlineDownloadsHome.value = enabled
        persist()
    }

    /** Deletes completed downloads (files, subtitles and skip times included). */
    fun deleteDownloads(downloadIds: Collection<String>) {
        ensureLoaded()
        downloadIds.toList().forEach(::cancelDownload)
    }

    /**
     * The languages to tick when the download options open, out of the [available] ones.
     * - A saved selection exists: exactly the available languages that are in it (languages never
     *   offered before are NOT ticked).
     * - Only the legacy "unticked" list exists (older app version): everything except those.
     * - Nothing saved: everything.
     */
    fun initialSubtitleSelection(available: List<String>): Set<String> {
        ensureLoaded()
        val saved = _selectedSubtitleLanguages.value
        return when {
            saved != null -> available.filterTo(mutableSetOf<String>()) { it in saved }
            legacyDeselectedSubtitleLanguages.isNotEmpty() -> {
                val deselected = legacyDeselectedSubtitleLanguages.mapTo(mutableSetOf<String>()) { canonicalSubtitleLanguage(it) }
                available.filterTo(mutableSetOf<String>()) { it !in deselected }
            }
            else -> available.toSet()
        }
    }

    /**
     * Remembers the choice made in the download options: the ticked languages. Languages that were
     * not offered this time keep their saved state.
     */
    fun rememberSubtitleSelection(offered: List<String>, ticked: List<String>) {
        ensureLoaded()
        val previous = _selectedSubtitleLanguages.value.orEmpty()
        val updated = (previous - offered.toSet()) + ticked.map { canonicalSubtitleLanguage(it) }
        if (updated == _selectedSubtitleLanguages.value) return
        _selectedSubtitleLanguages.value = updated
        legacyDeselectedSubtitleLanguages = emptySet()
        persist()
    }

    /**
     * Applies the choice synced from the cloud for this profile. [languages] is the saved
     * selection; [legacyDeselected] is what older app versions synced (unticked languages), used
     * only when [languages] is absent and nothing is saved locally.
     */
    fun replaceSelectedSubtitleLanguages(languages: Collection<String>?, legacyDeselected: Collection<String>?) {
        ensureLoaded()
        if (languages != null) {
            val updated = languages.mapTo(mutableSetOf<String>()) { canonicalSubtitleLanguage(it) }
            if (updated == _selectedSubtitleLanguages.value) return
            _selectedSubtitleLanguages.value = updated
            legacyDeselectedSubtitleLanguages = emptySet()
            persist()
        } else if (legacyDeselected != null && _selectedSubtitleLanguages.value == null) {
            val updated = legacyDeselected.toSet()
            if (updated == legacyDeselectedSubtitleLanguages) return
            legacyDeselectedSubtitleLanguages = updated
            persist()
        }
    }

    /**
     * The language keys of the subtitles that [stream] offers (its own and the add-ons'), looked up
     * the way the player does (same add-on requests, with the video hash when it can be computed)
     * and grouped like the player's subtitle picker. Empty when there is nothing to download.
     */
    suspend fun previewSubtitleLanguages(
        contentType: String,
        videoId: String,
        stream: StreamItem,
    ): List<String> {
        ensureLoaded()
        var extras = subtitleRequestExtras(stream, stream.playableDirectUrl)
        val directUrl = stream.playableDirectUrl?.trim()
        if (extras?.videoHash == null && directUrl != null && directUrl.isHttpUrl()) {
            // Like the player: look subtitles up with the OpenSubtitles hash of the video if possible.
            val hashed = try {
                withTimeoutOrNull(SUBTITLE_HASH_TIMEOUT_MS) {
                    computeVideoHash(directUrl, sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            if (hashed != null) {
                extras = (extras ?: SubtitleRequestExtras()).copy(
                    videoHash = hashed.first,
                    videoSize = extras?.videoSize ?: hashed.second,
                )
            }
        }
        val found = DownloadSubtitles.discover(
            requests = addonSubtitleRequests(type = contentType, videoId = videoId, extras = extras),
            streamSubtitles = stream.externalSubtitles,
        )
        val key = previewKey(contentType, videoId, stream)
        val previewed = PreviewedSubtitles(extras, found.subtitles, found.fromAddons)
        previewedSubtitles.update { current ->
            (current - key + (key to previewed)).toList().takeLast(MAX_PREVIEWED_SUBTITLES).toMap()
        }
        return subtitleLanguageKeys(found.subtitles)
    }

    private fun previewKey(contentType: String, videoId: String, stream: StreamItem): String =
        "$contentType|$videoId|${stream.playableDirectUrl?.trim() ?: stream.p2pInfoHash}"

    // The extras the player sends to subtitle add-ons for a stream: a torrent has no playback URL.
    private fun subtitleRequestExtras(stream: StreamItem, sourceUrl: String?): SubtitleRequestExtras? =
        if (sourceUrl == null) {
            SubtitleRequestExtras.from(stream.behaviorHints)
        } else {
            SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl)
        }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
        downloadSubtitles: Boolean = true,
        subtitleLanguages: List<String> = emptyList(),
    ): DownloadEnqueueResult {
        ensureLoaded()

        val directUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        // A torrent without an HTTP link is downloaded through the P2P engine instead.
        val p2pInfoHash = if (directUrl == null && stream.isTorrentStream) stream.p2pInfoHash else null
        if (directUrl == null && p2pInfoHash == null) return DownloadEnqueueResult.MissingUrl
        if (directUrl != null && !directUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }
        if (p2pInfoHash != null) {
            if (!AppFeaturePolicy.p2pEnabled || !P2pDownloadPlatform.isSupported) {
                return DownloadEnqueueResult.P2pUnsupported
            }
            P2pSettingsRepository.ensureLoaded()
            if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
                return DownloadEnqueueResult.P2pDisabled
            }
        }
        val sourceUrl = directUrl
            ?: runCatching { buildP2pMagnetUri(checkNotNull(p2pInfoHash), stream.p2pTrackers) }.getOrNull()
            ?: return DownloadEnqueueResult.MissingUrl

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            deleteFilesUnlessShared(existing)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            extension = if (p2pInfoHash != null) {
                stream.behaviorHints.filename.fileExtensionFromFileName() ?: "mkv"
            } else {
                sourceUrl.fileExtensionFromUrl()
            },
            downloadId = downloadId,
        )

        val previewId = previewKey(contentType, videoId, stream)
        val previewed = previewedSubtitles.value[previewId]
        if (previewed != null) previewedSubtitles.update { it - previewId }

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = if (previewed?.fromAddons == true) {
                // The add-on results are saved below; nothing left to ask them.
                emptyList()
            } else {
                addonSubtitleRequests(
                    type = contentType,
                    videoId = videoId,
                    extras = previewed?.extras
                        ?: subtitleRequestExtras(stream, if (p2pInfoHash != null) null else sourceUrl),
                )
            },
            sourceSubtitles = if (previewed != null) {
                filterSubtitlesByLanguages(previewed.subtitles, subtitleLanguages)
            } else {
                stream.externalSubtitles
            },
            downloadSubtitles = downloadSubtitles,
            subtitleLanguages = subtitleLanguages,
            p2pInfoHash = p2pInfoHash,
            p2pFileIdx = if (p2pInfoHash != null) stream.p2pFileIdx else null,
            p2pFilename = if (p2pInfoHash != null) stream.behaviorHints.filename else null,
            p2pTrackers = if (p2pInfoHash != null) stream.p2pTrackers else emptyList(),
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
    }

    /**
     * Called when the app goes to the background on Apple devices. Regular downloads are handed to
     * the system, which keeps them going, so only torrent downloads (which need this app and its
     * engine running) are paused. They resume automatically when the app comes back.
     */
    fun pauseActiveDownloads() {
        ensureLoaded()
        val active = _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading && it.isP2p }
            .map { it.id }
        pausedForBackground.addAll(active)
        active.forEach(::pauseDownload)
    }

    private fun resumeDownloadsPausedForBackground() {
        val ids = pausedForBackground.toList()
        pausedForBackground.clear()
        ids.forEach { id ->
            // Skip anything the user paused, cancelled or finished in the meantime.
            if (_uiState.value.items.firstOrNull { it.id == id }?.status == DownloadStatus.Paused) {
                resumeDownload(id)
            }
        }
    }

    private fun observeAppForeground() {
        if (foregroundJob != null) return
        foregroundJob = p2pScope.launch {
            AppForegroundMonitor.events().collect { visibility ->
                if (visibility == AppVisibility.Foreground) resumeDownloadsPausedForBackground()
            }
        }
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.cancel()
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        deleteFilesUnlessShared(item)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
    }

    /**
     * Deletes the downloaded file, the partial file, the subtitles and the skip times of [item] (all
     * of them are named after the file) unless another record, in any profile, still points to the
     * same file. In that case only the record is removed by the caller and the files stay.
     * Returns true if the files were deleted.
     */
    private fun deleteFilesUnlessShared(item: DownloadItem): Boolean {
        if (fileReferencedByAnother(item.fileName, excludingItemId = item.id)) return false
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)
        return true
    }

    /**
     * Whether a record other than [excludingItemId] of [excludingProfileId] points to the file
     * [fileName]: the active profile's list is read from memory, every other profile's from its
     * saved list. A saved list that cannot be read counts as "referenced": when unsure the file is
     * kept.
     */
    private fun fileReferencedByAnother(
        fileName: String,
        excludingItemId: String,
        excludingProfileId: Int = ProfileRepository.activeProfileId,
    ): Boolean {
        val itemsByProfile = allProfileItems() ?: return true
        return isFileReferencedElsewhere(fileName, excludingProfileId, excludingItemId, itemsByProfile)
    }

    /** The profiles whose saved downloads count: the known profiles (all possible ones before they are loaded). */
    private fun profileIdsToScan(): Set<Int> {
        val known = ProfileRepository.state.value.profiles.map { it.profileIndex }
        val ids = if (known.isNotEmpty()) known else (1..MAX_PROFILES).toList()
        return (ids + ProfileRepository.activeProfileId).toSet()
    }

    /**
     * The downloads of EVERY profile (all profiles share one downloads folder but each keeps its own
     * list): the active profile's from memory, the others from their saved payloads. Null when a
     * saved payload exists but cannot be read, because then the other profile's files are unknown.
     */
    private fun allProfileItems(): Map<Int, List<DownloadItem>>? {
        val activeProfileId = ProfileRepository.activeProfileId
        val result = mutableMapOf<Int, List<DownloadItem>>()
        for (profileId in profileIdsToScan()) {
            if (profileId == activeProfileId) {
                result[profileId] = _uiState.value.items
                continue
            }
            val payload = DownloadsStorage.loadPayload(profileId).orEmpty().trim()
            if (payload.isEmpty()) continue
            result[profileId] = DownloadsCodec.decodeItemsOrNull(payload) ?: return null
        }
        return result
    }

    /**
     * File names of the downloads of EVERY profile. Looking only at the active profile would make
     * the unfinished downloads of the other profiles look like leftovers and delete them. Null when
     * some profile's list cannot be read: then nothing is swept.
     */
    private fun allProfilesKnownFileNames(): Set<String>? =
        allProfileItems()?.values?.flatMapTo(mutableSetOf<String>()) { items -> items.map { it.fileName } }

    private fun loadFromDisk() {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        observeAppForeground()
        // Leftovers of downloads that no longer exist (crash, forced quit, restart mid-cancel).
        // Delayed and age-checked so a download started right now is never touched.
        p2pScope.launch {
            delay(ORPHAN_SWEEP_DELAY_MS)
            val knownFileNames = allProfilesKnownFileNames() ?: return@launch
            DownloadsPlatformDownloader.removeOrphanedPartialFiles(knownFileNames)
        }
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _selectedSubtitleLanguages.value = null
            legacyDeselectedSubtitleLanguages = emptySet()
            _offlineDownloadsHome.value = true
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }
        _selectedSubtitleLanguages.value = DownloadsCodec.decodeSelectedLanguages(payload)
        legacyDeselectedSubtitleLanguages = DownloadsCodec.decodeDeselectedLanguages(payload)
        _offlineDownloadsHome.value = DownloadsCodec.decodeOfflineDownloadsHome(payload)

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                // A download that is running right now (it kept going while another profile was
                // active) is not restored: restoring would mark a running torrent download as paused.
                val statusNormalized = if (item.id in activeHandles) {
                    item
                } else {
                    DownloadsPlatformDownloader.restoreItem(item)
                }

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        normalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .forEach(::startDownload)
    }

    private fun startDownload(item: DownloadItem) {
        if (item.isP2p && !P2pDownloadPlatform.runsInPlatformDownloader) {
            startP2pDownload(item)
            return
        }
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes -> applyProgress(item.id, downloadedBytes, totalBytes) },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
            },
            onPaused = {
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
            },
        )

        activeHandles[item.id] = handle
        handleOwners[item.id] = ProfileRepository.activeProfileId
    }

    // ---- Torrent (P2P) downloads -------------------------------------------------------------
    //
    // The engine exposes each torrent file as a local HTTP stream. A torrent download starts that
    // stream and copies it to a file with the regular platform downloader, so pause/resume (Range
    // requests against the .part file), progress and file handling are shared with HTTP downloads.
    //
    // Disk use: while a stream is open the engine keeps the file's pieces in its cache, so a
    // running torrent download needs about twice the file size. The free-space check below refuses
    // to start when that does not fit, and the engine cache is reclaimed whenever the download
    // stops (finished, paused, failed or cancelled), so the file is not stored twice afterwards.
    // The .part file holds everything downloaded so far, so nothing is lost by reclaiming.

    private fun startP2pDownload(item: DownloadItem) {
        val handle = P2pDownloadHandle(item.id)
        activeHandles[item.id] = handle
        handleOwners[item.id] = ProfileRepository.activeProfileId
        handle.job = p2pScope.launch {
            try {
                runP2pDownload(item, handle)
            } finally {
                if (activeHandles[item.id] === handle) activeHandles.remove(item.id)
            }
        }
    }

    private suspend fun runP2pDownload(item: DownloadItem, handle: P2pDownloadHandle) {
        var slotHeld = false
        var stream: P2pDownloadStream? = null
        try {
            val infoHash = item.p2pInfoHash
            P2pSettingsRepository.ensureLoaded()
            if (infoHash == null ||
                !AppFeaturePolicy.p2pEnabled ||
                !P2pDownloadPlatform.isSupported ||
                !P2pSettingsRepository.uiState.value.p2pEnabled
            ) {
                markFailed(item.id, getString(Res.string.downloads_p2p_unavailable))
                return
            }

            // Only a few torrents run at once; the rest wait here in the order they were started.
            torrentSlots.acquire()
            slotHeld = true

            stream = try {
                withTimeoutOrNull(P2P_START_TIMEOUT_MS) {
                    P2pStreamingEngine.openDownloadStream(
                        P2pStreamRequest(
                            infoHash = infoHash,
                            fileIdx = item.p2pFileIdx,
                            filename = item.p2pFilename,
                            trackers = item.p2pTrackers,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                markFailed(
                    item.id,
                    error.message?.takeIf { it.isNotBlank() } ?: getString(Res.string.download_failed),
                )
                return
            }
            if (stream == null) {
                markFailed(item.id, getString(Res.string.downloads_p2p_no_peers))
                return
            }
            currentCoroutineContext().ensureActive()

            val outcome = CompletableDeferred<P2pDownloadOutcome>()
            var spaceChecked = false
            val transfer = DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    item.copy(sourceUrl = stream.localUrl, sourceHeaders = emptyMap()),
                ),
                onProgress = { downloadedBytes, totalBytes ->
                    if (totalBytes != null && totalBytes > 0L) {
                        if (!spaceChecked) {
                            spaceChecked = true
                            val shortfall = TorrentDiskBudget.reserve(
                                downloadId = item.id,
                                totalBytes = totalBytes,
                                downloadedBytes = downloadedBytes,
                                freeBytes = P2pDownloadPlatform.freeDiskSpaceBytes(),
                            )
                            if (shortfall != null) {
                                handle.stopping = true
                                outcome.complete(
                                    P2pDownloadOutcome.NotEnoughSpace(shortfall.neededBytes, shortfall.freeBytes),
                                )
                            }
                        } else {
                            TorrentDiskBudget.update(item.id, totalBytes, downloadedBytes)
                        }
                    }
                    if (!handle.stopping) applyProgress(item.id, downloadedBytes, totalBytes)
                },
                onSuccess = { localFileUri, totalBytes ->
                    handle.stopping = true
                    outcome.complete(P2pDownloadOutcome.Success(localFileUri, totalBytes))
                },
                onFailure = { message ->
                    handle.stopping = true
                    outcome.complete(P2pDownloadOutcome.Failure(message))
                },
                onPaused = {
                    handle.stopping = true
                    outcome.complete(P2pDownloadOutcome.Paused)
                },
            )
            handle.transfer = transfer
            if (handle.stopping && !outcome.isCompleted) transfer.cancel()

            when (val result = outcome.await()) {
                is P2pDownloadOutcome.Success -> mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = result.localFileUri,
                        downloadedBytes = result.totalBytes?.takeIf { it > 0L } ?: current.downloadedBytes,
                        totalBytes = result.totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                is P2pDownloadOutcome.Failure -> markFailed(
                    item.id,
                    result.message.ifBlank { getString(Res.string.download_failed) },
                )
                is P2pDownloadOutcome.NotEnoughSpace -> {
                    transfer.cancel()
                    markFailed(
                        item.id,
                        getString(
                            Res.string.downloads_p2p_not_enough_space,
                            formatP2pMegabytes(result.neededBytes),
                            formatP2pMegabytes(result.freeBytes),
                        ),
                    )
                }
                P2pDownloadOutcome.Paused -> mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
            }
        } finally {
            handle.stopping = true
            withContext(NonCancellable) {
                handle.transfer?.cancel()
                // Gives the engine's cached copy of the file back; the .part file keeps what was
                // downloaded, so a paused download resumes from there.
                stream?.let { runCatching { P2pStreamingEngine.closeDownloadStream(it) } }
                TorrentDiskBudget.release(item.id)
                if (slotHeld) torrentSlots.release()
            }
        }
    }

    private fun markFailed(downloadId: String, message: String) {
        mutateItem(downloadId) { current ->
            if (current.status != DownloadStatus.Downloading) {
                current
            } else {
                current.copy(
                    status = DownloadStatus.Failed,
                    errorMessage = message,
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
        }
    }

    /**
     * Progress arrives several times a second. Publishing is cheap, but persisting re-encodes the
     * whole list and rewrites the file, so progress is only persisted every few seconds. Status
     * changes always persist immediately, and downloads resume from the partial file's real size,
     * so a stale persisted byte count is harmless.
     */
    private fun applyProgress(downloadId: String, downloadedBytes: Long, totalBytes: Long?) {
        val now = DownloadsClock.nowEpochMs()
        val shouldPersist = now - lastProgressPersistEpochMs >= PROGRESS_PERSIST_INTERVAL_MS
        if (shouldPersist) lastProgressPersistEpochMs = now
        mutateItem(downloadId, persistChange = shouldPersist) { current ->
            if (current.status != DownloadStatus.Downloading) {
                current
            } else {
                current.copy(
                    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                    totalBytes = totalBytes?.takeIf { it > 0L },
                    updatedAtEpochMs = now,
                    errorMessage = null,
                )
            }
        }
    }

    private fun mutateItem(
        downloadId: String,
        persistChange: Boolean = true,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        val owner = handleOwners[downloadId]
        if (owner != null && owner != ProfileRepository.activeProfileId) {
            mutateItemOfOtherProfile(owner, downloadId, persistChange, transform)
            return
        }
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            if (persistChange) persist()
        }
    }

    /** Updates a running download of a profile that is not the active one, in that profile's saved list. */
    private fun mutateItemOfOtherProfile(
        profileId: Int,
        downloadId: String,
        persistChange: Boolean,
        transform: (DownloadItem) -> DownloadItem,
    ) {
        if (!persistChange) return
        val payload = DownloadsStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isEmpty()) return
        var changed = false
        val updated = DownloadsCodec.decodeItems(payload).map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }
        if (!changed) return
        DownloadsStorage.savePayload(
            profileId,
            DownloadsCodec.encode(
                updated,
                DownloadsCodec.decodeSelectedLanguages(payload),
                DownloadsCodec.decodeDeselectedLanguages(payload),
                DownloadsCodec.decodeOfflineDownloadsHome(payload),
            ),
        )
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encode(
                _uiState.value.items,
                _selectedSubtitleLanguages.value,
                legacyDeselectedSubtitleLanguages,
                _offlineDownloadsHome.value,
            ),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

private const val PROGRESS_PERSIST_INTERVAL_MS = 2_000L
private const val ORPHAN_SWEEP_DELAY_MS = 20_000L
private const val P2P_START_TIMEOUT_MS = 3L * 60L * 1_000L
// Computing the video hash reads a few blocks of the file; the subtitle lookup goes on without it.
private const val SUBTITLE_HASH_TIMEOUT_MS = 3_000L
private const val MAX_PREVIEWED_SUBTITLES = 8

private class P2pDownloadHandle(val itemId: String) : DownloadsTaskHandle {
    var job: Job? = null
    var transfer: DownloadsTaskHandle? = null

    /** True once this download is ending (finished, failed, paused or cancelled). */
    var stopping: Boolean = false

    override fun cancel() {
        stopping = true
        // Cancel the coroutine first so a "cancelled" error from the aborted transfer is never
        // reported as a failure.
        job?.cancel()
        transfer?.cancel()
    }
}

private sealed interface P2pDownloadOutcome {
    data class Success(val localFileUri: String, val totalBytes: Long?) : P2pDownloadOutcome
    data class Failure(val message: String) : P2pDownloadOutcome
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : P2pDownloadOutcome
    data object Paused : P2pDownloadOutcome
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    // Legacy (older app versions): the UNticked languages. Only read as a fallback.
    val deselectedSubtitleLanguages: List<String> = emptyList(),
    // The ticked languages; null = never saved.
    val subtitleLanguages: List<String>? = null,
    val offlineDownloadsHome: Boolean = true,
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    /** Like [decodeItems], but null when the payload cannot be decoded (instead of an empty list). */
    fun decodeItemsOrNull(payload: String): List<DownloadItem>? =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrNull()

    fun decodeDeselectedLanguages(payload: String): Set<String> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).deselectedSubtitleLanguages.toSet()
        }.getOrDefault(emptySet())

    fun decodeSelectedLanguages(payload: String): Set<String>? =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).subtitleLanguages?.toSet()
        }.getOrNull()

    fun decodeOfflineDownloadsHome(payload: String): Boolean =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).offlineDownloadsHome
        }.getOrDefault(true)

    fun encode(
        items: Collection<DownloadItem>,
        selectedSubtitleLanguages: Set<String>?,
        legacyDeselectedSubtitleLanguages: Set<String>,
        offlineDownloadsHome: Boolean,
    ): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
                deselectedSubtitleLanguages = legacyDeselectedSubtitleLanguages.toList().sorted(),
                subtitleLanguages = selectedSubtitleLanguages?.toList()?.sorted(),
                offlineDownloadsHome = offlineDownloadsHome,
            ),
        )
}

/** Saved languages are stored as the shared language key, so different spellings of one language match. */
private fun canonicalSubtitleLanguage(language: String): String = downloadSubtitleLanguageKey(language)

/** What [DownloadsRepository.previewSubtitleLanguages] found, kept until the download is queued. */
private class PreviewedSubtitles(
    val extras: SubtitleRequestExtras?,
    val subtitles: List<StreamSubtitle>,
    val fromAddons: Boolean,
)

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    extension: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String?.fileExtensionFromFileName(): String? {
    val suffix = this?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase()
        ?.trim()
        .orEmpty()
    return suffix.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
}

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
