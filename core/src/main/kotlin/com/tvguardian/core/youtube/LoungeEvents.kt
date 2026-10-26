package com.tvguardian.core.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Playback states reported by the TV's YouTube app (numbers from the lounge protocol). */
enum class LoungePlayback(val code: Int, val label: String) {
    STOPPED(-1, "STOPPED"),
    ENDED(0, "ENDED"),
    PLAYING(1, "PLAYING"),
    PAUSED(2, "PAUSED"),
    BUFFERING(3, "BUFFERING"),
    CUED(5, "CUED"),
    ADVERT(1081, "ADVERT"),
    ADVERT_SKIPPED(1082, "ADVERT_SKIPPED");

    companion object {
        fun parse(value: String?): LoungePlayback =
            value?.toIntOrNull()?.let { code -> entries.firstOrNull { it.code == code } } ?: STOPPED
    }
}

/** What the TV's YouTube app tells us. */
sealed interface LoungeEvent {
    /** Session ids from the connection ("c" and "S" events) — handled inside the session. */
    data class SessionId(val sid: String) : LoungeEvent
    data class GSessionId(val gsessionId: String) : LoungeEvent

    /** Current video (videoId is null when nothing is loaded). */
    data class NowPlaying(val videoId: String?, val state: LoungePlayback, val currentTime: Double?, val duration: Double?) : LoungeEvent
    data class StateChanged(val state: LoungePlayback, val currentTime: Double?, val duration: Double?) : LoungeEvent
    data class VolumeChanged(val volume: Int, val muted: Boolean) : LoungeEvent
    data class ScreenStatus(val screenName: String?) : LoungeEvent

    /** The YouTube app on the TV closed (or the TV went off). */
    data object ScreenDisconnected : LoungeEvent
    data class Other(val type: String) : LoungeEvent
}

/**
 * Splits the lounge's streamed response into JSON chunks. The stream looks like
 *   123
 *   [[0,["c","SID","",8]],[1,["S","gsession"]], ...]
 * i.e. a length line, then a JSON array of [eventId, [type, payload...]] (possibly over several lines).
 */
class LoungeChunkParser {
    private var remaining = -1
    private val buffer = StringBuilder()

    /** Feed one line; returns the chunk's events once complete, otherwise null. */
    fun feed(line: String): JsonArray? {
        if (remaining < 0) {
            line.trim().toIntOrNull()?.let {
                remaining = it
                buffer.clear()
            }
            return null
        }
        buffer.append(line).append('\n')
        remaining -= line.length + 1
        if (remaining > 0) return null
        // Lengths are counted by the server; if our count is off, keep reading until it parses.
        val parsed = runCatching { Json.parseToJsonElement(buffer.toString()).jsonArray }.getOrNull() ?: return null
        remaining = -1
        buffer.clear()
        return parsed
    }
}

/** One raw event: [eventId, [type, payload?]]. */
data class RawLoungeEvent(val id: Long, val event: LoungeEvent)

object LoungeEventParser {
    fun parse(chunk: JsonArray): List<RawLoungeEvent> = chunk.mapNotNull { entry ->
        runCatching {
            val pair = entry.jsonArray
            val id = pair[0].jsonPrimitive.longOrNull ?: return@runCatching null
            val body = pair[1].jsonArray
            val type = body[0].jsonPrimitive.content
            val payload = body.getOrNull(1)
            RawLoungeEvent(id, toEvent(type, payload))
        }.getOrNull()
    }

    private fun toEvent(type: String, payload: JsonElement?): LoungeEvent {
        val data = payload as? JsonObject
        return when (type) {
            "c" -> LoungeEvent.SessionId((payload as JsonPrimitive).content)
            "S" -> LoungeEvent.GSessionId((payload as JsonPrimitive).content)
            "nowPlaying" -> LoungeEvent.NowPlaying(
                videoId = data?.string("videoId")?.takeIf { it.isNotBlank() },
                state = LoungePlayback.parse(data?.string("state")),
                currentTime = data?.string("currentTime")?.toDoubleOrNull(),
                duration = data?.string("duration")?.toDoubleOrNull(),
            )
            "onStateChange" -> LoungeEvent.StateChanged(
                state = LoungePlayback.parse(data?.string("state")),
                currentTime = data?.string("currentTime")?.toDoubleOrNull(),
                duration = data?.string("duration")?.toDoubleOrNull(),
            )
            "onVolumeChanged" -> LoungeEvent.VolumeChanged(
                volume = data?.string("volume")?.toIntOrNull() ?: 0,
                muted = data?.string("muted") == "true",
            )
            "loungeStatus" -> LoungeEvent.ScreenStatus(screenName(data))
            "loungeScreenDisconnected" -> LoungeEvent.ScreenDisconnected
            else -> LoungeEvent.Other(type)
        }
    }

    /** loungeStatus.devices is a JSON *string* listing connected devices; the TV is type LOUNGE_SCREEN. */
    private fun screenName(data: JsonObject?): String? = runCatching {
        val devices = Json.parseToJsonElement(data?.string("devices") ?: return null).jsonArray
        devices.map { it.jsonObject }.firstOrNull { it.string("type") == "LOUNGE_SCREEN" }?.string("name")
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
