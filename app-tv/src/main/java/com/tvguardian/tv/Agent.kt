package com.tvguardian.tv

import android.content.Context
import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.tvguardian.core.DeviceStatus
import com.tvguardian.core.FirestorePaths
import com.tvguardian.core.PairingCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/**
 * The TV's connection to the cloud. Runs for the life of the app process (kept alive by the
 * accessibility service):
 *  1. signs in anonymously, so the TV's own Google account is never involved;
 *  2. while unpaired, publishes a pairing code and waits for a parent to claim it;
 *  3. while paired, sends a heartbeat every minute and watches for being removed by the parent.
 */
object Agent {
    sealed interface State {
        data object Starting : State
        data class Unpaired(val code: String, val expiresAtMillis: Long) : State
        data class Paired(val familyId: String, val deviceName: String?) : State
        data class Error(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Starting)

    private const val PREFS = "agent"
    private const val KEY_FAMILY_ID = "familyId"
    private const val RETRY_DELAY_MILLIS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var appContext: Context
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context) {
        appContext = context.applicationContext
        scope.launch { runForever() }
    }

    private suspend fun runForever() {
        while (true) {
            try {
                val uid = ensureSignedIn()
                val familyId = prefs.getString(KEY_FAMILY_ID, null)
                    ?: waitForPairing(uid).also { prefs.edit().putString(KEY_FAMILY_ID, it).apply() }
                runPaired(uid, familyId)
                prefs.edit().remove(KEY_FAMILY_ID).apply()
                SpikeState.log("This TV was removed by the parent: showing a new pairing code")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = State.Error(e.message ?: e.javaClass.simpleName)
                SpikeState.log("Cloud error: ${e.message}. Retrying in ${RETRY_DELAY_MILLIS / 1000}s")
                delay(RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun ensureSignedIn(): String {
        auth.currentUser?.let { return it.uid }
        val uid = auth.signInAnonymously().await().user!!.uid
        SpikeState.log("Signed in to the cloud (anonymous TV account)")
        return uid
    }

    /** Publishes pairing codes (a fresh one every 10 minutes) until a parent claims one; returns the familyId. */
    private suspend fun waitForPairing(uid: String): String {
        while (true) {
            val code = PairingCodes.generate()
            val ref = db.collection(FirestorePaths.PAIRING_CODES).document(code)
            val expiresAt = System.currentTimeMillis() + PairingCodes.VALIDITY_MILLIS
            try {
                ref.set(
                    mapOf(
                        "agentUid" to uid,
                        "model" to deviceModel(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "expiresAt" to Timestamp(Date(expiresAt)),
                        "familyId" to null,
                    )
                ).await()
            } catch (e: FirebaseFirestoreException) {
                // Denied when another TV is already using this code: just pick another.
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) continue
                throw e
            }
            state.value = State.Unpaired(code, expiresAt)
            SpikeState.log("Pairing code ${PairingCodes.display(code)} is ready")

            val familyId = withTimeoutOrNull(expiresAt - System.currentTimeMillis()) {
                ref.changes().mapNotNull { it.getString("familyId") }.first()
            }
            runCatching { ref.delete().await() }
            if (familyId != null) {
                SpikeState.log("Paired with a parent's phone")
                return familyId
            }
        }
    }

    /** Returns when the parent removes this TV. */
    private suspend fun runPaired(uid: String, familyId: String) = coroutineScope {
        val deviceRef = db.document(FirestorePaths.device(familyId, uid))
        state.value = State.Paired(familyId, deviceName = null)

        val sync = PairedSync(appContext, deviceRef)
        val jobs = listOf(
            launch {
                while (true) {
                    runCatching { deviceRef.update(heartbeatFields()).await() }
                        .onFailure { SpikeState.log("Heartbeat failed: ${it.message}") }
                    delay(DeviceStatus.HEARTBEAT_MILLIS)
                }
            },
            launch { sync.publishNowWatching() },
            launch { sync.runCommands() },
            launch { sync.recordHistory() },
        )

        // A cached "doesn't exist" can be stale, so only trust removal when the server says so.
        deviceRef.changes().first { snapshot ->
            if (snapshot.exists()) state.value = State.Paired(familyId, snapshot.getString("name"))
            !snapshot.exists() && !snapshot.metadata.isFromCache
        }
        jobs.forEach { it.cancel() }
        // Removed by the parent: a lock they set no longer has anyone to lift it.
        if (TvLock.locked.value) TvLock.unlock()
    }

    private fun heartbeatFields(): Map<String, Any> = mapOf(
        "lastSeen" to FieldValue.serverTimestamp(),
        "model" to deviceModel(),
        "osVersion" to "Android ${Build.VERSION.RELEASE}",
        "agentVersion" to agentVersion(),
        "protection" to mapOf(
            "accessibility" to (GuardAccessibilityService.instance != null),
            "notificationAccess" to (MediaWatcherService.instance != null),
        ),
    )

    private fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun agentVersion(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }
            .getOrNull() ?: "?"
}
