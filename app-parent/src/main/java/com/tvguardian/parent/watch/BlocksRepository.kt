package com.tvguardian.parent.watch

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tvguardian.core.BlockList
import com.tvguardian.core.BlockedVideo
import com.tvguardian.core.FirestorePaths
import com.tvguardian.parent.changes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** The family's blocked videos and channels (on the family doc, so they apply to every TV). */
object BlocksRepository {
    private const val CHANNELS = "blockedChannels"
    private const val VIDEOS = "blockedVideos"

    private val db get() = FirebaseFirestore.getInstance()

    fun blocks(familyId: String): Flow<BlockList> =
        db.document(FirestorePaths.family(familyId)).changes()
            .map { BlockList.fromFirestore(it.get(CHANNELS), it.get(VIDEOS)) }
            .catch { emit(BlockList()) }

    suspend fun blockChannel(familyId: String, channel: String) = update(familyId, CHANNELS, FieldValue.arrayUnion(channel))

    suspend fun unblockChannel(familyId: String, channel: String) = update(familyId, CHANNELS, FieldValue.arrayRemove(channel))

    suspend fun blockVideo(familyId: String, video: BlockedVideo) =
        update(familyId, VIDEOS, FieldValue.arrayUnion(BlockList.toFirestore(video)))

    suspend fun unblockVideo(familyId: String, video: BlockedVideo) =
        update(familyId, VIDEOS, FieldValue.arrayRemove(BlockList.toFirestore(video)))

    private suspend fun update(familyId: String, field: String, value: FieldValue) {
        db.document(FirestorePaths.family(familyId)).set(mapOf(field to value), SetOptions.merge()).await()
    }
}
