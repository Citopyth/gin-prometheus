package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateDecoderTest {

    private val profile = TestFixtures.boom2Profile()
    private val codec = profile.codec()

    private fun decode(body: ByteArray): DecodedState {
        val raw = codec.encode(PacketCodec.DEFAULT_INBOUND_HEADER, Command(1, 1), body)
        val result = codec.decode(raw)
        val frame = (result as DecodeResult.Ok).frame
        return StateDecoder.decode(profile, frame)
    }

    @Test
    fun `decodes the bytes captured from a real Boom 2`() {
        val state = decode(TestFixtures.boom2StateBody())
        assertEquals(emptyList(), state.problems)
        assertTrue(state.complete)
        // Captured bytes: these come from the published Boom 2 state packet.
        assertEquals("1/5 (ca. 20 %)", state.field("batteryLevel")?.value)
        assertEquals("20", state.field("byte0")?.value)
        assertEquals("05.00", state.field("firmware")?.value)
    }

    @Test
    fun `decodes the synthetic part of the fixture`() {
        val state = decode(TestFixtures.boom2StateBody())
        assertEquals("A3138K1234567890A", state.field("serial")?.value)
        assertEquals("an", state.field("bassUp")?.value)
        assertEquals("3", state.field("eqPreset")?.value)
        assertEquals("an", state.field("lightOn")?.value)
        assertEquals("200", state.field("lightBrightness")?.value)
        val eq = assertNotNull(state.field("customEq"))
        assertTrue(eq.value.startsWith("0.0 dB, 1.0 dB, -1.0 dB"), eq.value)
        assertEquals(8, eq.offsets.size)
    }

    @Test
    fun `every decoded field is flagged unverified, matching the shipped profile`() {
        val state = decode(TestFixtures.boom2StateBody())
        assertTrue(state.fields.isNotEmpty())
        assertTrue(
            state.fields.none { it.verified },
            "the profile must not claim verification it does not have",
        )
    }

    @Test
    fun `a short body is reported instead of crashing`() {
        val state = decode(ByteArray(20))
        assertTrue(state.problems.isNotEmpty())
        assertTrue(!state.complete)
        // The fields that do fit are still decoded.
        assertEquals("0/5 (ca. 0 %)", state.field("batteryLevel")?.value)
        // ...and the ones that do not are absent rather than wrong.
        assertNull(state.field("lightOn"))
        assertTrue(state.problems.any { it.contains("lightOn") }, state.problems.toString())
    }

    @Test
    fun `an empty body produces only problems`() {
        val state = decode(ByteArray(0))
        assertEquals(emptyList(), state.fields)
        assertEquals(profile.state.fields.size, state.problems.size)
    }

    @Test
    fun `a frame with the wrong command is flagged`() {
        val raw = codec.encode(PacketCodec.DEFAULT_INBOUND_HEADER, Command(2, 1), ByteArray(103))
        val frame = (codec.decode(raw) as DecodeResult.Ok).frame
        val state = StateDecoder.decode(profile, frame)
        assertTrue(state.problems.any { it.contains("02:01") }, state.problems.toString())
    }

    @Test
    fun `dB formatting rounds to one decimal without a locale`() {
        assertEquals("0.0 dB", StateDecoder.formatDb(0.0))
        assertEquals("1.0 dB", StateDecoder.formatDb(1.0))
        assertEquals("-1.5 dB", StateDecoder.formatDb(-1.5))
        assertEquals("12.3 dB", StateDecoder.formatDb(12.34))
        assertEquals("-12.0 dB", StateDecoder.formatDb(-12.0))
    }

    @Test
    fun `non printable ascii is shown as dots rather than control characters`() {
        val body = TestFixtures.boom2StateBody()
        body[7] = 0x00
        val state = decode(body)
        assertEquals(".5.00", state.field("firmware")?.value)
    }
}
