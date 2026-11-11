package io.github.sodarx.biliplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import io.github.sodarx.biliplay.core.CoverColors
import io.github.sodarx.biliplay.core.CoverPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode directly; color extraction must not depend on a hidden 1dp painter being drawn. */
internal suspend fun sampleCover(context: Context, cover: String): CoverPalette? {
    val request =
        ImageRequest.Builder(context)
            .data(cover)
            .size(96)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val image = result.image
    if (image.width <= 0 || image.height <= 0) return null
    return withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            val side = minOf(image.width, image.height).toFloat()
            val canvas = Canvas(bitmap)
            canvas.scale(96f / side, 96f / side)
            canvas.translate(-(image.width - side) / 2f, -(image.height - side) / 2f)
            image.draw(canvas)
            val pixels = IntArray(96 * 96)
            bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            CoverColors.palette(pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
