package io.github.sodarx.biliplay.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SongPresentationTest {
    @Test
    fun titleUsesBookNameInsteadOfMarketingTags() {
        assertEquals("晴天", SongTitles.display("【高音质】翻唱《晴天》完整版"))
    }

    @Test
    fun titleCollectsAllQuotedNamesInOrder() {
        assertEquals("A / B / C", SongTitles.display("《A》与「B」和“C”"))
    }

    @Test
    fun nestedWrappersAreRemoved() {
        assertEquals("夜曲", SongTitles.display("翻唱“《夜曲》”"))
    }

    @Test
    fun unmatchedQuotesKeepTheOriginalTitle() {
        assertEquals("未闭合《歌曲", SongTitles.display("未闭合《歌曲"))
    }

    @Test
    fun settingOffKeepsTheFullTitle() {
        assertEquals("前缀《Song》后缀", SongTitles.display("前缀《Song》后缀", false))
    }

    @Test
    fun noQuotesDoNotInventASongName() {
        assertEquals("Live performance 2026", SongTitles.display("Live performance 2026"))
    }

    @Test
    fun htmlQuotesAndApostropheInsideNamesAreHandled() {
        assertEquals("Song", SongTitles.display("前缀 &quot;Song&quot; 后缀"))
        assertEquals("Don't Stop", SongTitles.display("Live 'Don't Stop' cover"))
    }

    @Test
    fun emptyNamesAndDuplicatesDoNotPolluteTitle() {
        assertEquals("A", SongTitles.display("《  》《A》《A》"))
    }

    @Test
    fun musicNotesAreRemovedFromDownloadedAndCachedLyrics() {
        assertEquals("Hello world", LyricText.clean("♪🎵 Hello world ♫🎶"))
        assertEquals(
            1,
            parseSubtitle(
                    JSONObject(
                        """{"body":[{"from":0,"to":1,"content":"♪♫"},{"from":1,"to":2,"content":"🎵Hello"}]}"""
                    )
                )
                .size,
        )
    }

    @Test
    fun creatorsSubtitleIsNotAiJustBecauseAiTypeIsNonzero() {
        val tracks =
            parseSubtitleTracks(
                JSONObject(
                    """{"subtitle":{"subtitles":[{"lan":"en","lan_doc":"English","type":0,"ai_type":1,"subtitle_url":"https://i0.hdslb.com/synthetic.json"}]}}"""
                )
            )
        assertFalse(tracks.single().automatic)
    }

    @Test
    fun automaticSubtitleStillHasItsAiFlag() {
        val tracks =
            parseSubtitleTracks(
                JSONObject(
                    """{"subtitle":{"subtitles":[{"lan":"ai-en","lan_doc":"English","type":1,"ai_type":0,"subtitle_url":"https://i0.hdslb.com/synthetic.json"}]}}"""
                )
            )
        assertTrue(tracks.single().automatic)
    }

    private fun sub(
        lang: String,
        label: String = lang,
        automatic: Boolean = false,
        source: String = "",
    ) = SubtitleTrack(lang, label, "https://i0.hdslb.com/synthetic.json", automatic, source)

    @Test
    fun sourceMetadataOutranksTitleLanguage() {
        val french = sub("fr", source = "fr")
        val english = sub("en", source = "fr")
        assertEquals(french, SubtitlePolicy.choose(listOf(english, french), "English Song"))
    }

    @Test
    fun originalLabelOutranksAnEnglishMarketingTitle() {
        val japanese = sub("ja", "日文原文")
        assertEquals(japanese, SubtitlePolicy.choose(listOf(sub("en"), japanese), "English Song"))
    }

    @Test
    fun quotedSongLanguageIgnoresChineseMarketingProse() {
        val english = sub("en")
        assertEquals(
            english,
            SubtitlePolicy.choose(listOf(sub("zh"), english), "【高音质循环收藏】《Yesterday》完整版"),
        )
    }

    @Test
    fun originalAsrOutranksATranslation() {
        val english = sub("ai-en", automatic = true)
        assertEquals(english, SubtitlePolicy.choose(listOf(sub("zh", "中文翻译"), english), "中文宣传标题"))
    }

    @Test
    fun regionalAndCantoneseCodesAreSupported() {
        val english = sub("en-US")
        assertEquals(english, SubtitlePolicy.choose(listOf(sub("zh"), english), "《Yesterday》"))
        val chinese = sub("zh-Hant")
        assertEquals(chinese, SubtitlePolicy.choose(listOf(sub("en"), chinese), "粤语歌曲《光辉岁月》"))
    }
}
