package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioStatusModal
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal enum class DeleteDownloadsKind { Episode, Movie, Season, Show }

/** What the user asked to delete from a context menu, shown for confirmation first. */
internal class DeleteDownloadsRequest(
    val kind: DeleteDownloadsKind,
    val downloadIds: List<String>,
)

/** Asks for confirmation, then deletes. Renders nothing while [request] is null. */
@Composable
internal fun DeleteDownloadsHost(request: DeleteDownloadsRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    NuvioStatusModal(
        title = stringResource(
            when (request.kind) {
                DeleteDownloadsKind.Episode -> Res.string.delete_download_episode_title
                DeleteDownloadsKind.Movie -> Res.string.delete_download_movie_title
                DeleteDownloadsKind.Season -> Res.string.delete_download_season_title
                DeleteDownloadsKind.Show -> Res.string.delete_download_show_title
            },
        ),
        message = if (request.kind == DeleteDownloadsKind.Season || request.kind == DeleteDownloadsKind.Show) {
            stringResource(Res.string.delete_download_season_message, request.downloadIds.size)
        } else {
            stringResource(Res.string.delete_download_message)
        },
        isVisible = true,
        destructive = true,
        confirmText = stringResource(Res.string.action_delete),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            DownloadsRepository.deleteDownloads(request.downloadIds)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun deleteDownloadLabel(kind: DeleteDownloadsKind): String = stringResource(
    when (kind) {
        DeleteDownloadsKind.Episode -> Res.string.delete_download_episode
        DeleteDownloadsKind.Movie -> Res.string.delete_download_movie
        DeleteDownloadsKind.Season -> Res.string.delete_download_season
        DeleteDownloadsKind.Show -> Res.string.delete_download_show
    },
)
