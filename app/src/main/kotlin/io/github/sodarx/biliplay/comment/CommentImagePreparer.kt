package io.github.sodarx.biliplay.comment

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.sodarx.biliplay.core.CommentImageUpload
import io.github.sodarx.biliplay.core.CommentImages
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Decode and re-encode in memory: no original filename, EXIF/GPS or local file is uploaded. */
object CommentImagePreparer {
    suspend fun prepare(resolver: ContentResolver, uri: Uri): CommentImageUpload = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请通过系统照片选择器选择图片" }
        ensureActive()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= CommentImages.MaxInputBytes) { "所选图片超过 20 MB，请选择较小图片" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取所选图片，请重新选择")
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        var bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 80_000_000) { "所选图片尺寸过大" }
            val ratio = minOf(1.0, 2048.0 / maxOf(width, height))
            decoder.setTargetSize((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val alpha = bitmap.hasAlpha()
            val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            while (true) {
                ensureActive()
                val output = ByteArrayOutputStream()
                check(bitmap.compress(format, 88, output)) { "图片处理失败" }
                if (output.size() <= CommentImages.MaxUploadBytes)
                    return@withContext CommentImageUpload(output.toByteArray(),
                        if (alpha) "image/png" else "image/jpeg", bitmap.width, bitmap.height)
                require(maxOf(bitmap.width, bitmap.height) > 256) { "图片压缩后仍过大" }
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .75f).toInt().coerceAtLeast(1),
                    (bitmap.height * .75f).toInt().coerceAtLeast(1), true)
                bitmap.recycle()
                bitmap = smaller
            }
            @Suppress("UNREACHABLE_CODE")
            error("图片处理失败")
        } finally { bitmap.recycle() }
    }
}
