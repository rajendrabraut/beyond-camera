package com.beyond.camera.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.beyond.camera.MediaStoreSaver
import java.io.File
import java.nio.ByteBuffer

/**
 * Time-lapse: keeps one camera frame every [intervalMs] and encodes them as a 30 fps H.264 video.
 * Frames arrive on the analysis thread; [finish] may be called from any background thread.
 */
class TimelapseRecorder(
    private val context: Context,
    private val intervalMs: Long,
    private val onFrameCount: (Int) -> Unit
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var frames = 0
    private var lastAt = 0L
    private var finished = false
    private var failed = false
    private var width = 0
    private var height = 0
    private var row = ByteArray(0)
    private val info = MediaCodec.BufferInfo()
    private val file: File = MediaStoreSaver.tempFile(context, "mp4")

    fun onFrame(image: ImageProxy) {
        synchronized(lock) {
            if (finished || failed) return
            val now = SystemClock.elapsedRealtime()
            if (frames > 0 && now - lastAt < intervalMs) return
            try {
                if (codec == null) start(image.width, image.height, image.imageInfo.rotationDegrees)
                val c = codec ?: return
                if (image.width != width || image.height != height || image.planes.size < 3) return
                val index = c.dequeueInputBuffer(20_000)
                if (index < 0) return
                val target = c.getInputImage(index)
                if (target == null) {
                    c.queueInputBuffer(index, 0, 0, frames * FRAME_US, 0)
                    failed = true
                    return
                }
                for (p in 0 until 3) {
                    val src = image.planes[p]
                    val dst = target.planes[p]
                    val pw = if (p == 0) width else width / 2
                    val ph = if (p == 0) height else height / 2
                    copyPlane(src.buffer, src.rowStride, src.pixelStride, dst.buffer, dst.rowStride, dst.pixelStride, pw, ph)
                }
                c.queueInputBuffer(index, 0, width * height * 3 / 2, frames * FRAME_US, 0)
                lastAt = now
                frames++
                drain(false)
                onFrameCount(frames)
            } catch (t: Throwable) {
                failed = true
            }
        }
    }

    private fun start(w: Int, h: Int, rotation: Int) {
        width = w
        height = h
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w * h >= 1920 * 1080) 16_000_000 else 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        codec = encoder
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
            setOrientationHint(((rotation % 360) + 360) % 360)
        }
    }

    private fun copyPlane(
        src: ByteBuffer, srcRowStride: Int, srcPixelStride: Int,
        dst: ByteBuffer, dstRowStride: Int, dstPixelStride: Int,
        w: Int, h: Int
    ) {
        val srcRowBytes = (w - 1) * srcPixelStride + 1
        if (row.size < srcRowBytes) row = ByteArray(srcRowBytes)
        val srcLimit = src.limit()
        val dstLimit = dst.limit()
        for (y in 0 until h) {
            val srcPos = y * srcRowStride
            if (srcPos + srcRowBytes > srcLimit) break
            src.position(srcPos)
            src.get(row, 0, srcRowBytes)
            val dstPos = y * dstRowStride
            if (srcPixelStride == 1 && dstPixelStride == 1) {
                if (dstPos + w > dstLimit) break
                dst.position(dstPos)
                dst.put(row, 0, w)
            } else {
                if (dstPos + (w - 1) * dstPixelStride >= dstLimit) break
                for (x in 0 until w) {
                    dst.put(dstPos + x * dstPixelStride, row[x * srcPixelStride])
                }
            }
        }
        src.rewind()
    }

    private fun drain(end: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        var idle = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    idle++
                    if (idle > 400) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }

                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        m.writeSampleData(track, buffer, info)
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    val frameCount: Int get() = frames

    /** Stops, finalises the video and saves it to the gallery. Returns null if too few frames were captured. */
    fun finish(): Uri? {
        synchronized(lock) {
            if (finished) return null
            finished = true
            var ok = !failed && frames >= 2
            try {
                val c = codec
                if (c != null && ok) {
                    var index = -1
                    var tries = 0
                    while (index < 0 && tries++ < 50) index = c.dequeueInputBuffer(20_000)
                    if (index >= 0) {
                        c.queueInputBuffer(index, 0, 0, frames * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        drain(true)
                    }
                }
            } catch (t: Throwable) {
                ok = false
            }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            if (muxerStarted) {
                if (runCatching { muxer?.stop() }.isFailure) ok = false
            } else {
                ok = false
            }
            runCatching { muxer?.release() }
            muxer = null
            if (!ok) {
                file.delete()
                return null
            }
            return MediaStoreSaver.publish(context, file, true, "video/mp4")
        }
    }

    private companion object {
        const val FRAME_US = 33_333L
    }
}
