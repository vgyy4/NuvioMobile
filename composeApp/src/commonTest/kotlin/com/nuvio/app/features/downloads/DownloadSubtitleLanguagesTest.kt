package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleUnknownLanguageKey
import com.nuvio.app.features.streams.StreamSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadSubtitleLanguagesTest {

    @Test
    fun differentSpellingsOfOneLanguageShareAKey() {
        listOf("en", "eng", "English", "en-US", "en_GB", "English [CC]").forEach {
            assertEquals("en", downloadSubtitleLanguageKey(it), it)
        }
        listOf("fr", "fre", "fra", "French").forEach {
            assertEquals("fr", downloadSubtitleLanguageKey(it), it)
        }
        listOf("zh", "zho", "chi", "zh-Hant", "zh-TW").forEach {
            assertEquals("zh", downloadSubtitleLanguageKey(it), it)
        }
        assertEquals("es", downloadSubtitleLanguageKey("es-ES"))
    }

    @Test
    fun brazilianPortugueseAndLatinAmericanSpanishStayApart() {
        assertEquals("pt-br", downloadSubtitleLanguageKey("pt-BR"))
        assertEquals("pt-br", downloadSubtitleLanguageKey("Portuguese (Brazil)"))
        assertEquals("pt", downloadSubtitleLanguageKey("por"))
        assertEquals("es-419", downloadSubtitleLanguageKey("es-419"))
    }

    @Test
    fun missingOrUnrecognisedLanguagesFallBackToUnknown() {
        listOf(null, "", "  ", "und", "unknown", "xyz").forEach {
            assertEquals(SubtitleUnknownLanguageKey, downloadSubtitleLanguageKey(it), it.toString())
        }
    }

    @Test
    fun keysAreStable() {
        listOf("en", "pt-br", "es-419", "zh", SubtitleUnknownLanguageKey).forEach {
            assertEquals(it, downloadSubtitleLanguageKey(it))
        }
    }

    @Test
    fun filtersSubtitlesByTheChosenLanguageKeys() {
        val english = StreamSubtitle("https://example.com/en.srt", "eng")
        val americanEnglish = StreamSubtitle("https://example.com/us.srt", "en-US")
        val french = StreamSubtitle("https://example.com/fr.srt", "fre")
        val unknown = StreamSubtitle("https://example.com/xx.srt", "")
        val all = listOf(english, americanEnglish, french, unknown)

        assertEquals(all, filterSubtitlesByLanguages(all, emptyList()))
        assertEquals(listOf(english, americanEnglish), filterSubtitlesByLanguages(all, listOf("en")))
        assertEquals(listOf(french, unknown), filterSubtitlesByLanguages(all, listOf("fr", SubtitleUnknownLanguageKey)))
    }
}
