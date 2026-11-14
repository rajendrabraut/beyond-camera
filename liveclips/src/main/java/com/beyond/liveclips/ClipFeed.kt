package com.beyond.liveclips

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.io.File

private class ClipReactions(context: Context) {
    private val prefs = context.getSharedPreferences("clip_reactions", Context.MODE_PRIVATE)
    fun liked(file: File) = prefs.getBoolean("like:${file.name}", false)
    fun saved(file: File) = prefs.getBoolean("save:${file.name}", false)
    fun like(file: File, value: Boolean) = prefs.edit().putBoolean("like:${file.name}", value).apply()
    fun save(file: File, value: Boolean) = prefs.edit().putBoolean("save:${file.name}", value).apply()
    fun comments(file: File): List<String> = runCatching {
        val items = JSONArray(prefs.getString("comments:${file.name}", "[]"))
        (0 until items.length()).map { items.getString(it) }
    }.getOrDefault(emptyList())
    fun add(file: File, comment: String): List<String> {
        val notes = (comments(file) + comment).takeLast(100)
        prefs.edit().putString("comments:${file.name}", JSONArray(notes).toString()).apply()
        return notes
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun ClipFeed(
    clips: List<File>,
    selected: File?,
    store: ClipStore,
    status: String,
    onCreate: () -> Unit,
    onSelect: (File) -> Unit,
    onShare: (File) -> Unit,
    onNearby: (File) -> Unit,
    onGallery: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val reactions = remember { ClipReactions(context) }
    var savedOnly by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    val visible = remember(clips, savedOnly, revision) {
        if (savedOnly) clips.filter(reactions::saved) else clips
    }
    val pager = rememberPagerState(
        initialPage = visible.indexOf(selected).coerceAtLeast(0),
        pageCount = { visible.size }
    )
    val player = remember { ExoPlayer.Builder(context.applicationContext).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    var commentFor by remember { mutableStateOf<File?>(null) }
    var moreFor by remember { mutableStateOf<File?>(null) }
    var paused by remember { mutableStateOf(false) }
    val active = visible.getOrNull(pager.settledPage)

    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LaunchedEffect(active) {
        if (active == null) {
            player.clearMediaItems()
        } else {
            onSelect(active)
            paused = false
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(active)))
            player.prepare()
            player.play()
        }
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { visible[it].name }) { index ->
            val file = visible[index]
            Box(Modifier.fillMaxSize()) {
                if (file == active) {
                    AndroidView(
                        factory = { LayoutInflater.from(it).inflate(R.layout.clip_feed_player, null) as PlayerView },
                        update = { it.player = player },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(Modifier.fillMaxSize().clickable {
                        if (player.isPlaying) {
                            player.pause()
                            paused = true
                        } else {
                            player.play()
                            paused = false
                        }
                    })
                    if (paused) Text("▶", Modifier.align(Alignment.Center), color = Color.White, style = MaterialTheme.typography.displayMedium)
                }
                val liked = remember(file, revision) { reactions.liked(file) }
                val saved = remember(file, revision) { reactions.saved(file) }
                val comments = remember(file, revision) { reactions.comments(file).size }
                val credit = remember(file) { store.credit(file) }
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.77f))))) {
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 100.dp, bottom = 20.dp, top = 36.dp)) {
                        Text("My clip ${clips.size - clips.indexOf(file)}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        if (credit.isNotBlank()) Text(credit.substringBefore(" · "), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { reactions.like(file, !liked); revision++ }) {
                            Text(if (liked) "♥ Liked" else "♡ Like", color = if (liked) Color(0xffff647c) else Color.White)
                        }
                        TextButton(onClick = { commentFor = file }) {
                            Text(if (comments == 0) "Comment" else "$comments comments", color = Color.White)
                        }
                        TextButton(onClick = { reactions.save(file, !saved); revision++ }) {
                            Text(if (saved) "Saved" else "Save", color = Color.White)
                        }
                        TextButton(onClick = { moreFor = file }) { Text("Share", color = Color.White) }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (savedOnly) "No saved clips yet" else "Your exported clips appear here", color = Color.White)
                if (!savedOnly) Button(onClick = onCreate) { Text("Create a clip") }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { if (savedOnly) { pager.requestScrollToPage(0); savedOnly = false } }) {
                    Text(if (!savedOnly) "• My Clips" else "My Clips")
                }
                TextButton(onClick = { if (!savedOnly) { pager.requestScrollToPage(0); savedOnly = true } }) {
                    Text(if (savedOnly) "• Saved" else "Saved")
                }
            }
            if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 16.dp), color = Color.White)
        }
    }

    commentFor?.let { file ->
        var text by remember(file) { mutableStateOf("") }
        var notes by remember(file) { mutableStateOf(reactions.comments(file)) }
        AlertDialog(
            onDismissRequest = { commentFor = null },
            title = { Text("Comments") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notes.isEmpty()) Text("Comments are saved on this device.")
                    notes.forEach { Text(it) }
                    OutlinedTextField(value = text, onValueChange = { if (it.length <= 400) text = it }, label = { Text("Add a comment") })
                }
            },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
                notes = reactions.add(file, text.trim())
                revision++
                text = ""
            }) { Text("Post") } },
            dismissButton = { TextButton(onClick = { commentFor = null }) { Text("Close") } }
        )
    }
    moreFor?.let { file ->
        AlertDialog(
            onDismissRequest = { moreFor = null },
            title = { Text("Share clip") },
            text = {
                Column {
                    TextButton(onClick = { moreFor = null; onShare(file) }) { Text("Share via Android") }
                    TextButton(onClick = { moreFor = null; onNearby(file) }) { Text("Send nearby") }
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        TextButton(onClick = { moreFor = null; onGallery(file) }) { Text("Save to gallery") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moreFor = null }) { Text("Close") } }
        )
    }
}
