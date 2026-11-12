package com.beyond.camera.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class QrResult(val text: String, val format: String, val time: Long = System.currentTimeMillis())

sealed class QrContent {
    data class Url(val url: String) : QrContent()
    data class Wifi(val ssid: String, val password: String, val security: String) : QrContent()
    data class Phone(val number: String) : QrContent()
    data class Email(val address: String) : QrContent()
    data class Product(val code: String) : QrContent()
    data class Text(val text: String) : QrContent()
}

/** Result of checking a scanned link before the user opens it. */
class LinkCheck(val allowed: Boolean, val host: String, val warnings: List<String>)

/** Offline QR / barcode reader and writer (ZXing). Nothing is sent anywhere. */
object QrDecoder {
    private const val MAX_TEXT = 4096

    private val formats = listOf(
        BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX, BarcodeFormat.PDF_417,
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF,
        BarcodeFormat.CODABAR
    )

    private fun hints(tryHarder: Boolean): Map<DecodeHintType, Any> {
        val map = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        map[DecodeHintType.POSSIBLE_FORMATS] = formats
        map[DecodeHintType.CHARACTER_SET] = "UTF-8"
        if (tryHarder) map[DecodeHintType.TRY_HARDER] = true
        return map
    }

    // Live reader: only used from the single camera analysis thread.
    private val liveReader = MultiFormatReader().apply { setHints(hints(false)) }
    private var liveFrame = 0

    /** Reads a code from a live camera frame (luminance plane). */
    fun decode(image: ImageProxy): QrResult? {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val buffer = plane.buffer
        val stride = plane.rowStride
        val data = ByteArray(w * h)
        buffer.rewind()
        if (stride == w) {
            buffer.get(data, 0, min(buffer.remaining(), w * h))
        } else {
            for (y in 0 until h) {
                val pos = y * stride
                if (pos + w > buffer.limit()) break
                buffer.position(pos)
                buffer.get(data, y * w, w)
            }
        }
        val rotation = image.imageInfo.rotationDegrees
        val source: LuminanceSource = if (rotation == 90 || rotation == 270) {
            // Turn the frame upright so 1D barcodes held normally are read along their bars.
            val rotated = ByteArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) rotated[x * h + (h - 1 - y)] = data[row + x]
            }
            PlanarYUVLuminanceSource(rotated, h, w, 0, 0, h, w, false)
        } else {
            PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        }
        liveFrame++
        // Every third frame also tries light-on-dark codes.
        val attempt = if (liveFrame % 3 == 0) source.invert() else source
        return try {
            val result = liveReader.decodeWithState(BinaryBitmap(HybridBinarizer(attempt)))
            toResult(result.text, result.barcodeFormat)
        } catch (e: Exception) {
            null
        } finally {
            liveReader.reset()
        }
    }

    /** Reads a code from a picture already on the phone (screenshots, saved images). */
    fun decode(context: Context, uri: Uri): QrResult? {
        val bitmap = loadBitmap(context, uri, 2048) ?: return null
        try {
            var current = bitmap
            // Try the original size, then smaller copies: shrinking helps with blurry or very dense pictures.
            for (step in 0 until 3) {
                decodeBitmap(current)?.let { return it }
                val next = Bitmap.createScaledBitmap(
                    current,
                    max(64, current.width / 2),
                    max(64, current.height / 2),
                    true
                )
                if (current != bitmap) current.recycle()
                current = next
                if (current.width <= 64 || current.height <= 64) break
            }
            if (current != bitmap) current.recycle()
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBitmap(bitmap: Bitmap): QrResult? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, px)
        val reader = MultiFormatReader()
        val candidates = listOf(
            { BinaryBitmap(HybridBinarizer(source)) },
            { BinaryBitmap(GlobalHistogramBinarizer(source)) },
            { BinaryBitmap(HybridBinarizer(source.invert())) }
        )
        for (candidate in candidates) {
            try {
                val result = reader.decode(candidate(), hints(true))
                return toResult(result.text, result.barcodeFormat)
            } catch (e: Exception) {
                // try the next approach
            } finally {
                reader.reset()
            }
        }
        return null
    }

    private fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun toResult(text: String?, format: BarcodeFormat): QrResult? {
        if (text.isNullOrEmpty()) return null
        // Drop control characters and cap the length before the text reaches the UI or history.
        val clean = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }.take(MAX_TEXT)
        if (clean.isBlank()) return null
        return QrResult(clean, format.name.replace('_', ' '))
    }

    fun classify(result: QrResult): QrContent {
        val text = result.text.trim()
        val lower = text.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> QrContent.Url(text)
            lower.startsWith("wifi:") -> parseWifi(text)
            lower.startsWith("tel:") -> QrContent.Phone(text.substring(4))
            lower.startsWith("mailto:") -> QrContent.Email(text.substring(7).substringBefore('?'))
            result.format.startsWith("EAN") || result.format.startsWith("UPC") -> QrContent.Product(text)
            else -> QrContent.Text(text)
        }
    }

    private fun parseWifi(text: String): QrContent {
        val fields = HashMap<String, String>()
        val body = text.substring(5)
        val current = StringBuilder()
        val parts = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                current.append(body[i + 1])
                i += 2
                continue
            }
            if (c == ';') {
                parts.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        for (part in parts) {
            val split = part.indexOf(':')
            if (split > 0) fields[part.substring(0, split).uppercase()] = part.substring(split + 1)
        }
        val ssid = fields["S"] ?: return QrContent.Text(text)
        return QrContent.Wifi(ssid, fields["P"] ?: "", fields["T"] ?: "Open")
    }

    /** Only http/https links may be opened, and risky-looking ones carry warnings shown before opening. */
    fun checkLink(url: String): LinkCheck {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase() ?: ""
        if (uri == null || host.isEmpty() || (scheme != "http" && scheme != "https")) {
            return LinkCheck(false, host, listOf("This is not a normal web link, so it can't be opened from here."))
        }
        val warnings = ArrayList<String>()
        if (scheme == "http") warnings.add("Not encrypted (http). Anything you type on this site can be read by others.")
        if (!uri.userInfo.isNullOrEmpty()) warnings.add("The address hides its real site behind an \"@\" sign.")
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(':')) warnings.add("Uses a raw number address instead of a site name.")
        if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) {
            warnings.add("The site name uses look-alike characters.")
        }
        if (url.length > 300) warnings.add("Unusually long link.")
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) warnings.add("Uses an unusual port (${uri.port}).")
        return LinkCheck(true, host, warnings)
    }

    /** Makes a QR code picture for the given text, or null if the text is too long to encode. */
    fun encode(text: String, size: Int = 900): Bitmap? = try {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 2
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) px[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }
}

/** Scan history, kept only in this app's private storage. */
object QrHistory {
    private const val PREFS = "qr_history"
    private const val KEY = "items"
    private const val MAX = 60

    fun load(context: Context): List<QrResult> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                QrResult(o.getString("t"), o.optString("f", "QR CODE"), o.optLong("d", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, result: QrResult) {
        val items = ArrayList(load(context))
        items.removeAll { it.text == result.text }
        items.add(0, result)
        while (items.size > MAX) items.removeAt(items.size - 1)
        save(context, items)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun save(context: Context, items: List<QrResult>) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().put("t", item.text).put("f", item.format).put("d", item.time))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
