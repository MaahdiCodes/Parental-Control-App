package com.tvguardian.core

data class WatchItem(val packageName: String, val title: String, val channel: String?)

data class WatchSession(
    val item: WatchItem,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    /** Time actually spent playing (pauses excluded). */
    val watchedMillis: Long,
)

/**
 * Turns the stream of "what's playing" updates into watch-history sessions.
 * A session is one title in one app; it ends when the title changes or playback goes away.
 * Sessions with less than [minWatchedMillis] of actual playing are dropped (skipped or flicked past).
 */
class WatchSessionTracker(private val minWatchedMillis: Long = 10_000) {
    private var current: WatchItem? = null
    private var startedAt = 0L
    private var watched = 0L
    private var playingSince: Long? = null

    /** Feed every change. Returns the session that just ended, if it is worth keeping. */
    fun update(item: WatchItem?, playing: Boolean, nowMillis: Long): WatchSession? {
        var finished: WatchSession? = null
        if (item != current) {
            finished = finish(nowMillis)
            current = item
            startedAt = nowMillis
            watched = 0
        }
        if (current != null) {
            val since = playingSince
            if (playing && since == null) playingSince = nowMillis
            if (!playing && since != null) {
                watched += nowMillis - since
                playingSince = null
            }
        }
        return finished
    }

    /** Ends the current session (e.g. the agent is stopping). */
    fun finish(nowMillis: Long): WatchSession? {
        val item = current ?: return null
        val total = watched + (playingSince?.let { nowMillis - it } ?: 0)
        current = null
        playingSince = null
        watched = 0
        return WatchSession(item, startedAt, nowMillis, total).takeIf { total >= minWatchedMillis }
    }
}
