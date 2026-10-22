package fr.anon.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Removes descriptive metadata; codec configuration, orientation and relative timing survive. */
object MetadataCleaner {
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val pngRequired = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS")

    fun copyPng(input: InputStream, output: OutputStream) {
        val source = DataInputStream(input)
        val target = DataOutputStream(output)
        val signature = ByteArray(8).also(source::readFully)
        require(signature.contentEquals(pngSignature)) { "PNG invalide : export annulé." }
        target.write(signature)
        while (true) {
            val size = source.readInt().toLong() and 0xffffffffL
            require(size <= Int.MAX_VALUE) { "Bloc PNG trop grand." }
            val typeBytes = ByteArray(4).also(source::readFully)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val keep = type in pngRequired
            require(keep || type.first().isLowerCase()) { "Bloc PNG essentiel inconnu : $type" }
            if (keep) { target.writeInt(size.toInt()); target.write(typeBytes) }
            transfer(source, if (keep) target else null, size + 4) // include the existing CRC
            if (type == "IEND") break // discard anything appended after the PNG
        }
    }

    fun cleanMp4(file: File) {
        RandomAccessFile(file, "rw").use { data ->
            var hasMovie = false
            fun boxes(start: Long, end: Long, depth: Int) {
                require(depth < 16) { "MP4 trop imbriqué." }
                var position = start
                while (position < end) {
                    require(end - position >= 8) { "MP4 tronqué : export annulé." }
                    data.seek(position)
                    val shortSize = data.readInt().toLong() and 0xffffffffL
                    val type = ByteArray(4).also(data::readFully).toString(Charsets.ISO_8859_1)
                    val header = if (shortSize == 1L) 16 else 8
                    val size = when (shortSize) { 0L -> end - position; 1L -> data.readLong(); else -> shortSize }
                    require(size >= header && size <= end - position) { "Bloc MP4 invalide : export annulé." }
                    val payload = position + header
                    when (type) {
                        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex" -> {
                            if (type == "moov") hasMovie = true
                            boxes(payload, position + size, depth + 1)
                        }
                        "udta", "meta", "uuid", "XMP_", "free", "skip" -> {
                            data.seek(position + 4); data.writeBytes("free")
                            zero(data, payload, size - header)
                        }
                        "mvhd", "tkhd", "mdhd" -> {
                            require(size - header >= 12) { "En-tête MP4 incomplet." }
                            data.seek(payload)
                            val version = data.readUnsignedByte()
                            require(version == 0 || version == 1) { "Version MP4 inconnue." }
                            val clockBytes = if (version == 1) 16 else 8
                            require(size - header >= 4 + clockBytes) { "Dates MP4 incomplètes." }
                            zero(data, payload + 4, clockBytes.toLong())
                            if (type == "mdhd") {
                                val languageOffset = if (version == 1) 32 else 20
                                require(size - header >= languageOffset + 4) { "Langue MP4 incomplète." }
                                data.seek(payload + languageOffset)
                                data.writeShort(0x55c4) // ISO 639-2 "und": no source language disclosed
                            }
                        }
                        "hdlr" -> {
                            require(size - header >= 24) { "Piste MP4 incomplète." }
                            zero(data, payload + 24, size - header - 24)
                        }
                    }
                    position += size
                }
            }
            boxes(0, data.length(), 0)
            require(hasMovie) { "MP4 sans piste : export annulé." }
            data.fd.sync()
        }
    }

    private fun zero(file: RandomAccessFile, offset: Long, count: Long) {
        file.seek(offset)
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val length = minOf(remaining, buffer.size.toLong()).toInt()
            file.write(buffer, 0, length); remaining -= length
        }
    }

    private fun transfer(input: DataInputStream, output: OutputStream?, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val length = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, length)
            output?.write(buffer, 0, length); remaining -= length
        }
    }
}
