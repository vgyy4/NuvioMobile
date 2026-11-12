package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.homeSectionHorizontalPaddingForWidth
import com.nuvio.app.features.watching.application.WatchingState
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A downloaded movie (one file) or show (its downloaded episodes), shown as one poster card.
 * [downloads] are the completed files, episodes in season order.
 */
internal class DownloadedTitle(
    val key: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val downloads: List<DownloadItem>,
    val isShow: Boolean,
) {
    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = parentMetaId,
        type = parentMetaType,
        name = title,
        poster = poster,
        banner = background,
        logo = logo,
    )
}

internal fun List<DownloadItem>.toDownloadedMovies(): List<DownloadedTitle> =
    filterNot(DownloadItem::isEpisode).map { movie ->
        DownloadedTitle(
            key = movie.id,
            parentMetaId = movie.parentMetaId,
            parentMetaType = movie.parentMetaType,
            title = movie.title,
            poster = movie.poster,
            background = movie.background,
            logo = movie.logo,
            downloads = listOf(movie),
            isShow = false,
        )
    }

internal fun List<DownloadItem>.toDownloadedShows(): List<DownloadedTitle> =
    filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (parentMetaId, episodes) ->
            val sorted = episodes.sortedForSeriesDownloads()
            val first = sorted.firstOrNull() ?: return@mapNotNull null
            DownloadedTitle(
                key = parentMetaId,
                parentMetaId = parentMetaId,
                parentMetaType = first.parentMetaType,
                title = first.title,
                poster = sorted.firstNotNullOfOrNull { it.poster },
                background = sorted.firstNotNullOfOrNull { it.background },
                logo = sorted.firstNotNullOfOrNull { it.logo },
                downloads = sorted,
                isShow = true,
            )
        }
        .sortedBy { it.title.lowercase() }

/** The same poster row Home catalogs and the Library use, so size, shape and labels follow the user's poster settings. */
@Composable
internal fun DownloadsPosterShelf(
    title: String,
    entries: List<DownloadedTitle>,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onClick: (DownloadedTitle) -> Unit,
    onLongClick: (DownloadedTitle) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        NuvioShelfSection(
            title = title,
            entries = entries,
            modifier = Modifier.fillMaxWidth(),
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { entry -> entry.key },
        ) { entry ->
            val preview = entry.toMetaPreview()
            HomePosterCard(
                item = preview,
                detailLine = if (entry.isShow) {
                    stringResource(Res.string.downloads_episode_count, entry.downloads.size)
                } else {
                    null
                },
                isWatched = WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = preview,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                ),
                onClick = { onClick(entry) },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}
