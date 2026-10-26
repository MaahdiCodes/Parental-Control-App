package com.tvguardian.parent.youtube

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tvguardian.core.Commands
import com.tvguardian.core.DeviceStatus
import com.tvguardian.core.FirestorePaths
import com.tvguardian.core.WatchItem
import com.tvguardian.core.WatchSession
import com.tvguardian.core.WatchSessionTracker
import com.tvguardian.core.YouTubeLinks
import com.tvguardian.core.youtube.Lounge
import com.tvguardian.core.youtube.LoungeEvent
import com.tvguardian.core.youtube.LoungeException
import com.tvguardian.core.youtube.LoungePlayback
import com.tvguardian.core.youtube.LoungeSession
import com.tvguardian.core.youtube.VideoInfoLookup
import com.tvguardian.parent.devices.DevicesRepository
import com.tvguardian.parent.devices.TvDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a live link to the YouTube app on each TV paired with a "Link with TV code" code (e.g. LG),
 * and does for it what the TV agent does on a Google TV: publishes now-watching, records history,
 * enforces "Block YouTube", and runs the parent's commands.
 */
object YouTubeLinkManager {
    private const val TAG = "YouTubeLink"
    private const val CLOSED_RETRY_MILLIS = 60_000L
    private const val ERROR_RETRY_MILLIS = 30_000L
    private const val TOKEN_MAX_AGE_MILLIS = 60 * 60_000L
    private const val VOLUME_STEP = 10
    private const val YOUTUBE = "YouTube"

    /** A connected TV: its session and what we know about its volume. */
    private class LiveLink(val session: LoungeSession) {
        @Volatile var volume = 50
        @Volatile var volumeBeforeMute: Int? = null
    }

    private val live = ConcurrentHashMap<String, LiveLink>()
    private val devices = ConcurrentHashMap<String, TvDevice>()

    /**
     * Runs the links for all of the family's YouTube-linked TVs until cancelled.
     * Calls [onNoLinkedTvs] whenever the family has none (so the service can stop).
     */
    suspend fun run(context: Context, familyId: String, onNoLinkedTvs: () -> Unit) = coroutineScope {
        val jobs = mutableMapOf<String, Job>()
        DevicesRepository.devices(familyId).collect { result ->
            val linked = result.getOrNull()?.filter { it.isYouTubeLink && it.screenId != null } ?: return@collect
            linked.forEach { devices[it.id] = it }
            val ids = linked.map { it.id }.toSet()
            (jobs.keys - ids).forEach { id ->
                jobs.remove(id)?.cancel()
                devices.remove(id)
            }
            linked.filter { it.id !in jobs }.forEach { device ->
                jobs[device.id] = launch { runLink(context, familyId, device.id) }
            }
            if (linked.isEmpty()) onNoLinkedTvs()
        }
    }

    /** Runs a command for a YouTube-linked TV and returns a message for the parent. */
    suspend fun execute(familyId: String, device: TvDevice, type: String, args: Map<String, String>): String {
        if (type == Commands.LOCK || type == Commands.UNLOCK) {
            val block = type == Commands.LOCK
            DevicesRepository.setYouTubeBlocked(familyId, device.id, block)
            if (block) runCatching { live[device.id]?.session?.send("pause") }
            return if (block) "✓ YouTube blocked: anything played on ${device.name} is paused straight away"
            else "✓ YouTube unblocked"
        }
        val link = live[device.id]
            ?: return "YouTube isn't open on ${device.name} right now (or the link is still connecting). Try again in a minute."
        return runCatching {
            when (type) {
                Commands.PAUSE, Commands.STOP -> link.session.send("pause").let { "✓ Paused" }
                Commands.PLAY -> link.session.send("play").let { "✓ Playing" }
                Commands.PLAY_URL -> {
                    val videoId = YouTubeLinks.videoId(args[Commands.ARG_URL].orEmpty()) ?: return "Failed: not a YouTube link"
                    if (device.youtubeBlocked) return "YouTube is blocked on ${device.name}. Unblock it first."
                    link.session.send("setPlaylist", mapOf("videoId" to videoId))
                    "✓ Changing the video"
                }
                Commands.VOLUME_UP, Commands.VOLUME_DOWN -> {
                    val step = if (type == Commands.VOLUME_UP) VOLUME_STEP else -VOLUME_STEP
                    val volume = (link.volume + step).coerceIn(0, 100)
                    link.session.send("setVolume", mapOf("volume" to volume.toString()))
                    link.volume = volume
                    "✓ Volume $volume"
                }
                Commands.MUTE -> {
                    val restore = link.volumeBeforeMute
                    if (restore == null) {
                        link.volumeBeforeMute = link.volume
                        link.session.send("setVolume", mapOf("volume" to "0"))
                        "✓ Muted"
                    } else {
                        link.volumeBeforeMute = null
                        link.session.send("setVolume", mapOf("volume" to restore.toString()))
                        "✓ Unmuted"
                    }
                }
                else -> "That isn't available for TVs linked through YouTube."
            }
        }.getOrElse { "Failed: ${it.message}" }
    }

    /** One TV: connect while its YouTube app is open, check back every minute while it's closed. */
    private suspend fun runLink(context: Context, familyId: String, deviceId: String) {
        val db = FirebaseFirestore.getInstance()
        val publisher = Publisher(
            deviceRef = db.document(FirestorePaths.device(familyId, deviceId)),
            stateRef = db.document(FirestorePaths.nowState(familyId, deviceId)),
            historyRef = db.collection(FirestorePaths.history(familyId, deviceId)),
        )
        var token: String? = null
        var tokenAt = 0L

        while (true) {
            val device = devices[deviceId] ?: return
            try {
                val currentToken = token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_MAX_AGE_MILLIS }
                    ?: Lounge.refreshToken(Http.client, device.screenId!!).loungeToken.also {
                        token = it
                        tokenAt = System.currentTimeMillis()
                    }
                if (!Lounge.isOnline(Http.client, currentToken)) {
                    delay(CLOSED_RETRY_MILLIS)
                    continue
                }
                val session = LoungeSession(Http.client, Http.longPoll, currentToken, remoteDeviceId(context))
                coroutineScope {
                    val link = LiveLink(session)
                    session.connect().forEach { publisher.handle(it, link, deviceId) }
                    live[deviceId] = link
                    Log.i(TAG, "Connected to ${device.name}")
                    session.send("getNowPlaying")
                    val heartbeat = launch {
                        while (true) {
                            publisher.heartbeat()
                            delay(DeviceStatus.HEARTBEAT_MILLIS)
                        }
                    }
                    try {
                        session.listen { publisher.handle(it, link, deviceId) }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoungeException) {
                Log.i(TAG, "${device.name}: ${e.message}")
                if (e.tokenExpired || e.notPaired) token = null
                delay(if (e.notPaired) 10 * CLOSED_RETRY_MILLIS else 5_000)
            } catch (e: Exception) {
                Log.w(TAG, "${device.name}: ${e.message}")
                delay(ERROR_RETRY_MILLIS)
            } finally {
                if (live.remove(deviceId) != null) withContext(NonCancellable) { publisher.closed() }
            }
        }
    }

    /** Turns lounge events into the same Firestore data the TV agent writes. */
    private class Publisher(
        private val deviceRef: DocumentReference,
        private val stateRef: DocumentReference,
        private val historyRef: CollectionReference,
    ) {
        private val tracker = WatchSessionTracker()
        private var videoId: String? = null
        private var title: String? = null
        private var channel: String? = null
        private var state = LoungePlayback.STOPPED

        suspend fun handle(event: LoungeEvent, link: LiveLink, deviceId: String) {
            var thumbnail: String? = null
            var videoChanged = false
            when (event) {
                is LoungeEvent.NowPlaying -> {
                    if (event.videoId != videoId) {
                        videoChanged = true
                        videoId = event.videoId
                        val info = event.videoId?.let { VideoInfoLookup.lookup(Http.client, it) }
                        title = info?.title ?: event.videoId?.let { "YouTube video $it" }
                        channel = info?.channel
                        thumbnail = info?.thumbnailUrl
                            ?.let { VideoInfoLookup.download(Http.client, it) }
                            ?.let(::shrinkThumbnail)
                    }
                    state = event.state
                }
                is LoungeEvent.StateChanged -> state = event.state
                is LoungeEvent.VolumeChanged -> {
                    link.volume = event.volume
                    return
                }
                else -> return
            }

            val blocked = devices[deviceId]?.youtubeBlocked == true
            if (blocked && state == LoungePlayback.PLAYING) runCatching { link.session.send("pause") }

            val fields = mutableMapOf<String, Any?>(
                "foregroundApp" to YOUTUBE,
                "foregroundPackage" to null,
                "mediaApp" to YOUTUBE,
                "mediaPackage" to null,
                "videoId" to videoId,
                "title" to title,
                "channel" to channel,
                "playbackState" to state.label,
                "locked" to blocked,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            if (videoChanged) fields["thumbnail"] = thumbnail
            runCatching { stateRef.set(fields, SetOptions.merge()).await() }

            val item = title?.let { WatchItem(YOUTUBE, it, channel) }
            tracker.update(item, state == LoungePlayback.PLAYING, System.currentTimeMillis())?.let { save(it) }
        }

        suspend fun heartbeat() {
            runCatching { deviceRef.update("lastSeen", FieldValue.serverTimestamp()).await() }
        }

        /** YouTube closed on the TV: end the current video and clear now-watching. */
        suspend fun closed() {
            tracker.finish(System.currentTimeMillis())?.let { save(it) }
            videoId = null
            title = null
            channel = null
            state = LoungePlayback.STOPPED
            runCatching {
                stateRef.set(
                    mapOf(
                        "foregroundApp" to null, "mediaApp" to null, "videoId" to null, "title" to null,
                        "channel" to null, "playbackState" to null, "thumbnail" to null,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
            }
        }

        private suspend fun save(session: WatchSession) {
            runCatching {
                historyRef.add(
                    mapOf(
                        "packageName" to null,
                        "appName" to YOUTUBE,
                        "title" to session.item.title,
                        "channel" to session.item.channel,
                        "startedAt" to Timestamp(Date(session.startedAtMillis)),
                        "endedAt" to Timestamp(Date(session.endedAtMillis)),
                        "watchedSec" to session.watchedMillis / 1000,
                    )
                ).await()
            }
        }
    }
}
