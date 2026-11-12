package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.SubtitleUnknownLanguageKey
import com.nuvio.app.features.player.languageLabelResForCode
import com.nuvio.app.features.player.subtitleLanguageKey
import com.nuvio.app.features.streams.StreamSubtitle

/**
 * The language a subtitle is grouped under in the download options: the SAME key the player's
 * subtitle picker groups by ([subtitleLanguageKey]: ISO 639-1/2/3 codes, language names and
 * regional variants such as en-US or zh-Hant collapse to one language, pt-BR and es-419 stay
 * apart). A language the app has no label for cannot be shown by name, so it is grouped with the
 * subtitles whose language is missing under [SubtitleUnknownLanguageKey] ("Unknown") instead of
 * appearing as many separate, unnamed entries.
 *
 * Idempotent: applying it to a key returns the same key.
 */
internal fun downloadSubtitleLanguageKey(language: String?): String {
    if (language == SubtitleUnknownLanguageKey) return SubtitleUnknownLanguageKey
    val key = subtitleLanguageKey(language)
    return if (key != SubtitleUnknownLanguageKey && languageLabelResForCode(key) != null) {
        key
    } else {
        SubtitleUnknownLanguageKey
    }
}

/**
 * The language keys of [subtitles], each once: the user's preferred subtitle languages first
 * (like the player's picker), then alphabetically, "Unknown" last.
 */
internal fun subtitleLanguageKeys(subtitles: List<StreamSubtitle>): List<String> {
    val keys = subtitles.map { downloadSubtitleLanguageKey(it.language) }.distinct()
    if (keys.size < 2) return keys
    PlayerSettingsRepository.ensureLoaded()
    val settings = PlayerSettingsRepository.uiState.value
    val preferred = listOf(settings.preferredSubtitleLanguage, settings.secondaryPreferredSubtitleLanguage)
        .mapNotNull { language ->
            if (
                language == null ||
                language.equals(SubtitleLanguageOption.NONE, ignoreCase = true) ||
                language.equals(SubtitleLanguageOption.FORCED, ignoreCase = true) ||
                language.equals(SubtitleLanguageOption.DEVICE, ignoreCase = true)
            ) {
                null
            } else {
                downloadSubtitleLanguageKey(language).takeUnless { it == SubtitleUnknownLanguageKey }
            }
        }
        .distinct()
    return keys.sortedWith(
        compareBy<String>(
            { key -> preferred.indexOf(key).takeIf { it >= 0 } ?: Int.MAX_VALUE },
            { key -> if (key == SubtitleUnknownLanguageKey) "￿" else key },
        ),
    )
}

/** [subtitles] in one of [languages] (language keys or any spelling of them); all if [languages] is empty. */
internal fun filterSubtitlesByLanguages(
    subtitles: List<StreamSubtitle>,
    languages: List<String>,
): List<StreamSubtitle> {
    if (languages.isEmpty()) return subtitles
    val wanted = languages.map { downloadSubtitleLanguageKey(it) }.toSet()
    return subtitles.filter { downloadSubtitleLanguageKey(it.language) in wanted }
}
