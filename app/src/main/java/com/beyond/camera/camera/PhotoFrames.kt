package com.beyond.camera.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Retains the latest camera frame independently of still capture, JPEG encoding and disk I/O. */
internal class PhotoFrames {
    @Volatile private var newest: Snapshot? = null

    fun update(image: ImageProxy) {
        val width = image.width
        val height = image.height
        if (width % 2 != 0 || height % 2 != 0) return
        val pixels = ByteArray(width * height * 3 / 2)
        val y = image.planes[0]
        val yBuffer = y.buffer.duplicate()
        repeat(height) { row ->
            yBuffer.position(y.buffer.position() + row * y.rowStride)
            yBuffer.get(pixels, row * width, width)
        }
        val v = image.planes[2]
        val vBuffer = v.buffer.duplicate()
        val u = image.planes[1]
        if (v.pixelStride == 2) {
            // CameraX's NV21 V plane contains interleaved VU. Its last U may be exposed
            // only through the U plane, depending on how the device slices the buffers.
            val offset = width * height
            repeat(height / 2) { row ->
                vBuffer.position(v.buffer.position() + row * v.rowStride)
                val length = minOf(width, vBuffer.remaining())
                vBuffer.get(pixels, offset + row * width, length)
                if (length < width) {
                    pixels[offset + row * width + width - 1] =
                        u.buffer.get(u.buffer.position() + row * u.rowStride + (width / 2 - 1) * u.pixelStride)
                }
            }
        } else {
            copyChroma(vBuffer, v.rowStride, v.pixelStride, width, height, pixels, 0)
            copyChroma(u.buffer, u.rowStride, u.pixelStride, width, height, pixels, 1)
        }
        newest = Snapshot(pixels, width, height, image.imageInfo.rotationDegrees, SystemClock.elapsedRealtime())
    }

    fun snapshot(): Snapshot? = newest?.takeIf { SystemClock.elapsedRealtime() - it.receivedAt <= 300 }

    private fun copyChroma(
        source: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int,
        destination: ByteArray, component: Int
    ) {
        val start = source.position()
        repeat(height / 2) { row ->
            repeat(width / 2) { column ->
                destination[width * height + row * width + column * 2 + component] =
                    source.get(start + row * rowStride + column * pixelStride)
            }
        }
    }

    class Snapshot(
        private val pixels: ByteArray,
        private val width: Int,
        private val height: Int,
        private val rotation: Int,
        val receivedAt: Long
    ) {
        fun encode(quality: Int): Frame {
            val buffer = ByteArrayOutputStream()
            val image = YuvImage(pixels, ImageFormat.NV21, width, height, null)
            check(image.compressToJpeg(Rect(0, 0, width, height), quality, buffer)) { "Couldn't encode photo." }
            return Frame(buffer.toByteArray(), rotation)
        }
    }
}
