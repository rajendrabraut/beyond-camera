package com.beyond.camera.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyond.camera.MediaStoreSaver
import com.beyond.camera.Ui
import com.beyond.camera.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Scanned code", text))
    context.toast("Copied")
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}

private fun start(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }.onFailure { context.toast("No app can open this") }
}

private fun openLink(context: Context, url: String) {
    // Re-checked here so nothing but http/https ever leaves through this path.
    if (!QrDecoder.checkLink(url).allowed) {
        context.toast("This link can't be opened")
        return
    }
    start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addCategory(Intent.CATEGORY_BROWSABLE))
}

/** Bottom card with what was scanned. Nothing opens until the user taps a button. */
@Composable
fun QrResultSheet(result: QrResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val content = remember(result) { QrDecoder.classify(result) }
    var confirmUrl by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Ui.Card)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            val title = when (content) {
                is QrContent.Url -> "Web link"
                is QrContent.Wifi -> "Wi-Fi network"
                is QrContent.Phone -> "Phone number"
                is QrContent.Email -> "Email address"
                is QrContent.Product -> "Product barcode"
                is QrContent.Text -> "Text"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(result.format, color = Ui.Dim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))

            when (content) {
                is QrContent.Url -> {
                    val check = remember(content.url) { QrDecoder.checkLink(content.url) }
                    if (check.host.isNotEmpty()) {
                        Text(check.host, color = Ui.AccentSoft, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                    }
                    ScannedText(content.url)
                    for (warning in check.warnings) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFC857), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(warning, color = Color(0xFFFFE2A6), fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Actions {
                        if (check.allowed) {
                            Primary("Open") {
                                if (check.warnings.isEmpty()) openLink(context, content.url) else confirmUrl = content.url
                            }
                        }
                        Secondary("Copy") { copyText(context, content.url) }
                        Secondary("Share") { shareText(context, content.url) }
                    }
                }

                is QrContent.Wifi -> {
                    Field("Name", content.ssid)
                    Field("Security", content.security)
                    if (content.password.isNotEmpty()) Field("Password", content.password)
                    Spacer(Modifier.height(18.dp))
                    Actions {
                        if (content.password.isNotEmpty()) Primary("Copy password") { copyText(context, content.password) }
                        Secondary("Wi-Fi settings") { start(context, Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    }
                }

                is QrContent.Phone -> {
                    ScannedText(content.number)
                    Spacer(Modifier.height(18.dp))
                    Actions {
                        Primary("Dial") { start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", content.number, null))) }
                        Secondary("Copy") { copyText(context, content.number) }
                    }
                }

                is QrContent.Email -> {
                    ScannedText(content.address)
                    Spacer(Modifier.height(18.dp))
                    Actions {
                        Primary("Write email") {
                            start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", content.address, null)))
                        }
                        Secondary("Copy") { copyText(context, content.address) }
                    }
                }

                is QrContent.Product -> {
                    ScannedText(content.code)
                    Spacer(Modifier.height(18.dp))
                    Actions {
                        Primary("Copy") { copyText(context, content.code) }
                        Secondary("Share") { shareText(context, content.code) }
                    }
                }

                is QrContent.Text -> {
                    ScannedText(content.text)
                    Spacer(Modifier.height(18.dp))
                    Actions {
                        Primary("Copy") { copyText(context, content.text) }
                        Secondary("Share") { shareText(context, content.text) }
                    }
                }
            }
        }
    }

    confirmUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmUrl = null },
            title = { Text("Open this link?") },
            text = { Text("This link has warnings. Only open it if you trust where the code came from.\n\n$url") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUrl = null
                    openLink(context, url)
                }) { Text("Open anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmUrl = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ScannedText(text: String) {
    SelectionContainer {
        Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Ui.Dim, fontSize = 14.sp, modifier = Modifier.width(90.dp))
        SelectionContainer { Text(value, color = Color.White, fontSize = 15.sp) }
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Ui.Accent, contentColor = Color.White)
    ) { Text(label) }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) { Text(label) }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun QrHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(QrHistory.load(context)) }
    var selected by remember { mutableStateOf<QrResult?>(null) }
    val dateFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ui.Surface)
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Scan history", onBack) {
                if (items.isNotEmpty()) {
                    TextButton(onClick = {
                        QrHistory.clear(context)
                        items = emptyList()
                    }) { Text("Clear all", color = Ui.AccentSoft) }
                }
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Codes you scan will appear here.", color = Ui.Dim)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    items(items) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Ui.Card)
                                .clickable { selected = item }
                                .padding(14.dp)
                        ) {
                            Text(item.text, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${item.format}  ·  ${dateFormat.format(Date(item.time))}",
                                color = Ui.Dim,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
        selected?.let { QrResultSheet(it) { selected = null } }
    }
}

@Composable
fun QrCreateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.Surface)
            .navigationBarsPadding()
    ) {
        ScreenHeader("Create QR code", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 1200) text = it },
                label = { Text("Text or link") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val made = if (text.isBlank()) null else QrDecoder.encode(text.trim())
                    bitmap = made
                    if (made == null) context.toast("Type something shorter to make a code")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Ui.Accent, contentColor = Color.White)
            ) { Text("Make code") }
            Spacer(Modifier.height(20.dp))
            bitmap?.let { qr ->
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR code",
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Primary("Save to gallery") {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) { saveQr(context, qr) }
                            context.toast(if (uri != null) "Saved" else "Couldn't save")
                        }
                    }
                    Secondary("Share") {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) { saveQr(context, qr) }
                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_SEND)
                                    .setType("image/png")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
                            } else {
                                context.toast("Couldn't share")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun saveQr(context: Context, bitmap: Bitmap): Uri? {
    val file = MediaStoreSaver.tempFile(context, "png")
    return try {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaStoreSaver.publish(context, file, false, "image/png")
    } catch (e: Exception) {
        file.delete()
        null
    }
}
