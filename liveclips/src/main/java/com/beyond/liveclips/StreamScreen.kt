package com.beyond.liveclips

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
internal fun StreamScreen(clip: File?) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("39437") }
    var linkInput by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<PhoneStream?>(null) }
    var playing by remember { mutableStateOf<StreamLink?>(null) }
    var working by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { active = false; stream?.close() } }
    DisposableEffect(view, stream != null) {
        val previous = view.keepScreenOn
        if (stream != null) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { playing = null }

    if (notice.isNotBlank()) Text(notice)
    if (clip != null) {
        Text("Host this clip")
        Text("Enter an address viewers can reach. Use a shared VPN address or a public address with TCP forwarding to this phone. Leave Beyond open while viewers watch.")
        OutlinedTextField(host, { if (it.length <= 253) host = it }, label = { Text("Reachable address") }, singleLine = true)
        OutlinedTextField(port, { if (it.length <= 5 && it.all(Char::isDigit)) port = it }, label = { Text("TCP port") }, singleLine = true)
        val running = stream
        if (running == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(enabled = !working, onClick = {
                    scope.launch {
                        val address = withContext(Dispatchers.IO) {
                            runCatching {
                                NetworkInterface.getNetworkInterfaces().toList()
                                    .filter { it.isUp && !it.isLoopback }
                                    .flatMap { it.inetAddresses.toList() }
                                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
                            }.getOrNull()
                        }
                        if (address != null) host = address else notice = "No local IPv4 address found."
                    }
                }) { Text("Use local address") }
                Button(enabled = !working && host.isNotBlank(), onClick = {
                    working = true
                    scope.launch {
                        var started: PhoneStream? = null
                        try {
                            val address = host
                            val number = port.toIntOrNull() ?: error("Enter a TCP port.")
                            withContext(Dispatchers.IO) { started = PhoneStream.start(clip, address, number) }
                            if (active) { stream = started; started = null; notice = "Streaming is ready." }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            notice = e.message ?: "Could not start streaming."
                        } finally {
                            started?.close()
                            working = false
                        }
                    }
                }) { Text("Start hosting") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, running.link.uri.toString())
                    context.startActivity(Intent.createChooser(share, "Share private stream link"))
                }) { Text("Share link") }
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Beyond stream", running.link.uri.toString()).apply {
                            description.extras = PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                        })
                    notice = "Stream link copied."
                }) { Text("Copy link") }
            }
            OutlinedButton(onClick = { running.close(); stream = null; notice = "Hosting stopped." }) { Text("Stop hosting") }
        }
    }
    Text("Watch a stream")
    OutlinedTextField(linkInput, { if (it.length <= 512) linkInput = it }, label = { Text("Beyond stream link") },
        modifier = Modifier.fillMaxWidth())
    Button(enabled = linkInput.isNotBlank(), onClick = {
        runCatching { StreamLink.parse(linkInput) }
            .onSuccess { playing = it; notice = "" }
            .onFailure { notice = "Enter a valid Beyond stream link." }
    }) { Text("Watch") }
    playing?.let { StreamPlayer(it) { notice = it } }
}

@OptIn(UnstableApi::class)
@Composable
private fun StreamPlayer(link: StreamLink, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(link) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory { PhoneStreamDataSource(link) }
                .createMediaSource(MediaItem.fromUri(link.uri)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError("Could not play this clip. Check the link and confirm the host phone is reachable.")
            }
        }
        player.addListener(listener)
        onDispose { player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player }, modifier = Modifier.fillMaxWidth().height(300.dp))
}
