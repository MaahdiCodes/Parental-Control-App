package com.tvguardian.parent.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvguardian.core.Commands
import com.tvguardian.core.DeviceStatus
import com.tvguardian.core.YouTubeLinks
import com.tvguardian.parent.devices.TvDevice
import kotlinx.coroutines.launch

private enum class TextPrompt { MESSAGE, VIDEO, LOCK, VOICE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    familyId: String,
    device: TvDevice,
    now: Long,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val nowState by remember(device.id) { WatchRepository.nowState(familyId, device.id) }.collectAsState(initial = null)
    val history by remember(device.id) { WatchRepository.history(familyId, device.id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var sending by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<TextPrompt?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val online = DeviceStatus.isOnline(device.lastSeenMillis, now)
    val locked = if (device.isYouTubeLink) device.youtubeBlocked else nowState?.locked == true

    if (showHistory) {
        BackHandler { showHistory = false }
        HistoryScreen(familyId, device, onBack = { showHistory = false })
        return
    }

    val send: (String, Map<String, String>) -> Unit = { type, args ->
        scope.launch {
            sending = type
            val message = CommandRouter.send(context, familyId, device, type, args)
            sending = null
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = onManage) { Text("Rename / remove") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val lastSeen = DeviceStatus.describeLastSeen(device.lastSeenMillis, now)
                Text(
                    when {
                        device.isYouTubeLink && online -> "● YouTube is open on this TV"
                        device.isYouTubeLink -> "● YouTube closed · last open $lastSeen"
                        online -> "● Online"
                        else -> "● Offline · last seen $lastSeen"
                    },
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when {
                        device.isYouTubeLink ->
                            "Linked through YouTube: only YouTube can be seen and controlled on this TV. " +
                                "Controls work while YouTube is open on it."
                        !online -> "Commands only work while the TV is on."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { NowWatchingCard(nowState, isYouTubeLink = device.isYouTubeLink) }
            item {
                if (device.isYouTubeLink) {
                    YouTubeLinkControls(
                        blocked = locked,
                        busy = sending != null,
                        onCommand = { send(it, emptyMap()) },
                        onChangeVideo = { prompt = TextPrompt.VIDEO },
                    )
                } else {
                    Controls(
                        locked = locked,
                        busy = sending != null,
                        onCommand = { send(it, emptyMap()) },
                        onPrompt = { prompt = it },
                    )
                }
            }
            item { HistorySummaryCard(history, onClick = { showHistory = true }) }
        }
    }

    when (prompt) {
        TextPrompt.MESSAGE -> TextPromptDialog(
            title = "Send a message to the TV",
            label = "Message",
            initial = "Dinner time! TV off in 5 minutes.",
            confirm = "Send",
            validate = { it.isNotBlank() },
            checkboxLabel = "🗣 Also read it aloud on the TV",
            onDismiss = { prompt = null },
            onConfirm = { text, speak ->
                send(Commands.MESSAGE, mapOf(Commands.ARG_TEXT to text.trim(), Commands.ARG_SPEAK to speak.toString()))
            },
        )
        TextPrompt.VOICE -> VoiceMessageDialog(
            onDismiss = { prompt = null },
            onSend = { audio -> send(Commands.VOICE, mapOf(Commands.ARG_AUDIO to audio)) },
        )
        TextPrompt.VIDEO -> TextPromptDialog(
            title = "Change the video on the TV",
            label = "YouTube link",
            initial = "",
            confirm = "Play",
            validate = { YouTubeLinks.videoId(it) != null },
            hint = "Tip: in the YouTube app tap Share → Copy link, then paste it here. " +
                "Or tap Share → Play on TV to skip this step.",
            onDismiss = { prompt = null },
            onConfirm = { text, _ ->
                send(Commands.PLAY_URL, mapOf(Commands.ARG_URL to YouTubeLinks.watchUrl(YouTubeLinks.videoId(text)!!)))
            },
        )
        TextPrompt.LOCK -> TextPromptDialog(
            title = "Lock the TV",
            label = "Message on the lock screen",
            initial = "Time's up! The TV is locked.",
            confirm = "Lock TV",
            validate = { true },
            hint = "The remote stops working until you unlock it from this app.",
            onDismiss = { prompt = null },
            onConfirm = { text, _ -> send(Commands.LOCK, mapOf(Commands.ARG_TEXT to text.trim())) },
        )
        null -> Unit
    }
}

@Composable
private fun NowWatchingCard(state: NowState?, isYouTubeLink: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Now watching", style = MaterialTheme.typography.labelLarge)
            val title = state?.title
            if (state == null) {
                Text(if (isYouTubeLink) "Nothing yet. Open YouTube on the TV." else "Waiting for the TV…")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                state.thumbnail?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(128.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.channel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (title != null) {
                        Text(
                            listOfNotNull(state.mediaApp, state.playbackState?.lowercase()).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.foregroundApp?.let {
                Text("On screen: $it", style = MaterialTheme.typography.bodySmall)
            }
            if (state.locked) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        if (isYouTubeLink) "🚫 YouTube is blocked on this TV" else "🔒 The TV is locked",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

/** For TVs linked through YouTube: only what YouTube's own remote control allows. */
@Composable
private fun YouTubeLinkControls(
    blocked: Boolean,
    busy: Boolean,
    onCommand: (String) -> Unit,
    onChangeVideo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocked) {
            Button(onClick = { onCommand(Commands.UNLOCK) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("✅ Unblock YouTube")
            }
        } else {
            Button(
                onClick = { onCommand(Commands.LOCK) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("🚫 Block YouTube (pause anything played)") }
        }
        ControlRow {
            ControlButton("⏸ Pause", busy) { onCommand(Commands.PAUSE) }
            ControlButton("▶ Play", busy) { onCommand(Commands.PLAY) }
        }
        ControlRow {
            ControlButton("🔉 Vol −", busy) { onCommand(Commands.VOLUME_DOWN) }
            ControlButton("🔇 Mute", busy) { onCommand(Commands.MUTE) }
            ControlButton("🔊 Vol +", busy) { onCommand(Commands.VOLUME_UP) }
        }
        ControlRow {
            ControlButton("▶ Change video", busy, onChangeVideo)
        }
    }
}

@Composable
private fun Controls(
    locked: Boolean,
    busy: Boolean,
    onCommand: (String) -> Unit,
    onPrompt: (TextPrompt) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (locked) {
            Button(onClick = { onCommand(Commands.UNLOCK) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("🔓 Unlock TV")
            }
        } else {
            Button(
                onClick = { onPrompt(TextPrompt.LOCK) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("🔒 Lock TV") }
        }
        ControlRow {
            ControlButton("⏸ Pause", busy) { onCommand(Commands.PAUSE) }
            ControlButton("▶ Play", busy) { onCommand(Commands.PLAY) }
            ControlButton("⏹ Stop", busy) { onCommand(Commands.STOP) }
        }
        ControlRow {
            ControlButton("⌂ Home", busy) { onCommand(Commands.HOME) }
            ControlButton("← Back", busy) { onCommand(Commands.BACK) }
            ControlButton("✕ Close app", busy) { onCommand(Commands.CLOSE_APP) }
        }
        ControlRow {
            ControlButton("🔉 Vol −", busy) { onCommand(Commands.VOLUME_DOWN) }
            ControlButton("🔇 Mute", busy) { onCommand(Commands.MUTE) }
            ControlButton("🔊 Vol +", busy) { onCommand(Commands.VOLUME_UP) }
        }
        ControlRow {
            ControlButton("💬 Message", busy) { onPrompt(TextPrompt.MESSAGE) }
            ControlButton("🎤 Voice", busy) { onPrompt(TextPrompt.VOICE) }
            ControlButton("▶ Change video", busy) { onPrompt(TextPrompt.VIDEO) }
        }
    }
}

@Composable
private fun ControlRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.ControlButton(label: String, busy: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Today's totals and the latest video; opens the full history. */
@Composable
private fun HistorySummaryCard(history: List<HistoryEntry>, onClick: () -> Unit) {
    val todayStart = dayStart(System.currentTimeMillis())
    val today = history.filter { (it.startedAtMillis ?: 0) >= todayStart }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📺 Watch history", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (today.isEmpty()) "Nothing watched today"
                    else "Today: ${today.size} videos · ${formatDuration(today.sumOf { it.watchedSec })}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                history.firstOrNull()?.let {
                    Text(
                        "Latest: ${it.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    validate: (String) -> Boolean,
    onDismiss: () -> Unit,
    /** Receives the text and, when [checkboxLabel] is given, whether the box was ticked. */
    onConfirm: (String, Boolean) -> Unit,
    hint: String? = null,
    checkboxLabel: String? = null,
) {
    var text by remember { mutableStateOf(initial) }
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) })
                checkboxLabel?.let {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { checked = !checked },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text(it)
                    }
                }
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = validate(text), onClick = { onConfirm(text, checked); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
