package com.beyond.liveclips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipDurationTest {
    @Test fun acceptsFiveSecondsAndRejectsShorterClips() {
        assertEquals(5_000L, clipDuration(5_000, 7_000, 6_000))
        assertNull(clipDuration(4_999, 7_000, 6_000))
    }

    @Test fun fullSongFitsAvailableMedia() {
        assertEquals(125_000L, clipDuration(null, 150_000, 125_000))
        assertEquals(50_000L, clipDuration(null, 50_000, 125_000))
        assertNull(clipDuration(125_000, 124_999, 125_000))
        assertNull(clipDuration(null, 80_000, 4_000))
    }

    @Test fun parsesFractionalSecondsAndRejectsInvalidInput() {
        assertEquals(5_500L, secondsToMs("5.5"))
        assertNull(secondsToMs("-1"))
        assertNull(secondsToMs("NaN"))
        assertNull(secondsToMs("Infinity"))
    }
}
