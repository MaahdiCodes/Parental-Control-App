package com.tvguardian.core.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Client for YouTube's "Link with TV code" remote-control protocol (the "lounge" API).
 * Unofficial: it's what phones use to control the YouTube app on smart TVs, so YouTube may change it.
 * Protocol details follow the open-source pyytlounge library.
 */
object Lounge {
    const val API_BASE = "https://www.youtube.com/api/lounge"

    data class Screen(val screenId: String, val name: String?, val loungeToken: String)

    /** Pairs using the code shown in the TV's YouTube app (Settings → Link with TV code). */
    suspend fun pairWithCode(http: OkHttpClient, code: String): Screen {
        val digits = code.filter(Char::isDigit)
        require(digits.length >= 10) { "The TV code is the 12-digit number in YouTube → Settings → Link with TV code." }
        val json = post(http, "$API_BASE/pairing/get_screen", mapOf("pairing_code" to digits))
            ?: throw LoungeException("YouTube didn't accept that code. Codes change after a while: check the TV and try again.")
        val screen = json["screen"]?.jsonObject ?: throw LoungeException("That code didn't match a TV.")
        return Screen(
            screenId = screen.string("screenId") ?: throw LoungeException("No screen id in YouTube's reply"),
            name = screen.string("name"),
            loungeToken = screen.string("loungeToken") ?: throw LoungeException("No token in YouTube's reply"),
        )
    }

    /** Lounge tokens expire; a paired screenId can always get a fresh one. */
    suspend fun refreshToken(http: OkHttpClient, screenId: String): Screen {
        val json = post(http, "$API_BASE/pairing/get_lounge_token_batch", mapOf("screen_ids" to screenId))
            ?: throw LoungeException("YouTube didn't answer the token refresh")
        val screen = json["screens"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LoungeException("This TV is no longer linked. Link it again with a new TV code.", notPaired = true)
        return Screen(
            screenId = screen.string("screenId") ?: screenId,
            name = screen.string("name"),
            loungeToken = screen.string("loungeToken") ?: throw LoungeException("No token in YouTube's reply"),
        )
    }

    /** True while the YouTube app is open on the TV. */
    suspend fun isOnline(http: OkHttpClient, loungeToken: String): Boolean {
        val json = post(http, "$API_BASE/pairing/get_screen_availability", mapOf("lounge_token" to loungeToken)) ?: return false
        return json["screens"]?.jsonArray?.any { (it as? JsonObject)?.string("status") == "online" } == true
    }

    private suspend fun post(http: OkHttpClient, url: String, form: Map<String, String>): JsonObject? =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                runCatching { Json.parseToJsonElement(response.body!!.string()).jsonObject }.getOrNull()
            }
        }

    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

class LoungeException(
    message: String,
    /** The lounge token expired: refresh it, then reconnect. */
    val tokenExpired: Boolean = false,
    /** The session ended on YouTube's side: reconnect. */
    val sessionLost: Boolean = false,
    /** The TV was unlinked: a new TV code is needed. */
    val notPaired: Boolean = false,
) : Exception(message)

/**
 * One live connection to a TV's YouTube app. Call [connect], then [listen] (blocks while connected,
 * delivering events), and [send] commands from anywhere meanwhile.
 */
class LoungeSession(
    private val http: OkHttpClient,
    /** Same client with a long read timeout, for the always-open listening request. */
    private val longPollHttp: OkHttpClient,
    private val loungeToken: String,
    private val deviceId: String,
    private val deviceName: String = "TV Guardian",
) {
    private var sid: String? = null
    private var gsessionId: String? = null
    private var lastEventId = 0L
    private var commandOffset = 1
    private val sendLock = Mutex()

    /** Opens the session; returns the first events (usually including the current video). */
    suspend fun connect(): List<LoungeEvent> {
        val url = bindUrl(mapOf("RID" to "1", "VER" to "8", "CVER" to "1", "auth_failure_option" to "send_error"))
        val form = mapOf(
            "app" to "web",
            "mdx-version" to "3",
            "name" to deviceName,
            "id" to deviceId,
            "device" to "REMOTE_CONTROL",
            "capabilities" to "que,dsdtr,atp,vsp",
            "magnaKey" to "cloudPairedDevice",
            "ui" to "false",
            "theme" to "cl",
            "loungeIdToken" to loungeToken,
        )
        val events = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).post(formBody(form)).build()).execute().use { response ->
                checkResponse(response)
                val parser = LoungeChunkParser()
                response.body!!.string().lineSequence().mapNotNull(parser::feed).flatMap(::accept).toList()
            }
        }
        if (sid == null || gsessionId == null) throw LoungeException("YouTube didn't open a session", sessionLost = true)
        commandOffset = 1
        return events
    }

    /**
     * Listens for events until the session ends (throws [LoungeException]) or the coroutine is cancelled.
     * YouTube closes each listening request after a while; we simply open the next one.
     */
    suspend fun listen(onEvent: suspend (LoungeEvent) -> Unit) {
        while (true) {
            val url = bindUrl(commonParams() + mapOf("RID" to "rpc", "CI" to "0", "TYPE" to "xmlhttp"))
            val call = longPollHttp.newCall(Request.Builder().url(url).get().build())
            cancellable(call) {
                call.execute().use { response ->
                    checkResponse(response)
                    val source = response.body!!.source()
                    val parser = LoungeChunkParser()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val chunk = parser.feed(line) ?: continue
                        for (event in accept(chunk)) {
                            onEvent(event)
                            if (event == LoungeEvent.ScreenDisconnected) {
                                throw LoungeException("YouTube was closed on the TV", sessionLost = true)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Sends a command such as "pause", or "setPlaylist" with videoId. */
    suspend fun send(command: String, params: Map<String, String> = emptyMap()) = sendLock.withLock {
        val offset = commandOffset
        val form = buildMap {
            put("count", "1")
            put("ofs", offset.toString())
            put("req0__sc", command)
            params.forEach { (key, value) -> put("req0_$key", value) }
        }
        withContext(Dispatchers.IO) {
            val url = bindUrl(commonParams() + mapOf("RID" to offset.toString()))
            http.newCall(Request.Builder().url(url).post(formBody(form)).build()).execute().use(::checkResponse)
        }
        commandOffset = offset + 1
    }

    /** Remembers session ids and the last event id; returns the events worth passing on. */
    private fun accept(chunk: JsonArray): List<LoungeEvent> =
        LoungeEventParser.parse(chunk).mapNotNull { raw ->
            lastEventId = maxOf(lastEventId, raw.id)
            when (val event = raw.event) {
                is LoungeEvent.SessionId -> { sid = event.sid; null }
                is LoungeEvent.GSessionId -> { gsessionId = event.gsessionId; null }
                else -> event
            }
        }

    private fun commonParams(): Map<String, String> = mapOf(
        "name" to deviceName,
        "loungeIdToken" to loungeToken,
        "SID" to (sid ?: ""),
        "AID" to lastEventId.toString(),
        "gsessionid" to (gsessionId ?: ""),
        "device" to "REMOTE_CONTROL",
        "app" to "youtube-desktop",
        "VER" to "8",
        "v" to "2",
    )

    private fun checkResponse(response: Response) {
        if (response.isSuccessful) return
        val reason = "${response.code} ${response.message}"
        throw when {
            response.code == 401 -> LoungeException("Link expired ($reason)", tokenExpired = true)
            response.code == 400 || response.code == 404 || response.code == 410 ->
                LoungeException("Session ended ($reason)", sessionLost = true)
            else -> LoungeException("YouTube error $reason", sessionLost = true)
        }
    }

    private fun bindUrl(params: Map<String, String>): HttpUrl =
        "${Lounge.API_BASE}/bc/bind".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private fun formBody(form: Map<String, String>) =
        FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()

    /** Runs a blocking OkHttp call so that cancelling the coroutine also aborts the network request. */
    private suspend fun cancellable(call: Call, block: suspend () -> Unit) = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            watcher.cancel()
        }
    }
}
