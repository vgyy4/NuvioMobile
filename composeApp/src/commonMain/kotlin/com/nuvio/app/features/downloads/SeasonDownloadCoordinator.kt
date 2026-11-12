package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.streams.SeasonBatchEpisode
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/** Everything needed to download the rest of a season once the user picked the first episode's source. */
internal data class SeasonDownloadBatch(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    /** The source the user picked for the first episode; later episodes get the matching release. */
    val template: StreamItem,
    val downloadSubtitles: Boolean,
    val subtitleLanguages: List<String>,
    val episodes: List<SeasonBatchEpisode>,
)

/**
 * Downloads the remaining episodes of a season. For each one it looks up the available sources and
 * takes the release that matches the one picked for the first episode: the same "binge group" (the
 * add-on's own marker for "same release, next episode"), otherwise the same add-on at the same
 * resolution. Episodes without a matching source are skipped and counted in the summary, never
 * downloaded from some other release unasked. Episodes that already have a download are left alone.
 */
internal object SeasonDownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The source lookup used here has one slot, so seasons are processed one at a time.
    private val mutex = Mutex()

    private const val LOOKUP_TIMEOUT_MS = 60_000L
    private const val LOOKUP_POLL_MS = 400L

    fun start(batch: SeasonDownloadBatch) {
        if (batch.episodes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                var started = 0
                var noSource = 0
                var alreadyDownloaded = 0
                for (episode in batch.episodes) {
                    try {
                        when (downloadEpisode(batch, episode)) {
                            EpisodeOutcome.Started -> started += 1
                            EpisodeOutcome.NoSource -> noSource += 1
                            EpisodeOutcome.AlreadyDownloaded -> alreadyDownloaded += 1
                            EpisodeOutcome.Failed -> noSource += 1
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        noSource += 1
                    }
                }
                PlayerStreamsRepository.clearEpisodeStreams()
                NuvioToastController.show(
                    getString(Res.string.season_download_summary, started, noSource, alreadyDownloaded),
                )
            }
        }
    }

    private enum class EpisodeOutcome { Started, NoSource, AlreadyDownloaded, Failed }

    private suspend fun downloadEpisode(batch: SeasonDownloadBatch, episode: SeasonBatchEpisode): EpisodeOutcome {
        DownloadsRepository.ensureLoaded()
        val logicalKey = "${batch.parentMetaId.trim()}|${episode.season}|${episode.episode}"
        if (DownloadsRepository.uiState.value.items.any { it.logicalContentKey == logicalKey }) {
            return EpisodeOutcome.AlreadyDownloaded
        }

        val candidates = lookUpSources(batch, episode)
        val chosen = chooseMatchingSource(batch.template, candidates) ?: return EpisodeOutcome.NoSource

        val target = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(chosen)) {
            when (
                val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream = chosen,
                    season = episode.season,
                    episode = episode.episode,
                )
            ) {
                is DirectDebridPlayableResult.Success -> resolved.stream
                else -> return EpisodeOutcome.Failed
            }
        } else {
            chosen
        }

        val result = DownloadsRepository.enqueueFromStream(
            contentType = batch.contentType,
            videoId = episode.videoId,
            parentMetaId = batch.parentMetaId,
            parentMetaType = batch.parentMetaType,
            title = batch.title,
            logo = batch.logo,
            poster = batch.poster,
            background = batch.background,
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
            stream = target,
            downloadSubtitles = batch.downloadSubtitles,
            subtitleLanguages = batch.subtitleLanguages,
        )
        return if (result == DownloadEnqueueResult.Started) EpisodeOutcome.Started else EpisodeOutcome.Failed
    }

    private suspend fun lookUpSources(batch: SeasonDownloadBatch, episode: SeasonBatchEpisode): List<StreamItem> {
        PlayerStreamsRepository.loadEpisodeStreams(
            type = batch.contentType,
            videoId = episode.videoId,
            season = episode.season,
            episode = episode.episode,
            forceRefresh = false,
        )
        // Sources arrive from several add-ons over time: wait until they have all answered.
        val state = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            while (true) {
                val current = PlayerStreamsRepository.episodeStreamsState.value
                if (!current.isAnyLoading && (current.hasAnyStreams || current.emptyStateReason != null)) {
                    return@withTimeoutOrNull current
                }
                delay(LOOKUP_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: PlayerStreamsRepository.episodeStreamsState.value
        return state.groups.flatMap { it.streams }
    }

    /** The release that matches [template], or null if this episode has none. */
    internal fun chooseMatchingSource(template: StreamItem, candidates: List<StreamItem>): StreamItem? {
        val usable = candidates.filter { it.playableDirectUrl != null || (it.isTorrentStream && it.p2pInfoHash != null) }
        val bingeGroup = template.behaviorHints.bingeGroup?.takeIf { it.isNotBlank() }
        if (bingeGroup != null) {
            usable.firstOrNull { it.behaviorHints.bingeGroup == bingeGroup }?.let { return it }
        }
        val templateResolution = template.resolutionToken()
        return usable.firstOrNull { candidate ->
            candidate.addonId == template.addonId &&
                (templateResolution == null || candidate.resolutionToken() == templateResolution)
        }
    }

    private val resolutionPattern = Regex("(2160p|4k|uhd|1440p|1080p|720p|576p|480p)", RegexOption.IGNORE_CASE)

    private fun StreamItem.resolutionToken(): String? {
        val text = "${name.orEmpty()} ${title.orEmpty()} ${description.orEmpty()} ${behaviorHints.filename.orEmpty()}"
        val raw = resolutionPattern.find(text)?.value?.lowercase() ?: return null
        return if (raw == "4k" || raw == "uhd") "2160p" else raw
    }
}
