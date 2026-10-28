package com.tvguardian.parent.watch

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.tvguardian.core.Commands
import com.tvguardian.core.FirestorePaths
import com.tvguardian.parent.changes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** What a TV is showing right now (from state/now). */
data class NowState(
    val foregroundApp: String?,
    val foregroundPackage: String?,
    val mediaApp: String?,
    /** Known only for YouTube-linked TVs. */
    val videoId: String?,
    val title: String?,
    val channel: String?,
    val playbackState: String?,
    val thumbnail: ImageBitmap?,
    val locked: Boolean,
)

data class HistoryEntry(
    val id: String,
    val title: String,
    val channel: String?,
    val appName: String?,
    val startedAtMillis: Long?,
    val watchedSec: Long,
)

object WatchRepository {
    private const val HISTORY_LIMIT = 300L
    private const val RESPONSE_TIMEOUT_MILLIS = 20_000L

    private val db get() = FirebaseFirestore.getInstance()

    fun nowState(familyId: String, deviceId: String): Flow<NowState?> =
        db.document(FirestorePaths.nowState(familyId, deviceId)).changes()
            .map { if (it.exists()) toNowState(it) else null }
            .catch { emit(null) }

    fun history(familyId: String, deviceId: String, limit: Long = HISTORY_LIMIT): Flow<List<HistoryEntry>> =
        db.collection(FirestorePaths.history(familyId, deviceId))
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .limit(limit)
            .changes()
            .map { snapshot -> snapshot.documents.map(::toHistoryEntry) }
            .catch { emit(emptyList()) }

    /**
     * Sends a command and waits for the TV to report back.
     * Returns a short message for the parent ("✓ Paused", "The TV didn't respond…").
     */
    suspend fun sendCommand(familyId: String, deviceId: String, type: String, args: Map<String, String> = emptyMap()): String {
        val ref = db.collection(FirestorePaths.commands(familyId, deviceId)).document()
        val outcome = withTimeoutOrNull(RESPONSE_TIMEOUT_MILLIS) {
            ref.set(
                mapOf(
                    "type" to type,
                    "args" to args,
                    "status" to Commands.STATUS_PENDING,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "createdBy" to FirebaseAuth.getInstance().uid,
                )
            ).await()
            ref.changes().first { it.getString("status") != Commands.STATUS_PENDING }
        }
        // A voice recording is large and only needed once: don't keep it in the database.
        if (type == Commands.VOICE) runCatching { ref.delete().await() }
        val result = outcome?.getString("result").orEmpty()
        return when (outcome?.getString("status")) {
            null -> "The TV didn't respond. Is it switched on and connected?"
            Commands.STATUS_DONE -> "✓ $result"
            Commands.STATUS_EXPIRED -> "The TV received this too late, so it was skipped."
            else -> "Failed: $result"
        }
    }

    private fun toNowState(doc: DocumentSnapshot) = NowState(
        foregroundApp = doc.getString("foregroundApp"),
        foregroundPackage = doc.getString("foregroundPackage"),
        mediaApp = doc.getString("mediaApp"),
        videoId = doc.getString("videoId"),
        title = doc.getString("title"),
        channel = doc.getString("channel"),
        playbackState = doc.getString("playbackState"),
        thumbnail = doc.getString("thumbnail")?.let(::decodeThumbnail),
        locked = doc.getBoolean("locked") == true,
    )

    private fun toHistoryEntry(doc: DocumentSnapshot) = HistoryEntry(
        id = doc.id,
        title = doc.getString("title") ?: "?",
        channel = doc.getString("channel"),
        appName = doc.getString("appName"),
        startedAtMillis = doc.getTimestamp("startedAt")?.toDate()?.time,
        watchedSec = doc.getLong("watchedSec") ?: 0,
    )

    private fun decodeThumbnail(base64: String): ImageBitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
