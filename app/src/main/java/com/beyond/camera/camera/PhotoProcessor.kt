package com.beyond.camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.beyond.camera.MediaStoreSaver
import com.beyond.camera.engine.BurstEngine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns captured frames into finished photos in the gallery. Call from a background thread. */
object PhotoProcessor {
    private val COPIED_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE
    )

    data class Edit(
        val quality: Int,
        val enhance: BurstEngine.Params?,
        val portrait: Boolean,
        val square: Boolean,
        val stampAt: Long?
    ) {
        val needed: Boolean get() = enhance != null || portrait || square || stampAt != null
    }

    fun saveOriginal(context: Context, frame: Frame, location: Location?): Uri? {
        val file = MediaStoreSaver.tempFile(context, "jpg")
        return try {
            file.writeBytes(frame.jpeg)
            if (location != null || frame.rotation != 0) {
                runCatching {
                    val exif = ExifInterface(file)
                    val needsOrientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
                    ) == ExifInterface.ORIENTATION_UNDEFINED && frame.rotation != 0
                    if (needsOrientation) exif.rotate(frame.rotation)
                    if (location != null) exif.setGpsInfo(location)
                    if (needsOrientation || location != null) exif.saveAttributes()
                }
            }
            MediaStoreSaver.publish(context, file, false, "image/jpeg")
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Renders a complete JPEG in cache before replacing the published original. */
    fun finish(
        context: Context,
        uri: Uri,
        rotation: Int,
        edit: Edit,
        location: Location?,
        extraFrames: List<File>
    ): Boolean {
        val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val upright = edit.portrait || edit.square || edit.stampAt != null
        var bitmap = when {
            edit.portrait -> decodeUpright(Frame(source, rotation), 3264)
            extraFrames.isNotEmpty() && edit.enhance != null ->
                BurstEngine.process(listOf(source) + extraFrames.map { it.readBytes() }, edit.enhance)
            edit.enhance != null -> BurstEngine.enhanceSingle(source, edit.enhance)
            else -> decodeUpright(Frame(source, rotation), Int.MAX_VALUE)
        } ?: return false

        val output = MediaStoreSaver.tempFile(context, "jpg")
        try {
            if (edit.portrait) {
                edit.enhance?.let { bitmap = BurstEngine.enhanceBitmap(bitmap, it) }
                val blurred = portrait(bitmap)
                if (blurred != null) {
                    bitmap.recycle()
                    bitmap = blurred
                } else if (edit.enhance == null && !edit.square && edit.stampAt == null) {
                    return false
                }
            } else if (upright && edit.enhance != null) {
                bitmap = rotate(bitmap, rotation) ?: return false
            }
            bitmap = decorate(bitmap, edit.square, edit.stampAt)
            if (!FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.JPEG, edit.quality, it) }) {
                return false
            }
            writeExif(output, source, if (upright) 0 else rotation, location)
            return MediaStoreSaver.replacePhoto(context, uri, output, source)
        } finally {
            bitmap.recycle()
            output.delete()
        }
    }

    private fun writeExif(file: File, source: ByteArray, rotation: Int, location: Location?) {
        val dst = ExifInterface(file)
        val src = ExifInterface(ByteArrayInputStream(source))
        for (tag in COPIED_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        val orientation = when (rotation) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Beyond Camera")
        if (location != null) dst.setGpsInfo(location)
        dst.saveAttributes()
    }

    private fun decodeUpright(frame: Frame, maxLongSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, opts)
        if (bitmap != null) {
            val longSide = max(bitmap.width, bitmap.height)
            if (longSide > maxLongSide) {
                val scale = maxLongSide.toFloat() / longSide
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt(),
                    (bitmap.height * scale).roundToInt(),
                    true
                )
                if (scaled != bitmap) bitmap.recycle()
                bitmap = scaled
            }
            rotate(bitmap, frame.rotation) ?: run {
                bitmap.recycle()
                null
            }
        } else {
            null
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    /** Returns an upright copy and recycles the input, or null if memory ran out (input is kept). */
    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap? {
        if (degrees % 360 == 0) return bitmap
        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun decorate(input: Bitmap, square: Boolean, stampAt: Long?): Bitmap {
        var bitmap = input
        try {
            if (square && bitmap.width != bitmap.height) {
                val side = min(bitmap.width, bitmap.height)
                val cropped = Bitmap.createBitmap(
                    bitmap,
                    (bitmap.width - side) / 2,
                    (bitmap.height - side) / 2,
                    side,
                    side
                )
                if (cropped != bitmap) bitmap.recycle()
                bitmap = cropped
            }
            if (stampAt != null) {
                if (!bitmap.isMutable) {
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    if (copy == null) throw IllegalStateException("Could not copy bitmap for timestamp")
                    bitmap.recycle()
                    bitmap = copy
                }
                if (bitmap.isMutable) {
                    val text = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(stampAt))
                    val size = max(bitmap.width, bitmap.height) / 40f
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = size
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.RIGHT
                        setShadowLayer(size / 6f, 0f, size / 16f, Color.argb(190, 0, 0, 0))
                    }
                    Canvas(bitmap).drawText(text, bitmap.width - size * 1.2f, bitmap.height - size * 1.2f, paint)
                }
            }
        } catch (e: Throwable) {
            if (bitmap != input) bitmap.recycle()
            throw e
        }
        return bitmap
    }

    /** Segments the person on-device and blurs everything else. Returns null when no person is found. */
    private fun portrait(source: Bitmap): Bitmap? {
        val w = source.width
        val h = source.height
        val segScale = 512f / max(w, h)
        val small = Bitmap.createScaledBitmap(
            source,
            max(1, (w * segScale).roundToInt()),
            max(1, (h * segScale).roundToInt()),
            true
        )
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
        val mask = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)), 15, TimeUnit.SECONDS)
        } finally {
            runCatching { segmenter.close() }
        }
        val mw = mask.width
        val mh = mask.height
        val floats = mask.buffer.apply { rewind() }.asFloatBuffer()
        val alpha = ByteArray(mw * mh)
        var person = 0
        for (i in 0 until mw * mh) {
            val c = floats.get(i)
            // Soft edge between "surely background" (0.3) and "surely person" (0.7).
            val t = ((c - 0.3f) / 0.4f).coerceIn(0f, 1f)
            val a = (t * t * (3f - 2f * t) * 255f).toInt()
            alpha[i] = a.toByte()
            if (a > 128) person++
        }
        if (person < mw * mh / 50) {
            small.recycle()
            return null
        }
        val maskBitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        maskBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(alpha))

        // Blur a small copy (cheap), then stretch it back as the background.
        val bw = small.width
        val bh = small.height
        val px = IntArray(bw * bh)
        small.getPixels(px, 0, bw, 0, 0, bw, bh)
        val radius = max(3, max(bw, bh) / 60)
        repeat(3) { blurArgb(px, bw, bh, radius) }
        val blurred = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        blurred.setPixels(px, 0, bw, 0, 0, bw, bh)
        small.recycle()

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val full = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(blurred, null, full, smooth)
        val layer = canvas.saveLayer(full, null)
        canvas.drawBitmap(source, 0f, 0f, null)
        val cut = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskBitmap, null, full, cut)
        canvas.restoreToCount(layer)
        blurred.recycle()
        maskBitmap.recycle()
        return result
    }

    /** One box-blur pass (horizontal then vertical) over ARGB pixels. */
    private fun blurArgb(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w * h)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = px[row + k.coerceIn(0, w - 1)]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val add = px[row + min(w - 1, x + r + 1)]
                val sub = px[row + max(0, x - r)]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = tmp[k.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (y in 0 until h) {
                px[y * w + x] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val add = tmp[min(h - 1, y + r + 1) * w + x]
                val sub = tmp[max(0, y - r) * w + x]
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }
}
