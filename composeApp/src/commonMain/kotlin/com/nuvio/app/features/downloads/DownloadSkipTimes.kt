package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Skip times (intro, recap, outro, credits) saved next to a downloaded video, so the skip buttons
 * also work offline. Stored in the same sidecar folder as the downloaded subtitles, which means
 * they are deleted with the video and cleaned up with it when orphaned.
 */
internal object DownloadSkipTimes {
    private const val FILE_NAME = "skip.json"
    private const val FETCH_TIMEOUT_MS = 12_000L
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches and stores the skip times of [item]. Does nothing if they are already stored. */
    suspend fun prepare(item: DownloadItem, localVideoUri: String) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        if (runCatching { storage.read(FILE_NAME) }.getOrNull() != null) return
        val intervals = try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetch(item) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } ?: return
        // An empty list is stored too: it means "looked up, nothing known", so it is not retried.
        runCatching {
            storage.write(
                FILE_NAME,
                json.encodeToString(
                    StoredSkipTimes(
                        intervals.map { StoredSkipInterval(it.startTime, it.endTime, it.type, it.provider) },
                    ),
                ),
            )
        }
    }

    /** The stored skip times of a downloaded video, or null if none were stored. */
    fun local(localVideoUri: String): List<SkipInterval>? {
        if (!localVideoUri.startsWith("file:")) return null
        val text = runCatching { DownloadSubtitleStorage(localVideoUri).read(FILE_NAME) }.getOrNull() ?: return null
        return runCatching {
            json.decodeFromString<StoredSkipTimes>(text).intervals.map {
                SkipInterval(it.startTime, it.endTime, it.type, it.provider)
            }
        }.getOrNull()
    }

    private suspend fun fetch(item: DownloadItem): List<SkipInterval> {
        val videoId = item.videoId
        if (!item.isEpisode) {
            return SkipIntroRepository.getMovieSkipIntervals(
                contentId = item.parentMetaId,
                videoId = videoId,
                requireSkipIntroEnabled = false,
            )
        }
        val season = item.seasonNumber ?: return emptyList()
        val episode = item.episodeNumber ?: return emptyList()
        val imdbFromContent = item.parentMetaId.takeIf { it.startsWith("tt") }
        return when {
            videoId.startsWith("mal:") -> SkipIntroRepository.getSkipIntervalsForMal(
                malId = videoId.removePrefix("mal:").substringBefore(':'),
                episode = episode,
                requireSkipIntroEnabled = false,
                imdbId = imdbFromContent,
                imdbSeason = season,
                imdbEpisode = episode,
            )
            videoId.startsWith("kitsu:") -> SkipIntroRepository.getSkipIntervalsForKitsu(
                kitsuId = videoId.removePrefix("kitsu:").substringBefore(':'),
                episode = episode,
                requireSkipIntroEnabled = false,
                imdbId = imdbFromContent,
                imdbSeason = season,
                imdbEpisode = episode,
            )
            else -> SkipIntroRepository.getSkipIntervals(
                imdbId = videoId.substringBefore(':').takeIf { it.startsWith("tt") },
                season = season,
                episode = episode,
                requireSkipIntroEnabled = false,
            )
        }
    }
}

@Serializable
private data class StoredSkipTimes(val intervals: List<StoredSkipInterval> = emptyList())

@Serializable
private data class StoredSkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
)
