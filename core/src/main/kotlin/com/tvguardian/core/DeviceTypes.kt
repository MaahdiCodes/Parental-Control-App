package com.tvguardian.core

/** The `type` field of a device doc. */
object DeviceTypes {
    /** A Google TV / Android TV running the TV Guardian agent: full control. */
    const val GOOGLE_TV = "googletv"

    /** Any TV whose YouTube app is linked with a "Link with TV code" code (e.g. LG): YouTube only. */
    const val YOUTUBE_LINK = "youtube"
}
