package com.beyond.camera.diagnostics

internal data class PhotoTiming(
    val totalMs: Long,
    val frameMs: Long,
    val saveMs: Long,
    val queueMs: Long
)
