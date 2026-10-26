package com.tvguardian.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingCodesTest {
    @Test
    fun `generated codes are six digits`() {
        repeat(100) {
            val code = PairingCodes.generate(Random(it))
            assertEquals(6, code.length)
            assertTrue(code.all(Char::isDigit))
        }
    }

    @Test
    fun `qr payload round-trips through parse`() {
        assertEquals("042917", PairingCodes.parse(PairingCodes.qrPayload("042917")))
    }

    @Test
    fun `typed codes tolerate spaces and dashes`() {
        assertEquals("123456", PairingCodes.parse(" 123 456 "))
        assertEquals("123456", PairingCodes.parse("123-456"))
    }

    @Test
    fun `rejects anything that is not a six digit code`() {
        assertNull(PairingCodes.parse("12345"))
        assertNull(PairingCodes.parse("1234567"))
        assertNull(PairingCodes.parse("12a456"))
        assertNull(PairingCodes.parse("https://example.com"))
        assertNull(PairingCodes.parse(""))
    }

    @Test
    fun `display splits the code in two`() {
        assertEquals("123 456", PairingCodes.display("123456"))
    }
}
