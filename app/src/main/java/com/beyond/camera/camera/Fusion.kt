package com.beyond.camera.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Multi-frame fusion. Several shots of the same scene are aligned and merged into one cleaner image:
 *  - DENOISE: same-exposure frames are averaged, skipping pixels that moved (night and zoomed shots).
 *  - HDR: darker / normal / brighter frames are blended so each area comes from its best exposure.
 *
 * Works in horizontal strips, so memory stays low even at full sensor resolution.
 */
object Fusion {
    enum class Kind { DENOISE, HDR }

    class Options(
        val kind: Kind,
        val gamma: Float = 1f,
        val contrast: Float = 0f,
        val saturation: Float = 1f,
        val sharpen: Float = 0f
    )

    private class Luma(val w: Int, val h: Int, val d: IntArray)

    private const val STRIP = 256

    /** Returns the merged image in sensor orientation, or null if merging was not possible. */
    fun merge(input: List<ByteArray>, options: Options): Bitmap? {
        if (input.isEmpty()) return null
        val decoders = ArrayList<BitmapRegionDecoder>()
        try {
            val first = bounds(input[0])
            val w = first.first
            val h = first.second
            if (w <= 0 || h <= 0) return null
            val frames = input.filter { bounds(it) == first }
            val n = frames.size

            var sample = 1
            while (w / (sample * 2) >= 480) sample *= 2
            val thumbs = ArrayList<Luma>()
            for (frame in frames) thumbs.add(decodeLuma(frame, sample) ?: return null)

            // Stage 1: coarse alignment on small copies.
            val tdx = IntArray(n)
            val tdy = IntArray(n)
            val dx = IntArray(n)
            val dy = IntArray(n)
            for (i in 1 until n) {
                val shift = align(thumbs[0], thumbs[i], 4)
                tdx[i] = shift.first
                tdy[i] = shift.second
                dx[i] = shift.first * sample
                dy[i] = shift.second * sample
            }

            for (frame in frames) {
                @Suppress("DEPRECATION")
                decoders.add(BitmapRegionDecoder.newInstance(frame, 0, frame.size, false) ?: return null)
            }

            // Stage 2: pixel-accurate alignment on a full-resolution centre crop.
            val cs = min(768, min(w, h))
            val cx = (w - cs) / 2
            val cy = (h - cs) / 2
            val refCrop = regionLuma(decoders[0], Rect(cx, cy, cx + cs, cy + cs))
            if (refCrop != null) {
                for (i in 1 until n) {
                    val sx = (cx + dx[i]).coerceIn(0, w - cs)
                    val sy = (cy + dy[i]).coerceIn(0, h - cs)
                    val crop = regionLuma(decoders[i], Rect(sx, sy, sx + cs, sy + cs)) ?: continue
                    val fine = align(refCrop, crop, 4)
                    dx[i] = (sx - cx) + fine.first
                    dy[i] = (sy - cy) + fine.second
                }
            }

            val hdr = options.kind == Kind.HDR && n > 1
            val tw = thumbs[0].w
            val th = thumbs[0].h
            val maps: Array<IntArray>? = if (hdr) hdrWeights(thumbs, tdx, tdy) else null

            // Ghost rejection for DENOISE: pixels that differ a lot from the reference are left out.
            val ghost = IntArray(256) { d ->
                when {
                    d <= 10 -> 256
                    d >= 36 -> 0
                    else -> 256 * (36 - d) / 26
                }
            }
            val tone = toneCurve(options.gamma, options.contrast)
            val sat = (options.saturation * 256f).toInt()

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bufs = Array(n) { IntArray(w * STRIP) }
            val rowStart = IntArray(n)
            val rowEnd = IntArray(n)
            val base = IntArray(n)
            val outBuf = IntArray(w * STRIP)
            val rowW = Array(n) { IntArray(tw) }
            val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val xScale = if (w > 1) ((tw - 1) shl 16) / (w - 1) else 0

            var y0 = 0
            while (y0 < h) {
                val y1 = min(h, y0 + STRIP)
                for (i in 0 until n) {
                    val r0 = (y0 + dy[i]).coerceIn(0, h)
                    val r1 = (y1 + dy[i]).coerceIn(0, h)
                    rowStart[i] = r0
                    rowEnd[i] = r1
                    if (r1 > r0) {
                        val region = decoders[i].decodeRegion(Rect(0, r0, w, r1), decodeOpts)
                        if (region == null || region.width != w) {
                            region?.recycle()
                            out.recycle()
                            return null
                        }
                        region.getPixels(bufs[i], 0, w, 0, 0, w, r1 - r0)
                        region.recycle()
                    }
                }

                for (y in y0 until y1) {
                    for (i in 0 until n) {
                        val sy = y + dy[i]
                        base[i] = if (sy >= rowStart[i] && sy < rowEnd[i]) (sy - rowStart[i]) * w else -1
                    }
                    if (maps != null) {
                        // Vertical interpolation of the weight maps for this row.
                        val fy = if (h > 1) y.toLong() * ((th - 1) shl 16) / (h - 1) else 0L
                        val ty = (fy shr 16).toInt().coerceIn(0, th - 1)
                        val ty2 = min(th - 1, ty + 1)
                        val f = (fy and 0xFFFFL).toInt()
                        for (i in 0 until n) {
                            val map = maps[i]
                            val row = rowW[i]
                            val a = ty * tw
                            val b = ty2 * tw
                            for (x in 0 until tw) {
                                row[x] = (map[a + x] * (65536 - f) + map[b + x] * f) shr 16
                            }
                        }
                    }

                    val o = (y - y0) * w
                    val refBase = base[0]
                    val refBuf = bufs[0]
                    for (x in 0 until w) {
                        val p = refBuf[refBase + x]
                        val pr = (p shr 16) and 255
                        val pg = (p shr 8) and 255
                        val pb = p and 255
                        var sr: Int
                        var sg: Int
                        var sb: Int
                        var sw: Int

                        if (maps != null) {
                            val fx = x * xScale
                            val tx = fx shr 16
                            val tx2 = if (tx + 1 < tw) tx + 1 else tx
                            val f = fx and 0xFFFF
                            val w0 = ((rowW[0][tx] * (65536 - f) + rowW[0][tx2] * f) shr 16) + 1
                            sr = pr * w0
                            sg = pg * w0
                            sb = pb * w0
                            sw = w0
                            for (i in 1 until n) {
                                val bi = base[i]
                                if (bi < 0) continue
                                val sx = x + dx[i]
                                if (sx < 0 || sx >= w) continue
                                val wi = (rowW[i][tx] * (65536 - f) + rowW[i][tx2] * f) shr 16
                                if (wi <= 0) continue
                                val q = bufs[i][bi + sx]
                                sr += ((q shr 16) and 255) * wi
                                sg += ((q shr 8) and 255) * wi
                                sb += (q and 255) * wi
                                sw += wi
                            }
                        } else {
                            val py = (pr + pg + pg + pb) shr 2
                            sr = pr shl 8
                            sg = pg shl 8
                            sb = pb shl 8
                            sw = 256
                            for (i in 1 until n) {
                                val bi = base[i]
                                if (bi < 0) continue
                                val sx = x + dx[i]
                                if (sx < 0 || sx >= w) continue
                                val q = bufs[i][bi + sx]
                                val qr = (q shr 16) and 255
                                val qg = (q shr 8) and 255
                                val qb = q and 255
                                val wi = ghost[abs(((qr + qg + qg + qb) shr 2) - py)]
                                if (wi == 0) continue
                                sr += qr * wi
                                sg += qg * wi
                                sb += qb * wi
                                sw += wi
                            }
                        }

                        var r = tone[sr / sw]
                        var g = tone[sg / sw]
                        var b = tone[sb / sw]
                        if (sat != 256) {
                            val yv = (r * 77 + g * 150 + b * 29) shr 8
                            r = clamp(yv + (((r - yv) * sat) shr 8))
                            g = clamp(yv + (((g - yv) * sat) shr 8))
                            b = clamp(yv + (((b - yv) * sat) shr 8))
                        }
                        outBuf[o + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out.setPixels(outBuf, 0, w, 0, y0, w, y1 - y0)
                y0 = y1
            }

            if (options.sharpen > 0f) sharpen(out, options.sharpen)
            return out
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Exception) {
            return null
        } finally {
            decoders.forEach { runCatching { it.recycle() } }
        }
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun bounds(jpeg: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth to o.outHeight
    }

    private fun toLuma(bitmap: Bitmap): Luma {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 255) + 2 * ((p shr 8) and 255) + (p and 255)) shr 2
        }
        return Luma(w, h, px)
    }

    private fun decodeLuma(jpeg: ByteArray, sample: Int): Luma? {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o) ?: return null
        return toLuma(bitmap).also { bitmap.recycle() }
    }

    private fun regionLuma(decoder: BitmapRegionDecoder, rect: Rect): Luma? {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = runCatching { decoder.decodeRegion(rect, o) }.getOrNull() ?: return null
        return toLuma(bitmap).also { bitmap.recycle() }
    }

    private fun half(src: Luma): Luma {
        val w = src.w / 2
        val h = src.h / 2
        val d = IntArray(w * h)
        for (y in 0 until h) {
            val a = (y * 2) * src.w
            val b = a + src.w
            for (x in 0 until w) {
                val x2 = x * 2
                d[y * w + x] = (src.d[a + x2] + src.d[a + x2 + 1] + src.d[b + x2] + src.d[b + x2 + 1]) shr 2
            }
        }
        return Luma(w, h, d)
    }

    private fun median(l: Luma): Int {
        val hist = IntArray(256)
        for (v in l.d) hist[v and 255]++
        val halfCount = l.d.size / 2
        var sum = 0
        for (i in 0 until 256) {
            sum += hist[i]
            if (sum >= halfCount) return i
        }
        return 128
    }

    /**
     * Median-threshold-bitmap alignment (works across different exposures).
     * Returns (ox, oy) so that ref(x, y) matches img(x + ox, y + oy).
     */
    private fun align(ref: Luma, img: Luma, levels: Int): Pair<Int, Int> {
        if (ref.w != img.w || ref.h != img.h) return 0 to 0
        val pr = ArrayList<Luma>()
        val pi = ArrayList<Luma>()
        pr.add(ref)
        pi.add(img)
        for (l in 1 until levels) {
            if (pr.last().w < 48 || pr.last().h < 48) break
            pr.add(half(pr.last()))
            pi.add(half(pi.last()))
        }
        var ox = 0
        var oy = 0
        for (l in pr.size - 1 downTo 0) {
            ox *= 2
            oy *= 2
            val a = pr[l]
            val b = pi[l]
            val ma = median(a)
            val mb = median(b)
            var best = mtbError(a, ma, b, mb, ox, oy)
            var bx = ox
            var by = oy
            for (cy in -1..1) {
                for (cx in -1..1) {
                    if (cx == 0 && cy == 0) continue
                    val e = mtbError(a, ma, b, mb, ox + cx, oy + cy)
                    if (e < best - 1e-6) {
                        best = e
                        bx = ox + cx
                        by = oy + cy
                    }
                }
            }
            ox = bx
            oy = by
        }
        return ox to oy
    }

    private fun mtbError(a: Luma, ma: Int, b: Luma, mb: Int, ox: Int, oy: Int): Double {
        val x0 = max(0, -ox)
        val x1 = min(a.w, b.w - ox)
        val y0 = max(0, -oy)
        val y1 = min(a.h, b.h - oy)
        if (x1 - x0 < 8 || y1 - y0 < 8) return Double.MAX_VALUE
        var err = 0L
        var count = 0L
        for (y in y0 until y1) {
            var ia = y * a.w + x0
            var ib = (y + oy) * b.w + x0 + ox
            for (x in x0 until x1) {
                val va = a.d[ia++]
                val vb = b.d[ib++]
                if (abs(va - ma) > 4 && abs(vb - mb) > 4) {
                    count++
                    if ((va > ma) != (vb > mb)) err++
                }
            }
        }
        return if (count < 64) Double.MAX_VALUE else err.toDouble() / count
    }

    /** Smooth per-frame weights (0..1024) at thumbnail size, favouring well-exposed areas. */
    private fun hdrWeights(thumbs: List<Luma>, tdx: IntArray, tdy: IntArray): Array<IntArray> {
        val n = thumbs.size
        val w = thumbs[0].w
        val h = thumbs[0].h
        val lut = FloatArray(256) { v ->
            val l = v / 255f - 0.5f
            exp(-(l * l) / (2f * 0.2f * 0.2f)) + 0.02f
        }
        val maps = Array(n) { i ->
            val m = FloatArray(w * h)
            val t = thumbs[i]
            for (y in 0 until h) {
                val sy = y + tdy[i]
                for (x in 0 until w) {
                    val sx = x + tdx[i]
                    m[y * w + x] = if (sx in 0 until t.w && sy in 0 until t.h) lut[t.d[sy * t.w + sx] and 255] else 0f
                }
            }
            boxBlur(boxBlur(m, w, h, 5), w, h, 5)
        }
        return Array(n) { i ->
            IntArray(w * h) { k ->
                var sum = 0f
                for (j in 0 until n) sum += maps[j][k]
                if (sum <= 0f) (if (i == 0) 1024 else 0) else (maps[i][k] / sum * 1024f).toInt()
            }
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val dst = FloatArray(w * h)
        val win = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / win
                sum += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / win
                sum += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
            }
        }
        return dst
    }

    private fun toneCurve(gamma: Float, contrast: Float): IntArray = IntArray(256) { v ->
        var x = v / 255f
        if (gamma != 1f) x = x.pow(gamma)
        if (contrast != 0f) {
            // Gentle S-curve around mid grey.
            val s = x * x * (3f - 2f * x)
            x += (s - x) * contrast
        }
        (x * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    /** In-place unsharp mask (4-neighbour), done in strips. */
    fun sharpen(bitmap: Bitmap, amount: Float) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return
        val k = (amount * 256f).toInt()
        val rows = 128
        val buf = IntArray(w * (rows + 2))
        val out = IntArray(w * rows)
        val carry = IntArray(w)
        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + rows)
            val n = y1 - y0
            bitmap.getPixels(buf, w, w, 0, y0, w, n)
            if (y0 == 0) System.arraycopy(buf, w, buf, 0, w) else System.arraycopy(carry, 0, buf, 0, w)
            if (y1 < h) bitmap.getPixels(buf, (n + 1) * w, w, 0, y1, w, 1) else System.arraycopy(buf, n * w, buf, (n + 1) * w, w)
            System.arraycopy(buf, n * w, carry, 0, w)
            for (j in 0 until n) {
                val c = (j + 1) * w
                for (x in 0 until w) {
                    val xl = if (x > 0) x - 1 else x
                    val xr = if (x < w - 1) x + 1 else x
                    val p = buf[c + x]
                    val up = buf[c - w + x]
                    val dn = buf[c + w + x]
                    val lf = buf[c + xl]
                    val rt = buf[c + xr]
                    val r = sharpenChannel((p shr 16) and 255, (up shr 16) and 255, (dn shr 16) and 255, (lf shr 16) and 255, (rt shr 16) and 255, k)
                    val g = sharpenChannel((p shr 8) and 255, (up shr 8) and 255, (dn shr 8) and 255, (lf shr 8) and 255, (rt shr 8) and 255, k)
                    val b = sharpenChannel(p and 255, up and 255, dn and 255, lf and 255, rt and 255, k)
                    out[j * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(out, 0, w, 0, y0, w, n)
            y0 = y1
        }
    }

    private fun sharpenChannel(c: Int, a: Int, b: Int, d: Int, e: Int, k: Int): Int {
        val lap = 4 * c - a - b - d - e
        if (lap > -8 && lap < 8) return c
        return clamp(c + ((lap * k) shr 10))
    }
}
