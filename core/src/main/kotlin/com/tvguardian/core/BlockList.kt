package com.tvguardian.core

/** A video the parent blocked. Google TVs only know title + channel; YouTube-linked TVs also know the id. */
data class BlockedVideo(val title: String, val channel: String?, val videoId: String?)

/**
 * Videos and channels the parent blocked, for the whole family (stored on families/{id} as
 * `blockedChannels: [string]` and `blockedVideos: [{title, channel, videoId}]`).
 */
data class BlockList(
    val channels: List<String> = emptyList(),
    val videos: List<BlockedVideo> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && videos.isEmpty()

    /** Why this video is blocked ("channel" / "video"), or null if it isn't. */
    fun reasonFor(title: String?, channel: String?, videoId: String?): String? {
        if (channel != null && channels.any { same(it, channel) }) return "channel"
        val blocked = videos.any { video ->
            if (video.videoId != null && videoId != null) {
                video.videoId == videoId
            } else {
                title != null && same(video.title, title) && (video.channel == null || channel == null || same(video.channel, channel))
            }
        }
        return if (blocked) "video" else null
    }

    companion object {
        fun fromFirestore(channels: Any?, videos: Any?) = BlockList(
            channels = (channels as? List<*>).orEmpty().filterIsInstance<String>(),
            videos = (videos as? List<*>).orEmpty().mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                BlockedVideo(
                    title = map["title"] as? String ?: return@mapNotNull null,
                    channel = map["channel"] as? String,
                    videoId = map["videoId"] as? String,
                )
            },
        )

        fun toFirestore(video: BlockedVideo): Map<String, String?> =
            mapOf("title" to video.title, "channel" to video.channel, "videoId" to video.videoId)

        private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
    }
}
