package com.tvguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchSessionTrackerTest {
    private val video1 = WatchItem("com.google.android.youtube.tv", "Video one", "Channel A")
    private val video2 = WatchItem("com.google.android.youtube.tv", "Video two", "Channel B")

    @Test
    fun `session ends when the title changes and counts only playing time`() {
        val tracker = WatchSessionTracker()
        assertNull(tracker.update(video1, playing = true, nowMillis = 0))
        assertNull(tracker.update(video1, playing = false, nowMillis = 60_000)) // paused after 60s
        assertNull(tracker.update(video1, playing = true, nowMillis = 120_000)) // resumed after a 60s pause
        val session = assertNotNull(tracker.update(video2, playing = true, nowMillis = 150_000))
        assertEquals(video1, session.item)
        assertEquals(0, session.startedAtMillis)
        assertEquals(150_000, session.endedAtMillis)
        assertEquals(90_000, session.watchedMillis)
    }

    @Test
    fun `session ends when playback goes away`() {
        val tracker = WatchSessionTracker()
        tracker.update(video1, playing = true, nowMillis = 0)
        val session = assertNotNull(tracker.update(null, playing = false, nowMillis = 30_000))
        assertEquals(30_000, session.watchedMillis)
        assertNull(tracker.update(null, playing = false, nowMillis = 40_000))
    }

    @Test
    fun `short sessions are dropped`() {
        val tracker = WatchSessionTracker(minWatchedMillis = 10_000)
        tracker.update(video1, playing = true, nowMillis = 0)
        assertNull(tracker.update(video2, playing = true, nowMillis = 5_000))
    }

    @Test
    fun `a paused-only session is dropped`() {
        val tracker = WatchSessionTracker()
        tracker.update(video1, playing = false, nowMillis = 0)
        assertNull(tracker.finish(nowMillis = 600_000))
    }

    @Test
    fun `finish closes the current session`() {
        val tracker = WatchSessionTracker()
        tracker.update(video1, playing = true, nowMillis = 1_000)
        val session = assertNotNull(tracker.finish(nowMillis = 61_000))
        assertEquals(60_000, session.watchedMillis)
        assertNull(tracker.finish(nowMillis = 70_000))
    }
}
