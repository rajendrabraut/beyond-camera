package com.beyond.camera.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Beyond Camera image engine, written from scratch for this app.
 *
 * Design follows published, openly documented methods (no code copied):
 *  - Burst alignment and robust merge after Google's HDR+ paper (Hasinoff et al., SIGGRAPH Asia 2016)
 *    and its open analysis by Monod, Delon and Veit (IPOL 2021): coarse-to-fine tile matching on an
 *    image pyramid, then per-tile weights that leave out frames that don't match the reference.
 *  - HDR merge of bracketed exposures in linear light with well-exposedness weights, after Mertens,
 *    Kautz and Van Reeth, "Exposure Fusion" (2007).
 *  - Finishing: local tone mapping on a smooth base layer (shadows lifted, highlights held, detail kept),
 *    edge-aware chroma noise reduction and noise-aware sharpening.
 *
 * Phones only hand apps finished JPEGs (not raw sensor data), so the engine works on those.
 * Everything runs in horizontal strips so memory stays low at full sensor resolution.
 */
object BurstEngine {
    enum class Kind { DENOISE, HDR }

    class Params(
        val kind: Kind,
        /** 0..1: how much dark areas are lifted. */
        val shadows: Float = 0.3f,
        /** 0..1: how much bright areas are held back. */
        val highlights: Float = 0.3f,
        /** 0..1: colour-noise reduction strength. */
        val chroma: Float = 0.6f,
        /** 0..1: sharpening strength. */
        val sharpen: Float = 0.35f,
        val saturation: Float = 1.05f,
        /** Overall brightening in linear light (Night). Highlights are compressed, not clipped. */
        val gain: Float = 1f
    )

    private class Luma(val w: Int, val h: Int, val d: IntArray)
    private class Motion(val tx: Int, val ty: Int, val dx: IntArray, val dy: IntArray)
    private class LowMaps(val w: Int, val h: Int, val y: FloatArray, val cb: FloatArray, val cr: FloatArray)

    private const val TILE_Q = 8          // tile size at quarter resolution
    private const val TILE = 32           // the same tile at full resolution
    private const val STRIP_TILES = 4     // tile rows merged per strip
    private const val REFINE = 2          // full-resolution refinement search radius

    private val TO_LIN = FloatArray(256) { srgbToLinear(it / 255f) }
    private val FROM_LIN = IntArray(4097) { (linearToSrgb(it / 4096f) * 255f + 0.5f).toInt().coerceIn(0, 255) }
    private val WELL_EXPOSED = FloatArray(256) { v ->
        val t = v / 255f - 0.5f
        exp(-(t * t) / (2f * 0.2f * 0.2f))
    }

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private fun fromLin(v: Float): Int {
        val i = (v * 4096f).toInt()
        return FROM_LIN[if (i < 0) 0 else if (i > 4096) 4096 else i]
    }

    private fun clamp8(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    // ---------------------------------------------------------------------------------------------
    // Public entry points
    // ---------------------------------------------------------------------------------------------

    /** Finishing only, for a single frame. Returns null if memory ran out. */
    fun enhanceSingle(jpeg: ByteArray, p: Params): Bitmap? = try {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (bitmap == null) {
            null
        } else {
            val sigma = estimateNoise(bitmap)
            if (p.gain != 1f) applyGain(bitmap, p.gain)
            finish(bitmap, p, sigma)
            bitmap
        }
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * Finishing only, for a photo that is already decoded (Portrait). Works in place when the bitmap
     * is mutable; otherwise on a copy. Returns the input unchanged if memory runs out.
     */
    fun enhanceBitmap(bitmap: Bitmap, p: Params): Bitmap = try {
        val target = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)?.also { bitmap.recycle() } ?: bitmap
        }
        if (target.isMutable) {
            val sigma = estimateNoise(target)
            if (p.gain != 1f) applyGain(target, p.gain)
            finish(target, p, sigma)
        }
        target
    } catch (e: OutOfMemoryError) {
        bitmap
    }

    /**
     * Aligns and merges a burst, then finishes it. For [Kind.HDR] the first frame must be the normal
     * exposure. Returns the result in sensor orientation, or null if merging wasn't possible.
     */
    fun process(input: List<ByteArray>, p: Params): Bitmap? {
        if (input.isEmpty()) return null
        if (input.size == 1) return enhanceSingle(input[0], p)
        val decoders = ArrayList<BitmapRegionDecoder>()
        try {
            val size = bounds(input[0])
            val w = size.first
            val h = size.second
            if (w < TILE * 2 || h < TILE * 2) return null
            val frames = input.filter { bounds(it) == size }
            val n = frames.size
            if (n < 2) return enhanceSingle(frames[0], p)

            // 1. Quarter-resolution luma for alignment.
            val quarter = ArrayList<Luma>()
            for (f in frames) quarter.add(decodeLuma(f, 4) ?: return null)
            if (quarter.any { it.w != quarter[0].w || it.h != quarter[0].h }) return null

            // 2. Reference: the normal exposure for HDR, otherwise the sharpest frame (least hand shake).
            val ref = if (p.kind == Kind.HDR) 0 else quarter.indices.maxByOrNull { sharpness(quarter[it]) } ?: 0

            // 3. Exposure gains that bring every frame to the reference brightness (linear light).
            val gains = FloatArray(n) { i ->
                if (p.kind == Kind.HDR && i != ref) exposureRatio(quarter[ref], quarter[i]) else 1f
            }
            val luts: Array<IntArray?> = Array(n) { i ->
                if (gains[i] == 1f) null else IntArray(256) { v -> fromLin(TO_LIN[v] * gains[i]) }
            }
            val aligned = Array(n) { i ->
                val lut = luts[i]
                if (lut == null) quarter[i] else Luma(quarter[i].w, quarter[i].h, IntArray(quarter[i].d.size) { k -> lut[quarter[i].d[k]] })
            }

            // 4. Coarse-to-fine tile alignment.
            val motion = Array(n) { i -> if (i == ref) null else alignTiles(aligned[ref], aligned[i]) }

            for (f in frames) {
                @Suppress("DEPRECATION")
                decoders.add(BitmapRegionDecoder.newInstance(f, 0, f.size, false) ?: return null)
            }
            val sigma = noiseOfRegion(decoders[ref], w, h)

            // Per-pixel ghost test on the green channel; thresholds follow the measured noise.
            val g1 = 2.5f * sigma + 4f
            val g2 = g1 * 3f
            val ghost = IntArray(256) { d ->
                when {
                    d <= g1 -> 256
                    d >= g2 -> 0
                    else -> (256f * (g2 - d) / (g2 - g1)).toInt()
                }
            }
            val tile1 = 1.5f * sigma + 3f
            val tile2 = 4f * sigma + 10f

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tilesX = (w + TILE - 1) / TILE
            val tilesY = (h + TILE - 1) / TILE
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

            val ox = Array(n) { IntArray(tilesX) }
            val oy = Array(n) { IntArray(tilesX) }
            val tw = Array(n) { IntArray(tilesX) }
            val altBuf = arrayOfNulls<IntArray>(n)
            val altTop = IntArray(n)
            val altRows = IntArray(n)
            val stripRows = STRIP_TILES * TILE
            val refBuf = IntArray(w * stripRows)
            val outBuf = IntArray(w * stripRows)
            val hdr = p.kind == Kind.HDR
            val white = max(1f, gains.maxOrNull() ?: 1f) * p.gain
            val toneNorm = (1f + 0.18f) / (1f + 0.18f / (white * white))

            var ty0 = 0
            while (ty0 < tilesY) {
                val y0 = ty0 * TILE
                val y1 = min(h, y0 + stripRows)
                val rows = y1 - y0
                if (!decodeRows(decoders[ref], w, y0, y1, opts, refBuf)) {
                    out.recycle()
                    return null
                }

                for (i in 0 until n) {
                    if (i == ref) continue
                    val m = motion[i] ?: continue
                    // Full-resolution offsets for this strip (one row of tiles is enough: tiles share a row
                    // only within one strip of STRIP_TILES rows, so take the middle tile row's motion).
                    var minDy = Int.MAX_VALUE
                    var maxDy = Int.MIN_VALUE
                    val stripTy = min(m.ty - 1, ty0 + STRIP_TILES / 2)
                    for (t in 0 until tilesX) {
                        val k = stripTy * m.tx + min(t, m.tx - 1)
                        ox[i][t] = m.dx[k] * 4
                        oy[i][t] = m.dy[k] * 4
                        minDy = min(minDy, oy[i][t])
                        maxDy = max(maxDy, oy[i][t])
                    }
                    val top = (y0 + minDy - REFINE - 2).coerceIn(0, h)
                    val bottom = (y1 + maxDy + REFINE + 2).coerceIn(0, h)
                    val needed = (bottom - top) * w
                    if (bottom <= top) {
                        altRows[i] = 0
                        continue
                    }
                    var buf = altBuf[i]
                    if (buf == null || buf.size < needed) {
                        buf = IntArray(needed)
                        altBuf[i] = buf
                    }
                    if (!decodeRows(decoders[i], w, top, bottom, opts, buf)) {
                        altRows[i] = 0
                        continue
                    }
                    altTop[i] = top
                    altRows[i] = bottom - top

                    // Refine each tile to the pixel and score how well it matches the reference.
                    for (t in 0 until tilesX) {
                        val x0 = t * TILE
                        val x1 = min(w, x0 + TILE)
                        var bx = ox[i][t]
                        var by = oy[i][t]
                        var best = tileError(refBuf, y0, buf, top, altRows[i], w, x0, x1, y0, y1, bx, by, luts[i])
                        val cx = bx
                        val cy = by
                        for (round in 0 until 3) {
                            var moved = false
                            for (d in 0 until 4) {
                                val nx = bx + if (d == 0) 1 else if (d == 1) -1 else 0
                                val ny = by + if (d == 2) 1 else if (d == 3) -1 else 0
                                if (abs(nx - cx) > REFINE || abs(ny - cy) > REFINE) continue
                                val e = tileError(refBuf, y0, buf, top, altRows[i], w, x0, x1, y0, y1, nx, ny, luts[i])
                                if (e < best) {
                                    best = e
                                    bx = nx
                                    by = ny
                                    moved = true
                                }
                            }
                            if (!moved) break
                        }
                        ox[i][t] = bx
                        oy[i][t] = by
                        tw[i][t] = when {
                            best <= tile1 -> 256
                            best >= tile2 -> 0
                            else -> (256f * (tile2 - best) / (tile2 - tile1)).toInt()
                        }
                    }
                }

                // Merge this strip.
                for (y in y0 until y1) {
                    val rRow = (y - y0) * w
                    val oRow = rRow
                    for (t in 0 until tilesX) {
                        val x0 = t * TILE
                        val x1 = min(w, x0 + TILE)
                        for (x in x0 until x1) {
                            val pr = refBuf[rRow + x]
                            val rr = (pr shr 16) and 255
                            val rg = (pr shr 8) and 255
                            val rb = pr and 255
                            if (!hdr) {
                                var sr = rr shl 8
                                var sg = rg shl 8
                                var sb = rb shl 8
                                var sw = 256
                                for (i in 0 until n) {
                                    if (i == ref || altRows[i] == 0) continue
                                    val wt = tw[i][t]
                                    if (wt == 0) continue
                                    val ay = y + oy[i][t] - altTop[i]
                                    val ax = x + ox[i][t]
                                    if (ay < 0 || ay >= altRows[i] || ax < 0 || ax >= w) continue
                                    val q = altBuf[i]!![ay * w + ax]
                                    val qg = (q shr 8) and 255
                                    val wi = (wt * ghost[abs(qg - rg)]) shr 8
                                    if (wi == 0) continue
                                    sr += ((q shr 16) and 255) * wi
                                    sg += qg * wi
                                    sb += (q and 255) * wi
                                    sw += wi
                                }
                                var r = sr / sw
                                var g = sg / sw
                                var b = sb / sw
                                if (p.gain != 1f) {
                                    val lr = TO_LIN[r] * p.gain
                                    val lg = TO_LIN[g] * p.gain
                                    val lb = TO_LIN[b] * p.gain
                                    val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                                    val s = if (lum > 1e-5f) toneNorm * (1f + lum / (white * white)) / (1f + lum) else 1f
                                    r = fromLin(lr * s)
                                    g = fromLin(lg * s)
                                    b = fromLin(lb * s)
                                }
                                outBuf[oRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            } else {
                                val refY = (rr * 77 + rg * 150 + rb * 29) shr 8
                                val refClipped = rr >= 250 || rg >= 250 || rb >= 250
                                var w0 = WELL_EXPOSED[refY] + 0.03f
                                if (refClipped) w0 = 0.02f
                                var lr = TO_LIN[rr] * w0
                                var lg = TO_LIN[rg] * w0
                                var lb = TO_LIN[rb] * w0
                                var sw = w0
                                for (i in 0 until n) {
                                    if (i == ref || altRows[i] == 0) continue
                                    val wt = tw[i][t]
                                    val ay = y + oy[i][t] - altTop[i]
                                    val ax = x + ox[i][t]
                                    if (ay < 0 || ay >= altRows[i] || ax < 0 || ax >= w) continue
                                    val q = altBuf[i]!![ay * w + ax]
                                    val qr = (q shr 16) and 255
                                    val qg = (q shr 8) and 255
                                    val qb = q and 255
                                    if (qr >= 250 || qg >= 250 || qb >= 250) continue
                                    var wi = WELL_EXPOSED[(qr * 77 + qg * 150 + qb * 29) shr 8]
                                    if (!refClipped) {
                                        // Only trust this frame where it shows the same scene as the reference.
                                        val lut = luts[i]
                                        val normalised = if (lut != null) lut[qg] else qg
                                        wi *= ghost[abs(normalised - rg)] / 256f
                                        wi *= wt / 256f
                                    }
                                    if (wi <= 0.001f) continue
                                    val gi = gains[i]
                                    lr += TO_LIN[qr] * gi * wi
                                    lg += TO_LIN[qg] * gi * wi
                                    lb += TO_LIN[qb] * gi * wi
                                    sw += wi
                                }
                                lr /= sw
                                lg /= sw
                                lb /= sw
                                // Global compression so recovered highlights fit, mid-grey kept in place.
                                val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                                val s = if (lum > 1e-5f) toneNorm * (1f + lum / (white * white)) / (1f + lum) else 1f
                                val r = fromLin(lr * s * p.gain)
                                val g = fromLin(lg * s * p.gain)
                                val b = fromLin(lb * s * p.gain)
                                outBuf[oRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }
                out.setPixels(outBuf, 0, w, 0, y0, w, rows)
                ty0 += STRIP_TILES
            }

            finish(out, p, sigma * 0.6f)
            return out
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: Exception) {
            return null
        } finally {
            decoders.forEach { runCatching { it.recycle() } }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Alignment
    // ---------------------------------------------------------------------------------------------

    private fun alignTiles(ref: Luma, alt: Luma): Motion {
        val pr = arrayListOf(ref)
        val pa = arrayListOf(alt)
        while (pr.size < 4 && pr.last().w >= TILE_Q * 8 && pr.last().h >= TILE_Q * 8) {
            pr.add(half(pr.last()))
            pa.add(half(pa.last()))
        }
        var prev: Motion? = null
        val cand = IntArray(6)
        for (l in pr.size - 1 downTo 0) {
            val r = pr[l]
            val a = pa[l]
            val tx = max(1, r.w / TILE_Q)
            val ty = max(1, r.h / TILE_Q)
            val dx = IntArray(tx * ty)
            val dy = IntArray(tx * ty)
            val pm = prev
            val radius = when {
                pm == null -> 4
                l > 0 -> 2
                else -> 1
            }
            for (j in 0 until ty) {
                for (i in 0 until tx) {
                    var nc = 0
                    if (pm == null) {
                        cand[0] = 0
                        cand[1] = 0
                        nc = 1
                    } else {
                        val pi = min(i / 2, pm.tx - 1)
                        val pj = min(j / 2, pm.ty - 1)
                        val ni = if (i % 2 == 0) pi - 1 else pi + 1
                        val nj = if (j % 2 == 0) pj - 1 else pj + 1
                        // Parent tile plus its two nearest neighbours, as in HDR+.
                        for (c in 0 until 3) {
                            val ii = if (c == 1) ni else pi
                            val jj = if (c == 2) nj else pj
                            if (ii < 0 || jj < 0 || ii >= pm.tx || jj >= pm.ty) continue
                            val k = jj * pm.tx + ii
                            cand[nc * 2] = pm.dx[k] * 2
                            cand[nc * 2 + 1] = pm.dy[k] * 2
                            nc++
                        }
                    }
                    var bx = 0
                    var by = 0
                    var best = Int.MAX_VALUE
                    for (c in 0 until nc) {
                        val cx = cand[c * 2]
                        val cy = cand[c * 2 + 1]
                        for (sy in cy - radius..cy + radius) {
                            for (sx in cx - radius..cx + radius) {
                                val s = tileSad(r, a, i * TILE_Q, j * TILE_Q, sx, sy, best)
                                if (s < best || (s == best && abs(sx) + abs(sy) < abs(bx) + abs(by))) {
                                    best = s
                                    bx = sx
                                    by = sy
                                }
                            }
                        }
                    }
                    dx[j * tx + i] = bx
                    dy[j * tx + i] = by
                }
            }
            prev = Motion(tx, ty, dx, dy)
        }
        return prev!!
    }

    private fun tileSad(r: Luma, a: Luma, x0: Int, y0: Int, ox: Int, oy: Int, limit: Int): Int {
        val x1 = min(r.w, x0 + TILE_Q)
        val y1 = min(r.h, y0 + TILE_Q)
        var sum = 0
        for (y in y0 until y1) {
            val ay = (y + oy).coerceIn(0, a.h - 1)
            val rRow = y * r.w
            val aRow = ay * a.w
            for (x in x0 until x1) {
                val ax = (x + ox).coerceIn(0, a.w - 1)
                sum += abs(r.d[rRow + x] - a.d[aRow + ax])
            }
            if (sum > limit) return sum
        }
        return sum
    }

    private fun tileError(
        refBuf: IntArray, refTop: Int,
        altBuf: IntArray, altTop: Int, altRows: Int,
        w: Int, x0: Int, x1: Int, y0: Int, y1: Int,
        ox: Int, oy: Int, lut: IntArray?
    ): Float {
        var sum = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            val ay = y + oy - altTop
            if (ay in 0 until altRows) {
                val rRow = (y - refTop) * w
                val aRow = ay * w
                var x = x0
                while (x < x1) {
                    val ax = x + ox
                    if (ax in 0 until w) {
                        val gr = (refBuf[rRow + x] shr 8) and 255
                        var ga = (altBuf[aRow + ax] shr 8) and 255
                        if (lut != null) ga = lut[ga]
                        sum += abs(gr - ga)
                        count++
                    }
                    x += 2
                }
            }
            y += 2
        }
        return if (count < 32) Float.MAX_VALUE else sum.toFloat() / count
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

    private fun sharpness(l: Luma): Long {
        var sum = 0L
        for (y in 1 until l.h - 1 step 2) {
            val row = y * l.w
            for (x in 1 until l.w - 1 step 2) {
                val c = l.d[row + x]
                sum += abs(l.d[row + x + 1] - c) + abs(l.d[row + l.w + x] - c)
            }
        }
        return sum
    }

    /** Linear-light ratio reference / frame over pixels that are well exposed in both. */
    private fun exposureRatio(ref: Luma, alt: Luma): Float {
        val ratios = ArrayList<Float>()
        var k = 0
        while (k < ref.d.size) {
            val a = ref.d[k]
            val b = alt.d[k]
            if (a in 25..225 && b in 25..225) ratios.add(TO_LIN[a] / TO_LIN[b])
            k += 3
        }
        if (ratios.size < 100) return 1f
        ratios.sort()
        return ratios[ratios.size / 2].coerceIn(0.05f, 20f)
    }

    // ---------------------------------------------------------------------------------------------
    // Finishing
    // ---------------------------------------------------------------------------------------------

    private fun applyGain(bitmap: Bitmap, gain: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val white = max(1f, gain)
        val norm = (1f + 0.18f) / (1f + 0.18f / (white * white))
        val rows = 64
        val buf = IntArray(w * rows)
        var y0 = 0
        while (y0 < h) {
            val n = min(rows, h - y0)
            bitmap.getPixels(buf, 0, w, 0, y0, w, n)
            for (k in 0 until w * n) {
                val px = buf[k]
                val lr = TO_LIN[(px shr 16) and 255] * gain
                val lg = TO_LIN[(px shr 8) and 255] * gain
                val lb = TO_LIN[px and 255] * gain
                val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                val s = if (lum > 1e-5f) norm * (1f + lum / (white * white)) / (1f + lum) else 1f
                buf[k] = (0xFF shl 24) or (fromLin(lr * s) shl 16) or (fromLin(lg * s) shl 8) or fromLin(lb * s)
            }
            bitmap.setPixels(buf, 0, w, 0, y0, w, n)
            y0 += n
        }
    }

    /** Local tone mapping + chroma noise reduction + saturation, then sharpening. In place. */
    private fun finish(bitmap: Bitmap, p: Params, sigma: Float) {
        val low = lowMaps(bitmap) ?: return
        val w = bitmap.width
        val h = bitmap.height
        val sx = low.w.toFloat() / w
        val sy = low.h.toFloat() / h
        val chromaT = 8f + 2.5f * sigma
        val gainLut = FloatArray(257) { b -> toneGain(b / 256f, p) }
        val rows = 64
        val buf = IntArray(w * rows)
        val lw = low.w
        var y0 = 0
        while (y0 < h) {
            val n = min(rows, h - y0)
            bitmap.getPixels(buf, 0, w, 0, y0, w, n)
            for (j in 0 until n) {
                val fy = ((y0 + j) + 0.5f) * sy - 0.5f
                val yi = fy.toInt().coerceIn(0, low.h - 1)
                val yi2 = min(low.h - 1, yi + 1)
                val wy = (fy - yi).coerceIn(0f, 1f)
                val row = j * w
                for (x in 0 until w) {
                    val fx = (x + 0.5f) * sx - 0.5f
                    val xi = fx.toInt().coerceIn(0, lw - 1)
                    val xi2 = min(lw - 1, xi + 1)
                    val wx = (fx - xi).coerceIn(0f, 1f)
                    val a = yi * lw + xi
                    val b = yi * lw + xi2
                    val c = yi2 * lw + xi
                    val d = yi2 * lw + xi2
                    val w00 = (1f - wx) * (1f - wy)
                    val w01 = wx * (1f - wy)
                    val w10 = (1f - wx) * wy
                    val w11 = wx * wy
                    val baseY = low.y[a] * w00 + low.y[b] * w01 + low.y[c] * w10 + low.y[d] * w11
                    val lowCb = low.cb[a] * w00 + low.cb[b] * w01 + low.cb[c] * w10 + low.cb[d] * w11
                    val lowCr = low.cr[a] * w00 + low.cr[b] * w01 + low.cr[c] * w10 + low.cr[d] * w11

                    val px = buf[row + x]
                    val r = ((px shr 16) and 255).toFloat()
                    val g = ((px shr 8) and 255).toFloat()
                    val bl = (px and 255).toFloat()
                    val yv = 0.299f * r + 0.587f * g + 0.114f * bl
                    var cb = (bl - yv) * 0.564f
                    var cr = (r - yv) * 0.713f

                    // Local tone: gain from the smooth base, applied to the pixel so fine detail is kept.
                    val gi = baseY.coerceIn(0f, 255.99f)
                    val g0 = gainLut[gi.toInt()]
                    val g1 = gainLut[gi.toInt() + 1]
                    val gain = g0 + (g1 - g0) * (gi - gi.toInt())
                    val y2 = yv * gain

                    // Chroma noise: small differences from the smooth colour are noise; big ones are edges.
                    if (p.chroma > 0f) {
                        val dcb = cb - lowCb
                        val dcr = cr - lowCr
                        val mag = abs(dcb) + abs(dcr)
                        if (mag < chromaT) {
                            val k = p.chroma * (1f - mag / chromaT)
                            cb = lowCb + dcb * (1f - k)
                            cr = lowCr + dcr * (1f - k)
                        }
                    }
                    val cs = p.saturation * sqrt(gain)
                    cb *= cs
                    cr *= cs

                    val nr = (y2 + 1.403f * cr).toInt()
                    val ng = (y2 - 0.344f * cb - 0.714f * cr).toInt()
                    val nb = (y2 + 1.773f * cb).toInt()
                    buf[row + x] = (0xFF shl 24) or (clamp8(nr) shl 16) or (clamp8(ng) shl 8) or clamp8(nb)
                }
            }
            bitmap.setPixels(buf, 0, w, 0, y0, w, n)
            y0 += n
        }
        if (p.sharpen > 0f) sharpen(bitmap, p.sharpen, (2f * sigma + 4f).toInt())
    }

    /** Brightness-dependent gain: lifts shadows, gently holds highlights, leaves mid-tones. */
    private fun toneGain(t: Float, p: Params): Float {
        val u = t.coerceIn(0.02f, 1f)
        var gain = 1f + p.shadows * 1.6f * (1f - u).pow(3)
        if (u > 0.6f) {
            val k = (u - 0.6f) / 0.4f
            gain *= 1f - p.highlights * 0.22f * k * k
        }
        return gain.coerceIn(0.6f, 2.4f)
    }

    /** Smooth 1/8-resolution brightness and colour, used as the base layer for finishing. */
    private fun lowMaps(bitmap: Bitmap): LowMaps? = try {
        val w = max(4, bitmap.width / 8)
        val h = max(4, bitmap.height / 8)
        val step1 = Bitmap.createScaledBitmap(bitmap, max(8, bitmap.width / 4), max(8, bitmap.height / 4), true)
        val small = Bitmap.createScaledBitmap(step1, w, h, true)
        if (step1 != bitmap && step1 != small) step1.recycle()
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != bitmap) small.recycle()
        val y = FloatArray(w * h)
        val cb = FloatArray(w * h)
        val cr = FloatArray(w * h)
        for (k in px.indices) {
            val p = px[k]
            val r = ((p shr 16) and 255).toFloat()
            val g = ((p shr 8) and 255).toFloat()
            val b = (p and 255).toFloat()
            val yv = 0.299f * r + 0.587f * g + 0.114f * b
            y[k] = yv
            cb[k] = (b - yv) * 0.564f
            cr[k] = (r - yv) * 0.713f
        }
        val radius = max(2, min(w, h) / 40)
        LowMaps(
            w, h,
            boxBlur(boxBlur(y, w, h, radius), w, h, radius),
            boxBlur(boxBlur(cb, w, h, 2), w, h, 2),
            boxBlur(boxBlur(cr, w, h, 2), w, h, 2)
        )
    } catch (e: OutOfMemoryError) {
        null
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

    /** Unsharp mask (4-neighbour) that ignores changes smaller than [threshold], so noise isn't sharpened. */
    private fun sharpen(bitmap: Bitmap, amount: Float, threshold: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return
        val k = (amount * 256f).toInt()
        val t = threshold.coerceIn(4, 40)
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
                    // Work on brightness so colours don't fringe.
                    val yc = lumaOf(p)
                    val lap = 4 * yc - lumaOf(up) - lumaOf(dn) - lumaOf(lf) - lumaOf(rt)
                    if (lap > -t && lap < t) {
                        out[j * w + x] = p
                        continue
                    }
                    val delta = (lap * k) shr 10
                    out[j * w + x] = (0xFF shl 24) or
                        (clamp8(((p shr 16) and 255) + delta) shl 16) or
                        (clamp8(((p shr 8) and 255) + delta) shl 8) or
                        clamp8((p and 255) + delta)
                }
            }
            bitmap.setPixels(out, 0, w, 0, y0, w, n)
            y0 = y1
        }
    }

    private fun lumaOf(p: Int): Int = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8

    // ---------------------------------------------------------------------------------------------
    // Decoding helpers
    // ---------------------------------------------------------------------------------------------

    private fun bounds(jpeg: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth to o.outHeight
    }

    private fun decodeLuma(jpeg: ByteArray, sample: Int): Luma? {
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o) ?: return null
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        for (i in px.indices) px[i] = lumaOf(px[i])
        return Luma(w, h, px)
    }

    private fun decodeRows(
        decoder: BitmapRegionDecoder, w: Int, top: Int, bottom: Int,
        opts: BitmapFactory.Options, dst: IntArray
    ): Boolean {
        if (bottom <= top) return false
        val region = runCatching { decoder.decodeRegion(Rect(0, top, w, bottom), opts) }.getOrNull() ?: return false
        return try {
            if (region.width != w || region.height != bottom - top) return false
            region.getPixels(dst, 0, w, 0, 0, w, bottom - top)
            true
        } finally {
            region.recycle()
        }
    }

    /** Noise level (8-bit units) from a centre crop: median of neighbour differences. */
    private fun noiseOfRegion(decoder: BitmapRegionDecoder, w: Int, h: Int): Float {
        val s = min(512, min(w, h))
        val x0 = (w - s) / 2
        val y0 = (h - s) / 2
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val region = runCatching { decoder.decodeRegion(Rect(x0, y0, x0 + s, y0 + s), opts) }.getOrNull() ?: return 3f
        return try {
            estimateNoise(region)
        } finally {
            region.recycle()
        }
    }

    private fun estimateNoise(bitmap: Bitmap): Float {
        val s = min(512, min(bitmap.width, bitmap.height))
        val x0 = (bitmap.width - s) / 2
        val y0 = (bitmap.height - s) / 2
        val px = IntArray(s * s)
        bitmap.getPixels(px, 0, s, x0, y0, s, s)
        val hist = IntArray(256)
        var count = 0
        for (y in 0 until s) {
            val row = y * s
            for (x in 0 until s - 1) {
                val d = abs(((px[row + x] shr 8) and 255) - ((px[row + x + 1] shr 8) and 255))
                hist[d]++
                count++
            }
        }
        var acc = 0
        var median = 0
        for (i in 0 until 256) {
            acc += hist[i]
            if (acc >= count / 2) {
                median = i
                break
            }
        }
        // MAD of a difference of two noisy samples -> per-pixel sigma.
        return (median / (0.6745f * 1.4142f)).coerceIn(0.8f, 25f)
    }
}
