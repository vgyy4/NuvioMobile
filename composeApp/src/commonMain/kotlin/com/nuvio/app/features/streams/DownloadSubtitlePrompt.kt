package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.player.languageLabelForCode
import kotlinx.coroutines.CompletableDeferred
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** A pending "download the subtitles too?" question. [answer] is true, false, or null if dismissed. */
internal class DownloadSubtitlePrompt(
    val languageCodes: List<String>,
    val answer: CompletableDeferred<Boolean?> = CompletableDeferred(),
)

@Composable
internal fun DownloadSubtitlePromptDialog(prompt: DownloadSubtitlePrompt) {
    val languages = prompt.languageCodes.joinToString(", ") { languageLabelForCode(it) }
    DialogSurface(
        onDismissRequest = { prompt.answer.complete(null) },
        title = stringResource(Res.string.download_subtitles_prompt_title),
        message = stringResource(Res.string.download_subtitles_prompt_message, languages),
    ) {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.download_subtitles_prompt_skip),
                onClick = { prompt.answer.complete(false) },
            )
            DialogButton(
                text = stringResource(Res.string.download_subtitles_prompt_download),
                onClick = { prompt.answer.complete(true) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
