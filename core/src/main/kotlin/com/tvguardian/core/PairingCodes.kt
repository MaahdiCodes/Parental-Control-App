package com.tvguardian.core

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** The 6-digit code (and matching QR code) a TV shows until a parent pairs it. */
object PairingCodes {
    const val LENGTH = 6
    const val VALIDITY_MILLIS = 10 * 60 * 1000L

    private const val QR_PREFIX = "TVGUARDIAN:PAIR:"
    private val secureRandom = SecureRandom().asKotlinRandom()

    fun generate(random: Random = secureRandom): String =
        (0 until LENGTH).joinToString("") { random.nextInt(10).toString() }

    fun qrPayload(code: String): String = QR_PREFIX + code

    /** "123456" -> "123 456", easier to read off a TV screen. */
    fun display(code: String): String = code.chunked(3).joinToString(" ")

    /**
     * Accepts a scanned QR payload or a typed code (spaces and dashes ignored).
     * Returns the bare 6-digit code, or null if the input isn't one.
     */
    fun parse(input: String): String? {
        val raw = input.trim().removePrefix(QR_PREFIX).filterNot { it == ' ' || it == '-' }
        return raw.takeIf { it.length == LENGTH && it.all(Char::isDigit) }
    }
}
