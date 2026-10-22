@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package fr.anon.app

import android.content.Context
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.Transformer

object VideoExport {
    fun builder(context: Context): Transformer.Builder {
        val platform = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()
        val decoder = object : Codec.DecoderFactory by platform {
            override fun createForVideoDecoding(format: Format, outputSurface: Surface, requestSdrToneMapping: Boolean): Codec {
                val codec = platform.createForVideoDecoding(format, outputSurface, requestSdrToneMapping)
                // ML inference is slower than a shader. Keep a single decoded frame in flight so
                // SurfaceTexture cannot overwrite a queued frame while the GL thread is detecting.
                return object : Codec by codec {
                    override fun getMaxPendingFrameCount() = 1
                }
            }
        }
        return Transformer.Builder(context)
            .setMuxerFactory(PrivacyMuxerFactory())
            .setAssetLoaderFactory(ExoPlayerAssetLoader.Factory(context, decoder, Clock.DEFAULT))
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
    }
}
