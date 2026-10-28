package com.tvguardian.tv

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.tvguardian.core.BlockList
import com.tvguardian.core.Commands
import com.tvguardian.core.FirestorePaths
import com.tvguardian.core.WatchItem
import com.tvguardian.core.WatchSession
import com.tvguardian.core.WatchSessionTracker
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.util.Date

/** The jobs that run while the TV is paired; each runs until the agent cancels it (unpaired or stopping). */
class PairedSync(private val context: Context, private val deviceRef: DocumentReference) {

    /** Keeps state/now up to date: open app, what's playing (with a small thumbnail), and the lock. */
    @OptIn(FlowPreview::class)
    suspend fun publishNowWatching() {
        val stateRef = deviceRef.collection(FirestorePaths.STATE).document("now")
        var lastThumbnailKey: String? = null
        combine(SpikeState.foregroundApp, SpikeState.nowPlaying, TvLock.locked) { app, playing, locked ->
            Triple(app, playing, locked)
        }
            .debounce(500) // coalesce bursts (e.g. title and artwork arriving a moment apart)
            .collect { (app, playing, locked) ->
                val fields = mutableMapOf<String, Any?>(
                    "foregroundPackage" to app,
                    "foregroundApp" to app?.let { AppNames.label(context, it) },
                    "mediaPackage" to playing?.packageName,
                    "mediaApp" to playing?.packageName?.let { AppNames.label(context, it) },
                    "title" to playing?.title?.takeIf { it.isNotBlank() },
                    "channel" to playing?.subtitle?.takeIf { it.isNotBlank() },
                    "playbackState" to playing?.state,
                    "durationMs" to playing?.durationMs,
                    "locked" to locked,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                // The thumbnail is the biggest field: only send it when it changes.
                val thumbnailKey = "${playing?.packageName}|${playing?.title}|${playing?.art != null}"
                if (thumbnailKey != lastThumbnailKey) {
                    fields["thumbnail"] = playing?.art?.let(::encodeThumbnail)
                    lastThumbnailKey = thumbnailKey
                }
                runCatching { stateRef.set(fields, SetOptions.merge()).await() }
                    .onFailure { SpikeState.log("Couldn't publish now-watching: ${it.message}") }
            }
    }

    /** Runs pending commands from the parent, oldest first, and reports each outcome. */
    suspend fun runCommands() {
        val handled = mutableSetOf<String>()
        deviceRef.collection(FirestorePaths.COMMANDS)
            .whereEqualTo("status", Commands.STATUS_PENDING)
            .changes()
            .collect { snapshot ->
                val pending = snapshot.documents
                    .filter { handled.add(it.id) }
                    .sortedBy { it.getTimestamp("createdAt")?.seconds ?: 0 }
                for (doc in pending) {
                    val type = doc.getString("type").orEmpty()
                    @Suppress("UNCHECKED_CAST")
                    val args = doc.get("args") as? Map<String, Any?> ?: emptyMap()
                    val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time
                    val (status, result) = if (Commands.isStale(createdAt, System.currentTimeMillis())) {
                        Commands.STATUS_EXPIRED to "Arrived too late (the TV was probably off)"
                    } else {
                        try {
                            Commands.STATUS_DONE to CommandExecutor.execute(context, type, args)
                        } catch (e: Exception) {
                            Commands.STATUS_FAILED to (e.message ?: e.javaClass.simpleName)
                        }
                    }
                    SpikeState.log("Command $type: $status ($result)")
                    runCatching {
                        doc.reference.update(
                            mapOf("status" to status, "result" to result, "completedAt" to FieldValue.serverTimestamp())
                        ).await()
                    }.onFailure { SpikeState.log("Couldn't report command result: ${it.message}") }
                }
            }
    }

    /** Stops videos and channels the parent blocked: pause, back to the home screen, and say why. */
    suspend fun enforceBlocks() {
        val familyRef = deviceRef.parent.parent ?: return
        val blocks = familyRef.changes().map { BlockList.fromFirestore(it.get("blockedChannels"), it.get("blockedVideos")) }
        combine(blocks, SpikeState.nowPlaying) { list, playing -> list to playing }.collect { (list, playing) ->
            if (playing == null || !playing.isPlaying || list.isEmpty) return@collect
            val reason = list.reasonFor(playing.title, playing.subtitle, videoId = null) ?: return@collect
            SpikeState.log("Blocked $reason: \"${playing.title}\" (${playing.subtitle})")
            MediaControl.pause(context)
            GuardAccessibilityService.instance?.apply {
                goHome()
                showBanner(if (reason == "channel") "🚫 This channel is blocked by a parent" else "🚫 This video is blocked by a parent")
            }
        }
    }

    /** Writes a history entry each time a watched video (10s+ of playing) ends. */
    suspend fun recordHistory() {
        val tracker = WatchSessionTracker()
        SpikeState.nowPlaying.collect { playing ->
            val item = playing?.title?.takeIf { it.isNotBlank() }?.let { title ->
                WatchItem(playing.packageName, title, playing.subtitle?.takeIf { it.isNotBlank() })
            }
            tracker.update(item, playing?.isPlaying == true, System.currentTimeMillis())?.let { save(it) }
        }
    }

    private suspend fun save(session: WatchSession) {
        runCatching {
            deviceRef.collection(FirestorePaths.HISTORY).add(
                mapOf(
                    "packageName" to session.item.packageName,
                    "appName" to AppNames.label(context, session.item.packageName),
                    "title" to session.item.title,
                    "channel" to session.item.channel,
                    "startedAt" to Timestamp(Date(session.startedAtMillis)),
                    "endedAt" to Timestamp(Date(session.endedAtMillis)),
                    "watchedSec" to session.watchedMillis / 1000,
                )
            ).await()
            SpikeState.log("History: \"${session.item.title}\" watched ${session.watchedMillis / 1000}s")
        }.onFailure { SpikeState.log("Couldn't save history: ${it.message}") }
    }

    /** ~240px-wide JPEG as base64 (a few KB): small enough to live inside the Firestore doc. */
    private fun encodeThumbnail(art: Bitmap): String? = runCatching {
        val width = 240
        val height = (art.height * width / art.width.coerceAtLeast(1)).coerceIn(1, 480)
        val scaled = Bitmap.createScaledBitmap(art, width, height, true)
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}
