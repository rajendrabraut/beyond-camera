package com.beyond.camera.diagnostics

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.beyond.camera.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object CaptureDiagnostics {
    private const val SITE = "https://beyond-camera-diagnostics.plummaze.chatgpt.site"
    private const val KEY = "pairing_code"
    private const val WORK = "capture-diagnostics"
    private const val MAX_JPEG = 24 * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.getSharedPreferences(WORK, Context.MODE_PRIVATE)
    private fun queue(context: Context) = File(context.noBackupFilesDir, WORK)

    fun photo(context: Context, jpeg: ByteArray, rotation: Int, mode: String, timing: PhotoTiming,
        frameAgeMs: Long?, earlier: Boolean) {
        enqueue(context, jpeg, rotation, mode, timing.totalMs, frameAgeMs, earlier, timing)
    }

    fun video(context: Context, mode: String, durationMs: Long) {
        enqueue(context, null, 0, mode, durationMs, null, false)
    }

    private fun enqueue(context: Context, jpeg: ByteArray?, rotation: Int, mode: String,
        elapsedMs: Long, frameAgeMs: Long?, earlier: Boolean, timing: PhotoTiming? = null) {
        val app = context.applicationContext
        val key = prefs(app).getString(KEY, null) ?: return
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        io.execute {
            if (prefs(app).getString(KEY, null) != key) return@execute
            try {
                if (jpeg != null && jpeg.size > MAX_JPEG) throw IOException("JPEG exceeds upload size")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (jpeg != null) BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                val details = JSONObject().apply {
                    put("app", BuildConfig.VERSION_NAME)
                    put("mode", mode)
                    put("source", if (jpeg == null) "video" else if (frameAgeMs != null) "buffer" else "still")
                    put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.SDK_INT}")
                    put("width", bounds.outWidth.coerceAtLeast(0))
                    put("height", bounds.outHeight.coerceAtLeast(0))
                    put("savedMs", elapsedMs)
                    put("rotation", rotation)
                    put("frameAgeMs", frameAgeMs)
                    put("earlierFrame", earlier)
                    if (timing != null) {
                        put("frameMs", timing.frameMs)
                        put("saveMs", timing.saveMs)
                        put("queueMs", timing.queueMs)
                    }
                }
                val folder = queue(app)
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create diagnostics queue")
                val data = File(folder, "$id.data")
                val pending = File(folder, "$id.tmp")
                var ready = false
                try {
                    if (jpeg == null) data.writeText("{}") else data.writeBytes(jpeg)
                    pending.writeText(details.toString())
                    if (!pending.renameTo(File(folder, "$id.json"))) throw IOException("Cannot queue diagnostics")
                    ready = true
                } finally {
                    pending.delete()
                    if (!ready) data.delete()
                }
                schedule(app)
            } catch (e: Exception) {
                Log.w("CaptureDiagnostics", "Could not queue capture report", e)
            }
        }
    }

    private fun schedule(app: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    class UploadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result {
            val app = applicationContext
            val key = prefs(app).getString(KEY, null) ?: return Result.success()
            val reports = queue(app).listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
            for (report in reports) {
                if (isStopped || prefs(app).getString(KEY, null) != key) return Result.success()
                val data = File(report.parentFile, "${report.nameWithoutExtension}.data")
                if (!data.isFile) {
                    report.delete()
                    continue
                }
                val code = try {
                    upload(report, data, key)
                } catch (_: IOException) {
                    return Result.retry()
                }
                when {
                    code == 401 || code == 403 || code in 300..399 -> {
                        prefs(app).edit().putBoolean("reconnect", true).apply()
                        return Result.success()
                    }
                    code == 429 || code >= 500 -> return Result.retry()
                    code !in 200..299 -> {
                        Log.w("CaptureDiagnostics", "Capture report rejected by site (HTTP $code)")
                    }
                }
                report.delete()
                data.delete()
            }
            return Result.success()
        }

        private fun upload(report: File, data: File, key: String): Int {
            val metadata = report.readBytes()
            val video = JSONObject(metadata.toString(Charsets.UTF_8)).optString("source") == "video"
            val connection = URL("$SITE/api/captures/${report.nameWithoutExtension}")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(data.length())
                connection.setRequestProperty("Content-Type", if (video) "application/json" else "image/jpeg")
                connection.setRequestProperty("OAI-Sites-Authorization", "Bearer $key")
                connection.setRequestProperty("X-Capture-Report",
                    Base64.encodeToString(metadata, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING))
                connection.outputStream.use { output -> data.inputStream().use { it.copyTo(output) } }
                return connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    }

    @Composable
    fun Settings() {
        val app = LocalContext.current.applicationContext
        val settings = remember(app) { prefs(app) }
        var enabled by remember { mutableStateOf(settings.contains(KEY)) }
        var code by remember { mutableStateOf("") }
        var reconnect by remember { mutableStateOf(settings.getBoolean("reconnect", false)) }
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Capture diagnostics")
            Text("Send new photos and video capture logs to your private Beyond Camera site after local save.")
            Text(SITE)
            if (!enabled || reconnect) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Private site key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(enabled = code.trim().length in 16..4096, onClick = {
                    settings.edit().putString(KEY, code.trim()).remove("reconnect").apply()
                    enabled = true
                    reconnect = false
                    code = ""
                    schedule(app)
                }) { Text(if (reconnect) "Reconnect uploads" else "Enable photo uploads") }
            }
            if (enabled) {
                Text(if (reconnect) "Pairing expired. Saved reports are waiting." else "Uploads enabled")
                Button(onClick = {
                    settings.edit().remove(KEY).remove("reconnect").apply()
                    WorkManager.getInstance(app).cancelUniqueWork(WORK)
                    io.execute { queue(app).listFiles()?.forEach { it.delete() } }
                    enabled = false
                    reconnect = false
                }) { Text("Stop uploads and clear pending reports") }
            }
        }
    }
}
