package fr.anon.app

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.CRC32

class MetadataCleanerTest {
    private fun bytes(writer: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(writer)
    }.toByteArray()
    private fun box(type: String, payload: ByteArray) = bytes { writeInt(payload.size + 8); writeBytes(type); write(payload) }
    private fun chunk(type: String, payload: ByteArray): ByteArray = bytes {
        writeInt(payload.size); writeBytes(type); write(payload)
        writeInt(CRC32().apply { update(type.toByteArray()); update(payload) }.value.toInt())
    }

    @Test fun pngRemovesExifTextXmpAndTrailingDataButPreservesPixelsAndTransparency() {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val required = chunk("IHDR", ByteArray(13)) + chunk("tRNS", byteArrayOf(0, 127)) +
            chunk("IDAT", byteArrayOf(12, 23, 34)) + chunk("IEND", byteArrayOf())
        val source = signature + chunk("eXIf", "GPS PRIVATE_TEST".toByteArray()) +
            chunk("iTXt", "XMP PRIVATE_TEST".toByteArray()) + chunk("tEXt", "Author PRIVATE_TEST".toByteArray()) +
            required + "TRAILING_PRIVATE_TEST".toByteArray()
        val output = ByteArrayOutputStream()
        MetadataCleaner.copyPng(source.inputStream(), output)
        assertArrayEquals(signature + required, output.toByteArray())
    }

    @Test fun mp4ErasesMetadataAndBothTimestampVersionsWithoutMovingSamplesOrRotation() {
        val header0 = bytes { writeInt(0); writeInt(12345); writeInt(54321); writeInt(90000); writeInt(360000) }
        val header1 = bytes { writeInt(1 shl 24); writeLong(12345); writeLong(54321); writeInt(90000); writeLong(360000) }
        val video = box("mdat", ByteArray(400) { (it % 251).toByte() })
        val ftyp = box("ftyp", "isom0000isom".toByteArray())
        val source = ftyp + video + box("moov", box("mvhd", header0) + box("udta", "GPS PRIVATE_TEST".toByteArray()) +
            box("trak", box("tkhd", header1) + box("mdia", box("mdhd", header0 + byteArrayOf(0x15, 0xc7.toByte(), 0, 0)) + box("meta", "XMP PRIVATE_TEST".toByteArray())))) +
            box("uuid", "PRIVATE_TEST_device_serial".toByteArray())
        val file = File.createTempFile("anon-metadata", ".mp4")
        try {
            file.writeBytes(source)
            MetadataCleaner.cleanMp4(file)
            val clean = file.readBytes()
            assertEquals(source.size, clean.size)
            assertArrayEquals(ftyp + video, clean.copyOfRange(0, ftyp.size + video.size))
            assertFalse(String(clean).contains("PRIVATE_TEST"))
            fun find(type: String) = clean.toString(Charsets.ISO_8859_1).indexOf(type) + 4
            for (type in listOf("mvhd", "mdhd")) {
                val offset = find(type)
                assertArrayEquals(ByteArray(8), clean.copyOfRange(offset + 4, offset + 12))
                assertArrayEquals(header0.copyOfRange(12, 20), clean.copyOfRange(offset + 12, offset + 20))
            }
            val offset = find("tkhd")
            assertArrayEquals(ByteArray(16), clean.copyOfRange(offset + 4, offset + 20))
            assertArrayEquals(header1.copyOfRange(20, header1.size), clean.copyOfRange(offset + 20, offset + header1.size))
            assertArrayEquals(byteArrayOf(0x55, 0xc4.toByte()), clean.copyOfRange(find("mdhd") + 20, find("mdhd") + 22))
        } finally { file.delete() }
    }

    @Test fun malformedMp4IsRejectedRatherThanPublished() {
        val file = File.createTempFile("anon-malformed", ".mp4")
        try {
            file.writeBytes(bytes { writeInt(100); writeBytes("moov") })
            assertThrows(IllegalArgumentException::class.java) { MetadataCleaner.cleanMp4(file) }
        } finally { file.delete() }
    }
}
