package com.tvguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceStatusTest {
    private val now = 1_000_000_000L

    @Test
    fun `online until three minutes without a heartbeat`() {
        assertTrue(DeviceStatus.isOnline(now - 10_000, now))
        assertTrue(DeviceStatus.isOnline(now - 179_000, now))
        assertFalse(DeviceStatus.isOnline(now - 180_000, now))
        assertFalse(DeviceStatus.isOnline(null, now))
    }

    @Test
    fun `last seen descriptions`() {
        assertEquals("never", DeviceStatus.describeLastSeen(null, now))
        assertEquals("just now", DeviceStatus.describeLastSeen(now - 30_000, now))
        assertEquals("5 min ago", DeviceStatus.describeLastSeen(now - 5 * 60_000, now))
        assertEquals("2 h ago", DeviceStatus.describeLastSeen(now - 125 * 60_000, now))
        assertEquals("3 days ago", DeviceStatus.describeLastSeen(now - 3 * 24 * 60 * 60_000L, now))
    }

    @Test
    fun `clock skew in the future reads as just now`() {
        assertEquals("just now", DeviceStatus.describeLastSeen(now + 60_000, now))
        assertTrue(DeviceStatus.isOnline(now + 60_000, now))
    }
}
