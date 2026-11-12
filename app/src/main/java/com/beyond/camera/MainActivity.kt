package com.beyond.camera

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyond.camera.qr.QrCreateScreen
import com.beyond.camera.qr.QrHistoryScreen
import com.beyond.camera.settings.SettingsScreen
import com.beyond.camera.settings.VolumeKeyAction
import com.beyond.camera.viewer.ViewerScreen

private enum class Screen { CAMERA, SETTINGS, VIEWER, QR_HISTORY, QR_CREATE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MediaStoreSaver.clearTemp(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ui.Accent)) {
                PermissionGate {
                    var screen by remember { mutableStateOf(Screen.CAMERA) }
                    BackHandler(enabled = screen != Screen.CAMERA) { screen = Screen.CAMERA }
                    when (screen) {
                        Screen.CAMERA -> CameraScreen(
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenViewer = { screen = Screen.VIEWER },
                            onOpenQrHistory = { screen = Screen.QR_HISTORY },
                            onOpenQrCreate = { screen = Screen.QR_CREATE }
                        )

                        Screen.SETTINGS -> SettingsScreen { screen = Screen.CAMERA }
                        Screen.VIEWER -> ViewerScreen { screen = Screen.CAMERA }
                        Screen.QR_HISTORY -> QrHistoryScreen { screen = Screen.CAMERA }
                        Screen.QR_CREATE -> QrCreateScreen { screen = Screen.CAMERA }
                    }
                }
            }
        }
    }

    // Volume buttons act as camera controls while the camera screen is showing.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && VolumeKeyEvents.cameraActive && appSettings.volumeKeys != VolumeKeyAction.VOLUME) {
            if (event?.repeatCount == 0 || appSettings.volumeKeys == VolumeKeyAction.ZOOM) {
                VolumeKeyEvents.events.tryEmit(keyCode)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && VolumeKeyEvents.cameraActive && appSettings.volumeKeys != VolumeKeyAction.VOLUME) return true
        return super.onKeyUp(keyCode, event)
    }
}

/** Asks for camera + microphone (and legacy storage on Android 9 and older), then shows [content]. */
@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requested = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    var cameraGranted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedOnce = true
        cameraGranted = context.hasPermission(Manifest.permission.CAMERA)
    }

    LaunchedEffect(Unit) {
        if (requested.any { !context.hasPermission(it) }) launcher.launch(requested)
    }

    // Re-check when returning from the system Settings screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraGranted = context.hasPermission(Manifest.permission.CAMERA)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (cameraGranted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Beyond Camera needs access to your camera to take photos, record videos and scan codes.",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { launcher.launch(requested) },
                colors = ButtonDefaults.buttonColors(containerColor = Ui.Accent, contentColor = Color.White)
            ) {
                Text("Allow camera")
            }
            if (askedOnce) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }) {
                    Text("Open settings")
                }
            }
        }
    }
}
