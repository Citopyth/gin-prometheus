package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecibelsTest {

    @Test
    fun `the scale matches the bytes the speaker was seen to hold`() {
        assertEquals(0.0, Decibels.fromRaw(120))
        assertEquals(120, Decibels.toRaw(0.0))
        assertEquals(0x78, Decibels.NEUTRAL_RAW)
    }

    @Test
    fun `the byte that wrecked the sound decodes to what it was`() {
        // 0xf0 went to a real speaker on every band at once. Recording the number here
        // so the next person does not have to rediscover what it meant.
        assertEquals(12.0, Decibels.fromRaw(0xf0))
        assertEquals("+12.0 dB", Decibels.format(Decibels.fromRaw(0xf0)))
        assertTrue(0xf0 !in Decibels.SAFE_RAW_RANGE)
    }

    @Test
    fun `the safe range is whole decibels either side of neutral`() {
        assertEquals(60..180, Decibels.SAFE_RAW_RANGE)
        assertEquals(-6.0, Decibels.fromRaw(60))
        assertEquals(6.0, Decibels.fromRaw(180))
        assertTrue(Decibels.NEUTRAL_RAW in Decibels.SAFE_RAW_RANGE)
    }

    @Test
    fun `the sign is explicit so plus six cannot be misread as six`() {
        assertEquals("+3.0 dB", Decibels.format(3.0))
        assertEquals("-3.0 dB", Decibels.format(-3.0))
        assertEquals("0.0 dB", Decibels.format(0.0))
        assertEquals("+0.5 dB", Decibels.format(0.49))
    }

    @Test
    fun `a value read back off the device carries no plus`() {
        assertEquals("3.0 dB", Decibels.formatUnsigned(3.0))
        assertEquals("-3.0 dB", Decibels.formatUnsigned(-3.0))
    }

    @Test
    fun `raw and decibels are inverse on the whole safe range`() {
        for (raw in Decibels.SAFE_RAW_RANGE step Decibels.RAW_PER_DB) {
            assertEquals(raw, Decibels.toRaw(Decibels.fromRaw(raw)), "raw=$raw")
        }
    }

    @Test
    fun `a zero scale cannot divide by zero`() {
        assertEquals(0.0, Decibels.fromRaw(200, offset = 120, scale = 0.0))
    }
}
