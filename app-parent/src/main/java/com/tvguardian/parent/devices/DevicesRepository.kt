package com.tvguardian.parent.devices

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.tvguardian.core.DeviceTypes
import com.tvguardian.core.FirestorePaths
import com.tvguardian.core.youtube.Lounge
import com.tvguardian.core.youtube.LoungeException
import com.tvguardian.parent.youtube.Http
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.IOException

data class TvDevice(
    val id: String,
    val name: String,
    val model: String?,
    val lastSeenMillis: Long?,
    val accessibilityOn: Boolean?,
    val notificationAccessOn: Boolean?,
    /** [DeviceTypes.GOOGLE_TV] (TV Guardian agent) or [DeviceTypes.YOUTUBE_LINK] (YouTube only, e.g. LG). */
    val type: String = DeviceTypes.GOOGLE_TV,
    /** YouTube-linked TVs: the TV's YouTube screen id from pairing. */
    val screenId: String? = null,
    /** YouTube-linked TVs: anything played gets paused straight away. */
    val youtubeBlocked: Boolean = false,
    /** YouTube-linked TVs: the phone (install id) that keeps the link running, so only one phone records it. */
    val linkHost: String? = null,
) {
    val isYouTubeLink get() = type == DeviceTypes.YOUTUBE_LINK
}

object DevicesRepository {
    private val db get() = FirebaseFirestore.getInstance()

    /** Live list of the family's TVs. Errors are delivered as a failed Result instead of crashing the UI. */
    fun devices(familyId: String): Flow<Result<List<TvDevice>>> = callbackFlow {
        val registration = db.collection(FirestorePaths.devices(familyId))
            .orderBy("pairedAt")
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> trySend(Result.failure(error))
                    snapshot != null -> trySend(Result.success(snapshot.documents.map(::toDevice)))
                }
            }
        awaitClose { registration.remove() }
    }

    /**
     * Claims the code a TV is showing and adds that TV to the family, in one atomic batch
     * (the security rules only allow creating the device if the claimed code belongs to it).
     */
    suspend fun pair(familyId: String, code: String, name: String) {
        val codeRef = db.collection(FirestorePaths.PAIRING_CODES).document(code)
        val pairing = codeRef.get().await()
        if (!pairing.exists()) throw PairingException("No TV is showing that code. Check the code on the TV.")
        if (pairing.getString("familyId") != null) throw PairingException("That code was already used.")
        val expiresAt = pairing.getTimestamp("expiresAt")?.toDate()?.time ?: 0
        if (expiresAt < System.currentTimeMillis()) {
            throw PairingException("That code has expired. The TV is showing a new one.")
        }
        val tvUid = pairing.getString("agentUid") ?: throw PairingException("That code is invalid.")

        try {
            db.batch()
                .set(
                    db.document(FirestorePaths.family(familyId)),
                    mapOf("ownerUid" to familyId, "updatedAt" to FieldValue.serverTimestamp()),
                    SetOptions.merge(),
                )
                .update(codeRef, "familyId", familyId)
                .set(
                    db.document(FirestorePaths.device(familyId, tvUid)),
                    mapOf(
                        "name" to name,
                        "type" to DeviceTypes.GOOGLE_TV,
                        "model" to pairing.getString("model"),
                        "pairingCode" to code,
                        "pairedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                .commit()
                .await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                throw PairingException("Couldn't pair: the code may have just expired. Try the new code on the TV.")
            }
            throw e
        }
    }

    /**
     * Links a TV through its YouTube app (Settings → Link with TV code), for TVs that can't run
     * TV Guardian such as LG. Only YouTube can then be seen and controlled.
     */
    suspend fun pairYouTubeTv(familyId: String, code: String, name: String, linkHost: String) {
        val screen = try {
            Lounge.pairWithCode(Http.client, code)
        } catch (e: LoungeException) {
            throw PairingException(e.message ?: "YouTube didn't accept that code.")
        } catch (e: IllegalArgumentException) {
            throw PairingException(e.message ?: "That isn't a TV code.")
        } catch (e: IOException) {
            throw PairingException("Couldn't reach YouTube. Check the phone's internet connection.")
        }
        val deviceRef = db.collection(FirestorePaths.devices(familyId)).document()
        db.batch()
            .set(
                db.document(FirestorePaths.family(familyId)),
                mapOf("ownerUid" to familyId, "updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
            .set(
                deviceRef,
                mapOf(
                    "name" to name,
                    "type" to DeviceTypes.YOUTUBE_LINK,
                    "screenId" to screen.screenId,
                    "model" to (screen.name ?: "TV with YouTube"),
                    "pairedAt" to FieldValue.serverTimestamp(),
                    "youtubeBlocked" to false,
                    "linkHost" to linkHost,
                ),
            )
            .commit()
            .await()
    }

    suspend fun setLinkHost(familyId: String, deviceId: String, linkHost: String) {
        db.document(FirestorePaths.device(familyId, deviceId)).update("linkHost", linkHost).await()
    }

    suspend fun setYouTubeBlocked(familyId: String, deviceId: String, blocked: Boolean) {
        db.document(FirestorePaths.device(familyId, deviceId)).update("youtubeBlocked", blocked).await()
    }

    suspend fun rename(familyId: String, deviceId: String, name: String) {
        db.document(FirestorePaths.device(familyId, deviceId)).update("name", name).await()
    }

    /** Removes the TV and everything stored under it (Firestore doesn't delete sub-collections by itself). */
    suspend fun remove(familyId: String, deviceId: String) {
        val devicePath = FirestorePaths.device(familyId, deviceId)
        for (sub in FirestorePaths.DEVICE_SUBCOLLECTIONS) {
            val docs = db.collection("$devicePath/$sub").get().await().documents
            docs.chunked(400).forEach { chunk ->
                db.batch().apply { chunk.forEach { delete(it.reference) } }.commit().await()
            }
        }
        db.document(devicePath).delete().await()
    }

    private fun toDevice(doc: DocumentSnapshot): TvDevice {
        val protection = doc.get("protection") as? Map<*, *>
        return TvDevice(
            id = doc.id,
            name = doc.getString("name") ?: "TV",
            model = doc.getString("model"),
            lastSeenMillis = doc.getTimestamp("lastSeen")?.toDate()?.time,
            accessibilityOn = protection?.get("accessibility") as? Boolean,
            notificationAccessOn = protection?.get("notificationAccess") as? Boolean,
            type = doc.getString("type") ?: DeviceTypes.GOOGLE_TV,
            screenId = doc.getString("screenId"),
            youtubeBlocked = doc.getBoolean("youtubeBlocked") == true,
            linkHost = doc.getString("linkHost"),
        )
    }
}

class PairingException(message: String) : Exception(message)
