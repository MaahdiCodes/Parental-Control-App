package com.tvguardian.tv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared in-memory state for the Phase 0 feasibility test.
 * The services write here; the on-screen debug panel reads it.
 * Every log line is also appended to files/spike-log.txt so it can be pulled with tools/pull-log.ps1.
 */
object SpikeState {
    private const val TAG = "TvGuardian"
    private const val MAX_LOG_LINES = 80

    data class NowPlaying(
        val packageName: String,
        val title: String?,
        val subtitle: String?,
        val state: String,
        val durationMs: Long? = null,
        val art: Bitmap? = null,
    ) {
        val isPlaying get() = state == "PLAYING"
    }

    val foregroundApp = MutableStateFlow<String?>(null)
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    private var logFile: File? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        logFile = File(context.filesDir, "spike-log.txt")
    }

    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        Log.i(TAG, message)
        log.update { (it + line).takeLast(MAX_LOG_LINES) }
        runCatching { logFile?.appendText(line + "\n") }
    }

    fun clearLog() {
        log.value = emptyList()
        runCatching { logFile?.writeText("") }
    }
}
