package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.nuvio
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

/**
 * Movies and episodes that were watched to the end, as the same poster cards as the other shelves.
 * Every card has a trash button in its top right corner; the header deletes all of them.
 * Episodes are separate cards (show poster, show title, "S1E2 - episode title" below).
 */
@Composable
internal fun DownloadsFinishedShelf(
    items: List<DownloadItem>,
    watchedKeys: Set<String>,
    onClick: (DownloadItem) -> Unit,
    onLongClick: (DownloadItem) -> Unit,
    onTrash: (DownloadItem) -> Unit,
    onDeleteAll: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = sectionPadding, end = sectionPadding - 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.downloads_section_finished),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onDeleteAll) {
                    Text(text = stringResource(Res.string.downloads_delete_finished))
                }
            }
            NuvioShelfSection(
                title = "",
                entries = items,
                modifier = Modifier.fillMaxWidth(),
                headerHorizontalPadding = sectionPadding,
                rowContentPadding = PaddingValues(horizontal = sectionPadding),
                viewAllPillSize = NuvioViewAllPillSize.Compact,
                key = { entry -> entry.id },
            ) { entry ->
                val preview = MetaPreview(
                    id = entry.parentMetaId,
                    type = entry.parentMetaType,
                    name = entry.title,
                    poster = entry.poster,
                    banner = entry.background,
                    logo = entry.logo,
                )
                val episodeLine = if (entry.isEpisode) {
                    listOfNotNull(
                        localizedSeasonEpisodeCode(entry.seasonNumber, entry.episodeNumber),
                        entry.episodeTitle?.trim()?.takeIf { it.isNotBlank() },
                    ).joinToString(" - ").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                Box {
                    HomePosterCard(
                        item = preview,
                        detailLine = episodeLine,
                        isWatched = true,
                        onClick = { onClick(entry) },
                        onLongClick = { onLongClick(entry) },
                    )
                    IconButton(
                        onClick = { onTrash(entry) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(30.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
