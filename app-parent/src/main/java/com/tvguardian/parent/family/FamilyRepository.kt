package com.tvguardian.parent.family

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.tvguardian.core.FirestorePaths
import com.tvguardian.parent.changes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Date

data class FamilyInfo(
    val familyId: String,
    /** Parents who joined with an invite (the owner isn't in this list). */
    val joinedParents: List<String>,
    /** uid -> email, for showing who's in the family. */
    val emails: Map<String, String>,
)

/**
 * A family is the set of TVs one account owns (families/{owner uid}). Other parents can join it
 * with an invite code and then see and control the same TVs with their own Google account.
 */
object FamilyRepository {
    private const val INVITE_VALID_MILLIS = 24 * 60 * 60 * 1000L
    private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O or 1/I mix-ups
    private val random = SecureRandom()
    private val db get() = FirebaseFirestore.getInstance()

    private fun userDoc(uid: String) = db.document("users/$uid")

    /** The family this account uses: its own unless it joined another parent's. */
    fun familyId(uid: String): Flow<String> =
        userDoc(uid).changes()
            .map { it.getString("familyId") ?: uid }
            .catch { emit(uid) }
            .distinctUntilChanged()

    suspend fun currentFamilyId(uid: String): String =
        runCatching { userDoc(uid).get().await().getString("familyId") }.getOrNull() ?: uid

    fun family(familyId: String): Flow<FamilyInfo?> =
        db.document(FirestorePaths.family(familyId)).changes()
            .map<_, FamilyInfo?> { doc ->
                FamilyInfo(
                    familyId = familyId,
                    joinedParents = (doc.get("parents") as? List<*>).orEmpty().filterIsInstance<String>(),
                    emails = (doc.get("parentEmails") as? Map<*, *>).orEmpty()
                        .mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }
                        .toMap(),
                )
            }
            .catch { emit(null) }

    /** Creates a code another parent can use to join this family within 24 hours. */
    suspend fun createInvite(familyId: String, me: FirebaseUser): String {
        val code = (1..8).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")
        db.batch()
            .set(
                db.document(FirestorePaths.family(familyId)),
                mapOf("parentEmails" to mapOf(me.uid to (me.email ?: "?"))),
                SetOptions.merge(),
            )
            .set(
                db.document("invites/$code"),
                mapOf(
                    "familyId" to familyId,
                    "createdBy" to me.uid,
                    "expiresAt" to Timestamp(Date(System.currentTimeMillis() + INVITE_VALID_MILLIS)),
                ),
            )
            .commit()
            .await()
        return code
    }

    /** Joins the family an invite code belongs to. */
    suspend fun join(code: String, me: FirebaseUser) {
        val cleaned = code.uppercase().filter { it.isLetterOrDigit() }
        val invite = runCatching { db.document("invites/$cleaned").get().await() }.getOrNull()
        val familyId = invite?.getString("familyId")
        val expiresAt = invite?.getTimestamp("expiresAt")?.toDate()?.time ?: 0
        when {
            familyId == null -> throw FamilyException("That invite code doesn't exist. Check it and try again.")
            expiresAt < System.currentTimeMillis() -> throw FamilyException("That invite has expired. Ask for a new one.")
            familyId == me.uid -> throw FamilyException("That's your own family's invite.")
        }
        try {
            db.batch()
                .update(
                    db.document(FirestorePaths.family(familyId!!)),
                    FieldPath.of("parents"), FieldValue.arrayUnion(me.uid),
                    FieldPath.of("parentEmails", me.uid), me.email ?: "?",
                    FieldPath.of("lastJoinCode"), cleaned,
                )
                .set(userDoc(me.uid), mapOf("familyId" to familyId))
                .commit()
                .await()
        } catch (e: FirebaseFirestoreException) {
            throw FamilyException(
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "Couldn't join: you may already be in this family."
                else "Couldn't join: ${e.message}"
            )
        }
    }

    /** Leaves a family you joined and goes back to your own. */
    suspend fun leave(familyId: String, me: FirebaseUser) {
        db.batch()
            .update(
                db.document(FirestorePaths.family(familyId)),
                FieldPath.of("parents"), FieldValue.arrayRemove(me.uid),
                FieldPath.of("parentEmails", me.uid), FieldValue.delete(),
            )
            .set(userDoc(me.uid), mapOf("familyId" to me.uid))
            .commit()
            .await()
    }

    /** The owner removes a parent who joined. */
    suspend fun removeParent(familyId: String, uid: String) {
        db.document(FirestorePaths.family(familyId)).update(
            FieldPath.of("parents"), FieldValue.arrayRemove(uid),
            FieldPath.of("parentEmails", uid), FieldValue.delete(),
        ).await()
    }

    /** If this account was removed from a family it had joined, go back to its own. */
    suspend fun resetToOwnFamily(uid: String) {
        userDoc(uid).set(mapOf("familyId" to uid)).await()
    }
}

class FamilyException(message: String) : Exception(message)
