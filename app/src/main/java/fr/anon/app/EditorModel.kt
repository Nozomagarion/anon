@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package fr.anon.app

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class EditorState(
    val source: Uri? = null,
    val name: String = "",
    val video: Boolean = false,
    val photo: Bitmap? = null,
    val preview: Bitmap? = null,
    val rendering: Boolean = false,
    val faces: List<FaceRegion> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val strength: Int = 65,
    val seed: Int = java.security.SecureRandom().nextInt(1_000_000),
    val busy: String? = null,
    val progress: Int = 0,
    val error: String? = null,
    val output: Uri? = null,
    val manual: Boolean = false,
    val durationMs: Long = 0
)

class EditorModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val context get() = getApplication<Application>()
    private var operation: Job? = null
    private var render: Job? = null
    private var transformer: Transformer? = null
    private var tempOutput: File? = null
    private var renderGeneration = 0

    fun import(uri: Uri) {
        if (mutable.value.busy != null) return
        render?.cancel()
        mutable.value = EditorState(strength = mutable.value.strength, busy = "Ouverture du média…")
        operation = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val type = context.contentResolver.getType(uri) ?: ""
                    require(type.startsWith("image/") || type.startsWith("video/")) { "Choisis une photo ou une vidéo." }
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Média importé"
                    if (type.startsWith("video/")) {
                        val duration = MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(context, uri)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        }
                        EditorState(source = uri, name = name, video = true, durationMs = duration,
                            strength = mutable.value.strength)
                    } else {
                        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val scale = minOf(1f, 4096f / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1))
                        }
                        val faces = FaceDetectionEngine(false).use { it.detect(bitmap) }
                        EditorState(source = uri, name = name, photo = bitmap, faces = faces,
                            selected = faces.map { it.id }.toSet(), strength = mutable.value.strength)
                    }
                }
                mutable.value = result
                if (!result.video) updatePreview()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = null, error = friendly(e)) }
        }
    }

    fun toggle(id: Int) {
        val s = mutable.value
        if (s.video || s.busy != null) return
        mutable.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id, output = null)
        updatePreview()
    }
    fun selectAll(all: Boolean) {
        val s = mutable.value
        mutable.value = s.copy(selected = if (all) s.faces.map { it.id }.toSet() else emptySet(), output = null)
        updatePreview()
    }
    fun manual(value: Boolean) { mutable.value = mutable.value.copy(manual = value) }
    fun addRegion(left: Float, top: Float, right: Float, bottom: Float) {
        val s = mutable.value
        if (s.video || s.busy != null) return
        val id = (s.faces.maxOfOrNull { it.id } ?: 0) + 1
        val region = FaceRegion(id, left, top, right, bottom)
        mutable.value = s.copy(faces = s.faces + region, selected = s.selected + id, manual = false, output = null)
        updatePreview()
    }
    fun strength(value: Int) {
        mutable.value = mutable.value.copy(strength = value.coerceIn(20, 100), output = null)
        if (!mutable.value.video) updatePreview()
    }
    fun shuffle() {
        mutable.value = mutable.value.copy(seed = (mutable.value.seed + kotlin.random.Random.nextInt(1, 4)) % 1_000_000, output = null)
        if (!mutable.value.video) updatePreview()
    }

    private fun updatePreview() {
        val snapshot = mutable.value
        val source = snapshot.photo ?: return
        render?.cancel()
        val generation = ++renderGeneration
        mutable.value = mutable.value.copy(rendering = true)
        render = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val scale = minOf(1f, 1536f / maxOf(source.width, source.height))
                val small = Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
                try {
                    FaceTextures(context, snapshot.strength).use { textures ->
                        textures.render(small, snapshot.faces.filter { it.id in snapshot.selected }, snapshot.seed)
                    }
                } finally { if (small !== source) small.recycle() }
            }
            if (generation == renderGeneration && mutable.value.source == snapshot.source) {
                mutable.value = mutable.value.copy(preview = result, rendering = false)
            } else result.recycle()
        }
    }

    fun export() {
        val s = mutable.value
        if (s.source == null || s.busy != null) return
        mutable.value = s.copy(busy = "Préparation de l’export…", progress = 0, output = null, error = null)
        if (s.video) exportVideo(s) else {
            operation = viewModelScope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) {
                        val result = FaceTextures(context, s.strength).use { textures ->
                            textures.render(requireNotNull(s.photo), s.faces.filter { it.id in s.selected }, s.seed)
                        }
                        val temporary = File.createTempFile("photo-", ".png", context.cacheDir)
                        try {
                            temporary.outputStream().use { stream ->
                                check(result.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Écriture de l'image impossible." }
                            }
                            save("image/png", "png") { stream ->
                                temporary.inputStream().use { MetadataCleaner.copyPng(it, stream) }
                            }
                        } finally { result.recycle(); temporary.delete() }
                    }
                    mutable.value = mutable.value.copy(busy = null, output = uri, progress = 100)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(e) }
            }
        }
    }

    private fun exportVideo(s: EditorState) {
        val output = File(context.cacheDir, "export-${UUID.randomUUID()}.mp4")
        tempOutput = output
        val effect = ReplacementEffect(context, s.strength, s.seed)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(requireNotNull(s.source)))
            .setEffects(Effects(emptyList(), listOf(effect))).build()
        transformer = VideoExport.builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    operation?.cancel()
                    transformer = null
                    operation = viewModelScope.launch {
                        try {
                            mutable.value = mutable.value.copy(busy = "Enregistrement dans la galerie…")
                            val uri = withContext(Dispatchers.IO) {
                                save("video/mp4", "mp4") { stream -> output.inputStream().use { it.copyTo(stream) } }
                            }
                            mutable.value = mutable.value.copy(busy = null, output = uri, progress = 100)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { fail(e) }
                        finally { output.delete(); tempOutput = null }
                    }
                }
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    operation?.cancel(); transformer = null; output.delete(); tempOutput = null; fail(exception)
                }
            }).build()
        try {
            transformer!!.start(item, output.absolutePath)
            mutable.value = mutable.value.copy(busy = "Remplacement et floutage de tous les visages…")
            operation = viewModelScope.launch {
                val progress = ProgressHolder()
                while (isActive && transformer != null) {
                    if (transformer?.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        mutable.value = mutable.value.copy(progress = progress.progress)
                    }
                    delay(350)
                }
            }
        } catch (e: Exception) { transformer = null; output.delete(); tempOutput = null; fail(e) }
    }

    private fun save(mime: String, extension: String, writer: (java.io.OutputStream) -> Unit): Uri {
        val collection = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ANON_${UUID.randomUUID()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("video")) "${Environment.DIRECTORY_MOVIES}/ANON" else "${Environment.DIRECTORY_PICTURES}/ANON")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Impossible de créer le fichier dans la galerie.")
        try {
            requireNotNull(resolver.openOutputStream(uri)).use(writer)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    fun cancel() {
        // Once writing to the gallery starts, let the short atomic publication complete.
        if (mutable.value.busy == "Enregistrement dans la galerie…") return
        transformer?.cancel(); transformer = null
        operation?.cancel(); tempOutput?.delete(); tempOutput = null
        mutable.value = mutable.value.copy(busy = null, progress = 0)
    }
    private fun fail(e: Exception) { mutable.value = mutable.value.copy(busy = null, error = friendly(e)) }
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    private fun friendly(e: Exception): String = e.message ?: "Le traitement a échoué. Essaie un autre média."
    override fun onCleared() {
        transformer?.cancel(); tempOutput?.delete()
        super.onCleared()
    }
}
