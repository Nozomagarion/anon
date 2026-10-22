package fr.anon.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Each export frame is detected. A failed detector fails export instead of writing an untreated frame. */
class FaceDetectionEngine(private val video: Boolean, private val crowdSearch: Boolean = true) : AutoCloseable {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setMinFaceSize(.025f)
        .build())
    private var lastFaces = emptyList<FaceRegion>()
    private var nextId = 0

    fun detect(bitmap: Bitmap): List<FaceRegion> {
        val regions = detectSingle(bitmap).toMutableList()
        // Overlapping crops improve recall in crowds: small/occluded faces occupy more of the
        // detector's input. All crops use original pixels, never generated or upscaled detail.
        if (crowdSearch && max(bitmap.width, bitmap.height) >= 800) {
            val w = (bitmap.width * .60f).toInt()
            val h = (bitmap.height * .60f).toInt()
            for (top in listOf(0, bitmap.height - h)) for (left in listOf(0, bitmap.width - w)) {
                val tile = Bitmap.createBitmap(bitmap, left, top, w, h)
                try {
                    detectSingle(tile).forEach { local ->
                        val face = FaceRegion(0, (left + local.left * w) / bitmap.width,
                            (top + local.top * h) / bitmap.height, (left + local.right * w) / bitmap.width,
                            (top + local.bottom * h) / bitmap.height)
                        val duplicate = regions.indexOfFirst { overlap(it, face) > .35f }
                        if (duplicate < 0) regions.add(face)
                        else regions[duplicate] = regions[duplicate].union(face)
                    }
                } finally { tile.recycle() }
            }
        }
        if (!video) return regions.mapIndexed { index, region -> region.copy(id = index) }
        val available = lastFaces.toMutableList()
        val tracked = regions.map { face ->
            val match = available.maxByOrNull { overlap(it, face) }
            val id = if (match != null && overlap(match, face) > .12f) {
                available.remove(match); match.id
            } else nextId++
            face.copy(id = id)
        }
        lastFaces = tracked
        return tracked
    }

    private fun detectSingle(bitmap: Bitmap): List<FaceRegion> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
        return faces.mapIndexedNotNull { index, face ->
            val b = face.boundingBox
            val left = (b.left.toFloat() / bitmap.width).coerceIn(0f, 1f)
            val top = (b.top.toFloat() / bitmap.height).coerceIn(0f, 1f)
            val right = (b.right.toFloat() / bitmap.width).coerceIn(0f, 1f)
            val bottom = (b.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) null
            else FaceRegion(face.trackingId ?: index, left, top, right, bottom)
        }
    }
    private fun overlap(a: FaceRegion, b: FaceRegion): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersection / max(.000001f, min(a.width * a.height, b.width * b.height))
    }
    override fun close() = detector.close()
}
