package com.beyond.camera.camera

import kotlin.math.abs

/** Sparse luma analysis runs on the camera thread. It never changes the saved pixels. */
internal class FrameQuality private constructor(
    private val samples: IntArray,
    private val detail: Int
) {
    fun matchesScene(other: FrameQuality): Boolean {
        var change = 0
        var changedCells = 0
        for (i in samples.indices) {
            val difference = abs(samples[i] - other.samples[i])
            change += difference
            if (difference >= 24) changedCells++
        }
        return change <= 8 * samples.size && changedCells <= samples.size / 16
    }

    fun isClearlySharperThan(other: FrameQuality): Boolean =
        detail > other.detail + maxOf(samples.size, other.detail / 4)

    companion object {
        private const val COLUMNS = 24
        private const val ROWS = 18

        fun measure(pixels: ByteArray, width: Int, height: Int): FrameQuality {
            val samples = IntArray(COLUMNS * ROWS)
            if (width < 16 || height < 16) return FrameQuality(samples, 0)
            var detail = 0
            for (row in 0 until ROWS) {
                val y = 7 + (2 * row + 1) * (height - 14) / (2 * ROWS)
                for (column in 0 until COLUMNS) {
                    val x = 7 + (2 * column + 1) * (width - 14) / (2 * COLUMNS)
                    val center = y * width + x
                    var across = luma(pixels, center - 6, width)
                    var down = luma(pixels, center - 6 * width, 1)
                    var sum = across + down
                    for (offset in -5..6) {
                        val nextAcross = luma(pixels, center + offset, width)
                        val nextDown = luma(pixels, center + offset * width, 1)
                        detail += edgeEnergy(across, nextAcross) + edgeEnergy(down, nextDown)
                        sum += nextAcross + nextDown
                        across = nextAcross
                        down = nextDown
                    }
                    samples[row * COLUMNS + column] = sum / 26
                }
            }
            return FrameQuality(samples, detail)
        }

        // Sharp transitions concentrate energy in fewer pixels than a motion-smeared edge.
        private fun edgeEnergy(before: Int, after: Int): Int {
            val edge = (abs(before - after) - 12).coerceIn(0, 96)
            return edge * edge
        }

        private fun luma(pixels: ByteArray, center: Int, stride: Int): Int =
            ((pixels[center - stride].toInt() and 255) +
                (pixels[center].toInt() and 255) +
                (pixels[center + stride].toInt() and 255)) / 3
    }
}
