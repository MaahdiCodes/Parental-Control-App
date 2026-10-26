package com.tvguardian.tv

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * We don't care about notifications themselves: being an enabled notification listener is what
 * lets us call MediaSessionManager.getActiveSessions(), which tells us what every media app
 * (YouTube, Netflix, ...) is playing and lets us pause/play it.
 */
class MediaWatcherService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaWatcherService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<MediaController>()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        track(sessions.orEmpty())
    }

    override fun onListenerConnected() {
        instance = this
        SpikeState.log("Notification access connected (media sessions readable)")
        val component = ComponentName(this, MediaWatcherService::class.java)
        val sessionManager = getSystemService(MediaSessionManager::class.java)
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, mainHandler)
        track(sessionManager.getActiveSessions(component))
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        instance = null
        SpikeState.log("Notification access disconnected")
    }

    /** The session most likely to be "what's on screen": the one playing, else the first one. */
    fun currentController(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()

    private fun track(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        controllers.addAll(sessions)
        controllers.forEach { it.registerCallback(controllerCallback, mainHandler) }
        SpikeState.log("Active media sessions: ${sessions.joinToString { it.packageName }.ifEmpty { "none" }}")
        publish()
    }

    private fun publish() {
        val controller = currentController()
        controller?.metadata?.let { logMetadataKeys(controller.packageName, it) }
        val nowPlaying = controller?.let {
            val description = it.metadata?.description
            SpikeState.NowPlaying(
                packageName = it.packageName,
                title = description?.title?.toString()
                    ?: it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                subtitle = description?.subtitle?.toString()
                    ?: it.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                state = stateName(it.playbackState?.state),
                durationMs = it.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { ms -> ms > 0 },
                art = it.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: it.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: description?.iconBitmap,
            )
        }
        if (nowPlaying != SpikeState.nowPlaying.value) {
            SpikeState.nowPlaying.value = nowPlaying
            if (nowPlaying != null) {
                SpikeState.log(
                    "Now playing [${nowPlaying.state}] ${nowPlaying.packageName}: " +
                        "title=\"${nowPlaying.title}\" subtitle=\"${nowPlaying.subtitle}\" art=${nowPlaying.art != null}"
                )
            }
        }
    }

    private var lastMetadataDump: String? = null

    /** Phase 0: log every metadata field an app publishes, to find where (if anywhere) the title or video ID lives. */
    private fun logMetadataKeys(packageName: String, metadata: MediaMetadata) {
        val dump = metadata.keySet().sorted().joinToString { key ->
            val value = when {
                metadata.getBitmap(key) != null -> "<image>"
                metadata.getText(key) != null -> "\"${metadata.getText(key)}\""
                else -> metadata.getLong(key).toString()
            }
            "${key.removePrefix("android.media.metadata.")}=$value"
        }
        if (dump != lastMetadataDump) {
            lastMetadataDump = dump
            SpikeState.log("Metadata fields from $packageName: $dump")
        }
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_NONE, null -> "NONE"
        else -> "STATE_$state"
    }
}
