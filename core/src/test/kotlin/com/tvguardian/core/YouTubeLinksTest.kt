package com.tvguardian.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeLinksTest {
    private val id = "jNQXAC9IVRw"

    @Test
    fun `accepts the common link shapes`() {
        listOf(
            id,
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?feature=share&v=$id&t=10",
            "https://m.youtube.com/watch?v=$id",
            "youtube.com/watch?v=$id",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=abcDEF123",
            "https://www.youtube.com/shorts/$id",
            "https://music.youtube.com/watch?v=$id",
            "  https://youtu.be/$id  ",
        ).forEach { assertEquals(id, YouTubeLinks.videoId(it), "for input: $it") }
    }

    @Test
    fun `rejects non-YouTube or malformed input`() {
        listOf(
            "",
            "hello world",
            "https://example.com/watch?v=$id",
            "https://www.youtube.com/channel/UC123",
            "https://www.youtube.com/watch?v=short",
            "https://evil.com/youtu.be/$id",
        ).forEach { assertNull(YouTubeLinks.videoId(it), "for input: $it") }
    }

    @Test
    fun `finds the link inside shared text`() {
        assertEquals(id, YouTubeLinks.findVideoId("https://youtu.be/$id?si=abc"))
        assertEquals(id, YouTubeLinks.findVideoId("Check this out!\nMe at the zoo https://youtu.be/$id"))
        assertEquals(id, YouTubeLinks.findVideoId("Watch \"Me at the zoo\" on YouTube https://www.youtube.com/watch?v=$id"))
    }

    @Test
    fun `shared text without a youtube link finds nothing`() {
        assertNull(YouTubeLinks.findVideoId("Interesting article https://example.com/page"))
        assertNull(YouTubeLinks.findVideoId("Interesting")) // 11 letters, but not a link
    }

    @Test
    fun `builds a watch url`() {
        assertEquals("https://www.youtube.com/watch?v=$id", YouTubeLinks.watchUrl(id))
    }
}
