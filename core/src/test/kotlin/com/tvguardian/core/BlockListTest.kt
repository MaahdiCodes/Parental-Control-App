package com.tvguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockListTest {
    @Test
    fun `blocked channel matches regardless of case and spaces`() {
        val list = BlockList(channels = listOf("Bad Channel"))
        assertEquals("channel", list.reasonFor("Any title", " bad channel ", null))
        assertNull(list.reasonFor("Any title", "Good Channel", null))
    }

    @Test
    fun `blocked video matches by id when both sides know it`() {
        val list = BlockList(videos = listOf(BlockedVideo("Old title", "Ch", "abc123DEF45")))
        assertEquals("video", list.reasonFor("Renamed title", "Ch", "abc123DEF45"))
        assertNull(list.reasonFor("Old title", "Ch", "zzz123DEF45"))
    }

    @Test
    fun `blocked video matches by title and channel on a Google TV`() {
        val list = BlockList(videos = listOf(BlockedVideo("Scary Video", "Ch", "abc123DEF45")))
        assertEquals("video", list.reasonFor("scary video", "Ch", null))
        assertNull(list.reasonFor("Scary Video", "Other channel", null))
    }

    @Test
    fun `nothing playing is never blocked`() {
        val list = BlockList(channels = listOf("X"), videos = listOf(BlockedVideo("Y", null, null)))
        assertNull(list.reasonFor(null, null, null))
    }

    @Test
    fun `reads firestore data and skips malformed entries`() {
        val list = BlockList.fromFirestore(
            listOf("A", 5),
            listOf(mapOf("title" to "T", "channel" to "C", "videoId" to null), mapOf("channel" to "no title"), "junk"),
        )
        assertEquals(listOf("A"), list.channels)
        assertEquals(listOf(BlockedVideo("T", "C", null)), list.videos)
    }
}
