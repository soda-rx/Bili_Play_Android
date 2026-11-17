package io.github.sodarx.biliplay.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateAndQualityTest {
    private val normal = AudioSource("https://upos-sz-mirrorcos.bilivideo.com/normal.m4s", emptyList(), "标准", "audio/mp4")
    private val hires = normal.copy(url = "https://upos-sz-mirrorcos.bilivideo.com/hires.m4s", quality = "Hi-Res")

    @Test fun highQualityPreferenceSurvivesTracksWithoutHiResAndNewSourceLists() {
        val preference = PlaybackQuality.HiRes
        assertEquals(1, PlaybackQuality.select(listOf(normal, hires), preference))
        assertEquals(0, PlaybackQuality.select(listOf(normal), preference))
        assertEquals(0, PlaybackQuality.select(listOf(hires, normal), preference))
        assertEquals(1, PlaybackQuality.select(listOf(normal, hires), preference))
    }

    @Test fun explicitlySelectingStandardDoesNotUpgradeTheNextTrack() {
        assertEquals(0, PlaybackQuality.select(listOf(normal, hires), PlaybackQuality.Standard))
        assertEquals(1, PlaybackQuality.select(listOf(hires, normal), PlaybackQuality.Standard))
        assertEquals(0, PlaybackQuality.select(emptyList(), PlaybackQuality.HiRes))
    }

    @Test fun freshAccountsDefaultToTheHighestAvailableSource() {
        assertEquals(PlaybackQuality.Automatic, PlaybackQuality.normalize("unexpected"))
        assertEquals(1, PlaybackQuality.select(listOf(normal, hires), PlaybackQuality.Automatic))
        assertEquals(0, PlaybackQuality.select(listOf(hires, normal), PlaybackQuality.Automatic))
        assertEquals(0, PlaybackQuality.select(listOf(normal), PlaybackQuality.Automatic))
        assertEquals(0, PlaybackQuality.select(emptyList(), PlaybackQuality.Automatic))
    }

    @Test fun selectedNameMatchesTheSourceThatActuallyPlays() {
        assertEquals(
            "Hi-Res",
            PlaybackQuality.selectedName(listOf(normal, hires), PlaybackQuality.Automatic),
        )
        assertEquals(
            "标准",
            PlaybackQuality.selectedName(listOf(normal, hires), PlaybackQuality.Standard),
        )
        assertEquals(
            "标准",
            PlaybackQuality.selectedName(listOf(normal), PlaybackQuality.HiRes),
        )
    }

    @Test fun downloadDefaultsPreferHiResOnlyForEligibleAccounts() {
        assertEquals(
            PlaybackQuality.HiRes,
            PlaybackQuality.downloadDefault(PlaybackQuality.Automatic, vip = true),
        )
        assertEquals(
            PlaybackQuality.HiRes,
            PlaybackQuality.downloadDefault(PlaybackQuality.HiRes, vip = true),
        )
        assertEquals(
            PlaybackQuality.Standard,
            PlaybackQuality.downloadDefault(PlaybackQuality.Standard, vip = true),
        )
        assertEquals(
            PlaybackQuality.Standard,
            PlaybackQuality.downloadDefault(PlaybackQuality.Automatic, vip = false),
        )
    }

    private fun release(tag: String = "0.3.0", extension: String = "apk"): JSONObject = JSONObject()
        .put("tag_name", tag).put("draft", false).put("prerelease", false).put("body", "Synthetic release")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "Bili_Play_Android_$tag.$extension")
            .put("browser_download_url", "https://github.com/soda-rx/Bili_Play_Android/releases/download/$tag/Bili_Play_Android_$tag.$extension")
            .put("size", 1024)))

    @Test fun stableUpdatesUseNumericVersionsAndNeverDowngrade() {
        assertNotNull(ReleaseUpdate.parse(release("0.10.0"), "0.9.99"))
        assertNull(ReleaseUpdate.parse(release(), "0.3.0"))
        assertNull(ReleaseUpdate.parse(release(), "1.0.0"))
        assertNotNull(ReleaseUpdate.parse(release(), "0.3.0-rc.1"))
        assertNull(StableVersion.parse("01.2.3"))
        assertNull(StableVersion.parse("0.3.0-beta.1"))
    }

    @Test fun draftsPreviewsAndReleasesWithoutTheExpectedApkAreIgnored() {
        assertNull(ReleaseUpdate.parse(release().put("draft", true), "0.2.33"))
        assertNull(ReleaseUpdate.parse(release().put("prerelease", true), "0.2.33"))
        assertNull(ReleaseUpdate.parse(release("0.3.0-beta.1"), "0.2.33"))
        assertNull(ReleaseUpdate.parse(release(extension = "zip"), "0.2.33"))
        assertNotNull(ReleaseUpdate.parse(release(extension = "APK"), "0.2.33"))
    }

    @Test fun updateAssetsMustBelongToThisRepositoryAndUseHttps() {
        for (url in listOf("http://github.com/soda-rx/Bili_Play_Android/releases/download/0.3.0/Bili_Play_Android_0.3.0.apk",
            "https://github.com.evil.invalid/soda-rx/Bili_Play_Android/releases/download/0.3.0/Bili_Play_Android_0.3.0.apk",
            "https://github.com/other/repository/releases/download/0.3.0/Bili_Play_Android_0.3.0.apk")) {
            val data = release()
            data.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertNull(ReleaseUpdate.parse(data, "0.2.33"))
        }
    }

    @Test fun malformedDigestsOversizedAndAmbiguousAssetsAreRejected() {
        val data = release()
        val asset = data.getJSONArray("assets").getJSONObject(0)
        asset.put("size", ReleaseUpdate.MaxBytes + 1)
        assertNull(ReleaseUpdate.parse(data, "0.2.33"))
        asset.put("size", 1024).put("digest", "sha256:invalid")
        assertNull(ReleaseUpdate.parse(data, "0.2.33"))
        asset.put("digest", "sha256:" + "a".repeat(64))
        assertEquals("a".repeat(64), ReleaseUpdate.parse(data, "0.2.33")?.sha256)
        data.getJSONArray("assets").put(JSONObject(asset.toString()))
        assertNull(ReleaseUpdate.parse(data, "0.2.33"))
    }

    @Test fun redirectsCannotLeakRequestsToArbitraryHosts() {
        assertTrue(ReleaseUpdate.trustedDownloadUrl("https://release-assets.githubusercontent.com/synthetic"))
        assertFalse(ReleaseUpdate.trustedDownloadUrl("https://release-assets.githubusercontent.com.evil.invalid/synthetic"))
        assertFalse(ReleaseUpdate.trustedDownloadUrl("https://github.com@evil.invalid/synthetic"))
        assertFalse(ReleaseUpdate.trustedDownloadUrl("http://github.com/synthetic"))
        assertFalse(ReleaseUpdate.trustedDownloadUrl("https://github.com:8443/synthetic"))
    }

    @Test fun layoutRespondsToWidthAndAllowsFullHeightDockTravel() {
        assertEquals(2, AdaptiveLayout.homeColumns(360f))
        assertTrue(AdaptiveLayout.homeColumns(1280f) >= 5)
        assertTrue(AdaptiveLayout.splitPlayer(800f, 1000f))
        assertFalse(AdaptiveLayout.splitPlayer(400f, 850f))
        assertFalse(AdaptiveLayout.splitPlayer(900f, 250f))
        assertEquals(700f, AdaptiveLayout.dragTravel(900f, 40f, 740f), 0f)
        assertEquals(0f, AdaptiveLayout.dragTravel(Float.NaN, 0f, 1f), 0f)
    }

    @Test fun folderSearchRetainsItsQueryWhenRequestingLaterPages() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("""{"code":0,"data":{"medias":[],"has_more":false}}""")) }
            val api = BilibiliApi(apiBase = server.url("/"))
            api.folderTracks(42, 1, "Synthetic Song")
            api.folderTracks(42, 2, "Synthetic Song")
            for (page in 1..2) {
                val request = server.takeRequest().requestUrl!!
                assertEquals("42", request.queryParameter("media_id"))
                assertEquals("Synthetic Song", request.queryParameter("keyword"))
                assertEquals(page.toString(), request.queryParameter("pn"))
            }
        }
    }
}
