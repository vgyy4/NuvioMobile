package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.player.SubtitleUnknownLanguageKey
import com.nuvio.app.features.player.getLanguageLabelForCode
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.player.normalizeLanguageCode
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object DownloadSubtitles {
    private val json = Json { ignoreUnknownKeys = true }
    private val prepareLock = Mutex()

    /**
     * Saves the subtitles of [item] next to the downloaded video. Subtitles that are already saved
     * are kept; with [topUp] a finished set is looked at again, so languages another profile that
     * shares this file asked for are added to the same folder (both profiles then have them).
     */
    suspend fun prepare(
        item: DownloadItem,
        localVideoUri: String,
        topUp: Boolean = false,
    ): Unit = withContext(Dispatchers.Default) {
        // Skip times are saved with every download, whatever the subtitle choice is.
        DownloadSkipTimes.prepare(item, localVideoUri)
        // The user chose not to download subtitles for this item.
        if (!item.downloadSubtitles) return@withContext
        // Profiles share one folder per video: one writer at a time keeps manifest.json whole.
        prepareLock.withLock { saveSubtitles(item, localVideoUri, topUp) }
    }

    private suspend fun saveSubtitles(item: DownloadItem, localVideoUri: String, topUp: Boolean) {
        val storage = DownloadSubtitleStorage(localVideoUri)
        val manifest = readManifest(storage)
        val saved = manifest.tracks.filter { storage.localFileUri(it.fileName) != null }.toMutableList()
        if (!topUp && manifest.complete && saved.size == manifest.tracks.size) return
        var nextFileIndex = (saved.maxOfOrNull { it.fileName.substringBefore('.').toIntOrNull() ?: -1 } ?: -1) + 1
        // Subtitles found when the download was queued, plus what the add-ons offer now. Filtered
        // with the same grouping as the download options, so what was ticked there is saved here.
        val pending = filterSubtitlesByLanguages(
            discover(item.subtitleRequests, item.sourceSubtitles).subtitles,
            item.subtitleLanguages,
        ).filter { subtitle -> saved.none { it.sourceUrl == subtitle.url } }
        for (batch in pending.chunked(4)) {
            val fetched = coroutineScope {
                batch.map { subtitle -> async { subtitle to fetchSubtitleBody(subtitle) } }.awaitAll()
            }
            for ((subtitle, body) in fetched) {
                currentCoroutineContext().ensureActive()
                // One subtitle that cannot be fetched or saved never fails the download.
                if (body == null) continue
                val extension = PlayerSubtitleCueParser.fileExtension(body, subtitle.url)
                val fileName = "${nextFileIndex++}.$extension"
                try {
                    // What the offline player shows: a normalized language and a readable name.
                    val language = normalizeLanguageCode(subtitle.language)
                        ?.takeIf { downloadSubtitleLanguageKey(it) != SubtitleUnknownLanguageKey }
                        ?: subtitle.language.ifBlank { "und" }
                    val name = subtitle.name?.takeIf { it.isNotBlank() } ?: languageLabel(language)
                    storage.write(fileName, body)
                    saved += DownloadedSubtitle(subtitle.url, fileName, language, name)
                    storage.write("manifest.json", json.encodeToString(SubtitleManifest(tracks = saved.toList())))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        runCatching {
            storage.write("manifest.json", json.encodeToString(SubtitleManifest(complete = true, tracks = saved)))
        }
    }

    /**
     * The subtitles that can be downloaded for a stream: the stream's own plus the ones of the
     * subtitle add-ons, looked up with [requests] exactly as the player does ([loadAddonSubtitles];
     * an add-on that fails or times out only contributes nothing). Remote files only, one per URL.
     */
    suspend fun discover(
        requests: List<SubtitleAddonRequest>,
        streamSubtitles: List<StreamSubtitle>,
    ): DiscoveredSubtitles {
        val addonSubtitles = loadAddonSubtitles(requests).map {
            StreamSubtitle(url = it.url, language = it.language, name = it.display)
        }
        return DiscoveredSubtitles(
            subtitles = (streamSubtitles + addonSubtitles)
                .filter { it.url.isDownloadableSubtitleUrl() }
                .distinctBy { it.url },
            fromAddons = addonSubtitles.isNotEmpty(),
        )
    }

    // The text of a subtitle file, or null when it cannot be fetched (tried twice) or has no cues.
    private suspend fun fetchSubtitleBody(subtitle: StreamSubtitle): String? {
        repeat(2) {
            val body = try {
                withTimeoutOrNull(15_000L) {
                    httpGetTextWithHeaders(subtitle.url, mapOf("Accept" to "*/*") + subtitle.headers.orEmpty())
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            if (body != null) {
                return body.takeIf { it.isNotBlank() && PlayerSubtitleCueParser.parse(it, subtitle.url).isNotEmpty() }
            }
        }
        return null
    }

    private suspend fun languageLabel(language: String): String? =
        try {
            getLanguageLabelForCode(language)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private fun String.isDownloadableSubtitleUrl(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    fun localSubtitles(localVideoUri: String): List<StreamSubtitle> {
        if (!localVideoUri.startsWith("file:")) return emptyList()
        val storage = DownloadSubtitleStorage(localVideoUri)
        return readManifest(storage).tracks.mapNotNull { track ->
            val uri = storage.localFileUri(track.fileName) ?: return@mapNotNull null
            StreamSubtitle(url = uri, language = track.language, name = track.name)
        }
    }

    private fun readManifest(storage: DownloadSubtitleStorage): SubtitleManifest =
        runCatching {
            storage.read("manifest.json")?.let { json.decodeFromString<SubtitleManifest>(it) }
        }.getOrNull() ?: SubtitleManifest()
}

/** [subtitles] that can be downloaded; [fromAddons] tells whether any came from a subtitle add-on. */
internal class DiscoveredSubtitles(
    val subtitles: List<StreamSubtitle>,
    val fromAddons: Boolean,
)

@Serializable
private data class SubtitleManifest(
    val complete: Boolean = false,
    val tracks: List<DownloadedSubtitle> = emptyList(),
)

@Serializable
private data class DownloadedSubtitle(
    val sourceUrl: String,
    val fileName: String,
    val language: String,
    val name: String? = null,
)

internal expect class DownloadSubtitleStorage(localVideoUri: String) {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun localFileUri(fileName: String): String?
    fun remove()
}
