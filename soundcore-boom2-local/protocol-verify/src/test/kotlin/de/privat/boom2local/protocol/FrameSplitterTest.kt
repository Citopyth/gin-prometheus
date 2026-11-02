package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameSplitterTest {

    private val codec = PacketCodec()

    private fun complete(events: List<SplitEvent>) =
        events.filterIsInstance<SplitEvent.Complete>()

    private fun discarded(events: List<SplitEvent>) =
        events.filterIsInstance<SplitEvent.Discarded>()

    @Test
    fun `reassembles a frame fed one byte at a time`() {
        val splitter = FrameSplitter(codec)
        val raw = codec.encode(Command(1, 1), ByteArray(20) { it.toByte() })
        val events = mutableListOf<SplitEvent>()
        for (b in raw) {
            events += splitter.offer(byteArrayOf(b))
        }
        val frames = complete(events)
        assertEquals(1, frames.size)
        assertTrue(raw.contentEquals(frames[0].raw))
        assertEquals(0, splitter.buffered)
    }

    @Test
    fun `splits three frames arriving in one chunk`() {
        val splitter = FrameSplitter(codec)
        val a = codec.encode(Command(1, 1))
        val b = codec.encode(PacketCodec.DEFAULT_INBOUND_HEADER, Command(1, 1), ByteArray(30))
        val c = codec.encode(Command(0x0E, 0x05), byteArrayOf(0x40))
        val events = splitter.offer(a + b + c)
        val frames = complete(events)
        assertEquals(3, frames.size)
        assertTrue(a.contentEquals(frames[0].raw))
        assertTrue(b.contentEquals(frames[1].raw))
        assertTrue(c.contentEquals(frames[2].raw))
        assertEquals(0, discarded(events).size)
    }

    @Test
    fun `discards leading garbage and keeps the frame behind it`() {
        val splitter = FrameSplitter(codec)
        val raw = codec.encode(Command(1, 1))
        val events = splitter.offer(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()) + raw)
        assertEquals(1, complete(events).size)
        val junk = discarded(events)
        assertEquals(1, junk.size)
        assertEquals(3, junk[0].raw.size)
        assertTrue(raw.contentEquals(complete(events)[0].raw))
    }

    @Test
    fun `resyncs after a frame with an impossible length field`() {
        val splitter = FrameSplitter(codec)
        val broken = codec.encode(Command(1, 1))
        broken[7] = 0x01 // a one byte frame cannot exist
        broken[8] = 0x00
        val good = codec.encode(Command(0x0E, 0x05), byteArrayOf(1))
        val events = splitter.offer(broken + good)
        val frames = complete(events)
        assertEquals(1, frames.size)
        assertTrue(good.contentEquals(frames[0].raw))
        assertTrue(discarded(events).isNotEmpty())
    }

    @Test
    fun `keeps a header split across two reads`() {
        val splitter = FrameSplitter(codec)
        val raw = codec.encode(Command(1, 1))
        val first = splitter.offer(raw.copyOfRange(0, 3))
        assertEquals(0, complete(first).size)
        assertEquals(0, discarded(first).size, "a partial header must not be thrown away")
        assertEquals(3, splitter.buffered)
        val second = splitter.offer(raw.copyOfRange(3, raw.size))
        assertEquals(1, complete(second).size)
    }

    @Test
    fun `drops a long run of garbage but keeps a possible partial header`() {
        val splitter = FrameSplitter(codec)
        val events = splitter.offer(ByteArray(64) { 0x77 })
        assertEquals(1, discarded(events).size)
        assertEquals(PacketCodec.HEADER_SIZE - 1, splitter.buffered)
    }

    @Test
    fun `reset clears the buffer`() {
        val splitter = FrameSplitter(codec)
        splitter.offer(codec.encode(Command(1, 1)).copyOfRange(0, 6))
        assertEquals(6, splitter.buffered)
        splitter.reset()
        assertEquals(0, splitter.buffered)
    }

    @Test
    fun `offer honours a partial chunk length`() {
        val splitter = FrameSplitter(codec)
        val raw = codec.encode(Command(1, 1))
        val scratch = ByteArray(64)
        raw.copyInto(scratch, 0)
        val events = splitter.offer(scratch, raw.size)
        assertEquals(1, complete(events).size)
        assertEquals(0, splitter.buffered)
    }

    @Test
    fun `decoding a reassembled frame yields the original body`() {
        val splitter = FrameSplitter(codec)
        val body = ByteArray(103) { (it * 3).toByte() }
        val raw = codec.encode(PacketCodec.DEFAULT_INBOUND_HEADER, Command(1, 1), body)
        var events = listOf<SplitEvent>()
        var pos = 0
        val all = mutableListOf<SplitEvent>()
        while (pos < raw.size) {
            val end = minOf(pos + 13, raw.size)
            events = splitter.offer(raw.copyOfRange(pos, end))
            all += events
            pos = end
        }
        val frame = complete(all).single()
        val decoded = assertIs<DecodeResult.Ok>(codec.decode(frame.raw))
        assertTrue(body.contentEquals(decoded.frame.body))
    }
}
