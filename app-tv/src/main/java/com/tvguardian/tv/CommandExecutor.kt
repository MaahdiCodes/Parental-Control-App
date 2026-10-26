package com.tvguardian.tv

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.util.Base64
import com.tvguardian.core.Commands
import com.tvguardian.core.YouTubeLinks
import kotlinx.coroutines.delay

/** Runs one parent command on the TV. Returns a short result for the parent; throws if it can't be done. */
object CommandExecutor {
    /** Never "close" these: the home screen, system UI, or ourselves. */
    private val PROTECTED_PACKAGES = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.android.systemui",
        "com.tvguardian.tv",
    )

    suspend fun execute(context: Context, type: String, args: Map<String, Any?>): String = when (type) {
        Commands.PAUSE -> MediaControl.pause(context).let { "Paused" }
        Commands.PLAY -> MediaControl.play(context).let { "Playing" }
        Commands.STOP -> MediaControl.stop(context).let { "Stopped" }
        Commands.HOME -> guard().goHome().let { "Went to the home screen" }
        Commands.BACK -> guard().goBack().let { "Went back" }
        Commands.CLOSE_APP -> closeForegroundApp(context)
        Commands.LOCK -> {
            guard() // the lock screen needs the accessibility service
            TvLock.lock(args[Commands.ARG_TEXT] as? String)
            "TV locked"
        }
        Commands.UNLOCK -> TvLock.unlock().let { "TV unlocked" }
        Commands.MESSAGE -> {
            val text = (args[Commands.ARG_TEXT] as? String)?.trim()
            require(!text.isNullOrEmpty()) { "Message is empty" }
            guard().showBanner(text)
            when {
                args[Commands.ARG_SPEAK] != "true" -> "Message shown on TV"
                TvSpeech.speak(context, text) -> "Message shown and read aloud"
                else -> "Message shown (this TV can't read text aloud)"
            }
        }
        Commands.VOICE -> {
            val encoded = args[Commands.ARG_AUDIO] as? String
            require(!encoded.isNullOrEmpty() && encoded.length <= Commands.VOICE_MAX_BASE64_CHARS) { "Voice message missing or too long" }
            MediaControl.pause(context) // so they can hear it
            val millis = VoicePlayer.play(context, Base64.decode(encoded, Base64.DEFAULT))
            GuardAccessibilityService.instance?.showBanner("🔊 Voice message from a parent")
            "Playing your voice message (${(millis + 500) / 1000}s)"
        }
        Commands.PLAY_URL -> {
            val videoId = YouTubeLinks.videoId(args[Commands.ARG_URL] as? String ?: "")
                ?: throw IllegalArgumentException("Not a YouTube link")
            require(!TvLock.locked.value) { "The TV is locked. Unlock it first." }
            MediaControl.openYouTube(context, YouTubeLinks.watchUrl(videoId))
            "Opening the video on YouTube"
        }
        Commands.VOLUME_UP -> adjustVolume(context, AudioManager.ADJUST_RAISE).let { "Volume up" }
        Commands.VOLUME_DOWN -> adjustVolume(context, AudioManager.ADJUST_LOWER).let { "Volume down" }
        Commands.MUTE -> adjustVolume(context, AudioManager.ADJUST_TOGGLE_MUTE).let { "Mute toggled" }
        else -> throw IllegalArgumentException("Unknown command $type")
    }

    private fun guard(): GuardAccessibilityService =
        GuardAccessibilityService.instance
            ?: throw IllegalStateException("Accessibility is off on the TV. Run tools/setup-tv.ps1 again.")

    private suspend fun closeForegroundApp(context: Context): String {
        val pkg = SpikeState.foregroundApp.value
        if (pkg == null || pkg in PROTECTED_PACKAGES) {
            guard().goHome()
            return "No app was open"
        }
        MediaControl.pause(context)
        guard().goHome()
        delay(1_000) // let it move to the background before ending it
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
        return "Closed ${AppNames.label(context, pkg)}"
    }

    private fun adjustVolume(context: Context, direction: Int) {
        context.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }
}
