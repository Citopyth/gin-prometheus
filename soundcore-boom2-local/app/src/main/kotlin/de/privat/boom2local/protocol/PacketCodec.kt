package de.privat.boom2local.protocol

import kotlinx.serialization.Serializable

/** How to read the 16 bit length field of a frame. */
@Serializable
enum class LengthSemantics {
    /** The field counts the whole frame: header + command + length field + body + checksum. */
    TOTAL,

    /** The field counts the body only. */
    BODY,
}

/** Which way a frame travelled, derived from its 5 byte header. */
enum class Direction { TO_DEVICE, FROM_DEVICE, UNKNOWN }

/** A decoded frame. */
class Frame(
    val header: ByteArray,
    val command: Command,
    val body: ByteArray,
    val direction: Direction,
) {
    override fun toString(): String =
        "Frame(${direction}, $command, body=${body.size}B: ${Hex.encode(body)})"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return header.contentEquals(other.header) &&
            command == other.command &&
            body.contentEquals(other.body) &&
            direction == other.direction
    }

    override fun hashCode(): Int {
        var h = header.contentHashCode()
        h = 31 * h + command.hashCode()
        h = 31 * h + body.contentHashCode()
        h = 31 * h + direction.hashCode()
        return h
    }
}

/** Result of [PacketCodec.decode]. */
sealed interface DecodeResult {
    data class Ok(val frame: Frame) : DecodeResult
    data class Err(val rule: String, val message: String) : DecodeResult
}

/**
 * Encodes and decodes the Soundcore RFCOMM frame.
 *
 * Layout, as documented by the community reimplementations listed in PROTOCOL.md:
 *
 * ```
 *  offset  size  content
 *  0       5     header            08 ee 00 00 00 towards the device
 *                                  09 ff 00 00 01 from the device
 *  5       2     command           [category, code]
 *  7       2     length, u16 LE    see LengthSemantics
 *  9       n     body
 *  9+n     1     checksum          sum of every preceding byte, masked to 8 bits
 * ```
 *
 * None of this is verified against a Soundcore Boom 2 yet. The header bytes and the
 * length semantics are therefore configurable, and [CaptureValidator] exists to decide
 * them from a real capture instead of from assumption.
 */
class PacketCodec(
    val outboundHeader: ByteArray = DEFAULT_OUTBOUND_HEADER,
    val inboundHeader: ByteArray = DEFAULT_INBOUND_HEADER,
    val lengthSemantics: LengthSemantics = LengthSemantics.TOTAL,
    val maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE,
) {

    init {
        require(outboundHeader.size == HEADER_SIZE) {
            "outbound header must be $HEADER_SIZE bytes, got ${outboundHeader.size}"
        }
        require(inboundHeader.size == HEADER_SIZE) {
            "inbound header must be $HEADER_SIZE bytes, got ${inboundHeader.size}"
        }
        require(maxFrameSize in MIN_FRAME_SIZE..0xFFFF) {
            "maxFrameSize must be in $MIN_FRAME_SIZE..65535, got $maxFrameSize"
        }
    }

    val headers: List<ByteArray> get() = listOf(outboundHeader, inboundHeader)

    /** Builds a frame to send to the device. */
    fun encode(command: Command, body: ByteArray = EMPTY_BODY): ByteArray =
        encode(outboundHeader, command, body)

    fun encode(header: ByteArray, command: Command, body: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "header must be $HEADER_SIZE bytes" }
        val total = PREFIX_SIZE + body.size + CHECKSUM_SIZE
        require(total <= maxFrameSize) {
            "frame of $total bytes exceeds maxFrameSize $maxFrameSize"
        }
        val out = ByteArray(total)
        header.copyInto(out, 0)
        out[5] = command.category.toByte()
        out[6] = command.code.toByte()
        val lengthValue = when (lengthSemantics) {
            LengthSemantics.TOTAL -> total
            LengthSemantics.BODY -> body.size
        }
        out[7] = (lengthValue and 0xFF).toByte()
        out[8] = ((lengthValue ushr 8) and 0xFF).toByte()
        body.copyInto(out, PREFIX_SIZE)
        out[total - 1] = checksum(out, 0, total - 1)
        return out
    }

    /**
     * Length of the frame that starts at [offset], read from its length field.
     * Returns -1 when fewer than [PREFIX_SIZE] bytes are available, and 0 when the
     * field yields an impossible length (a desync, most likely).
     */
    fun frameLengthAt(bytes: ByteArray, offset: Int = 0): Int {
        if (bytes.size - offset < PREFIX_SIZE) return -1
        val lengthValue = (bytes[offset + 7].toInt() and 0xFF) or
            ((bytes[offset + 8].toInt() and 0xFF) shl 8)
        val total = when (lengthSemantics) {
            LengthSemantics.TOTAL -> lengthValue
            LengthSemantics.BODY -> PREFIX_SIZE + lengthValue + CHECKSUM_SIZE
        }
        return if (total < MIN_FRAME_SIZE || total > maxFrameSize) 0 else total
    }

    /** Decodes exactly one complete frame. */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < MIN_FRAME_SIZE) {
            return DecodeResult.Err(
                "frame-too-short",
                "a frame is at least $MIN_FRAME_SIZE bytes, got ${bytes.size}",
            )
        }
        val direction = directionOf(bytes)
        if (direction == Direction.UNKNOWN) {
            return DecodeResult.Err(
                "unknown-header",
                "header ${Hex.encode(bytes, 0, HEADER_SIZE)} matches neither " +
                    "${Hex.encode(outboundHeader)} nor ${Hex.encode(inboundHeader)}",
            )
        }
        val declared = frameLengthAt(bytes)
        if (declared <= 0) {
            return DecodeResult.Err(
                "bad-length",
                "length field ${Hex.encode(bytes, 7, 9)} yields no usable frame length",
            )
        }
        if (declared != bytes.size) {
            val alternative = when (lengthSemantics) {
                LengthSemantics.TOTAL -> "body-only length would be ${bytes.size - PREFIX_SIZE - CHECKSUM_SIZE}"
                LengthSemantics.BODY -> "total length would be ${bytes.size}"
            }
            return DecodeResult.Err(
                "length-mismatch",
                "length field says $declared bytes under $lengthSemantics semantics but " +
                    "the frame is ${bytes.size} bytes ($alternative)",
            )
        }
        val expected = checksum(bytes, 0, bytes.size - 1)
        val actual = bytes[bytes.size - 1]
        if (expected != actual) {
            return DecodeResult.Err(
                "bad-checksum",
                "checksum is ${Hex.encode(byteArrayOf(actual))}, expected " +
                    Hex.encode(byteArrayOf(expected)),
            )
        }
        val command = Command(bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF)
        val body = bytes.copyOfRange(PREFIX_SIZE, bytes.size - CHECKSUM_SIZE)
        return DecodeResult.Ok(
            Frame(bytes.copyOfRange(0, HEADER_SIZE), command, body, direction),
        )
    }

    fun directionOf(bytes: ByteArray, offset: Int = 0): Direction {
        if (bytes.size - offset < HEADER_SIZE) return Direction.UNKNOWN
        if (startsWith(bytes, offset, outboundHeader)) return Direction.TO_DEVICE
        if (startsWith(bytes, offset, inboundHeader)) return Direction.FROM_DEVICE
        return Direction.UNKNOWN
    }

    companion object {
        const val HEADER_SIZE = 5
        const val COMMAND_SIZE = 2
        const val LENGTH_SIZE = 2
        const val CHECKSUM_SIZE = 1

        /** Header + command + length field: everything before the body. */
        const val PREFIX_SIZE = HEADER_SIZE + COMMAND_SIZE + LENGTH_SIZE

        /** Shortest possible frame: prefix plus checksum, with an empty body. */
        const val MIN_FRAME_SIZE = PREFIX_SIZE + CHECKSUM_SIZE

        const val DEFAULT_MAX_FRAME_SIZE = 2048

        val DEFAULT_OUTBOUND_HEADER: ByteArray
            get() = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)

        val DEFAULT_INBOUND_HEADER: ByteArray
            get() = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

        val EMPTY_BODY: ByteArray get() = ByteArray(0)

        /** Sum of `bytes[from until to]`, masked to 8 bits. */
        fun checksum(bytes: ByteArray, from: Int, to: Int): Byte {
            var sum = 0
            for (i in from until to) sum += bytes[i].toInt() and 0xFF
            return (sum and 0xFF).toByte()
        }

        fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean {
            if (bytes.size - offset < prefix.size) return false
            for (i in prefix.indices) if (bytes[offset + i] != prefix[i]) return false
            return true
        }
    }
}
