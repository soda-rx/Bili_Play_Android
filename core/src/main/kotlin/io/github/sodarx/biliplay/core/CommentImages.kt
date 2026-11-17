package io.github.sodarx.biliplay.core

import java.net.URI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class CommentPicture(val url: String, val width: Int, val height: Int, val sizeKb: Double = 0.0)

class CommentImageUpload(val bytes: ByteArray, val mime: String, val width: Int, val height: Int) {
    init {
        require(bytes.size in 8..CommentImages.MaxUploadBytes) { "图片文件大小无效" }
        require(width in 1..2048 && height in 1..2048) { "图片尺寸无效" }
        val png = bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
        val jpeg = bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        require((mime == "image/png" && png) || (mime == "image/jpeg" && jpeg)) { "图片格式无效" }
    }
}

object CommentImages {
    const val MaxCount = 9
    const val MaxUploadBytes = 4 * 1024 * 1024
    const val MaxInputBytes = 20 * 1024 * 1024

    fun url(value: String): String? {
        val normalized = MediaUrls.normalize(value) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        return normalized.takeIf { (host == "hdslb.com" || host.endsWith(".hdslb.com")) &&
            uri.path.startsWith("/bfs/") && uri.rawFragment == null }
    }

    fun parse(content: JSONObject?): List<CommentPicture> = items(content?.optJSONArray("pictures"))
        .take(MaxCount).mapNotNull { row ->
            val source = url(row.optString("img_src")) ?: return@mapNotNull null
            CommentPicture(source, row.optInt("img_width").coerceIn(1, 32768),
                row.optInt("img_height").coerceIn(1, 32768),
                row.optDouble("img_size", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0)
        }.distinctBy { it.url }

    fun json(pictures: List<CommentPicture>): String {
        require(pictures.size <= MaxCount) { "最多选择九张图片" }
        return JSONArray().apply { pictures.forEach { picture ->
            require(url(picture.url) == picture.url && picture.width in 1..32768 &&
                picture.height in 1..32768 && picture.sizeKb.isFinite() && picture.sizeKb > 0) { "评论图片信息无效" }
            put(JSONObject().put("img_src", picture.url).put("img_width", picture.width)
                .put("img_height", picture.height).put("img_size", picture.sizeKb))
        } }.toString()
    }
}

/** Called only after the user presses Send; selecting or previewing never uploads. */
suspend fun BilibiliApi.uploadCommentImage(image: CommentImageUpload): CommentPicture {
    val csrf = sessions.value("bili_jct") ?: throw ApiFailure(-101, "请先登录")
    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("csrf", csrf).addFormDataPart("category", "daily")
        .addFormDataPart("biz", "new_dyn")
        .addFormDataPart("file_up", if (image.mime == "image/png") "comment.png" else "comment.jpg",
            image.bytes.toRequestBody(image.mime.toMediaType())).build()
    val data = envelope(exchange(Request.Builder().url(endpoint("x/dynamic/feed/draw/upload_bfs"))
        .header("User-Agent", BilibiliApi.USER_AGENT).header("Referer", "https://www.bilibili.com/")
        .post(form).build()))
    val url = CommentImages.url(data.optString("image_url")) ?: throw ApiFailure(-1, "图片上传结果无效")
    val width = data.optInt("image_width")
    val height = data.optInt("image_height")
    if (width !in 1..32768 || height !in 1..32768) throw ApiFailure(-1, "图片上传尺寸无效")
    val size = data.optDouble("img_size", image.bytes.size / 1024.0)
    if (!size.isFinite() || size <= 0) throw ApiFailure(-1, "图片上传大小无效")
    return CommentPicture(url, width, height, size)
}
