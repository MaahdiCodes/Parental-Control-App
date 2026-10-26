package com.tvguardian.parent.youtube

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Shared HTTP clients for talking to YouTube. */
internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** For the listening request that YouTube holds open while it waits for something to happen. */
    val longPoll: OkHttpClient = client.newBuilder().readTimeout(3, TimeUnit.MINUTES).build()
}

/** This phone's id as a YouTube "remote", kept so the TV sees one remote rather than a new one each time. */
internal fun remoteDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences("youtube_link", Context.MODE_PRIVATE)
    return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("deviceId", it).apply()
    }
}

/** ~240px-wide JPEG as base64, the same thumbnail format the TV agent stores. */
internal fun shrinkThumbnail(bytes: ByteArray): String? = runCatching {
    val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val width = 240
    val height = (image.height * width / image.width.coerceAtLeast(1)).coerceIn(1, 480)
    val scaled = Bitmap.createScaledBitmap(image, width, height, true)
    val out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()
