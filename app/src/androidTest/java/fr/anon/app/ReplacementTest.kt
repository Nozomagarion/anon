@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package fr.anon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentSkipListMap

class ReplacementTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun replacementContainsNoSourcePixelsAndLeavesBackgroundUntouched() {
        val a = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val face = FaceRegion(0, .3f, .3f, .7f, .7f)
        FaceTextures(context, 65).use { textures ->
            val outA = textures.render(a, listOf(face), 2)
            val outB = textures.render(b, listOf(face), 2)
            for (y in 60 until 140) for (x in 60 until 140) {
                assertEquals("Original pixels leaked at $x,$y", outA.getPixel(x, y), outB.getPixel(x, y))
            }
            assertEquals(Color.RED, outA.getPixel(5, 5))
            assertEquals(Color.BLUE, outB.getPixel(5, 5))
            val none = textures.render(a, emptyList(), 2)
            assertTrue(a.sameAs(none))
            none.recycle(); outA.recycle(); outB.recycle()
        }
        a.recycle(); b.recycle()
    }

    @Test fun bundledDetectorWorksOffline() {
        val atlas = context.assets.open("faces.webp").use(BitmapFactory::decodeStream)!!
        val faces = FaceDetectionEngine(false).use { it.detect(atlas) }
        assertTrue("Expected all four fictional fixture faces, got ${faces.size}", faces.size == 4)
        atlas.recycle()
    }

    /** Runs against a short fixture pushed to external app storage, using the production exporter. */
    @Test fun realVideoExportsWithFacesReplacedAndAudioPreserved() {
        val input = File(context.getExternalFilesDir(null), "test-input.mp4")
        assertTrue("Push a test clip to ${input.absolutePath} first", input.isFile)
        val output = File(context.getExternalFilesDir(null), "test-output.mp4").apply { delete() }
        val done = CountDownLatch(1)
        var failure: Exception? = null
        var transformer: Transformer? = null
        val processedFrames = ConcurrentSkipListMap<Long, List<FaceRegion>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(emptyList(), listOf(ReplacementEffect(context, 65, 0,
                    onFrameProcessed = { time, masks -> processedFrames[time] = masks })))).build()
            transformer = VideoExport.builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { done.countDown() }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { failure = exception; done.countDown() }
                }).build()
            transformer!!.start(item, output.absolutePath)
        }
        val completed = done.await(900, TimeUnit.SECONDS)
        if (!completed) InstrumentationRegistry.getInstrumentation().runOnMainSync { transformer?.cancel() }
        assertTrue("Export timed out", completed)
        failure?.let { throw it }
        assertTrue("No output", output.length() > 1000)
        MediaMetadataRetriever().use { original ->
            MediaMetadataRetriever().use { edited ->
                original.setDataSource(input.absolutePath); edited.setDataSource(output.absolutePath)
                for (key in listOf(MediaMetadataRetriever.METADATA_KEY_LOCATION,
                    MediaMetadataRetriever.METADATA_KEY_TITLE, MediaMetadataRetriever.METADATA_KEY_ARTIST,
                    MediaMetadataRetriever.METADATA_KEY_AUTHOR, MediaMetadataRetriever.METADATA_KEY_ALBUM,
                    MediaMetadataRetriever.METADATA_KEY_WRITER)) {
                    assertTrue("Personal metadata survived: $key", edited.extractMetadata(key).isNullOrBlank())
                }
                assertFalse("Injected source metadata survived", output.readBytes().toString(Charsets.ISO_8859_1).contains("PRIVATE_TEST"))
                assertEquals(original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
                    edited.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val originalDuration = original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                val editedDuration = edited.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue(kotlin.math.abs(originalDuration - editedDuration) < 150)
                val frameCount = original.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                if (frameCount != null) assertEquals("Every input frame must be processed", frameCount, processedFrames.size)
                var checked = 0
                for (time in listOf(0L, 500_000L, 1_000_000L)) {
                    val nearest = listOfNotNull(processedFrames.floorEntry(time), processedFrames.ceilingEntry(time))
                        .minBy { kotlin.math.abs(it.key - time) }
                    val before = original.getFrameAtTime(nearest.key, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    val after = edited.getFrameAtTime(nearest.key, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    val masks = nearest.value
                    val expected = FaceTextures(context, 65).use { it.render(before, masks, 0) }
                    for (face in masks) {
                        val x = ((face.left + face.right) / 2 * before.width).toInt().coerceIn(0, before.width - 1)
                        val y = ((face.top + face.bottom) / 2 * before.height).toInt().coerceIn(0, before.height - 1)
                        var diff = 0L
                        for (dy in -3..3) for (dx in -3..3) {
                            val sx = (x + dx).coerceIn(0, before.width - 1)
                            val sy = (y + dy).coerceIn(0, before.height - 1)
                            val a = expected.getPixel(sx, sy); val b = after.getPixel(sx, sy)
                            diff += kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
                        }
                        assertTrue("Replacement doesn't match fictional texture at $x,$y / $time us (mean error ${diff / 49})", diff / 49 < 45)
                        checked++
                    }
                    expected.recycle(); before.recycle(); after.recycle()
                }
                assertTrue("Fixture must contain a detectable face", checked > 0)
            }
        }
    }
}
