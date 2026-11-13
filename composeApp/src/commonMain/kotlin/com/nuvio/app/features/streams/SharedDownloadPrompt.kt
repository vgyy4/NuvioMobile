package com.nuvio.app.features.streams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.downloads.DownloadItem
import kotlinx.coroutines.CompletableDeferred
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** What the user chose when another profile's download of the same video comes from a different source. */
internal enum class SharedDownloadChoice {
    /** Use the other profile's file (no download). */
    UseExisting,

    /** Download the picked source as a separate file. */
    DownloadAnyway,
}

/**
 * The question asked when another profile already downloaded this video from a different source.
 * [existingStream] describes the source of that download, [selectedStream] is the one just picked.
 * [answer] holds the choice, or null if the question was dismissed or cancelled (cancels the download).
 */
internal class SharedDownloadPrompt(
    val existingStream: StreamItem,
    val selectedStream: StreamItem,
    val answer: CompletableDeferred<SharedDownloadChoice?> = CompletableDeferred(),
)

/** A stream card's content rebuilt from what a download stored about its source. */
internal fun DownloadItem.toExistingStreamItem(): StreamItem = StreamItem(
    name = streamTitle,
    description = streamSubtitle,
    addonName = providerName,
    addonId = providerAddonId.orEmpty(),
)

@Composable
internal fun SharedDownloadDialog(prompt: SharedDownloadPrompt) {
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val formatStreamSize = rememberStreamSizeLabelFormat()
    DialogSurface(
        onDismissRequest = { prompt.answer.complete(null) },
        modifier = Modifier.heightIn(max = 640.dp),
        title = stringResource(Res.string.download_shared_title),
        message = stringResource(Res.string.download_shared_message),
    ) {
        CompositionLocalProvider(LocalStreamSizeLabelFormat provides formatStreamSize) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.download_shared_existing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StreamCard(
                    stream = prompt.existingStream,
                    enabled = false,
                    appendInstantServiceToDefaultName = false,
                    showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                    showAddonLogo = streamBadgeSettings.showAddonLogo,
                    badgePlacement = streamBadgeSettings.badgePlacement,
                    onClick = {},
                )
                Text(
                    text = stringResource(Res.string.download_shared_selected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                StreamCard(
                    stream = prompt.selectedStream,
                    enabled = false,
                    appendInstantServiceToDefaultName = false,
                    showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                    showAddonLogo = streamBadgeSettings.showAddonLogo,
                    badgePlacement = streamBadgeSettings.badgePlacement,
                    onClick = {},
                    outlined = true,
                )
            }
        }
        // Stacked, the last button is shown first: use the existing file, download anyway, cancel.
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = { prompt.answer.complete(null) },
            )
            DialogButton(
                text = stringResource(Res.string.download_shared_download_anyway),
                onClick = { prompt.answer.complete(SharedDownloadChoice.DownloadAnyway) },
            )
            DialogButton(
                text = stringResource(Res.string.download_shared_use_existing),
                onClick = { prompt.answer.complete(SharedDownloadChoice.UseExisting) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
