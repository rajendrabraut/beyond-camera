package com.beyond.camera.viewer

import android.content.Intent
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beyond.camera.engine.ProcessingQueue
import coil.size.Size
import com.beyond.camera.MediaItem
import com.beyond.camera.MediaStoreSaver
import com.beyond.camera.Ui
import com.beyond.camera.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app viewer. Opens on the newest capture; swipe left for older ones,
 * swipe right from the newest to go back to the camera.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<MediaItem?>(null) }
    var info by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { MediaStoreSaver.query(context) }
    }

    val list = items
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (list == null) return@Box
        if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No photos or videos yet", color = Ui.Dim)
                TextButton(onClick = onBack) { Text("Back to camera", color = Ui.AccentSoft) }
            }
            return@Box
        }

        // Page 0 is an empty "back to camera" page; captures start at page 1.
        val pagerState = rememberPagerState(initialPage = 1) { list.size + 1 }
        LaunchedEffect(pagerState.settledPage) {
            if (pagerState.settledPage == 0) onBack()
        }
        val current = list.getOrNull(pagerState.currentPage - 1)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = list.getOrNull(page - 1)
            if (item == null) {
                Box(Modifier.fillMaxSize())
            } else if (item.isVideo) {
                VideoPage(item, active = pagerState.settledPage == page)
            } else {
                PhotoPage(item, active = pagerState.settledPage == page) { chrome = !chrome }
            }
        }

        if (chrome && current != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to camera", tint = Color.White) }
                Text(
                    "${pagerState.currentPage} of ${list.size}",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType(if (current.isVideo) "video/*" else "image/*")
                        .putExtra(Intent.EXTRA_STREAM, current.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
                }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                IconButton(onClick = { info = current }) { Icon(Icons.Filled.Info, "Details", tint = Color.White) }
                IconButton(onClick = { confirmDelete = current }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (item.isVideo) "Delete this video?" else "Delete this photo?") },
            text = { Text("It will be removed from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (MediaStoreSaver.delete(context, item)) {
                        val remaining = items.orEmpty().filter { it.uri != item.uri }
                        items = remaining
                        if (remaining.isEmpty()) onBack()
                    } else {
                        context.toast("Couldn't delete this item")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    info?.let { item ->
        val date = remember(item) {
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.dateAdded * 1000))
        }
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text("Details") },
            text = {
                Text(
                    buildString {
                        appendLine(item.name)
                        appendLine(date)
                        if (item.width > 0 && item.height > 0) {
                            append("${item.width} x ${item.height}")
                            if (!item.isVideo) append("  (%.1f MP)".format(item.width * item.height / 1_000_000f))
                            appendLine()
                        }
                        append("%.2f MB".format(item.size / 1_048_576f))
                    }
                )
            },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun PhotoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Full sensor resolution is loaded once the user zooms in, so every pixel stays crisp.
    var fullRes by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val revision = ProcessingQueue.revision(item.uri)
    val request = remember(item.uri, fullRes, revision) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .memoryCacheKey("${item.uri}:viewer:$revision:$fullRes")
            .apply { if (fullRes) size(Size.ORIGINAL).allowHardware(false) }
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                boxSize = this.size
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale > 1.01f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            scale = newScale
                            offset = if (newScale > 1.01f) clampOffset(offset + event.calculatePan(), newScale) else Offset.Zero
                            if (newScale > 1.3f) fullRes = true
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                            fullRes = true
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = "Photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
        if (scale > 1.01f) {
            Text(
                "%.1fx".format(scale),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 60.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean) {
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) playing = false }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setVideoURI(item.uri)
                        setOnCompletionListener { playing = false }
                        setOnErrorListener { _, _, _ ->
                            playing = false
                            true
                        }
                        start()
                    }
                },
                onRelease = { it.stopPlayback() }
            )
        } else {
            AsyncImage(
                model = item.uri,
                contentDescription = "Video",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { playing = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
    }
}
