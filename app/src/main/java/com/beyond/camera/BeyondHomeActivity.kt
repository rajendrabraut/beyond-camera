package com.beyond.camera

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.beyond.liveclips.MainActivity as LiveClipsActivity

class BeyondHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xffa8ead2),
                surface = Color(0xff111615)
            )) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Beyond", style = MaterialTheme.typography.displaySmall)
                        OpenFeature("Camera", "Take photos, record video, and scan codes.") {
                            startActivity(Intent(this@BeyondHomeActivity, MainActivity::class.java))
                        }
                        OpenFeature("Live Clips", "Record and edit short videos with music.") {
                            openClips(false)
                        }
                        OpenFeature("Bitchat", "Share messages and files over Bluetooth.") {
                            openClips(true)
                        }
                    }
                }
            }
        }
    }

    private fun openClips(sharing: Boolean) {
        startActivity(Intent(this, LiveClipsActivity::class.java).apply {
            putExtra(LiveClipsActivity.OPEN_BITCHAT, sharing)
        })
    }
}

@Composable
private fun OpenFeature(title: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
