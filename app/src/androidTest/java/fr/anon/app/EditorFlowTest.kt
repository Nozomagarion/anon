package fr.anon.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.content.ContentUris
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EditorFlowTest {
    @Test fun sharedPhotoSelectionAndGalleryExportWorkInRealActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val input = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PRIVATE_TEST_ORIGINAL.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }))
        val image = context.assets.open("faces.webp").use(BitmapFactory::decodeStream)!!
        val jpeg = File(context.cacheDir, "metadata-test.jpg")
        jpeg.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        image.recycle()
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MODEL, "PRIVATE_TEST_PHONE")
            setAttribute(ExifInterface.TAG_ARTIST, "PRIVATE_TEST_AUTHOR")
            setAttribute(ExifInterface.TAG_DATETIME, "2001:02:03 04:05:06")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,21/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        assertNotNull(ExifInterface(jpeg.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        requireNotNull(resolver.openOutputStream(input)).use { out -> jpeg.inputStream().use { it.copyTo(out) } }
        jpeg.delete()
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, input)
        try {
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                fun waitFor(predicate: (ViewGroup) -> Boolean) {
                    val end = System.currentTimeMillis() + 30_000
                    while (System.currentTimeMillis() < end) {
                        var ready = false
                        scenario.onActivity { ready = predicate(it.findViewById(android.R.id.content)) }
                        if (ready) return
                        Thread.sleep(100)
                    }
                    fail("Editor did not reach the expected state")
                }
                fun click(text: String) = scenario.onActivity { activity ->
                    val button = flatten(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                        .first { it.text.toString() == text }
                    assertTrue(button.isEnabled); button.performClick()
                }
                waitFor { root -> flatten(root).filterIsInstance<TextView>().any { it.text.contains("4 / 4 visages") } }
                click("Aucun")
                waitFor { root -> flatten(root).filterIsInstance<Button>().first { it.text.startsWith("Exporter") }.isEnabled.not() }
                click("Tous")
                waitFor { root -> flatten(root).filterIsInstance<Button>().first { it.text.startsWith("Exporter") }.isEnabled }
                click("Exporter dans la galerie  ↗")
                waitFor { root -> flatten(root).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.contains("Enregistré dans la galerie") } }
                resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                    "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("ANON_%"), "${MediaStore.Images.Media._ID} DESC")!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(1).matches(Regex("ANON_[0-9a-f-]{36}\\.png")))
                    val exported = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    val png = requireNotNull(resolver.openInputStream(exported)).use { it.readBytes() }
                    assertFalse(png.toString(Charsets.ISO_8859_1).contains("PRIVATE_TEST"))
                    java.io.DataInputStream(png.inputStream()).use { data ->
                        data.skipBytes(8)
                        while (true) {
                            val length = data.readInt()
                            val type = ByteArray(4).also(data::readFully).toString(Charsets.US_ASCII)
                            assertTrue("Unexpected metadata chunk $type", type in setOf("IHDR", "IDAT", "IEND", "PLTE", "tRNS"))
                            data.skipBytes(length + 4)
                            if (type == "IEND") break
                        }
                    }
                    requireNotNull(resolver.openInputStream(exported)).use { stream ->
                        File(context.getExternalFilesDir(null), "metadata-photo-output.png").outputStream().use { stream.copyTo(it) }
                    }
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "editor-photo.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } finally { resolver.delete(input, null, null) }
    }

    private fun flatten(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { flatten(view.getChildAt(it)) } else emptyList()
}
