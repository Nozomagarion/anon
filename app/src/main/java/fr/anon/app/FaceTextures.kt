package fr.anon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Only fictional pixels enter these textures. Source face pixels are never blurred or blended. */
class FaceTextures(context: Context, strength: Int) : AutoCloseable {
    val tiles: List<Bitmap>
    init {
        val atlas = context.assets.open("faces.webp").use(BitmapFactory::decodeStream)
            ?: error("Les visages de remplacement sont introuvables.")
        tiles = (0..3).map { index ->
            val crop = Bitmap.createBitmap(atlas, (index % 2) * (atlas.width / 2),
                (index / 2) * (atlas.height / 2), atlas.width / 2, atlas.height / 2)
            val small = Bitmap.createScaledBitmap(crop, 160, 160, true)
            if (crop !== small) crop.recycle()
            val result = blur(small, 4 + strength.coerceIn(20, 100) / 6)
            small.recycle()
            result
        }
        atlas.recycle()
    }

    fun render(source: Bitmap, regions: List<FaceRegion>, seed: Int): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, 0f, 0f, paint)
        for (face in regions) {
            val box = face.padded()
            canvas.drawBitmap(tiles[Math.floorMod(face.id + seed, tiles.size)], null as Rect?,
                RectF(box.left * source.width, box.top * source.height,
                    box.right * source.width, box.bottom * source.height), paint)
        }
        return result
    }

    override fun close() { tiles.forEach { it.recycle() } }

    companion object {
        // Three separable box passes approximate a Gaussian. Work is independent of source pixels.
        private fun blur(bitmap: Bitmap, radius: Int): Bitmap {
            val w = bitmap.width
            val h = bitmap.height
            var src = IntArray(w * h)
            var dst = IntArray(w * h)
            bitmap.getPixels(src, 0, w, 0, 0, w, h)
            repeat(3) {
                for (horizontal in listOf(true, false)) {
                    val rows = if (horizontal) h else w
                    val columns = if (horizontal) w else h
                    val count = 2 * radius + 1
                    for (row in 0 until rows) {
                        fun at(col: Int) = if (horizontal) row * w + col.coerceIn(0, w - 1)
                            else col.coerceIn(0, h - 1) * w + row
                        var r = 0; var g = 0; var b = 0
                        for (i in -radius..radius) {
                            val c = src[at(i)]; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
                        }
                        for (col in 0 until columns) {
                            dst[at(col)] = (255 shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                            val old = src[at(col - radius)]
                            val next = src[at(col + radius + 1)]
                            r += (next shr 16 and 255) - (old shr 16 and 255)
                            g += (next shr 8 and 255) - (old shr 8 and 255)
                            b += (next and 255) - (old and 255)
                        }
                    }
                    val swap = src; src = dst; dst = swap
                }
            }
            return Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
