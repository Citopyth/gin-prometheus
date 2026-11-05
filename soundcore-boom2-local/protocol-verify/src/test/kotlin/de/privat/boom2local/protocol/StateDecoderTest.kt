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
    fun `decodes every confirmed field of the captured frame`() {
        val state = decode(TestFixtures.boom2StateBody())
        assertEquals(emptyList(), state.problems)
        assertTrue(state.complete)
        assertEquals("31", state.field("volume")?.value)
        assertEquals("2/5 (ca. 40 %)", state.field("batteryLevel")?.value)
        assertEquals("an", state.field("bassUp")?.value)
        assertEquals("03.11", state.field("firmware")?.value)
        assertEquals("ACCF0X0E25101905", state.field("serial")?.value)
    }

    @Test
    fun `the serial number stops before the control byte that follows it`() {
        // The profile said 17 characters, which dragged a 0x01 in and rendered it as a
        // dot. The capture shows 16.
        val serial = assertNotNull(decode(TestFixtures.boom2StateBody()).field("serial"))
        assertEquals(16, serial.value.length)
        assertTrue(!serial.value.contains('.'), serial.value)
    }

    @Test
    fun `all nine equaliser bands sit where the capture puts them`() {
        val eq = assertNotNull(decode(TestFixtures.boom2StateBody()).field("customEq"))
        assertEquals(9, eq.offsets.size)
        assertEquals(listOf(34, 37, 40, 43, 46, 49, 52, 55, 58), eq.offsets)
        // 0x78 is 120, which the Space 2's formula maps to exactly 0 dB.
        assertEquals(List(9) { "0.0 dB" }.joinToString(", "), eq.value)
    }

    @Test
    fun `the fields confirmed against hardware say so, and the rest do not`() {
        val state = decode(TestFixtures.boom2StateBody())
        val confirmed = state.fields.filter { it.verified }.map { it.key }.toSet()
        assertEquals(
            setOf("volume", "batteryLevel", "bassUp", "firmware", "serial", "customEq"),
            confirmed,
        )
        val unconfirmed = state.fields.filterNot { it.verified }.map { it.key }.toSet()
        assertEquals(setOf("eqPreset", "lightMode", "lightBrightness", "lightOn"), unconfirmed)
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
        assertNull(state.field("customEq"))
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
        assertEquals(".3.11", state.field("firmware")?.value)
    }

    @Test
    fun `an unsolicited frame is decoded through the field it points at`() {
        val codec = profile.codec()
        fun notification(command: Command, value: Int): DecodedField? {
            val raw = codec.encode(
                PacketCodec.DEFAULT_INBOUND_HEADER,
                command,
                byteArrayOf(value.toByte()),
            )
            val frame = (codec.decode(raw) as DecodeResult.Ok).frame
            return StateDecoder.decodeNotification(profile, frame)
        }
        assertEquals("1/5 (ca. 20 %)", notification(Command(0x01, 0x03), 1)?.value)
        assertEquals("Akku", notification(Command(0x01, 0x03), 1)?.label)
        assertEquals("31", notification(Command(0x01, 0x09), 0x1f)?.value)
        assertEquals("an", notification(Command(0x02, 0x04), 1)?.value)
        assertEquals("aus", notification(Command(0x02, 0x04), 0)?.value)
        // A command the profile does not describe is reported as unknown, not guessed.
        assertNull(notification(Command(0x7a, 0x7b), 1))
    }
}
