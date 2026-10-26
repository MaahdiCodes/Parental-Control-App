package com.tvguardian.parent.devices

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.tvguardian.core.FirestorePaths
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class TvDevice(
    val id: String,
    val name: String,
    val model: String?,
    val lastSeenMillis: Long?,
    val accessibilityOn: Boolean?,
    val notificationAccessOn: Boolean?,
)

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
        )
    }
}

class PairingException(message: String) : Exception(message)
