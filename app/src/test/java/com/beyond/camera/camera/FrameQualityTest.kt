package com.beyond.camera.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {
    @Test
    fun favorsClearEdgesOverTheSameSceneWithMotionSmear() {
        val sharp = stripedFrame(0)
        val smeared = stripedFrame(3)
        assertTrue(sharp.matchesScene(smeared))
        assertTrue(sharp.isClearlySharperThan(smeared))
        assertFalse(smeared.isClearlySharperThan(sharp))
    }

    @Test
    fun rejectsAnEarlierSceneEvenWhenItHasMoreDetail() {
        val sharp = stripedFrame(0)
        val dark = FrameQuality.measure(ByteArray(WIDTH * HEIGHT) { 32 }, WIDTH, HEIGHT)
        assertTrue(sharp.isClearlySharperThan(dark))
        assertFalse(sharp.matchesScene(dark))
    }

    @Test
    fun ignoresSmallDifferencesCausedBySensorNoise() {
        val clean = FrameQuality.measure(ByteArray(WIDTH * HEIGHT) { 96 }, WIDTH, HEIGHT)
        val noisy = ByteArray(WIDTH * HEIGHT) { i -> (96 + (i * 31 % 13) - 6).toByte() }
        val measured = FrameQuality.measure(noisy, WIDTH, HEIGHT)
        assertTrue(clean.matchesScene(measured))
        assertFalse(measured.isClearlySharperThan(clean))
    }

    private fun stripedFrame(blurRadius: Int): FrameQuality {
        val pixels = ByteArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH
            var sum = 0
            for (position in x - blurRadius..x + blurRadius) {
                sum += if (Math.floorMod(position, 32) < 16) 48 else 176
            }
            (sum / (blurRadius * 2 + 1)).toByte()
        }
        return FrameQuality.measure(pixels, WIDTH, HEIGHT)
    }

    private companion object {
        const val WIDTH = 352
        const val HEIGHT = 256
    }
}
