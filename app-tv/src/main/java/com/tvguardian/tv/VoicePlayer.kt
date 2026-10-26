package com.tvguardian.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/**
 * Plays a parent's recorded voice message out loud. Makes sure it can be heard: unmutes and raises
 * a very low volume to [MIN_VOLUME_FRACTION] for the message, then puts the volume back.
 */
object VoicePlayer {
    private const val MIN_VOLUME_FRACTION = 0.4

    private var player: MediaPlayer? = null
    private var volumeToRestore: Int? = null

    /** Starts playback and returns the message length in ms. */
    fun play(context: Context, audio: ByteArray): Int {
        stop(context)
        val file = File(context.cacheDir, "voice-message.m4a").apply { writeBytes(audio) }

        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val minimum = (max * MIN_VOLUME_FRACTION).toInt()
        if (current < minimum) {
            volumeToRestore = current
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minimum, 0)
        }

        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.path)
            prepare()
            setOnCompletionListener { stop(context) }
            start()
        }
        player = mediaPlayer
        return mediaPlayer.duration
    }

    fun stop(context: Context) {
        player?.release()
        player = null
        volumeToRestore?.let {
            context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        }
        volumeToRestore = null
    }
}
