package com.beyond.camera.camera

import java.io.OutputStream

/** Adds orientation to our NV21 encoder's JPEG without decoding or copying the image. */
internal object JpegOrientation {
    fun write(output: OutputStream, jpeg: ByteArray, rotation: Int) {
        val orientation: Byte = when (rotation) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> {
                output.write(jpeg)
                return
            }
        }
        require(jpeg.size >= 2 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        output.write(jpeg, 0, 2)
        output.write(byteArrayOf(
            0xff.toByte(), 0xe1.toByte(), 0, 34, // APP1 marker and length
            0x45, 0x78, 0x69, 0x66, 0, 0, // Exif identifier
            0x49, 0x49, 42, 0, 8, 0, 0, 0, // Little-endian TIFF header
            1, 0, // One IFD entry
            0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation, 0, 0, 0, // Orientation, one SHORT
            0, 0, 0, 0 // No following IFD
        ))
        output.write(jpeg, 2, jpeg.size - 2)
    }
}
