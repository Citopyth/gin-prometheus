package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InboundClassifierTest {

    private val profile = TestFixtures.boom2Profile()
    private val codec = profile.codec()

    private fun inbound(hex: String): Inbound {
        val decoded = codec.decode(Hex.decode(hex))
        return InboundClassifier.classify(profile, (decoded as DecodeResult.Ok).frame)
    }

    private fun frame(command: Command, body: ByteArray = ByteArray(0)): Inbound {
        val raw = codec.encode(PacketCodec.DEFAULT_INBOUND_HEADER, command, body)
        return InboundClassifier.classify(profile, (codec.decode(raw) as DecodeResult.Ok).frame)
    }

    @Test
    fun `the acknowledgement the speaker really sent is recognised`() {
        // Captured from the device: an empty frame echoing the command back.
        val ack = assertIs<Inbound.Acknowledgement>(
            inbound("09 ff 00 00 01 02 84 0a 00 99"),
        )
        assertEquals(Command(0x02, 0x84), ack.command)
        assertEquals("BassUp", ack.label)
    }

    @Test
    fun `every write command the speaker acknowledged is classified the same way`() {
        for (hex in listOf(
            "09 ff 00 00 01 02 84 0a 00 99",
            "09 ff 00 00 01 02 88 0a 00 9d",
            "09 ff 00 00 01 0e 08 0a 00 29",
            "09 ff 00 00 01 0e 05 0a 00 26",
        )) {
            assertIs<Inbound.Acknowledgement>(inbound(hex), hex)
        }
    }

    @Test
    fun `an announcement is a notification, not an acknowledgement`() {
        val announced = assertIs<Inbound.Notification>(
            inbound("09 ff 00 00 01 01 03 0b 00 02 1a"),
        )
        assertEquals("Akku", announced.field.label)
        assertEquals("2/5 (ca. 40 %)", announced.field.value)
    }

    @Test
    fun `the light announcement found in the capture is decoded`() {
        val announced = assertIs<Inbound.Notification>(
            inbound("09 ff 00 00 01 0e 09 0b 00 01 2c"),
        )
        assertEquals("Licht", announced.field.label)
        assertEquals("an", announced.field.value)
        assertTrue(!announced.field.verified, "the reading is plausible, not proven")
    }

    @Test
    fun `the state blob is classified as state`() {
        val state = assertIs<Inbound.State>(
            frame(Command(0x01, 0x01), TestFixtures.boom2StateBody()),
        )
        assertEquals("03.11", state.decoded.field("firmware")?.value)
    }

    @Test
    fun `an empty frame carrying the state command is an acknowledgement, not empty state`() {
        // Easy to get backwards: the command matches the state response, but with no
        // body there is no state in it.
        val ack = assertIs<Inbound.Acknowledgement>(frame(Command(0x01, 0x01)))
        assertEquals("Status lesen", ack.label)
    }

    @Test
    fun `a command the profile does not list stays unknown rather than being guessed at`() {
        val unknown = assertIs<Inbound.Unknown>(frame(Command(0x7a, 0x7b), byteArrayOf(1, 2)))
        assertEquals(Command(0x7a, 0x7b), unknown.command)
        assertEquals(2, unknown.body.size)
        // An empty frame from an unlisted command is not an acknowledgement either.
        assertIs<Inbound.Unknown>(frame(Command(0x7a, 0x7b)))
    }
}
