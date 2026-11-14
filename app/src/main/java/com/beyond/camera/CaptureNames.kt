package com.beyond.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object CaptureNames {
    private val clock = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private var lastCentisecond = Long.MIN_VALUE

    @Synchronized
    fun photo(capturedAt: Long): String {
        val centisecond = maxOf(capturedAt / 10, lastCentisecond + 1)
        lastCentisecond = centisecond
        val timestamp = clock.format(Date(centisecond * 10)).dropLast(1)
        return "BYND_IMG_$timestamp.jpg"
    }

    fun folder(input: String): String? {
        val name = input.trim()
        return name.takeIf {
            it.length in 1..64 && it != "." && it != ".." && !it.startsWith('.') &&
                it.none { c -> c.isISOControl() || c in "/\\:*?\"<>|" }
        }
    }
}
