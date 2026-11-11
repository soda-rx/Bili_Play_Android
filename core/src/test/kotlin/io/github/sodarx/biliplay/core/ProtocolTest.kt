package io.github.sodarx.biliplay.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val image = "7cd084941338484aae1ad9425b84077c"
    private val sub = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun publishedWbiReference() {
        assertEquals(
            "bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            WbiSigner.sign(
                mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"),
                image,
                sub,
                1702204169,
            ),
        )
    }

    @Test
    fun utf8AndSignatureReplacement() {
        val values = mapOf("keyword" to "五一四 !'()*", "w_rid" to "obsolete")
        val result = WbiSigner.sign(values, image, sub, 1)
        assertTrue(result.startsWith("keyword=%E4%BA%94%E4%B8%80%E5%9B%9B%20&wts=1&"))
        assertFalse(result.contains("obsolete"))
        assertEquals("五一四 !'()*", values["keyword"])
    }

    @Test
    fun videoLinkOriginAndPart() {
        assertEquals(
            VideoInput("BV17K5r6nEod", 2),
            VideoLinks.parse("https://www.bilibili.com/video/BV17K5r6nEod?p=2"),
        )
        listOf(
                "https://bilibili.com.evil.test/video/BV17K5r6nEod",
                "https://evil.test/?url=BV17K5r6nEod",
                "https://www.bilibili.com:8443/video/BV17K5r6nEod",
                "https://user@www.bilibili.com/video/BV17K5r6nEod",
            )
            .forEach { assertNull(VideoLinks.parse(it)) }
    }

    @Test
    fun mediaUrlsCannotEscapeAllowedHttpsOrigins() {
        assertTrue(MediaUrls.allowed("https://upos-sz.bilivideo.com/audio.m4s"))
        listOf(
                "http://upos.bilivideo.com/a",
                "https://bilivideo.com.evil.test/a",
                "https://localhost/a",
                "https://127.0.0.1/a",
                "https://upos.bilivideo.com:8080/a",
                "https://user@upos.bilivideo.com/a",
            )
            .forEach { assertFalse(MediaUrls.allowed(it)) }
    }

    @Test
    fun invalidPrimaryUsesValidatedBackupAndAacPreferred() {
        val data =
            JSONObject(
                """{"dash":{"audio":[{"codecs":"mp4a.40.2","baseUrl":"https://evil.test/a","backupUrl":["https://upos.bilivideo.com/backup"]},{"codecs":"opus","baseUrl":"https://upos.bilivideo.com/opus"}]}}"""
            )
        assertEquals("https://upos.bilivideo.com/backup", AudioParser.parse(data).single().url)
    }

    @Test(expected = ApiFailure::class)
    fun forbiddenAudioDoesNotBecomePlayable() {
        AudioParser.parse(
            JSONObject("""{"dash":{"audio":[{"baseUrl":"http://localhost/audio"}]}}""")
        )
    }
}
