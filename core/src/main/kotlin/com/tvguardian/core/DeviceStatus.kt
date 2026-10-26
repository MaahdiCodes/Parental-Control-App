package com.tvguardian.core

/** Online/offline is derived from the TV's heartbeat (`lastSeen`). */
object DeviceStatus {
    const val HEARTBEAT_MILLIS = 60_000L
    const val OFFLINE_AFTER_MILLIS = 3 * 60_000L

    fun isOnline(lastSeenMillis: Long?, nowMillis: Long): Boolean =
        lastSeenMillis != null && nowMillis - lastSeenMillis < OFFLINE_AFTER_MILLIS

    fun describeLastSeen(lastSeenMillis: Long?, nowMillis: Long): String {
        if (lastSeenMillis == null) return "never"
        val minutes = (nowMillis - lastSeenMillis).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}
