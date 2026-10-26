package com.tvguardian.core

import java.net.URI

/** Turns whatever a parent pastes (share link, watch URL, Shorts link, bare ID) into a YouTube video ID. */
object YouTubeLinks {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun videoId(input: String): String? {
        val text = input.trim()
        if (VIDEO_ID.matches(text)) return text
        val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()
        val candidate = when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/')
            "youtube.com", "music.youtube.com" -> when {
                path == "/watch" -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
                listOf("/shorts/", "/live/", "/embed/").any { path.startsWith(it) } -> path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }

    /**
     * Finds a YouTube link inside shared text (apps sometimes add a title or message around it).
     * Only tokens that look like YouTube links count: a bare word could pass for an 11-character ID.
     */
    fun findVideoId(sharedText: String): String? =
        sharedText.split(Regex("\\s+"))
            .filter { "youtu" in it.lowercase() }
            .firstNotNullOfOrNull { videoId(it) }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}
