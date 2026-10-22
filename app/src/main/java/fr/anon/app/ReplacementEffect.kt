@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package fr.anon.app

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLUtils
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

class ReplacementEffect(
    private val context: Context,
    private val strength: Int,
    private val seed: Int,
    private val preview: Boolean = false,
    internal val onFrameProcessed: ((Long, List<FaceRegion>) -> Unit)? = null
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Convertissez la vidéo HDR en SDR avant de l'importer.")
        return ReplacementProgram(this.context, strength, seed, preview, onFrameProcessed)
    }
}

/** Detection readback is downscaled; compositing and encoding keep the source resolution. */
private class ReplacementProgram(context: Context, strength: Int, private val seed: Int,
    private val preview: Boolean,
    private val onFrameProcessed: ((Long, List<FaceRegion>) -> Unit)?) : BaseGlShaderProgram(false, 1) {
    private val copy = GlProgram(VERTEX, COPY)
    private val replace = GlProgram(VERTEX, REPLACE)
    private val detector = FaceDetectionEngine(true, crowdSearch = !preview)
    private val textures = IntArray(4)
    private var readTexture = 0
    private var readFbo = 0
    private var width = 0
    private var height = 0
    private var readWidth = 0
    private var readHeight = 0
    private lateinit var pixels: ByteBuffer
    private lateinit var raw: IntArray
    private lateinit var flipped: IntArray
    private lateinit var bitmap: Bitmap
    private var lastDetectionUs = Long.MIN_VALUE
    private var previous = emptyList<FaceRegion>()
    private var previousSeen = emptyMap<Int, Pair<FaceRegion, Long>>()

    init {
        for (program in listOf(copy, replace)) {
            program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
        }
        glGenTextures(4, textures, 0)
        FaceTextures(context, strength).use { faces ->
            for (i in textures.indices) {
                glBindTexture(GL_TEXTURE_2D, textures[i])
                configureTexture()
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, faces.tiles[i], 0)
            }
        }
        GlUtil.checkGlError()
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseReadback()
        width = inputWidth; height = inputHeight
        val scale = minOf(1f, 960f / max(width, height))
        readWidth = max(1, (width * scale).roundToInt())
        readHeight = max(1, (height * scale).roundToInt())
        pixels = ByteBuffer.allocateDirect(readWidth * readHeight * 4).order(ByteOrder.nativeOrder())
        raw = IntArray(readWidth * readHeight)
        flipped = IntArray(raw.size)
        bitmap = Bitmap.createBitmap(readWidth, readHeight, Bitmap.Config.ARGB_8888)
        val names = IntArray(1)
        glGenTextures(1, names, 0); readTexture = names[0]
        glBindTexture(GL_TEXTURE_2D, readTexture); configureTexture()
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, readWidth, readHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glGenFramebuffers(1, names, 0); readFbo = names[0]
        val bound = IntArray(1); glGetIntegerv(GL_FRAMEBUFFER_BINDING, bound, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, readFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, readTexture, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
        glBindFramebuffer(GL_FRAMEBUFFER, bound[0])
        lastDetectionUs = Long.MIN_VALUE
        previous = emptyList(); previousSeen = emptyMap()
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val bound = IntArray(1); glGetIntegerv(GL_FRAMEBUFFER_BINDING, bound, 0)
            // Preview may reuse detections for 80 ms. Export always examines EVERY decoded frame.
            if (!preview || lastDetectionUs == Long.MIN_VALUE || presentationTimeUs < lastDetectionUs ||
                presentationTimeUs - lastDetectionUs >= 80_000) {
                if (presentationTimeUs < lastDetectionUs) previousSeen = emptyMap()
                glBindFramebuffer(GL_FRAMEBUFFER, readFbo)
                glViewport(0, 0, readWidth, readHeight)
                drawCopy(inputTexId)
                pixels.clear()
                glReadPixels(0, 0, readWidth, readHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
                GlUtil.checkGlError()
                pixels.rewind(); pixels.asIntBuffer().get(raw)
                for (y in 0 until readHeight) for (x in 0 until readWidth) {
                    val c = raw[(readHeight - 1 - y) * readWidth + x]
                    // GL RGBA bytes are ABGR ints on Android's little-endian CPUs.
                    flipped[y * readWidth + x] = (c and -0x1000000) or
                        ((c and 255) shl 16) or (c and 0x0000ff00) or ((c ushr 16) and 255)
                }
                bitmap.setPixels(flipped, 0, readWidth, 0, 0, readWidth, readHeight)
                val detected = detector.detect(bitmap)
                val retained = previousSeen.filterValues { presentationTimeUs - it.second in 0..200_000 }.toMutableMap()
                val masks = detected.map { face ->
                    val old = retained[face.id]?.first
                    retained[face.id] = face to presentationTimeUs
                    if (old == null) face else face.union(old)
                }.toMutableList()
                val ids = detected.map { it.id }.toSet()
                retained.filterKeys { it !in ids }.values.forEach { masks.add(it.first) }
                previousSeen = retained
                previous = masks
                lastDetectionUs = presentationTimeUs
                glBindFramebuffer(GL_FRAMEBUFFER, bound[0])
            }
            glViewport(0, 0, width, height)
            drawCopy(inputTexId)
            if (previous.isNotEmpty()) {
                replace.use()
                for (region in previous) {
                    val r = region.padded()
                    replace.setFloatsUniform("uRect", floatArrayOf(r.left, r.top, r.width, r.height))
                    replace.setSamplerTexIdUniform("uFace", textures[Math.floorMod(region.id + seed, 4)], 0)
                    replace.bindAttributesAndUniforms()
                    // discard outside the rectangle; inside, output is 100% fictional pixels.
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
                }
            }
            GlUtil.checkGlError()
            onFrameProcessed?.invoke(presentationTimeUs, previous.toList())
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun drawCopy(texture: Int) {
        copy.use(); copy.setSamplerTexIdUniform("uTexture", texture, 0)
        copy.bindAttributesAndUniforms(); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releaseReadback() {
        if (::bitmap.isInitialized) bitmap.recycle()
        if (readTexture != 0) glDeleteTextures(1, intArrayOf(readTexture), 0)
        if (readFbo != 0) glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
        readTexture = 0; readFbo = 0
    }

    override fun release() {
        try {
            releaseReadback(); detector.close()
            glDeleteTextures(4, textures, 0); copy.delete(); replace.delete()
        } finally { super.release() }
    }

    companion object {
        private fun configureTexture() {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        private const val VERTEX = """
            attribute vec4 aPosition;
            varying vec2 vTex;
            void main() { gl_Position = aPosition; vTex = aPosition.xy * 0.5 + 0.5; }
        """
        private const val COPY = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTexture, vTex); }
        """
        private const val REPLACE = """
            precision mediump float;
            uniform sampler2D uFace;
            uniform vec4 uRect;
            varying vec2 vTex;
            void main() {
                vec2 p = vec2(vTex.x, 1.0 - vTex.y);
                vec2 q = (p - uRect.xy) / uRect.zw;
                if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) discard;
                gl_FragColor = vec4(texture2D(uFace, q).rgb, 1.0);
            }
        """
    }
}
