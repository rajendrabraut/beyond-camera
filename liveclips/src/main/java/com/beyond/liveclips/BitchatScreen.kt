package com.beyond.liveclips

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.wifi.p2p.WifiP2pDevice
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@SuppressLint("MissingPermission")
@Composable
internal fun BitchatScreen(store: ClipStore, clip: File?, onClipOffered: () -> Unit, creators: ClipCreators) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var messages by remember { mutableStateOf(emptyList<String>()) }
    var message by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf<FileOffer?>(null) }
    var file by remember(clip) { mutableStateOf(clip) }
    var received by remember { mutableStateOf(receivedFiles(store.received)) }
    var sending by remember { mutableStateOf(false) }
    var verificationCode by remember { mutableStateOf<String?>(null) }
    var wifiPeers by remember { mutableStateOf(emptyList<WifiP2pDevice>()) }
    val link = remember {
        BitchatLink(context.applicationContext, scope, store.received) { event ->
            when (event) {
                is BitchatLink.Event.Status -> {
                    status = event.message
                    connected = event.connected
                }
                is BitchatLink.Event.Message -> {
                    val author = if (event.incoming) "Nearby device" else "You"
                    messages = (messages + "$author: ${event.text}").takeLast(30)
                }
                is BitchatLink.Event.Offer -> offer = event.offer
                is BitchatLink.Event.Verify -> verificationCode = event.code
                is BitchatLink.Event.Received -> {
                    received = receivedFiles(store.received)
                    status = "Received ${event.file.name}."
                }
            }
        }
    }
    val wifi = remember {
        lateinit var direct: WifiDirect
        direct = WifiDirect(context, { wifiPeers = it }, { info ->
            link.connectWifi("the nearby device") { direct.open(info) }
        }, { status = it })
        direct
    }
    DisposableEffect(link, wifi) {
        onDispose {
            link.close()
            wifi.close()
        }
    }

    fun refresh() {
        paired = runCatching { link.paired() }.getOrDefault(emptyList())
        status = if (link.adapter?.isEnabled != true) "Turn Bluetooth on to connect." else ""
    }

    fun permitted() = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) refresh() else status = "Nearby devices permission is needed to share over Bluetooth."
    }
    val bluetoothSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (permitted()) refresh()
    }
    val wifiPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
    var wifiTarget by remember { mutableStateOf<WifiP2pDevice?>(null) }
    val requestWifi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[wifiPermission] == true
        if (granted) {
            val target = wifiTarget
            if (target == null) wifi.discover() else wifi.connect(target)
        } else status = "Allow nearby access to use Wi-Fi Direct."
        wifiTarget = null
    }
    fun openWifi(device: WifiP2pDevice?) {
        status = if (device == null) "Looking for devices. Open Wi-Fi Direct on both phones." else "Connecting over Wi-Fi Direct…"
        if (!wifi.supported) {
            status = "Wi-Fi Direct is unavailable on this device."
        } else if (ContextCompat.checkSelfPermission(context, wifiPermission) != PackageManager.PERMISSION_GRANTED) {
            wifiTarget = device
            requestWifi.launch(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(wifiPermission)
                else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, wifiPermission)
            )
        } else if (device == null) wifi.discover() else wifi.connect(device)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !sending) {
            sending = true
            scope.launch {
                val temporary = File(context.cacheDir, "send-${UUID.randomUUID()}")
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(
                            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                        )?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?.replace(Regex("[^a-zA-Z0-9._ -]"), "_")?.take(100) ?: "Shared file"
                        check(temporary.mkdir())
                        File(temporary, name.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file").also {
                            copyFromUri(context, uri, it, Long.MAX_VALUE)
                        }
                    }
                    file = imported
                    status = "Ready to offer ${imported.name}."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    temporary.deleteRecursively()
                    status = e.message ?: "Couldn't open the file."
                } finally {
                    sending = false
                }
            }
        }
    }

    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = e.message ?: "Couldn't complete the Bluetooth operation."
            }
        }
    }

    Text("Use Bluetooth for files under 32 MB, or Wi-Fi Direct for any file size.")
    if (status.isNotBlank()) Text(status)
    verificationCode?.let { code ->
        Text("Confirm this code matches the other phone: $code")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { link.verifyPeer(true) }) { Text("Code matches") }
            OutlinedButton(onClick = { link.verifyPeer(false) }) { Text("Decline") }
        }
    }
    if (link.adapter != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            if (permitted()) bluetoothSettings.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            else request.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }) { Text("Bluetooth settings") }
        OutlinedButton(onClick = {
            if (permitted()) refresh() else request.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }) { Text("Refresh") }
    }
    if (link.adapter != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(enabled = !connected, onClick = {
            if (!permitted()) request.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else if (link.adapter.isEnabled) link.listen()
            else bluetoothSettings.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }) { Text("Listen") }
    }
    if (!connected) paired.forEach { device ->
        TextButton(onClick = { action { link.connect(device) } }) {
            Text("Connect to ${runCatching { device.name }.getOrNull() ?: device.address}")
        }
    }
    if (wifi.supported) {
        OutlinedButton(onClick = { openWifi(null) }) { Text("Find Wi-Fi Direct devices") }
        wifiPeers.forEach { device ->
            TextButton(onClick = { openWifi(device) }) {
                Text("Wi-Fi Direct · ${device.deviceName.ifBlank { device.deviceAddress }}")
            }
        }
    }
    if (link.adapter != null || wifi.supported) {
        TextButton(onClick = { link.close(); wifi.close(); wifiPeers = emptyList() }) { Text("Disconnect") }
    } else Text("Nearby sharing is unavailable on this device.")
    if (connected) {
        messages.forEach { Text(it) }
        OutlinedTextField(
            value = message,
            onValueChange = { if (it.length <= 2_000) message = it },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = message.isNotBlank(), onClick = {
            val text = message.trim()
            message = ""
            action { link.message(text) }
        }) { Text("Send message") }
    }
    if (link.adapter != null || wifi.supported) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = !sending, onClick = { pick.launch(arrayOf("*/*")) }) { Text("Choose file") }
            Button(enabled = file != null && !sending && (connected || wifi.supported), onClick = {
                val chosen = file ?: return@Button
                if ((!connected || chosen.length() >= BLUETOOTH_LIMIT) && !link.wifiConnected) {
                    openWifi(null)
                    return@Button
                }
                sending = true
                action {
                    try {
                        val credit = store.credit(chosen)
                        val sharedCredit = if (chosen.parentFile == store.clips || chosen.parentFile == store.received) {
                            creators.sharingCredit(chosen, credit)
                        } else credit
                        link.offer(chosen, sharedCredit)
                        file = null
                        onClipOffered()
                    } finally {
                        sending = false
                    }
                }
            }) {
                val useWifi = !link.wifiConnected && (!connected || (file?.length() ?: 0) >= BLUETOOTH_LIMIT)
                Text(if (useWifi) "Connect by Wi-Fi" else "Offer file")
            }
        }
    }
    file?.let {
        Text("Selected file: ${it.name}")
        if (it.length() >= BLUETOOTH_LIMIT) Text("This file uses Wi-Fi Direct.")
    }
    offer?.let { incoming ->
        HorizontalDivider()
        Text("Incoming file: ${incoming.name} · ${incoming.size / 1024} KB")
        val credit = ClipCreators.visibleCredit(incoming.credit)
        if (credit.isNotBlank()) Text(credit)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { action { link.decide(true) } }) { Text("Accept") }
            OutlinedButton(onClick = { action { link.decide(false) } }) { Text("Decline") }
        }
    }
    if (received.isNotEmpty()) {
        HorizontalDivider()
        Text("Received files")
        received.forEach { saved ->
            TextButton(onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", saved)
                val credit = store.credit(saved)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (credit.isNotBlank()) putExtra(Intent.EXTRA_TEXT, credit)
                    clipData = ClipData.newUri(context.contentResolver, saved.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share file"))
            }) { Text(saved.name.substringAfter('-')) }
        }
    }
}

private fun receivedFiles(directory: File): List<File> = directory.listFiles { file ->
    file.isFile && !file.name.endsWith(".part") && !file.name.endsWith(".credit.txt")
}.orEmpty().sortedByDescending { it.lastModified() }
