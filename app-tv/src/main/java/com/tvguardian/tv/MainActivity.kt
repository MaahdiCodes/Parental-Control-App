package com.tvguardian.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.tvguardian.core.PairingCodes
import kotlinx.coroutines.delay

/** Phase 0 debug panel: shows what the agent can see and has buttons to try each control. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val agentState by Agent.state.collectAsState()
                when (val state = agentState) {
                    is Agent.State.Unpaired -> PairingScreen(state)
                    else -> DebugPanel(cloudStatus(state))
                }
            }
        }
    }
}

private fun cloudStatus(state: Agent.State): Pair<String, Color> = when (state) {
    is Agent.State.Paired -> "Paired as \"${state.deviceName ?: "…"}\" · reporting to the parent app" to OkColor
    is Agent.State.Error -> "Cloud error: ${state.message} (retrying)" to BadColor
    Agent.State.Starting, is Agent.State.Unpaired -> "Connecting to the cloud…" to MutedColor
}

@Composable
private fun PairingScreen(state: Agent.State.Unpaired) {
    val qr = remember(state.code) { qrCodeBitmap(PairingCodes.qrPayload(state.code), sizePx = 512) }
    var secondsLeft by remember(state) { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        while (true) {
            secondsLeft = ((state.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            delay(1_000)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(horizontal = 48.dp, vertical = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Image(
            bitmap = qr,
            contentDescription = "Pairing QR code",
            modifier = Modifier
                .size(300.dp)
                .background(Color.White)
                .padding(8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Pair this TV with your phone", size = 30.sp, weight = FontWeight.Bold)
            Label("1. Open TV Guardian on your phone", size = 20.sp)
            Label("2. Tap \"Add TV\" and scan this QR code", size = 20.sp)
            Spacer(Modifier.height(12.dp))
            Label("Or type this code:", size = 18.sp, color = MutedColor)
            Label(PairingCodes.display(state.code), size = 56.sp, weight = FontWeight.Bold)
            Label("New code in ${secondsLeft / 60}:${"%02d".format(secondsLeft % 60)}", size = 16.sp, color = MutedColor)
        }
    }
}

private val TextColor = Color(0xFFE6E8EB)
private val MutedColor = Color(0xFF9AA3AD)
private val OkColor = Color(0xFF4ADE80)
private val BadColor = Color(0xFFF87171)

private data class PermissionStatus(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val notificationAccess: Boolean,
    val notificationConnected: Boolean,
) {
    companion object {
        fun check(context: Context): PermissionStatus {
            val component = ComponentName(context, GuardAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return PermissionStatus(
                accessibilityEnabled = enabledServices.contains(component.flattenToString()) ||
                    enabledServices.contains(component.flattenToShortString()),
                accessibilityConnected = GuardAccessibilityService.instance != null,
                notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName),
                notificationConnected = MediaWatcherService.instance != null,
            )
        }
    }
}

@Composable
private fun DebugPanel(cloudStatus: Pair<String, Color>) {
    val context = LocalContext.current
    val foreground by SpikeState.foregroundApp.collectAsState()
    val nowPlaying by SpikeState.nowPlaying.collectAsState()
    val log by SpikeState.log.collectAsState()
    var status by remember { mutableStateOf(PermissionStatus.check(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            status = PermissionStatus.check(context)
            delay(2_000)
        }
    }

    // 48dp/27dp padding keeps content inside the TV's overscan-safe area.
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        Label("TV Guardian", size = 22.sp, weight = FontWeight.Bold)
        Label(cloudStatus.first, color = cloudStatus.second)
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f)) {
                Label("Permissions", weight = FontWeight.Bold)
                StatusLine("Accessibility", status.accessibilityEnabled, status.accessibilityConnected)
                StatusLine("Notification access", status.notificationAccess, status.notificationConnected)
                Label("Foreground app: ${foreground ?: "-"}", color = MutedColor)
            }
            Column(Modifier.weight(1f)) {
                Label("Now playing", weight = FontWeight.Bold)
                val np = nowPlaying
                if (np == null) {
                    Label("Nothing detected", color = MutedColor)
                } else {
                    Label("${np.title ?: "<no title>"}  [${np.state}]")
                    Label("${np.subtitle ?: "<no subtitle>"} · ${np.packageName}", color = MutedColor)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { withAccessibility { it.runAutomaticTest() } }) { Text("▶ Run automatic test") }
            Button(onClick = { withAccessibility { it.dumpScreenTextLater(10_000) } }) { Text("Read screen text in 10s") }
            Button(onClick = { MediaControl.openYouTube(context, MediaControl.TEST_VIDEO_URL) }) { Text("Open test video") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { MediaControl.pause(context) }) { Text("Pause") }
            Button(onClick = { MediaControl.play(context) }) { Text("Play") }
            Button(onClick = { withAccessibility { it.showLockOverlay("Locked (test): 10 seconds", 10_000) } }) {
                Text("Lock 10s")
            }
            Button(onClick = { SpikeState.clearLog() }) { Text("Clear log") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Accessibility settings") }
            Button(onClick = { openSettings(context, "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }) {
                Text("Notification access settings")
            }
        }

        Spacer(Modifier.height(12.dp))
        Label("Log (newest first; full log: tools/pull-log.ps1)", weight = FontWeight.Bold)
        log.asReversed().take(7).forEach { Label(it, size = 13.sp, color = MutedColor) }
    }
}

@Composable
private fun StatusLine(name: String, enabled: Boolean, connected: Boolean) {
    val (text, color) = when {
        enabled && connected -> "ON" to OkColor
        enabled -> "enabled, not connected yet" to BadColor
        else -> "OFF: run tools/setup-tv.ps1" to BadColor
    }
    Row {
        Label("$name: ")
        Label(text, color = color, weight = FontWeight.Bold)
    }
}

@Composable
private fun Label(
    text: String,
    size: TextUnit = 16.sp,
    color: Color = TextColor,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(text, color = color, fontSize = size, fontWeight = weight)
}

private fun withAccessibility(action: (GuardAccessibilityService) -> Unit) {
    val service = GuardAccessibilityService.instance
    if (service == null) {
        SpikeState.log("Accessibility service is not running: run tools/setup-tv.ps1 first")
    } else {
        action(service)
    }
}

private fun openSettings(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { SpikeState.log("This TV has no '$action' screen: use tools/setup-tv.ps1 instead") }
}
