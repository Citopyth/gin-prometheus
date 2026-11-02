package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PacketCodecTest {

    private val codec = PacketCodec()

    @Test
    fun `encodes the state request byte for byte`() {
        // header(5) + command(2) + length(2) + checksum(1) = 10 bytes, so the length
        // field is 0x000a and the checksum is the 8 bit sum of the nine bytes before it.
        val frame = codec.encode(Command(0x01, 0x01))
        assertEquals("08 ee 00 00 00 01 01 0a 00 02", Hex.encode(frame))
        assertEquals(PacketCodec.MIN_FRAME_SIZE, frame.size)
    }

    @Test
    fun `checksum is the masked sum of everything before it`() {
        val frame = codec.encode(Command(0x02, 0x84), byteArrayOf(0x01))
        var sum = 0
        for (i in 0 until frame.size - 1) sum += frame[i].toInt() and 0xFF
        assertEquals((sum and 0xFF).toByte(), frame[frame.size - 1])
    }

    @Test
    fun `round trips a body of every length up to the ceiling`() {
        for (size in 0..64) {
            val body = ByteArray(size) { (it * 7).toByte() }
            val raw = codec.encode(Command(0x0E, 0x05), body)
            assertEquals(PacketCodec.PREFIX_SIZE + size + 1, raw.size)
            val decoded = assertIs<DecodeResult.Ok>(codec.decode(raw), "size $size")
            assertEquals(Command(0x0E, 0x05), decoded.frame.command)
            assertEquals(Direction.TO_DEVICE, decoded.frame.direction)
            assertTrue(body.contentEquals(decoded.frame.body), "body of size $size")
        }
    }

    @Test
    fun `recognises the inbound header`() {
        val raw = codec.encode(PacketCodec.DEFAULT_INBOUND_HEADER, Command(1, 1), byteArrayOf(20, 1))
        val decoded = assertIs<DecodeResult.Ok>(codec.decode(raw))
        assertEquals(Direction.FROM_DEVICE, decoded.frame.direction)
    }

    @Test
    fun `rejects a flipped checksum`() {
        val raw = codec.encode(Command(1, 1))
        raw[raw.size - 1] = (raw[raw.size - 1] + 1).toByte()
        val err = assertIs<DecodeResult.Err>(codec.decode(raw))
        assertEquals("bad-checksum", err.rule)
    }

    @Test
    fun `rejects an unknown header`() {
        val raw = codec.encode(Command(1, 1))
        raw[0] = 0x55
        val err = assertIs<DecodeResult.Err>(codec.decode(raw))
        assertEquals("unknown-header", err.rule)
    }

    @Test
    fun `rejects a frame shorter than the prefix`() {
        val err = assertIs<DecodeResult.Err>(codec.decode(byteArrayOf(0x08, 0xEE.toByte())))
        assertEquals("frame-too-short", err.rule)
    }

    @Test
    fun `reports a length mismatch and names the alternative reading`() {
        val raw = codec.encode(Command(1, 1), byteArrayOf(1, 2, 3))
        raw[7] = 0x20 // claim 32 bytes for a 13 byte frame
        val err = assertIs<DecodeResult.Err>(codec.decode(raw))
        assertEquals("length-mismatch", err.rule)
        assertTrue(err.message.contains("body-only"), err.message)
    }

    @Test
    fun `body length semantics encode and decode consistently`() {
        val bodyCodec = PacketCodec(lengthSemantics = LengthSemantics.BODY)
        val body = byteArrayOf(1, 2, 3, 4)
        val raw = bodyCodec.encode(Command(2, 0x88), body)
        assertEquals(4, raw[7].toInt() and 0xFF)
        assertEquals(0, raw[8].toInt() and 0xFF)
        val decoded = assertIs<DecodeResult.Ok>(bodyCodec.decode(raw))
        assertTrue(body.contentEquals(decoded.frame.body))
        // The same bytes read with TOTAL semantics must not pass as valid.
        assertIs<DecodeResult.Err>(codec.decode(raw))
    }

    @Test
    fun `frameLengthAt asks for more bytes and rejects nonsense`() {
        assertEquals(-1, codec.frameLengthAt(byteArrayOf(0x08, 0xEE.toByte(), 0, 0, 0)))
        val raw = codec.encode(Command(1, 1))
        assertEquals(10, codec.frameLengthAt(raw))
        raw[7] = 0x02 // a 2 byte frame is impossible
        raw[8] = 0x00
        assertEquals(0, codec.frameLengthAt(raw))
    }

    @Test
    fun `command parses and round trips`() {
        assertEquals(Command(0x01, 0x01), Command.parse("01 01"))
        assertEquals(Command(0x0E, 0x08), Command.parse("0e08"))
        assertEquals("02:84", Command(2, 0x84).toString())
        assertTrue(Command(2, 0x84).looksLikeWrite)
        assertTrue(!Command(1, 1).looksLikeWrite)
    }
}
