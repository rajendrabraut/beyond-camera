package com.beyond.liveclips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRangeTest {
    @Test fun acceptsWholeFileAndSeekRanges() {
        assertEquals(0L..999L, streamRange(null, 1000))
        assertEquals(50L..999L, streamRange("bytes=50-", 1000))
        assertEquals(50L..60L, streamRange("bytes=50-60", 1000))
        assertEquals(950L..999L, streamRange("bytes=950-1234", 1000))
    }

    @Test fun rejectsRangesOutsideFileAndMalformedRequests() {
        assertNull(streamRange("bytes=1000-", 1000))
        assertNull(streamRange("bytes=90-80", 1000))
        assertNull(streamRange("bytes=1-2,4-5", 1000))
        assertNull(streamRange(null, 0))
    }
}
