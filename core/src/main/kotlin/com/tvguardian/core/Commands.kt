package com.tvguardian.core

/** Commands the parent app sends and the TV executes (stored as `type` on a command doc). */
object Commands {
    const val PAUSE = "PAUSE"
    const val PLAY = "PLAY"
    const val STOP = "STOP"
    const val HOME = "HOME"
    const val BACK = "BACK"
    const val CLOSE_APP = "CLOSE_APP"
    const val LOCK = "LOCK"
    const val UNLOCK = "UNLOCK"
    const val MESSAGE = "MESSAGE"
    const val VOICE = "VOICE"
    const val PLAY_URL = "PLAY_URL"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val MUTE = "MUTE"

    const val ARG_TEXT = "text"
    const val ARG_URL = "url"

    /** MESSAGE: "true" to also read the text aloud on the TV. */
    const val ARG_SPEAK = "speak"

    /** VOICE: the recording (AAC in MP4, base64). Kept short so the command doc stays well under Firestore's 1 MB. */
    const val ARG_AUDIO = "audio"
    const val VOICE_MAX_SECONDS = 30
    const val VOICE_MAX_BASE64_CHARS = 700_000

    const val STATUS_PENDING = "pending"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"
    const val STATUS_EXPIRED = "expired"

    /** A command the TV only sees later (e.g. it was switched off) is dropped rather than run by surprise. */
    const val STALE_AFTER_MILLIS = 2 * 60_000L

    fun isStale(createdAtMillis: Long?, nowMillis: Long): Boolean =
        createdAtMillis != null && nowMillis - createdAtMillis > STALE_AFTER_MILLIS
}
