package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pDownloadStream
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.p2p.buildP2pMagnetUri
import com.nuvio.app.features.p2p.formatP2pMegabytes
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
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

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
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
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
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
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
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
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = if (p2pInfoHash != null) {
                    SubtitleRequestExtras.from(stream.behaviorHints)
                } else {
                    SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl)
                },
            ),
            sourceSubtitles = stream.externalSubtitles,
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
     * Called when the app goes to the background on a platform that cannot keep downloading there.
     * The downloads are resumed automatically when the app comes back.
     */
    fun pauseActiveDownloads() {
        ensureLoaded()
        val active = _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
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
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
    }

    private fun loadFromDisk() {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        observeAppForeground()
        // Leftovers of downloads that no longer exist (crash, forced quit, restart mid-cancel).
        // Delayed and age-checked so a download started right now is never touched.
        p2pScope.launch {
            delay(ORPHAN_SWEEP_DELAY_MS)
            DownloadsPlatformDownloader.removeOrphanedPartialFiles(
                _uiState.value.items.mapTo(mutableSetOf()) { it.fileName },
            )
        }
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)

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
            DownloadsCodec.encodeItems(_uiState.value.items),
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

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

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

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
