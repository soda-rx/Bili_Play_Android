package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LibraryLaunchTitleRegressionTest {
    @Test
    fun marketingQuoteDoesNotBecomePartOfTheBookTitle() =
        assertEquals("New Heart", SongTitles.display("“越听越上头” || 《New Heart》"))

    @Test
    fun nestedWrappersYieldTheActualName() = assertEquals("夜曲", SongTitles.display("翻唱“《「夜曲」》”"))

    @Test
    fun multipleBooksInsideAnOuterSentenceAreCollected() =
        assertEquals("A / B", SongTitles.display("“听听《A》和《B》”"))

    @Test
    fun internalPunctuationInABookTitleSurvives() =
        assertEquals("他说“你好”", SongTitles.display("《他说“你好”》现场"))

    @Test
    fun englishApostrophesDoNotEndAQuotedTitle() =
        assertEquals("Don't Stop", SongTitles.display("Live 'Don't Stop' cover"))

    @Test
    fun typographicApostrophesInsideWordsSurvive() =
        assertEquals("I'm / Don’t Stop", SongTitles.display("“I'm” 与 ‘Don’t Stop’"))

    @Test
    fun numericHtmlQuotesAndBooksAreDecoded() =
        assertEquals("Song / 夜曲", SongTitles.display("&#34;Song&#34; / &#x300A;夜曲&#x300B;"))

    @Test
    fun invisibleAndRepeatedWhitespaceIsNormalized() =
        assertEquals("Free Loop", SongTitles.display("《\u200BFree&nbsp;  Loop\uFEFF》"))

    @Test
    fun repeatedNamesIgnoreCaseButKeepTheFirstSpelling() =
        assertEquals("Song", SongTitles.display("《Song》 / “song” / 『SONG』"))

    @Test
    fun anUnclosedOuterQuoteCannotInventAnInnerName() =
        assertEquals("未闭合“《Song》", SongTitles.display("未闭合“《Song》"))

    @Test
    fun qualityLabelsAreNotSongNamesWhenThereIsAnActualTitle() =
        assertEquals("Song", SongTitles.display("“Hi-Res” / 《Song》 / ‘4K’"))

    @Test
    fun disabledExtractionReturnsTheExactOriginal() {
        val raw = "“越听越上头” &amp; 《Song》"
        assertEquals(raw, SongTitles.display(raw, false))
    }

    @Test
    fun apostropheQuotesNextToChinesePrefixesAreStillRecognized() =
        assertEquals("夜曲", SongTitles.display("翻唱'夜曲'现场"))

    @Test
    fun aLoneQuotedPhraseIsNotDiscardedByTheMarketingHeuristic() =
        assertEquals("越听越上头", SongTitles.display("新歌“越听越上头”"))

    @Test
    fun systemPlaybackTapOpensAnAuthenticatedQueuedSong() =
        assertTrue(PlaybackLaunch.canOpen(true, true, true, "a", listOf("a")))

    @Test
    fun systemPlaybackTapHonorsStartupAndAccountGates() {
        assertFalse(PlaybackLaunch.canOpen(false, true, true, "a", listOf("a")))
        assertFalse(PlaybackLaunch.canOpen(true, false, true, "a", listOf("a")))
        assertFalse(PlaybackLaunch.canOpen(true, true, false, "a", listOf("a")))
    }

    @Test
    fun aStaleSystemCardCannotOpenARemovedOrEmptySong() {
        assertFalse(PlaybackLaunch.canOpen(true, true, true, null, emptyList()))
        assertFalse(PlaybackLaunch.canOpen(true, true, true, "a", listOf("b")))
    }

    @Test
    fun theSystemTapUsesTheCurrentMemberRatherThanTheFirstSong() =
        assertTrue(PlaybackLaunch.canOpen(true, true, true, "b", listOf("a", "b")))

    private fun client(requests: MutableList<Map<String, String>>): BilibiliApi =
        BilibiliApi(
            sessions = SessionCookies().apply { importHeader("bili_jct=synthetic_csrf") },
            client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val body = req.body as FormBody
                        requests +=
                            (0 until body.size).associate { body.name(it) to body.value(it) }
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("Synthetic")
                            .body(
                                """{"code":0,"data":{}}"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                    .build(),
        )

    @Test
    fun publicAndPrivateFolderChoicesUseOppositeServerValues() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val api = client(requests)
        api.createFolder("Synthetic public", false)
        api.createFolder("Synthetic private", true)
        assertEquals(listOf("0", "1"), requests.map { it["privacy"] })
    }

    @Test
    fun checkboxesCanAddAndRemoveDifferentFoldersIndependently() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val api = client(requests)
        val track = Track(42, "BV0000000042", "Synthetic", "Synthetic", "", 10)
        api.favorite(track, Folder(1, "Synthetic A", 0, true), true)
        api.favorite(track, Folder(2, "Synthetic B", 0, true), true)
        api.favorite(track, Folder(1, "Synthetic A", 0, true), false)
        assertEquals(listOf("1", "2", null), requests.map { it["add_media_ids"] })
        assertEquals(listOf(null, null, "1"), requests.map { it["del_media_ids"] })
    }

    @Test
    fun subscribedFoldersRemainReadOnly() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val api = client(requests)
        try {
            api.favorite(
                Track(42, "BV0000000042", "Synthetic", "Synthetic", "", 10),
                Folder(1, "Synthetic", 0, false),
                true,
            )
            fail("read-only folder must reject writes")
        } catch (_: IllegalArgumentException) {
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun onlyReselectingHomeCanRequestScrollOrRefresh() {
        assertTrue(HomeTabAction.isReselection("home", "home"))
        for (from in listOf("favorites", "mine", "search", "player")) assertFalse(
            HomeTabAction.isReselection(from, "home")
        )
        assertFalse(HomeTabAction.isReselection("home", "favorites"))
    }
}
