package com.beyond.liveclips

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Page(val title: String, val icon: String) {
    CREATE("Create", "+"), MUSIC("Music", "♫"), CLIPS("Clips", "▶"), BITCHAT("Bitchat", "⇄")
}

class MainActivity : ComponentActivity() {
    private var sharedVideo by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xffa8ead2),
                secondary = Color(0xffffcf94),
                background = Color(0xff111615),
                surface = Color(0xff1b211f)
            )) {
                LiveClips(sharedVideo) { sharedVideo = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    @Suppress("DEPRECATION")
    private fun receive(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            sharedVideo = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf { it.scheme == "content" }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveClips(sharedVideo: Uri?, onSharedVideoHandled: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { ClipStore(app) }
    val library = remember { MusicLibrary(app) }
    val engine = remember { ClipEngine(app) }
    val recorder = remember { ClipRecorder() }
    val musicPlayer = remember { ExoPlayer.Builder(app).build() }
    var page by remember { mutableStateOf(Page.CREATE) }
    var draft by remember { mutableStateOf(store.read()) }
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var clips by remember { mutableStateOf(store.listClips()) }
    var selectedClip by remember { mutableStateOf(clips.firstOrNull()) }
    var preview by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var musicBusy by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var musicPlaying by remember { mutableStateOf<String?>(null) }
    var sendFile by remember { mutableStateOf<File?>(null) }
    var compact by remember { mutableStateOf(false) }
    val song = tracks.find { it.id == draft.songId }
    val songFile = song?.let { library.file(it) }
    val busy = working || recording

    DisposableEffect(Unit) { onDispose { musicPlayer.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        musicPlayer.pause()
        musicPlaying = null
        recorder.stop()
    }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { library.load() } }
            .onSuccess { tracks = it }
            .onFailure { status = it.message ?: "Couldn't open the music library." }
    }

    fun update(next: Draft) {
        try {
            store.write(next)
            draft = next
        } catch (e: Exception) {
            status = e.message ?: "Couldn't save the draft."
            throw e
        }
    }

    fun change(next: Draft) {
        runCatching { update(next) }
    }

    fun replaceVideo(file: File) {
        val previous = draft.video
        try {
            update(draft.copy(video = file, videoStartSeconds = "0"))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        previous?.delete()
        preview?.delete()
        preview = null
    }

    fun importVideo(uri: Uri) {
        if (busy) return
        page = Page.CREATE
        showCamera = false
        status = "Importing video…"
        working = true
        scope.launch {
            try {
                replaceVideo(withContext(Dispatchers.IO) { store.import(uri) })
                status = "Video added to the draft."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = e.message ?: "Couldn't import this video."
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(sharedVideo, busy) {
        if (sharedVideo != null && !busy) {
            importVideo(sharedVideo)
            onSharedVideoHandled()
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importVideo(uri)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !musicBusy) {
            musicBusy = true
            scope.launch {
                status = "Importing audio…"
                try {
                    tracks = library.import(uri)
                    status = "Song available offline."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = e.message ?: "Couldn't import this song."
                } finally {
                    musicBusy = false
                }
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        showCamera = it
        if (!it) status = "Allow camera access to record, or import a video."
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) change(draft.copy(originalSound = true))
        else status = "Microphone permission is needed to record original sound."
    }

    fun stopMusic() {
        musicPlayer.stop()
        musicPlaying = null
    }

    fun playSong(file: File, start: Long, end: Long) {
        musicPlayer.setMediaItem(engine.clipped(file, start, end))
        musicPlayer.prepare()
        musicPlayer.play()
    }

    suspend fun resolveEdit(video: File): ClipEdit = withContext(Dispatchers.IO) {
        val songStart = secondsToMs(draft.songStartSeconds) ?: error("Enter a valid song start.")
        val videoStart = secondsToMs(draft.videoStartSeconds) ?: error("Enter a valid video start.")
        val videoRemaining = mediaDuration(video, video = true) - videoStart
        val songRemaining = songFile?.let { mediaDuration(it) - songStart }
        require(draft.songId == null || songRemaining != null) { "Download the selected song first." }
        val requested = draft.lengthSeconds.takeIf { it.isNotBlank() }?.let {
            secondsToMs(it) ?: error("Enter a valid duration.")
        }
        val duration = if (requested == null && songRemaining != null) {
            clipDuration(songRemaining, videoRemaining, songRemaining)
        } else {
            clipDuration(requested, videoRemaining, songRemaining)
        } ?: error("Choose at least 5 seconds that fit both the video and the song.")
        ClipEdit(video, videoStart, duration, songFile, songStart, draft.originalSound, if (compact) 480 else 720)
    }

    fun render(save: Boolean) {
        val video = draft.video ?: return
        if (busy) return
        stopMusic()
        showCamera = false
        val temporary = File(store.clips, "${java.util.UUID.randomUUID()}.part")
        status = if (save) "Exporting clip…" else "Preparing preview…"
        working = true
        exportJob = scope.launch {
            try {
                val edit = resolveEdit(video)
                engine.export(edit, temporary)
                if (save) {
                    val finished = withContext(Dispatchers.IO) { store.commitClip(temporary, song?.credit.orEmpty()) }
                    clips = store.listClips()
                    selectedClip = finished
                    page = Page.CLIPS
                    status = "Clip saved on this device."
                } else {
                    val file = File(store.draftDirectory, "preview-${java.util.UUID.randomUUID()}.mp4")
                    check(temporary.renameTo(file)) { "Couldn't save the preview." }
                    preview?.delete()
                    preview = file
                    status = ""
                }
            } catch (e: CancellationException) {
                status = "Export cancelled."
                throw e
            } catch (e: Exception) {
                status = e.message ?: "Couldn't export this clip."
            } finally {
                temporary.delete()
                exportJob = null
                working = false
            }
        }
    }

    fun record() {
        if (recording) {
            recorder.stop()
            return
        }
        if (busy || recorder.capture == null) return
        stopMusic()
        working = true
        scope.launch {
            val file = store.newTake()
            try {
                val songStart = secondsToMs(draft.songStartSeconds) ?: error("Enter a valid song start.")
                val songRemaining = songFile?.let { withContext(Dispatchers.IO) { mediaDuration(it) } - songStart }
                require(draft.songId == null || songRemaining != null) { "Download the selected song first." }
                val requested = if (draft.lengthSeconds.isBlank()) songRemaining
                    ?: error("Enter a duration for a clip without music.")
                else secondsToMs(draft.lengthSeconds) ?: error("Enter a valid duration.")
                val duration = clipDuration(requested, 1_800_000L, songRemaining)
                    ?: error("Choose a duration from 5 seconds to the end of the selected song.")
                val mic = draft.originalSound
                recorder.record(file, duration, mic) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            status = "Recording…"
                            songFile?.let { playSong(it, songStart, songStart + duration) }
                        }
                        is VideoRecordEvent.Finalize -> {
                            stopMusic()
                            scope.launch {
                                try {
                                    val actual = withContext(Dispatchers.IO) { mediaDuration(file, video = true) }
                                    require(actual >= MIN_CLIP_MS) { "Record at least 5 seconds." }
                                    replaceVideo(file)
                                    if (actual < duration) change(draft.copy(lengthSeconds = (actual / 1000.0).toString()))
                                    showCamera = false
                                    status = "Take saved in your draft."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    file.delete()
                                    status = e.message ?: "Couldn't save the recording."
                                } finally {
                                    recording = false
                                }
                            }
                        }
                    }
                }
                recording = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                file.delete()
                status = e.message ?: "Couldn't record this clip."
            } finally {
                working = false
            }
        }
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            val credit = store.credit(file)
            if (credit.isNotBlank()) putExtra(Intent.EXTRA_TEXT, credit)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Share clip"))
    }

    Scaffold(bottomBar = {
        NavigationBar {
            Page.entries.forEach { item ->
                NavigationBarItem(
                    selected = page == item,
                    enabled = !busy,
                    onClick = {
                        stopMusic()
                        showCamera = false
                        status = ""
                        page = item
                    },
                    icon = { Text(item.icon) },
                    label = { Text(item.title) }
                )
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (page == Page.CREATE) "Beyond Live Clips" else page.title, style = MaterialTheme.typography.headlineMedium)
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            when (page) {
                Page.CREATE -> {
                    if (showCamera) {
                        RecordingPreview(recorder, front, { status = it }, Modifier.fillMaxWidth().height(280.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (!recording && draft.originalSound &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                                ) micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                else record()
                            }, enabled = recorder.capture != null && (!working || recording)) {
                                Text(if (recording) "Stop" else "Start recording")
                            }
                            OutlinedButton(onClick = { front = !front }, enabled = !busy) { Text("Flip camera") }
                        }
                    } else if (preview != null || draft.video != null) {
                        VideoPlayer(preview ?: draft.video!!, Modifier.fillMaxWidth().height(260.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showCamera = true
                            } else cameraPermission.launch(Manifest.permission.CAMERA)
                        }) { Text("Camera") }
                        OutlinedButton(enabled = !busy, onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("Import video") }
                    }
                    OutlinedButton(enabled = !busy, onClick = { showCamera = false; page = Page.MUSIC }) {
                        Text(song?.title ?: "Choose music")
                    }
                    if (song != null) TextButton(enabled = !busy, onClick = {
                        stopMusic()
                        change(draft.copy(songId = null, songStartSeconds = "0"))
                    }) { Text("Remove music from clip") }
                    SecondsField("Duration in seconds", draft.lengthSeconds, !busy) { change(draft.copy(lengthSeconds = it)) }
                    if (song != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = { change(draft.copy(lengthSeconds = "")) }) { Text("Full song") }
                            if (draft.lengthSeconds.isBlank()) Text("Full song selected", Modifier.padding(top = 12.dp))
                        }
                        SecondsField("Song start in seconds", draft.songStartSeconds, !busy) { change(draft.copy(songStartSeconds = it)) }
                    }
                    if (draft.video != null) {
                        SecondsField("Video start in seconds", draft.videoStartSeconds, !busy) { change(draft.copy(videoStartSeconds = it)) }
                    }
                    Row {
                        Checkbox(checked = draft.originalSound, enabled = !busy, onCheckedChange = {
                            if (it && showCamera && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else change(draft.copy(originalSound = it))
                        })
                        Text("Keep original sound", Modifier.padding(top = 14.dp))
                    }
                    Row {
                        Checkbox(checked = compact, enabled = !busy, onCheckedChange = { compact = it })
                        Text("Compact 480p export", Modifier.padding(top = 14.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && draft.video != null, onClick = { render(false) }) { Text("Preview edit") }
                        Button(enabled = !busy && draft.video != null, onClick = { render(true) }) { Text("Export MP4") }
                    }
                    if (exportJob != null) TextButton(onClick = { exportJob?.cancel() }) { Text("Cancel export") }
                }
                Page.MUSIC -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !musicBusy, onClick = {
                            musicBusy = true
                            scope.launch {
                                status = "Refreshing playlist…"
                                try {
                                    tracks = library.refresh()
                                    status = "Playlist updated."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    status = "Couldn't refresh. Your saved songs are still available."
                                } finally {
                                    musicBusy = false
                                }
                            }
                        }) { Text("Refresh playlist") }
                        OutlinedButton(enabled = !musicBusy, onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text("Import audio") }
                    }
                    tracks.forEach { track ->
                        val file = library.file(track)
                        HorizontalDivider()
                        Text(track.title, style = MaterialTheme.typography.titleMedium)
                        Text("${track.artist} · ${if (file == null) "Download to use" else "Available offline"}")
                        if (track.credit.isNotBlank()) Text(track.credit, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (file == null) {
                                Button(enabled = !musicBusy, onClick = {
                                    musicBusy = true
                                    scope.launch {
                                        status = "Downloading ${track.title}…"
                                        try {
                                            library.download(track)
                                            tracks = withContext(Dispatchers.IO) { library.load() }
                                            status = "${track.title} is available offline."
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            status = e.message ?: "Download interrupted. Try again."
                                        } finally {
                                            musicBusy = false
                                        }
                                    }
                                }) { Text("Download") }
                            } else {
                                Button(onClick = {
                                    stopMusic()
                                    change(draft.copy(songId = track.id, songStartSeconds = "0"))
                                    page = Page.CREATE
                                }) { Text("Use song") }
                                OutlinedButton(onClick = {
                                    if (musicPlaying == track.id) stopMusic()
                                    else {
                                        musicPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                        musicPlayer.prepare()
                                        musicPlayer.play()
                                        musicPlaying = track.id
                                    }
                                }) { Text(if (musicPlaying == track.id) "Stop" else "Play") }
                                if (track.id != "starter" && track.id != draft.songId) {
                                    TextButton(enabled = !musicBusy, onClick = {
                                        stopMusic()
                                        musicBusy = true
                                        scope.launch {
                                            try {
                                                library.remove(track)
                                                tracks = withContext(Dispatchers.IO) { library.load() }
                                                status = "Removed from this device."
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                status = "Couldn't remove the song."
                                            } finally {
                                                musicBusy = false
                                            }
                                        }
                                    }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
                Page.CLIPS -> {
                    if (clips.isEmpty()) Text("Your exported videos will appear here.")
                    selectedClip?.let { file ->
                        VideoPlayer(file, Modifier.fillMaxWidth().height(320.dp).pointerInput(file, clips) {
                            var distance = 0f
                            detectVerticalDragGestures(
                                onDragStart = { distance = 0f },
                                onDragEnd = {
                                    val index = clips.indexOf(file)
                                    if (distance < -64) clips.getOrNull(index + 1)?.let { selectedClip = it }
                                    if (distance > 64) clips.getOrNull(index - 1)?.let { selectedClip = it }
                                }
                            ) { change, drag ->
                                change.consume()
                                distance += drag
                            }
                        })
                        val credit = store.credit(file)
                        if (credit.isNotBlank()) Text(credit, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { share(file) }) { Text("Share") }
                            if (Build.VERSION.SDK_INT >= 29) OutlinedButton(enabled = !working, onClick = {
                                working = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.saveToGallery(file) }
                                        status = "Saved to the gallery."
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        status = e.message ?: "Couldn't save to the gallery."
                                    } finally {
                                        working = false
                                    }
                                }
                            }) { Text("Gallery") }
                            OutlinedButton(onClick = { sendFile = file; page = Page.BITCHAT }) { Text("Send nearby") }
                        }
                    }
                    clips.forEachIndexed { index, file ->
                        TextButton(onClick = { selectedClip = file }) {
                            Text("${if (selectedClip == file) "▶ " else ""}Clip ${clips.size - index}")
                        }
                    }
                }
                Page.BITCHAT -> BitchatScreen(store, sendFile, { sendFile = null })
            }
        }
    }
}

@Composable
private fun SecondsField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 10) onChange(it) },
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    Box(modifier) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
    }
}
