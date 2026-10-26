package com.tvguardian.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandsTest {
    @Test
    fun `commands older than two minutes are stale`() {
        assertTrue(Commands.isStale(0, Commands.STALE_AFTER_MILLIS + 1))
        assertFalse(Commands.isStale(0, 30_000))
    }

    @Test
    fun `missing timestamp is not stale`() {
        assertFalse(Commands.isStale(null, 30_000))
    }
}
