package com.nuvio.app.features.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.downloads.DownloadSubtitleMode
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.features.player.normalizeLanguageCode
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Settings for the subtitles that are downloaded together with a video. */
@Composable
internal fun DownloadSubtitleSettings(isTablet: Boolean) {
    val preferences by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.preferences
    }.collectAsStateWithLifecycle()
    var showModeDialog by remember { mutableStateOf(false) }
    var showLanguagesDialog by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.settings_downloads_subtitles_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_downloads_subtitles_mode),
                description = downloadSubtitleModeLabel(preferences.subtitleMode),
                isTablet = isTablet,
                onClick = { showModeDialog = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_downloads_subtitles_languages),
                description = if (preferences.subtitleLanguages.isEmpty()) {
                    stringResource(Res.string.settings_downloads_subtitles_languages_all)
                } else {
                    preferences.subtitleLanguages.joinToString(", ") { languageLabelForCode(it) }
                },
                enabled = preferences.subtitleMode != DownloadSubtitleMode.Never,
                isTablet = isTablet,
                onClick = { showLanguagesDialog = true },
            )
        }
    }

    if (showModeDialog) {
        DialogSurface(
            onDismissRequest = { showModeDialog = false },
            title = stringResource(Res.string.settings_downloads_subtitles_mode),
        ) {
            DownloadSubtitleMode.entries.forEach { mode ->
                DialogOption(
                    text = downloadSubtitleModeLabel(mode),
                    selected = preferences.subtitleMode == mode,
                    onClick = {
                        DownloadsRepository.setSubtitleMode(mode)
                        showModeDialog = false
                    },
                )
            }
        }
    }

    if (showLanguagesDialog) {
        val selected = preferences.subtitleLanguages.mapNotNull(::normalizeLanguageCode).toSet()
        DialogSurface(
            onDismissRequest = { showLanguagesDialog = false },
            modifier = Modifier.heightIn(max = 560.dp),
            title = stringResource(Res.string.settings_downloads_subtitles_languages),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    DialogOption(
                        text = stringResource(Res.string.settings_downloads_subtitles_languages_all),
                        selected = selected.isEmpty(),
                        onClick = { DownloadsRepository.setSubtitleLanguages(emptyList()) },
                    )
                }
                items(AvailableLanguageOptions, key = { it.code }) { option ->
                    val code = normalizeLanguageCode(option.code) ?: option.code
                    val isSelected = code in selected
                    DialogOption(
                        text = stringResource(option.labelRes),
                        selected = isSelected,
                        role = Role.Checkbox,
                        onClick = {
                            val updated = if (isSelected) selected - code else selected + code
                            DownloadsRepository.setSubtitleLanguages(updated.toList())
                        },
                    )
                }
            }
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_done),
                    onClick = { showLanguagesDialog = false },
                )
            }
        }
    }
}

@Composable
private fun downloadSubtitleModeLabel(mode: DownloadSubtitleMode): String = when (mode) {
    DownloadSubtitleMode.Ask -> stringResource(Res.string.settings_downloads_subtitles_mode_ask)
    DownloadSubtitleMode.Always -> stringResource(Res.string.settings_downloads_subtitles_mode_always)
    DownloadSubtitleMode.Never -> stringResource(Res.string.settings_downloads_subtitles_mode_never)
}
