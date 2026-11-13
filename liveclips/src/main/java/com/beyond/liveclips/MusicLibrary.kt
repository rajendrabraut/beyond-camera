package com.beyond.liveclips

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.math.PI
import kotlin.math.sin

internal data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val source: String = "",
    val license: String = "",
    val download: String = "",
    val localName: String = "$id.mp3"
) {
    val credit: String
        get() = if (license.isEmpty()) "" else "$title by $artist · $source · $license · Edited to fit the clip"
}

internal class MusicLibrary(private val context: Context) {
    private val directory = File(context.filesDir, "music").apply { mkdirs() }
    private val cachedCatalog = File(directory, "catalog.json")
    private val preferences = context.getSharedPreferences("music_catalog", Context.MODE_PRIVATE)
    private val starter = Track("starter", "Beyond Beat", "Beyond Live Clips", localName = "starter.wav")

    fun file(track: Track): File? = File(directory, track.localName).takeIf { it.isFile }

    fun load(): List<Track> {
        if (file(starter) == null) createStarter()
        val fallback = context.assets.open("music-catalog.json").bufferedReader().use { it.readText() }
        val remote = runCatching { parseCatalog(cachedCatalog.readText()) }.getOrElse { parseCatalog(fallback) }
        val imports = directory.listFiles { file -> file.name.startsWith("import-") && file.extension == "json" }
            .orEmpty().mapNotNull { metadata ->
                runCatching {
                    val title = JSONObject(metadata.readText()).getString("title")
                    val id = metadata.nameWithoutExtension
                    Track(id, title, "Local import", localName = "$id.audio")
                }.getOrNull()?.takeIf { file(it) != null }
            }
        val downloaded = directory.listFiles { file -> file.name.endsWith(".track.json") }
            .orEmpty().flatMap { runCatching { parseCatalog(it.readText()) }.getOrDefault(emptyList()) }
            .filter { file(it) != null }
        return listOf(starter) + imports + (downloaded + remote + parseCatalog(fallback)).distinctBy { it.id }
    }

    suspend fun refresh(): List<Track> = withContext(Dispatchers.IO) {
        val connection = connect(CATALOG_URL)
        try {
            if (cachedCatalog.isFile) {
                preferences.getString("etag", null)?.let { connection.setRequestProperty("If-None-Match", it) }
            }
            when (connection.responseCode) {
                304 -> Unit
                200 -> {
                    val data = connection.inputStream.use { it.readNBytesCompat(128 * 1024) }
                        .toString(Charsets.UTF_8)
                    require(parseCatalog(data).isNotEmpty()) { "The online playlist is empty." }
                    val temporary = File(directory, "catalog.part")
                    temporary.writeText(data)
                    if (!temporary.renameTo(cachedCatalog)) throw IOException("Couldn't update the playlist.")
                    preferences.edit().putString("etag", connection.getHeaderField("ETag")).apply()
                }
                else -> throw IOException("The online playlist is unavailable (${connection.responseCode}).")
            }
            load()
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(track: Track) = withContext(Dispatchers.IO) {
        if (file(track) != null) return@withContext
        val target = File(directory, track.localName)
        val partial = File(directory, "${track.id}.part")
        val validatorFile = File(directory, "${track.id}.etag")
        val validator = validatorFile.takeIf { it.isFile }?.readText()
        val offset = partial.length().takeIf { !validator.isNullOrBlank() && it < MAX_AUDIO_BYTES } ?: 0L
        val connection = connect(track.download)
        try {
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                connection.setRequestProperty("If-Range", validator)
            }
            val response = connection.responseCode
            val append = response == 206 && offset > 0 &&
                connection.getHeaderField("Content-Range").orEmpty().startsWith("bytes $offset-")
            if (response != 200 && !append) {
                if (response == 416) {
                    partial.delete()
                    validatorFile.delete()
                }
                throw IOException("Download interrupted. Tap Download to retry.")
            }
            val start = if (append) offset else 0L
            val expected = if (append) connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')?.toLongOrNull()
            else connection.getHeaderField("Content-Length")?.toLongOrNull()
            require(expected == null || expected in 1..MAX_AUDIO_BYTES) { "This song is too large." }
            val newValidator = connection.getHeaderField("ETag")?.takeUnless { it.startsWith("W/") }
                ?: connection.getHeaderField("Last-Modified")
            if (newValidator != null) validatorFile.writeText(newValidator) else validatorFile.delete()
            val output = java.io.FileOutputStream(partial, append)
            output.use { sink ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = start
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= MAX_AUDIO_BYTES) { "This song is too large." }
                        sink.write(buffer, 0, count)
                    }
                    if (expected != null && expected != total) throw IOException("Download interrupted. Tap Download to resume.")
                }
            }
            if (runCatching { mediaDuration(partial) }.getOrDefault(0L) < MIN_CLIP_MS) {
                partial.delete()
                validatorFile.delete()
                throw IOException("The downloaded song couldn't be read.")
            }
            val metadata = JSONObject()
                .put("id", track.id).put("title", track.title).put("artist", track.artist)
                .put("source", track.source).put("license", track.license).put("download", track.download)
            File(directory, "${track.id}.track.json").writeText(JSONArray().put(metadata).toString())
            if (!partial.renameTo(target)) throw IOException("Couldn't save the song.")
            validatorFile.delete()
        } finally {
            connection.disconnect()
        }
    }

    suspend fun import(uri: Uri): List<Track> = withContext(Dispatchers.IO) {
        val id = "import-${UUID.randomUUID()}"
        val audio = File(directory, "$id.audio")
        val metadata = File(directory, "$id.json")
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.take(120) ?: "Imported song"
        try {
            copyFromUri(context, uri, audio, MAX_AUDIO_BYTES)
            require(mediaDuration(audio) >= MIN_CLIP_MS) { "Choose audio at least 5 seconds long." }
            metadata.writeText(JSONObject().put("title", name).toString())
            load()
        } catch (e: Exception) {
            audio.delete()
            metadata.delete()
            throw e
        }
    }

    suspend fun remove(track: Track) = withContext(Dispatchers.IO) {
        require(track != starter)
        file(track)?.let { if (!it.delete()) throw IOException("Couldn't remove this song.") }
        if (track.id.startsWith("import-")) File(directory, "${track.id}.json").delete()
        else File(directory, "${track.id}.track.json").delete()
    }

    private fun parseCatalog(body: String): List<Track> {
        val rows = JSONArray(body)
        return (0 until minOf(rows.length(), 100)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            val source = row.optString("source")
            val license = row.optString("license")
            val audio = row.optString("download")
            if (!REMOTE_ID.matches(id) || license !in LICENSES ||
                !source.startsWith("https://") || !audio.startsWith("https://")) return@mapNotNull null
            val title = row.optString("title").take(120)
            val artist = row.optString("artist").take(120)
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            Track(id, title, artist, source, license, audio)
        }.distinctBy { it.id }
    }

    private fun createStarter() {
        val sampleRate = 22_050
        val samples = sampleRate * 16
        val wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        wave.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
        wave.putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        val melody = doubleArrayOf(261.63, 329.63, 392.0, 523.25, 220.0, 329.63, 440.0, 493.88)
        repeat(samples) { sample ->
            val time = sample.toDouble() / sampleRate
            val beat = time * 4
            val phase = beat % 1
            val note = melody[beat.toInt() % melody.size]
            val attack = minOf(phase * 14, 1.0)
            val envelope = attack * (1 - phase) * minOf((16 - time) * 5, 1.0)
            val sampleValue = (sin(2 * PI * note * time) + 0.22 * sin(4 * PI * note * time)) * envelope
            wave.putShort((sampleValue * 11_000).toInt().toShort())
        }
        val temporary = File(directory, "starter.part")
        temporary.writeBytes(wave.array())
        if (!temporary.renameTo(File(directory, starter.localName))) throw IOException("Couldn't prepare the starter track.")
    }

    private fun connect(url: String): HttpsURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "Music sources must use HTTPS." }
        return (parsed.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "BeyondLiveClips/0.1")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count == -1) return result.toByteArray()
            require(result.size() + count <= limit) { "Playlist is too large." }
            result.write(buffer, 0, count)
        }
    }

    companion object {
        private const val CATALOG_URL = "https://raw.githubusercontent.com/rajendrabraut/beyond-camera/main/liveclips/src/main/assets/music-catalog.json"
        private const val MAX_AUDIO_BYTES = 40_000_000L
        private val REMOTE_ID = Regex("[a-z0-9][a-z0-9-]{1,39}")
        private val LICENSES = setOf(
            "https://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/publicdomain/zero/1.0/"
        )
    }
}
