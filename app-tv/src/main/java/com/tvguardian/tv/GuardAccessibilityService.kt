package com.tvguardian.tv

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Phase 0 checks:
 *  - can we see which app is in the foreground?
 *  - can we send the TV HOME / BACK?
 *  - can we cover the screen with a lock overlay the remote can't dismiss?
 *  - can we read on-screen text (fallback for video titles)?
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardAccessibilityService? = null
            private set

        private const val MAX_SCREEN_TEXTS = 40
        const val ACTION_RUN_TEST = "com.tvguardian.tv.RUN_TEST"

        /** Pop-ups (volume bar, notifications) that sit on top of an app rather than replacing it. */
        private val TRANSIENT_WINDOW_PACKAGES = setOf("com.android.systemui")
        private const val BANNER_MILLIS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: View? = null
    private var overlayHideJob: Job? = null
    private var testJob: Job? = null

    /**
     * Phase 0 only: lets the PC start the test with
     * `adb shell am broadcast -a com.tvguardian.tv.RUN_TEST -p com.tvguardian.tv`.
     * Exported so the adb shell can reach it; remove before the app is used for real.
     */
    private val runTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = runAutomaticTest()
    }

    override fun onServiceConnected() {
        instance = this
        ContextCompat.registerReceiver(
            this, runTestReceiver, IntentFilter(ACTION_RUN_TEST), ContextCompat.RECEIVER_EXPORTED
        )
        SpikeState.log("Accessibility service connected")

        // The parent's lock: draw or remove the lock screen whenever it changes (also restores it after a restart).
        scope.launch {
            TvLock.locked.collect { locked ->
                if (locked) {
                    MediaControl.pause(this@GuardAccessibilityService)
                    showLockOverlay(TvLock.message, autoHideMillis = null)
                } else {
                    hideLockOverlay()
                }
            }
        }
        // While locked, nothing may play (e.g. something cast to the TV from a phone).
        scope.launch {
            combine(TvLock.locked, SpikeState.nowPlaying) { locked, nowPlaying -> locked && nowPlaying?.isPlaying == true }
                .collect { if (it) MediaControl.pause(this@GuardAccessibilityService) }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(runTestReceiver) }
        hideLockOverlay()
        scope.cancel()
        instance = null
        SpikeState.log("Accessibility service stopped")
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in TRANSIENT_WINDOW_PACKAGES) return
        if (pkg != SpikeState.foregroundApp.value) {
            SpikeState.foregroundApp.value = pkg
            SpikeState.log("Foreground app: $pkg")
        }
    }

    /** While the lock overlay is up, swallow every remote key except power and volume. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (overlay == null) return false
        if (event.keyCode in setOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            SpikeState.log("Blocked remote key while locked: ${KeyEvent.keyCodeToString(event.keyCode)}")
        }
        return true
    }

    fun goHome() {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        SpikeState.log("HOME action ${if (ok) "sent" else "FAILED"}")
    }

    fun goBack() {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        SpikeState.log("BACK action ${if (ok) "sent" else "FAILED"}")
    }

    /** Full-screen cover that swallows the remote. Stays until [hideLockOverlay] when [autoHideMillis] is null. */
    fun showLockOverlay(message: String, autoHideMillis: Long?) {
        if (overlay != null) return
        val view = TextView(this).apply {
            text = message
            textSize = 32f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            overlay = view
            SpikeState.log("Lock overlay SHOWN" + (autoHideMillis?.let { " (auto-hides in ${it / 1000}s)" } ?: ""))
        } catch (e: RuntimeException) {
            SpikeState.log("FAILED to show lock overlay: ${e.message}")
            return
        }
        if (autoHideMillis != null) {
            overlayHideJob = scope.launch {
                delay(autoHideMillis)
                hideLockOverlay()
            }
        }
    }

    /** A message across the top of the screen for a few seconds; doesn't take focus or block the remote. */
    fun showBanner(text: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(48, 28, 48, 28)
            background = GradientDrawable().apply {
                setColor(0xEE1E3A8A.toInt())
                cornerRadius = 28f
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 48
        }
        val windowManager = getSystemService(WindowManager::class.java)
        windowManager.addView(view, params)
        SpikeState.log("Showing message: $text")
        scope.launch {
            delay(BANNER_MILLIS)
            runCatching { windowManager.removeView(view) }
        }
    }

    fun hideLockOverlay() {
        overlayHideJob?.cancel()
        overlay?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
            SpikeState.log("Lock overlay hidden")
        }
        overlay = null
    }

    /** Logs the visible text of the current screen after a delay, so you have time to switch apps. */
    fun dumpScreenTextLater(delayMillis: Long) {
        scope.launch {
            SpikeState.log("Will read on-screen text in ${delayMillis / 1000}s: switch to YouTube now")
            delay(delayMillis)
            dumpScreenText()
        }
    }

    private fun dumpScreenText() {
        val texts = mutableListOf<String>()
        windows.mapNotNull { it.root }.forEach { collectText(it, texts) }
        SpikeState.log(
            "Screen text (${SpikeState.foregroundApp.value}): " +
                texts.joinToString(" | ").ifEmpty { "<nothing readable>" }
        )
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (out.size >= MAX_SCREEN_TEXTS) return
        val text = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!text.isNullOrEmpty() && text !in out) out += text
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
        }
    }

    /**
     * Hands-free test: opens a YouTube test video itself, then checks now-playing, pause, play,
     * the lock overlay and HOME, logging each result. The test video is only ~19s long, so the
     * steps are kept tight to finish before it ends.
     */
    fun runAutomaticTest() {
        if (testJob?.isActive == true) {
            SpikeState.log("TEST already running")
            return
        }
        testJob = scope.launch {
            SpikeState.log("TEST started: opening a YouTube test video")
            MediaControl.openYouTube(this@GuardAccessibilityService, MediaControl.TEST_VIDEO_URL)
            delay(8_000)
            SpikeState.log("TEST 1 foreground app = ${SpikeState.foregroundApp.value}")
            SpikeState.log("TEST 2 now playing = ${SpikeState.nowPlaying.value ?: "<nothing detected>"}")
            dumpScreenText()

            SpikeState.log("TEST 3 pausing")
            MediaControl.pause(this@GuardAccessibilityService)
            delay(4_000)
            SpikeState.log("TEST 3 state after pause = ${SpikeState.nowPlaying.value?.state}")

            SpikeState.log("TEST 4 resuming")
            MediaControl.play(this@GuardAccessibilityService)
            delay(3_000)
            SpikeState.log("TEST 4 state after play = ${SpikeState.nowPlaying.value?.state}")

            SpikeState.log("TEST 5 lock overlay for 20s: try BACK, HOME and other remote buttons")
            MediaControl.pause(this@GuardAccessibilityService)
            showLockOverlay(
                "Time's up!\n\nTEST: now press BACK and HOME on the remote.\nThis screen should NOT go away.\n\n" +
                    "It disappears by itself in 20 seconds.",
                20_000,
            )
            delay(21_000)

            SpikeState.log("TEST 6 going HOME")
            goHome()
            delay(2_000)
            SpikeState.log("TEST finished: open TV Guardian to read the results")
        }
    }
}
