package com.tvguardian.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.net.Uri
import android.view.KeyEvent

/** Pause/play/stop whatever is playing, and open a YouTube video. */
object MediaControl {
    private const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"

    /** "Me at the zoo", the first YouTube video: short and harmless, good for testing. */
    const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

    fun pause(context: Context) = send(context, "PAUSE", KeyEvent.KEYCODE_MEDIA_PAUSE) { it.pause() }
    fun play(context: Context) = send(context, "PLAY", KeyEvent.KEYCODE_MEDIA_PLAY) { it.play() }
    fun stop(context: Context) = send(context, "STOP", KeyEvent.KEYCODE_MEDIA_STOP) { it.stop() }

    fun openYouTube(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(intent).setPackage(YOUTUBE_TV_PACKAGE))
            SpikeState.log("Opened $url in YouTube TV app")
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(intent)
                SpikeState.log("YouTube TV app not found; opened $url with default handler")
            } catch (e: ActivityNotFoundException) {
                SpikeState.log("FAILED to open $url: no app can handle it")
            }
        }
    }

    private fun send(
        context: Context,
        name: String,
        keyCode: Int,
        action: (MediaController.TransportControls) -> Unit,
    ) {
        val controller = MediaWatcherService.instance?.currentController()
        if (controller != null) {
            action(controller.transportControls)
            SpikeState.log("Sent $name to ${controller.packageName} via media session")
        } else {
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            SpikeState.log("Sent $name as a media key (no media session available)")
        }
    }
}
