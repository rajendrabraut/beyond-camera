package com.beyond.camera.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyond.camera.BuildConfig
import com.beyond.camera.Ui
import com.beyond.camera.appSettings
import com.beyond.camera.hasPermission
import com.beyond.camera.toast

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val s = context.appSettings
    var confirmReset by remember { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.any { it }
        s.saveLocation = ok
        if (!ok) context.toast("Location permission is needed to tag photos")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            Group("Capture") {
                SwitchRow("Time stamp on photos", "Prints the date and time in the corner.", s.timestamp) { s.timestamp = it }
                SwitchRow(
                    "Scan codes in Photo mode",
                    "Spots QR codes and barcodes while you frame a photo (rear camera).",
                    s.scanQrInPhoto
                ) { s.scanQrInPhoto = it }
                ChoiceRow("Picture quality", PictureQuality.entries, s.pictureQuality, { it.label }) { s.pictureQuality = it }
                SwitchRow(
                    "Enhanced zoom",
                    "At 2x and above, merges several shots for a cleaner, sharper photo. Takes a moment longer.",
                    s.enhancedZoom
                ) { s.enhancedZoom = it }
            }

            Group("Controls") {
                ChoiceRow("Volume buttons", VolumeKeyAction.entries, s.volumeKeys, { it.label }) { s.volumeKeys = it }
                SwitchRow("Focus then shoot", "Tap to focus, tap the same spot again to take the photo.", s.focusAndShoot) {
                    s.focusAndShoot = it
                }
                ChoiceRow("Hold shutter button", HoldShutterAction.entries, s.holdShutter, { it.label }) { s.holdShutter = it }
            }

            Group("General") {
                SwitchRow("Save location", "Adds where a photo or video was taken. Off by default.", s.saveLocation) { on ->
                    if (!on) {
                        s.saveLocation = false
                    } else if (context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                        context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    ) {
                        s.saveLocation = true
                    } else {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
                SwitchRow("Shutter sound", null, s.shutterSound) { s.shutterSound = it }
                SwitchRow("Remember last mode", "Opens in the mode you used last instead of Photo.", s.savePreviousMode) {
                    s.savePreviousMode = it
                }
                ChoiceRow("Anti-flicker", AntiBanding.entries, s.antiBanding, { it.label }) { s.antiBanding = it }
            }

            Group("Privacy") {
                InfoRow(
                    "Works fully offline",
                    "This app has no internet permission. Photos, videos and scanned codes never leave your phone unless you share them."
                )
                InfoRow(
                    "Scanned links",
                    "Links are never opened automatically. Risky-looking links show a warning first."
                )
            }

            Group("About") {
                InfoRow("Beyond Camera", "Version ${BuildConfig.VERSION_NAME}")
                ActionRow("Restore default settings") { confirmReset = true }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore defaults?") },
            text = { Text("All camera settings go back to how they were when the app was installed.") },
            confirmButton = {
                TextButton(onClick = {
                    s.restoreDefaults()
                    confirmReset = false
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(
        title.uppercase(),
        color = Ui.AccentSoft,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 10.dp, bottom = 8.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ui.Card),
        content = content
    )
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, color = Color.White, fontSize = 16.sp)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Ui.Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ui.Accent, checkedThumbColor = Color.White)
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(label(selected), color = Ui.AccentSoft, fontSize = 15.sp)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    for (option in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = option == selected,
                                onClick = {
                                    onSelect(option)
                                    open = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Ui.Accent)
                            )
                            Text(label(option))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String) {
    RowText(title, subtitle, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Text(
        title,
        color = Ui.AccentSoft,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    )
}
