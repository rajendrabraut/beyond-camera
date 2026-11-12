package com.beyond.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** App palette. */
object Ui {
    val Accent = Color(0xFF8B7CFF)
    val AccentSoft = Color(0xFFC9C2FF)
    val Record = Color(0xFFFF4D5E)
    val Panel = Color(0xE61A1A22)
    val Chip = Color(0x66000000)
    val Surface = Color(0xFF121218)
    val Card = Color(0xFF1D1D27)
    val Dim = Color(0xFF9A9AA8)
}

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun Context.buzz(ms: Long = 35) {
    runCatching {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** Suspends until the future completes. */
suspend fun <T> ListenableFuture<T>.await(executor: Executor): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, executor)
    }

object LocationHelper {
    /** Most recent fix the system already has. Returns null when permission is missing. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Location? {
        val fine = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (provider in runCatching { manager.getProviders(true) }.getOrDefault(emptyList())) {
            val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || location.time > best.time) best = location
        }
        return best
    }
}
