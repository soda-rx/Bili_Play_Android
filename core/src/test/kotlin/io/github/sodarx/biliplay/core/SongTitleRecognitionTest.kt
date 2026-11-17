package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

/** Regression coverage for the display-only smart song title extraction. */
class SongTitleRecognitionTest {
    @Test
    fun addedBracketPairsAreRecognizedAsNames() {
        assertEquals("晴天", SongTitles.display("〖晴天〗"))
        assertEquals("晴天", SongTitles.display("〔晴天〕"))
        assertEquals("晴天", SongTitles.display("【晴天】"))
        assertEquals("晴天", SongTitles.display("(晴天)"))
        assertEquals("晴天", SongTitles.display("[晴天]"))
        assertEquals("晴天", SongTitles.display("{晴天}"))
    }

    @Test
    fun fullWidthBracketsFoldToHalfWidthPairs() {
        assertEquals("晴天", SongTitles.display("（晴天）"))
        assertEquals("晴天", SongTitles.display("［晴天］"))
        assertEquals("晴天", SongTitles.display("｛晴天｝"))
        assertEquals("晴天", SongTitles.display("【４Ｋ】《晴天》"))
        assertEquals("晴天", SongTitles.display("【高音质】翻唱《晴天》完整版"))
    }

    @Test
    fun leadingBracketNameIsUsedWhenThereIsNoBookName() {
        assertEquals("晴天", SongTitles.display("[晴天] 周杰伦"))
        assertEquals("晴天", SongTitles.display("【晴天】 - 周杰伦"))
        assertEquals("晴天 / 夜曲", SongTitles.display("〖晴天〗〖夜曲〗"))
        assertEquals("A / B", SongTitles.display("【A】 〖B〗"))
        assertEquals("晴天", SongTitles.display("《》【晴天】"))
    }

    @Test
    fun productivityBracketTagsDoNotHijackTheTitle() {
        assertEquals("晴天", SongTitles.display("【高音质】《晴天》"))
        assertEquals("晴天", SongTitles.display("《晴天》（官方MV）"))
        assertEquals("晴天", SongTitles.display("《晴天》【4K 60帧】"))
        assertEquals("晴天", SongTitles.display("【4K 60帧 高音质】《晴天》"))
        assertEquals("夜曲", SongTitles.display("（纯享版）《夜曲》"))
    }

    @Test
    fun edgeTagsArePeeledButProseInsideBracketsIsNotUsedAsAName() {
        assertEquals("周杰伦 - 晴天", SongTitles.display("周杰伦 - 晴天 (Official Video)"))
        assertEquals("周杰伦 - 晴天", SongTitles.display("周杰伦 - 晴天 (Live)"))
        assertEquals("晴天", SongTitles.display("晴天（Live）"))
        assertEquals("晴天", SongTitles.display("晴天【高音质】"))
        assertEquals("歌名", SongTitles.display("歌名（伴奏）"))
        assertEquals("【高音质】", SongTitles.display("【高音质】"))
    }

    @Test
    fun creditAndTagBracketsArePeeledFromTheDisplayTitle() {
        assertEquals("晴天", SongTitles.display("(Official Video) 晴天"))
        assertEquals("晴天", SongTitles.display("(Prod. by Somebody) 晴天"))
        assertEquals("晴天", SongTitles.display("【作曲：某某】晴天"))
        assertEquals("晴天", SongTitles.display("（原曲 某某）晴天"))
        assertEquals("晴天", SongTitles.display("【高音质】(Live) 晴天"))
    }

    @Test
    fun nestedWrappersKeepOnlyTheOutermostName() {
        assertEquals("夜曲", SongTitles.display("《「夜曲」》"))
        assertEquals("晴天", SongTitles.display("【《晴天》】"))
        assertEquals("晴天", SongTitles.display("【(晴天)】"))
        assertEquals("晴天", SongTitles.display("((晴天))"))
    }

    @Test
    fun nfkcWidthFormsAreNormalized() {
        assertEquals("ABC", SongTitles.display("《ＡＢＣ》"))
        assertEquals("Re:Zero", SongTitles.display("《Ｒｅ：Ｚｅｒｏ》"))
        assertEquals("Song", SongTitles.display("前缀＂Song＂后缀"))
    }

    @Test
    fun fullWidthAndIdeographicSpacesCollapse() {
        assertEquals("Free Loop", SongTitles.display("《\u3000Free\u00A0Loop》"))
        assertEquals("Free Loop", SongTitles.display("《Free&#160;Loop》"))
        assertEquals("Free Loop", SongTitles.display("《\u200BFree&nbsp;  Loop\uFEFF》"))
    }

    @Test
    fun invisibleAndVariationSelectorsAreStripped() {
        assertEquals("Song", SongTitles.display("《Son\u200Bg》"))
        assertEquals("Song", SongTitles.display("《Song\uFE0F》"))
    }

    @Test
    fun numericHtmlEntitiesDecodeBeforeExtraction() {
        assertEquals("晴天", SongTitles.display("&#12304;晴天&#12305;"))
        assertEquals("晴天", SongTitles.display("&#x3010;晴天&#x3011;"))
        assertEquals("Song / 夜曲", SongTitles.display("&#34;Song&#34; / &#x300A;夜曲&#x300B;"))
    }

    @Test
    fun multipleNamesKeepDocumentOrder() {
        assertEquals("A / B / C", SongTitles.display("《A》《B》《C》"))
        assertEquals(listOf("晴天", "夜曲"), SongTitles.quotedNames("《晴天》《夜曲》"))
        assertEquals(
            listOf("Song", "夜曲"),
            SongTitles.quotedNames("&#34;Song&#34; / &#x300A;夜曲&#x300B;"),
        )
    }

    @Test
    fun duplicateNamesCompareAfterCaseAndWhitespaceNormalization() {
        assertEquals("Song", SongTitles.display("《Song》［ song ］"))
        assertEquals("Song", SongTitles.display("【Song】 〔Song〕"))
        assertEquals("ABC", SongTitles.display("《ＡＢＣ》“abc”"))
        assertEquals("A / B", SongTitles.display("《A》“B”【C】"))
    }

    @Test
    fun mixedCandidateTitlesDropOnlyTagLikeNames() {
        assertEquals("晴天", SongTitles.display("“越听越上头”《晴天》"))
        assertEquals("Song", SongTitles.display("“Hi-Res” / 《Song》 / ‘4K’"))
        assertEquals("Song", SongTitles.display("“完整版”《Song》"))
    }

    @Test
    fun explicitBookNamesSurviveMetadataHeuristics() {
        assertEquals("高音质", SongTitles.display("《高音质》"))
        assertEquals("完整版", SongTitles.display("《完整版》"))
        assertEquals("翻唱", SongTitles.display("《翻唱》"))
    }

    @Test
    fun durationOnlyNamesAreSkipped() {
        assertEquals("《3:45》", SongTitles.display("《3:45》"))
        assertEquals("【03:45】", SongTitles.display("【03:45】"))
        assertEquals("【3分45秒】", SongTitles.display("【3分45秒】"))
        assertEquals("《10分20秒》", SongTitles.display("《10分20秒》"))
    }

    @Test
    fun unmatchedWrappersKeepTheOriginalTitle() {
        assertEquals("未闭合《歌曲", SongTitles.display("未闭合《歌曲"))
        assertEquals("未闭合“《Song》", SongTitles.display("未闭合“《Song》"))
        assertEquals("未闭合【翻唱", SongTitles.display("未闭合【翻唱"))
    }

    @Test
    fun unmatchedBracketStillRecoversAnEnclosedBookName() {
        assertEquals("晴天", SongTitles.display("未闭合【翻唱《晴天》"))
        assertEquals("夜曲", SongTitles.display("未闭合（《夜曲》"))
    }

    @Test
    fun bookAndQuoteWrappersStillWorkAlone() {
        assertEquals("晴天", SongTitles.display("「晴天」"))
        assertEquals("晴天", SongTitles.display("“晴天”"))
        assertEquals("晴天", SongTitles.display("‘晴天’"))
        assertEquals("晴天", SongTitles.display("'晴天'"))
        assertEquals("Don't Stop", SongTitles.display("《Don't Stop》"))
    }

    @Test
    fun edgeSeparatorsAreTrimmedFromExtractedNames() {
        assertEquals("晴天", SongTitles.display("《 - 晴天 - 》"))
        assertEquals("晴天", SongTitles.display("《　晴天　》"))
        assertEquals("晴天", SongTitles.display("《晴天》 - 周杰伦"))
    }

    @Test
    fun anchoredBracketNameSurvivesAFilteredQualityQuote() {
        assertEquals("晴天", SongTitles.display("（晴天）“Hi-Res”"))
        assertEquals("晴天", SongTitles.display("“Hi-Res”【晴天】"))
    }

    @Test
    fun disabledSwitchReturnsTheExactOriginal() {
        val raw = "【高音质】<b>《晴天》</b> &amp; &#34;Song&#34;"
        assertEquals(raw, SongTitles.display(raw, false))
    }

    @Test
    fun blankAndSymbolOnlyTitlesDoNotCrash() {
        assertEquals("", SongTitles.display(""))
        assertEquals("   ", SongTitles.display("   "))
        assertEquals("《》", SongTitles.display("《》"))
        assertEquals("()[]{}", SongTitles.display("()[]{}"))
        assertEquals("♪♪♪", SongTitles.display("♪♪♪"))
        assertEquals("【★★★】", SongTitles.display("【★★★】"))
        assertTrue(SongTitles.quotedNames(" ").isEmpty())
        assertTrue(SongTitles.quotedNames("Live performance 2026").isEmpty())
        assertTrue(SongTitles.quotedNames("【高音质】").isEmpty())
    }

    @Test
    fun overlongDescriptionCopiesAreSkippedButLongEnglishTitlesStay() {
        val description = "这是一段非常长的视频宣传描述内容需要被过滤掉".repeat(6)
        assertEquals("晴天", SongTitles.display("《晴天》“$description”"))
        val longTag = "宣传".repeat(21)
        assertEquals("晴天", SongTitles.display("【$longTag】晴天"))
        val englishTitle =
            "A Very Long English Song Title About " + List(25) { "About" }.joinToString(" ")
        assertEquals("Song / $englishTitle", SongTitles.display("《Song》\"$englishTitle\""))
    }
}
