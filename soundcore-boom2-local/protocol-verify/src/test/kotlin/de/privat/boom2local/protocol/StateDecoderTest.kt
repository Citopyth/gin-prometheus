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
    fun `the equaliser block is a list, and both captures show it`() {
        // This test used to assert nine bands at offsets 34, 37, 40 and so on, all
        // reading 0.0 dB. The first frame received from the speaker disproved that: the
        // block is a run of three-byte entries - gain, band, 0x0a - and there were nine
        // of them only because nine had been set.
        val older = TestFixtures.boom2StateBody()
        val newer = TestFixtures.boom2StateBodyAfterWrites()

        // Nine entries in the older capture, every gain at 0 dB.
        for (entry in 0 until 9) {
            val at = 34 + entry * 3
            assertEquals(0x78, older[at].toInt() and 0xFF, "gain of entry $entry")
            assertEquals(0x0a, older[at + 2].toInt() and 0xFF, "terminator of entry $entry")
        }
        // One entry in the newer one, and it is byte for byte what eqFlat sends.
        assertEquals(0x78, newer[34].toInt() and 0xFF)
        assertEquals(0xff, newer[35].toInt() and 0xFF)
        assertEquals(0x0a, newer[36].toInt() and 0xFF)
        assertEquals(0x00, newer[38].toInt() and 0xFF)

        // What the profile now reads there is one gain byte, and it says 0 dB.
        assertEquals("0.0 dB", decode(newer).field("eqGain")?.value)
    }

    @Test
    fun `the fields confirmed against hardware say so, and the rest do not`() {
        val state = decode(TestFixtures.boom2StateBody())
        val confirmed = state.fields.filter { it.verified }.map { it.key }.toSet()
        assertEquals(
            setOf("volume", "batteryLevel", "bassUp", "firmware", "serial"),
            confirmed,
        )
        val unconfirmed = state.fields.filterNot { it.verified }.map { it.key }.toSet()
        assertEquals(
            setOf(
                "eqPreset", "eqGain", "pairingOpen", "charging",
                "lightMode", "lightBrightness", "lightOn",
            ),
            unconfirmed,
        )
    }

    /** A state frame as the speaker would send it, for tests that build one by hand. */
    private fun inbound(profile: DeviceProfile, body: ByteArray) = Frame(
        header = PacketCodec.DEFAULT_INBOUND_HEADER,
        command = profile.stateResponseCommand,
        body = body,
        direction = Direction.FROM_DEVICE,
    )

    @Test
    fun `the EQ_BANDS type still decodes, though this profile no longer uses it`() {
        // The Boom 2's equaliser turned out to be a list, so its profile dropped this
        // type - but the type is a core capability, not a Boom 2 claim, and another
        // model may well have fixed bands. Without a test it would quietly rot into
        // something that throws the first time anyone needs it.
        val spec = FieldSpec(
            key = "bands",
            label = "Baender",
            type = FieldType.EQ_BANDS,
            indices = listOf(2, 4, 6),
            dbOffset = 120,
            dbScale = 10.0,
        )
        val profile = TestFixtures.boom2Profile().let { base ->
            base.copy(state = base.state.copy(fields = listOf(spec)))
        }
        val body = ByteArray(8).also {
            it[2] = 120.toByte()
            it[4] = 140.toByte()
            it[6] = 100.toByte()
        }
        val frame = inbound(profile, body)
        val decoded = assertNotNull(StateDecoder.decode(profile, frame).field("bands"))
        assertEquals(listOf(2, 4, 6), decoded.offsets)
        assertEquals("0.0 dB, 2.0 dB, -2.0 dB", decoded.value)

        // And an index past the end is a reported problem, not a crash.
        val short = StateDecoder.decode(profile, inbound(profile, ByteArray(3)))
        assertTrue(short.problems.isNotEmpty())
        assertNull(short.field("bands"))
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
        assertNull(state.field("eqGain"))
        assertTrue(state.problems.any { it.contains("lightOn") }, state.problems.toString())
    }

    @Test
    fun `an empty body produces only problems`() {
        val state = decode(ByteArray(0))
        assertEquals(emptyList(), state.fields)
        // Every field the state frame is supposed to carry. A field that only ever
        // arrives as a notification is not missing from this frame, it was never in it.
        assertEquals(profile.state.fields.count { it.inStateFrame }, state.problems.size)
    }

    @Test
    fun `a field marked outside the state frame is skipped, not reported missing`() {
        // The shipped profile no longer has one, so the mechanism is checked on a copy.
        val notInFrame = FieldSpec(
            key = "elsewhere",
            label = "Nur als Meldung",
            type = FieldType.BOOL,
            inStateFrame = false,
            note = "kommt nur als Meldung",
        )
        val copy = profile.copy(
            state = profile.state.copy(fields = profile.state.fields + notInFrame),
        )
        val state = StateDecoder.decode(
            copy,
            Frame(
                header = PacketCodec.DEFAULT_INBOUND_HEADER,
                command = copy.stateResponseCommand,
                body = TestFixtures.boom2StateBodyCharging(),
                direction = Direction.FROM_DEVICE,
            ),
        )
        assertNull(state.field("elsewhere"))
        assertTrue(state.problems.none { it.contains("elsewhere") }, state.problems.toString())
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
        // The exact bytes the real speaker sent when the cable went in, entry 30 of the
        // capture: 09 ff 00 00 01 | 01 04 | 0b 00 | 01 | 1a.
        assertEquals("an", notification(Command(0x01, 0x04), 1)?.value)
        assertEquals("Laedt (Byte 2)", notification(Command(0x01, 0x04), 1)?.label)
        // Since 2026-11-07 the field also has a place in the state frame: byte 2, read
        // as 01 while the cable was confirmed in and the battery climbing.
        val charging = profile.state.fields.single { it.key == "charging" }
        assertEquals(true, charging.inStateFrame)
        assertEquals(2, charging.offset)
        assertEquals(false, charging.verified)
        // A command the profile does not describe is reported as unknown, not guessed.
        assertNull(notification(Command(0x7a, 0x7b), 1))
    }
}
