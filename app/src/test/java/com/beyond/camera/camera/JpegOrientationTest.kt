package com.beyond.camera.camera

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JpegOrientationTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())

    @Test
    fun writesValidExifOrientationAndKeepsOriginalJpegPayload() {
        for ((rotation, expected) in listOf(90 to 6, 180 to 3, 270 to 8)) {
            val output = ByteArrayOutputStream()
            JpegOrientation.write(output, jpeg, rotation)
            val bytes = output.toByteArray()
            assertArrayEquals(jpeg.copyOfRange(0, 2), bytes.copyOfRange(0, 2))
            assertEquals(0xffe1, ByteBuffer.wrap(bytes).getShort(2).toInt() and 0xffff)
            val segmentLength = ByteBuffer.wrap(bytes).getShort(4).toInt()
            assertEquals("Exif\u0000\u0000", bytes.copyOfRange(6, 12).decodeToString())
            val tiff = ByteBuffer.wrap(bytes, 12, segmentLength - 8).slice().order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(42, tiff.getShort(2).toInt())
            val dir = tiff.getInt(4)
            assertEquals(1, tiff.getShort(dir).toInt())
            assertEquals(0x0112, tiff.getShort(dir + 2).toInt())
            assertEquals(3, tiff.getShort(dir + 4).toInt())
            assertEquals(1, tiff.getInt(dir + 6))
            assertEquals(expected, tiff.getShort(dir + 10).toInt())
            assertArrayEquals(jpeg.copyOfRange(2, jpeg.size), bytes.copyOfRange(4 + segmentLength, bytes.size))
        }
    }

    @Test
    fun writesOriginalBytesWhenNoRotationIsNeeded() {
        val output = ByteArrayOutputStream()
        JpegOrientation.write(output, jpeg, 0)
        assertArrayEquals(jpeg, output.toByteArray())
    }
}
