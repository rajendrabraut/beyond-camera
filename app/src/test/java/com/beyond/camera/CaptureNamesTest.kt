package com.beyond.camera

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureNamesTest {
    @Test
    fun usesSixteenDigitsAndAdvancesPastEqualTimestamps() {
        val time = Instant.parse("2026-11-14T16:40:42.981Z").toEpochMilli()
        assertEquals("BYND_IMG_2026111416404298.jpg", CaptureNames.photo(time))
        assertEquals("BYND_IMG_2026111416404299.jpg", CaptureNames.photo(time))
        assertEquals("BYND_IMG_2026111416404300.jpg", CaptureNames.photo(time))
    }

    @Test
    fun onlyAcceptsOneFolderName() {
        assertEquals("Trip 2026", CaptureNames.folder(" Trip 2026 "))
        assertNull(CaptureNames.folder("../Travel"))
        assertNull(CaptureNames.folder("Pictures/Travel"))
        assertNull(CaptureNames.folder(""))
    }
}
