package com.nuvio.app.features.streams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.player.SubtitleUnknownLanguageKey
import com.nuvio.app.features.player.languageLabelForCode
import kotlinx.coroutines.CompletableDeferred
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The options asked for before a download starts. [subtitleLanguageCodes] are language keys (see
 * `downloadSubtitleLanguageKey`). [answer] holds the subtitle languages to download (empty = no
 * subtitles), or null if the question was dismissed (cancels the download).
 */
internal class DownloadOptionsPrompt(
    val subtitleLanguageCodes: List<String>,
    /** Languages ticked when the dialog opens: what was ticked last time (everything on the first run). */
    val initiallySelected: Set<String> = subtitleLanguageCodes.toSet(),
    val answer: CompletableDeferred<List<String>?> = CompletableDeferred(),
)

@Composable
internal fun DownloadOptionsDialog(prompt: DownloadOptionsPrompt) {
    var selected by remember(prompt) { mutableStateOf(prompt.initiallySelected) }
    DialogSurface(
        onDismissRequest = { prompt.answer.complete(null) },
        modifier = Modifier.heightIn(max = 560.dp),
        title = stringResource(Res.string.download_options_title),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "subtitles-header") {
                DialogOption(
                    text = stringResource(Res.string.download_options_subtitles),
                    description = stringResource(Res.string.download_options_subtitles_hint),
                    selected = selected.size == prompt.subtitleLanguageCodes.size,
                    role = Role.Checkbox,
                    onClick = {
                        selected = if (selected.size == prompt.subtitleLanguageCodes.size) {
                            emptySet()
                        } else {
                            prompt.subtitleLanguageCodes.toSet()
                        }
                    },
                )
            }
            items(prompt.subtitleLanguageCodes, key = { it }) { code ->
                val isSelected = code in selected
                DialogOption(
                    // Subtitles whose language is missing or not recognised share one "Unknown" entry.
                    text = if (code == SubtitleUnknownLanguageKey) {
                        stringResource(Res.string.subtitle_language_unknown)
                    } else {
                        languageLabelForCode(code)
                    },
                    selected = isSelected,
                    role = Role.Checkbox,
                    onClick = { selected = if (isSelected) selected - code else selected + code },
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = { prompt.answer.complete(null) },
            )
            DialogButton(
                text = stringResource(Res.string.download_options_download),
                onClick = {
                    // Keep the order the languages were offered in.
                    prompt.answer.complete(prompt.subtitleLanguageCodes.filter { it in selected })
                },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
