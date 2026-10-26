package com.tvguardian.parent.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.tvguardian.core.Commands
import com.tvguardian.core.DeviceStatus
import com.tvguardian.core.YouTubeLinks
import com.tvguardian.parent.devices.DevicesRepository
import com.tvguardian.parent.devices.TvDevice
import com.tvguardian.parent.ui.ParentTheme
import com.tvguardian.parent.watch.WatchRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Appears in the share sheet: in the YouTube app, Share → TV Guardian plays that video on the TV.
 * With one TV it plays straight away; with several it asks which one.
 */
class ShareToTvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = YouTubeLinks.findVideoId(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        setContent {
            ParentTheme {
                ShareScreen(videoId, onDone = ::finish)
            }
        }
    }
}

@Composable
private fun ShareScreen(videoId: String?, onDone: () -> Unit) {
    val familyId = FirebaseAuth.getInstance().currentUser?.uid
    val devices by remember(familyId) {
        familyId?.let { DevicesRepository.devices(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }

    fun playOn(device: TvDevice) {
        if (familyId == null || videoId == null || status != null) return
        scope.launch {
            status = "Sending to ${device.name}…"
            status = WatchRepository.sendCommand(
                familyId, device.id, Commands.PLAY_URL, mapOf(Commands.ARG_URL to YouTubeLinks.watchUrl(videoId))
            )
            finished = true
            delay(2_500)
            onDone()
        }
    }

    // Only one TV: no need to ask.
    val tvs = devices?.getOrNull()
    LaunchedEffect(tvs?.size) {
        if (tvs?.size == 1) playOn(tvs.single())
    }

    Surface {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Play on TV", style = MaterialTheme.typography.titleLarge)
            val now = System.currentTimeMillis()
            when {
                videoId == null -> Text("That doesn't look like a YouTube video link.")
                familyId == null -> Text("Open TV Guardian and sign in first.")
                status != null -> {
                    if (!finished) CircularProgressIndicator()
                    Text(status.orEmpty())
                }
                devices == null -> CircularProgressIndicator()
                tvs.isNullOrEmpty() -> Text("No TVs are paired yet. Open TV Guardian and tap \"Add TV\".")
                else -> {
                    Text("Which TV?")
                    tvs.forEach { tv ->
                        val online = DeviceStatus.isOnline(tv.lastSeenMillis, now)
                        Button(onClick = { playOn(tv) }, enabled = online, modifier = Modifier.fillMaxWidth()) {
                            Text(if (online) tv.name else "${tv.name} (offline)")
                        }
                    }
                }
            }
            TextButton(onClick = onDone) { Text(if (finished) "Close" else "Cancel") }
        }
    }
}
