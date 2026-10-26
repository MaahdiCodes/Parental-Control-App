package com.tvguardian.tv

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** "com.google.android.youtube.tv" -> "YouTube", cached. */
object AppNames {
    private val cache = ConcurrentHashMap<String, String>()

    fun label(context: Context, packageName: String): String = cache.getOrPut(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
