package com.beyond.camera.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Bounded pre-shutter frames. Only the winning frame is copied at the tap. */
internal class PhotoFrames(private val sensorUsesElapsedClock: Boolean) {
    private val lock = Any()
    private var staging = ByteArray(0)
    private val history = ArrayDeque<BufferedFrame>(3)

    private class BufferedFrame(
        val snapshot: Snapshot,
        val timestampNs: Long,
        val quality: FrameQuality
    )

    fun update(image: ImageProxy) {
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        val sensorNs = image.imageInfo.timestamp
        val timestampNs = if (sensorUsesElapsedClock && sensorNs in 1..arrivalNs) sensorNs else arrivalNs
        val width = image.width
        val height = image.height
        if (width % 2 != 0 || height % 2 != 0) return
        val byteCount = width * height * 3 / 2
        if (staging.size != byteCount) staging = ByteArray(byteCount)
        val pixels = staging
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
        val frame = Snapshot(pixels, width, height, image.imageInfo.rotationDegrees)
        val quality = FrameQuality.measure(pixels, width, height)
        synchronized(lock) {
            if (history.lastOrNull()?.snapshot?.matchesFormat(frame) == false) history.clear()
            staging = if (history.size == 3) history.removeFirst().snapshot.pixels else ByteArray(0)
            history.addLast(BufferedFrame(frame, timestampNs, quality))
        }
    }

    fun snapshot(tapNs: Long): Snapshot? = synchronized(lock) {
        val newest = history.lastOrNull { it.timestampNs <= tapNs } ?: return@synchronized null
        if (tapNs - newest.timestampNs > MAX_FRAME_AGE_NS) return@synchronized null
        var best = newest
        for (older in history.asReversed()) {
            if (older.timestampNs >= newest.timestampNs) continue
            if (tapNs - older.timestampNs > MAX_FRAME_AGE_NS) break
            // Stop at a scene change, even if a still earlier frame resembles the current one.
            if (!older.quality.matchesScene(newest.quality)) break
            if (older.quality.isClearlySharperThan(best.quality)) best = older
        }
        best.snapshot.freeze()
    }

    private companion object {
        const val MAX_FRAME_AGE_NS = 120_000_000L
    }

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
        internal val pixels: ByteArray,
        private val width: Int,
        private val height: Int,
        private val rotation: Int
    ) {
        fun matchesFormat(other: Snapshot): Boolean =
            width == other.width && height == other.height && rotation == other.rotation

        fun freeze() = Snapshot(pixels.copyOf(), width, height, rotation)

        fun encode(quality: Int): Frame {
            val buffer = ByteArrayOutputStream()
            val image = YuvImage(pixels, ImageFormat.NV21, width, height, null)
            check(image.compressToJpeg(Rect(0, 0, width, height), quality, buffer)) { "Couldn't encode photo." }
            return Frame(buffer.toByteArray(), rotation)
        }
    }
}
