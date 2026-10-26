package com.tvguardian.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether the parent has locked the TV. Saved, so a locked TV stays locked after a restart.
 * GuardAccessibilityService draws/removes the lock screen whenever this changes.
 */
object TvLock {
    private const val PREFS = "lock"
    private const val KEY_LOCKED = "locked"
    private const val KEY_MESSAGE = "message"
    const val DEFAULT_MESSAGE = "This TV is locked by a parent."

    val locked = MutableStateFlow(false)
    var message: String = DEFAULT_MESSAGE
        private set

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        message = prefs.getString(KEY_MESSAGE, null) ?: DEFAULT_MESSAGE
        locked.value = prefs.getBoolean(KEY_LOCKED, false)
    }

    fun lock(lockMessage: String?) {
        message = lockMessage?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE
        prefs.edit().putBoolean(KEY_LOCKED, true).putString(KEY_MESSAGE, message).apply()
        locked.value = true
        SpikeState.log("TV locked by parent")
    }

    fun unlock() {
        prefs.edit().putBoolean(KEY_LOCKED, false).remove(KEY_MESSAGE).apply()
        locked.value = false
        SpikeState.log("TV unlocked by parent")
    }
}
