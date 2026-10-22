@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package fr.anon.app

import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.DefaultMuxer
import java.io.File

/** Metadata is blocked before muxing, then container timestamps are erased after close. */
class PrivacyMuxerFactory : Muxer.Factory by DefaultMuxer.Factory() {
    override fun create(path: String): Muxer {
        val platform = DefaultMuxer.Factory().create(path)
        return object : Muxer by platform {
            override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit
            override fun addTrack(format: Format): Muxer.TrackToken = platform.addTrack(
                format.buildUpon().setMetadata(null).setLanguage(null).setLabel(null).setId(null).build()
            )
            override fun close() {
                platform.close()
                try { MetadataCleaner.cleanMp4(File(path)) }
                catch (error: Exception) { throw Muxer.MuxerException("Nettoyage des métadonnées impossible : export annulé.", error) }
            }
        }
    }
}
