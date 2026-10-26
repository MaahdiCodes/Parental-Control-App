package com.tvguardian.parent.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tvguardian.core.Commands
import kotlinx.coroutines.delay
import java.io.File

private enum class RecordingState { IDLE, RECORDING, RECORDED }

/** Record a short voice message (up to 30 s), optionally listen back, then send it to play on the TV. */
@Composable
fun VoiceMessageDialog(onDismiss: () -> Unit, onSend: (base64Audio: String) -> Unit) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var state by remember { mutableStateOf(RecordingState.IDLE) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { recorder.release() } }

    fun startRecording() {
        error = null
        runCatching { recorder.start(onMaxReached = { state = RecordingState.RECORDED }) }
            .onSuccess { state = RecordingState.RECORDING }
            .onFailure { error = "Couldn't start recording: ${it.message}" }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "TV Guardian needs microphone access to record a voice message."
    }

    // Recording timer (the recorder itself stops at the limit and calls onMaxReached).
    LaunchedEffect(state) {
        if (state == RecordingState.RECORDING) {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                elapsedMillis = SystemClock.elapsedRealtime() - started
                delay(200)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (state != RecordingState.RECORDING) onDismiss() },
        title = { Text("🎤 Voice message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    RecordingState.IDLE -> Text(
                        "Record a message (up to ${Commands.VOICE_MAX_SECONDS} seconds). " +
                            "The TV pauses what's playing and plays it out loud."
                    )
                    RecordingState.RECORDING -> {
                        Text("Recording… ${elapsedMillis / 1000}s / ${Commands.VOICE_MAX_SECONDS}s", color = MaterialTheme.colorScheme.error)
                        LinearProgressIndicator(
                            progress = { elapsedMillis / (Commands.VOICE_MAX_SECONDS * 1000f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { recorder.stop(); state = RecordingState.RECORDED },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("⏹ Stop") }
                    }
                    RecordingState.RECORDED -> {
                        Text("Recorded ${recorder.durationMillis / 1000}s.")
                        OutlinedButton(onClick = { recorder.playBack() }, modifier = Modifier.fillMaxWidth()) {
                            Text("▶ Listen first")
                        }
                    }
                }
                if (state != RecordingState.RECORDING) {
                    Button(
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (hasMic) startRecording() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (state == RecordingState.IDLE) "⏺ Start recording" else "⏺ Record again") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = state == RecordingState.RECORDED,
                onClick = {
                    val audio = recorder.readBase64()
                    when {
                        audio == null -> error = "The recording is empty. Try again."
                        audio.length > Commands.VOICE_MAX_BASE64_CHARS -> error = "That recording is too long. Keep it under 30 seconds."
                        else -> {
                            onSend(audio)
                            onDismiss()
                        }
                    }
                },
            ) { Text("Send to TV") }
        },
        dismissButton = {
            TextButton(enabled = state != RecordingState.RECORDING, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Small AAC recorder (mono, 32 kbps: about 4 KB per second) plus playback for "listen first". */
private class VoiceRecorder(private val context: Context) {
    private val file = File(context.cacheDir, "voice-message.m4a")
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    var durationMillis = 0L
        private set

    fun start(onMaxReached: () -> Unit) {
        release()
        file.delete()
        @Suppress("DEPRECATION")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(22_050)
            setAudioEncodingBitRate(32_000)
            setMaxDuration(Commands.VOICE_MAX_SECONDS * 1000)
            setOutputFile(file.path)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stop()
                    onMaxReached()
                }
            }
            prepare()
            start()
        }
        recorder = mediaRecorder
        startedAt = SystemClock.elapsedRealtime()
    }

    fun stop() {
        val active = recorder ?: return
        durationMillis = SystemClock.elapsedRealtime() - startedAt
        runCatching { active.stop() } // throws if stopped within a fraction of a second; the file is then unusable
        active.release()
        recorder = null
    }

    fun playBack() {
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                start()
            }
        }.getOrNull()
    }

    fun readBase64(): String? =
        file.takeIf { it.exists() && it.length() > 0 }?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }

    fun release() {
        stop()
        player?.release()
        player = null
    }
}
