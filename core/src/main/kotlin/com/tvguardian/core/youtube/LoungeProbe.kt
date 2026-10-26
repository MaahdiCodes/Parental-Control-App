package com.tvguardian.core.youtube

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Developer tool for trying the YouTube link from a PC (read-only: it never pauses or changes anything).
 *   pair <12-digit TV code>        -> prints the screenId to use below
 *   watch <screenId> [seconds]     -> prints what the TV's YouTube app reports
 */
fun main(args: Array<String>) = runBlocking {
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val longPoll = http.newBuilder().readTimeout(3, TimeUnit.MINUTES).build()
    when (args.firstOrNull()) {
        "pair" -> {
            val screen = Lounge.pairWithCode(http, args.getOrNull(1) ?: error("usage: pair <code>"))
            println("Paired with \"${screen.name}\"")
            println("screenId: ${screen.screenId}")
        }
        "watch" -> {
            val screenId = args.getOrNull(1) ?: error("usage: watch <screenId> [seconds]")
            val seconds = args.getOrNull(2)?.toLongOrNull() ?: 60
            val screen = Lounge.refreshToken(http, screenId)
            println("Token refreshed for \"${screen.name}\". YouTube open on TV: ${Lounge.isOnline(http, screen.loungeToken)}")
            val session = LoungeSession(http, longPoll, screen.loungeToken, deviceId = UUID.randomUUID().toString(), deviceName = "TV Guardian (test)")
            session.connect().forEach { println("connect: $it") }
            session.send("getNowPlaying")
            withTimeoutOrNull(seconds * 1000) {
                session.listen { event ->
                    println("event: $event")
                    if (event is LoungeEvent.NowPlaying && event.videoId != null) {
                        println("   -> ${VideoInfoLookup.lookup(http, event.videoId)}")
                    }
                }
            }
            println("Done.")
        }
        else -> println("usage: pair <code> | watch <screenId> [seconds]")
    }
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()
}
