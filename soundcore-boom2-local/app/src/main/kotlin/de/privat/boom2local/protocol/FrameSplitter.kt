package de.privat.boom2local.protocol

/** What came out of [FrameSplitter.offer]. */
sealed interface SplitEvent {
    /** One complete, length-consistent frame. Checksum is *not* checked here. */
    class Complete(val raw: ByteArray) : SplitEvent

    /** Bytes thrown away to regain sync, with the reason. */
    class Discarded(val raw: ByteArray, val reason: String) : SplitEvent
}

/**
 * Reassembles frames from an RFCOMM byte stream.
 *
 * An RFCOMM socket hands out arbitrary chunks: half a frame, two frames at once, or a
 * frame split across three reads. This buffers until a frame is complete, and resyncs on
 * the next known header when the stream contains something unexpected — which it will,
 * the first time a framing assumption turns out to be wrong.
 */
class FrameSplitter(private val codec: PacketCodec) {

    private var buffer = ByteArray(0)

    val buffered: Int get() = buffer.size

    fun reset() {
        buffer = ByteArray(0)
    }

    fun offer(chunk: ByteArray, length: Int = chunk.size): List<SplitEvent> {
        require(length in 0..chunk.size) { "length out of range: $length" }
        if (length > 0) {
            val grown = ByteArray(buffer.size + length)
            buffer.copyInto(grown, 0)
            chunk.copyInto(grown, buffer.size, 0, length)
            buffer = grown
        }
        return drain()
    }

    private fun drain(): List<SplitEvent> {
        val events = mutableListOf<SplitEvent>()
        while (true) {
            if (buffer.isEmpty()) break

            // 1. Make sure the buffer starts on a header.
            if (codec.directionOf(buffer) == Direction.UNKNOWN) {
                val next = findHeader(from = 1)
                if (next > 0) {
                    events += SplitEvent.Discarded(
                        buffer.copyOfRange(0, next),
                        "no frame header at the start of the stream",
                    )
                    buffer = buffer.copyOfRange(next, buffer.size)
                    continue
                }
                // No further header in the buffer. Keep only what could still be the
                // beginning of one, so a header split across two reads survives.
                val keep = minOf(buffer.size, PacketCodec.HEADER_SIZE - 1)
                if (buffer.size > keep) {
                    events += SplitEvent.Discarded(
                        buffer.copyOfRange(0, buffer.size - keep),
                        "no frame header found",
                    )
                    buffer = buffer.copyOfRange(buffer.size - keep, buffer.size)
                }
                break
            }

            // 2. Need the length field before we know how far the frame reaches.
            val declared = codec.frameLengthAt(buffer)
            if (declared < 0) break // wait for more bytes
            if (declared == 0) {
                // Header looked right but the length field is impossible. Drop one byte
                // and look for the next header rather than trusting it.
                events += SplitEvent.Discarded(
                    buffer.copyOfRange(0, 1),
                    "implausible length field ${Hex.encode(buffer, 7, 9)}",
                )
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            // 3. Wait until the whole frame has arrived.
            if (buffer.size < declared) break

            events += SplitEvent.Complete(buffer.copyOfRange(0, declared))
            buffer = buffer.copyOfRange(declared, buffer.size)
        }
        return events
    }

    /** Index of the next known header at or after [from], or -1. */
    private fun findHeader(from: Int): Int {
        var i = maxOf(from, 0)
        while (i + PacketCodec.HEADER_SIZE <= buffer.size) {
            if (codec.directionOf(buffer, i) != Direction.UNKNOWN) return i
            i++
        }
        return -1
    }
}
