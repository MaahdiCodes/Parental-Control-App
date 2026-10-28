package com.tvguardian.parent.watch

import android.content.Context
import com.tvguardian.parent.devices.TvDevice
import com.tvguardian.parent.youtube.YouTubeLinkManager
import com.tvguardian.parent.youtube.YouTubeLinkService

/** Sends a command the right way for the kind of TV. Returns a short message for the parent. */
object CommandRouter {
    suspend fun send(
        context: Context,
        familyId: String,
        device: TvDevice,
        type: String,
        args: Map<String, String> = emptyMap(),
    ): String = if (device.isYouTubeLink) {
        // This phone talks to the TV's YouTube app directly; make sure the link is running.
        YouTubeLinkService.start(context)
        YouTubeLinkManager.execute(context, familyId, device, type, args)
    } else {
        // The TV Guardian agent on the TV picks it up from the cloud.
        WatchRepository.sendCommand(familyId, device.id, type, args)
    }
}
