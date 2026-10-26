package com.tvguardian.tv

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Reads a parent's text message aloud with the TV's text-to-speech engine (if it has one). */
object TvSpeech {
    private const val INIT_TIMEOUT_MILLIS = 5_000L

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    /** Returns false if this TV can't speak (no text-to-speech engine). */
    suspend fun speak(context: Context, text: String): Boolean {
        val initialised = ready ?: CompletableDeferred<Boolean>().also { deferred ->
            ready = deferred
            tts = TextToSpeech(context.applicationContext) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { initialised.await() } ?: false
        if (!ok) {
            // Try again from scratch next time.
            tts?.shutdown()
            tts = null
            ready = null
            return false
        }
        return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "parent-message") == TextToSpeech.SUCCESS
    }
}
