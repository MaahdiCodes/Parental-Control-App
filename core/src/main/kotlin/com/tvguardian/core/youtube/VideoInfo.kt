package com.tvguardian.core.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class VideoInfo(val title: String, val channel: String?, val thumbnailUrl: String?)

/** Title/channel/thumbnail for a video ID via YouTube's public oEmbed endpoint (free, no API key). */
object VideoInfoLookup {
    suspend fun lookup(http: OkHttpClient, videoId: String): VideoInfo? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url", "https://www.youtube.com/watch?v=$videoId")
            .addQueryParameter("format", "json")
            .build()
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null // private or removed videos
                val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
                fun field(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
                VideoInfo(
                    title = field("title") ?: return@use null,
                    channel = field("author_name"),
                    thumbnailUrl = field("thumbnail_url"),
                )
            }
        }.getOrNull()
    }

    /** Downloads a thumbnail's bytes (for the app to shrink and store). */
    suspend fun download(http: OkHttpClient, url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body!!.bytes() else null
            }
        }.getOrNull()
    }
}
