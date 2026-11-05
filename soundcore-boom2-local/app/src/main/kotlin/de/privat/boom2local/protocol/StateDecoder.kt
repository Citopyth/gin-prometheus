package de.privat.boom2local.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/** One decoded value out of a state frame. */
data class DecodedField(
    val key: String,
    val label: String,
    /** Human readable value. */
    val value: String,
    /** The bytes it came from, as hex. */
    val raw: String,
    /** Byte offsets it came from, for the diagnostics view. */
    val offsets: List<Int>,
    val verified: Boolean,
    val note: String? = null,
)

/** Everything read out of one state frame, including what could not be read. */
data class DecodedState(
    val command: Command,
    val bodySize: Int,
    val bodyHex: String,
    val fields: List<DecodedField>,
    val problems: List<String>,
) {
    fun field(key: String): DecodedField? = fields.firstOrNull { it.key == key }

    /** True when every field in the profile could be read. */
    val complete: Boolean get() = problems.isEmpty()
}

/**
 * Turns a state frame body into labelled values, driven entirely by the profile.
 *
 * Every offset here is a reverse engineered guess until a capture confirms it, so a
 * field whose offset is past the end of the body is reported as a problem instead of
 * crashing or silently showing zero.
 */
object StateDecoder {

    fun decode(profile: DeviceProfile, frame: Frame): DecodedState {
        val body = frame.body
        val fields = mutableListOf<DecodedField>()
        val problems = mutableListOf<String>()

        if (frame.command != profile.stateResponseCommand) {
            problems += "frame is ${frame.command}, the profile expects " +
                "${profile.stateResponseCommand} for state"
        }

        for (spec in profile.state.fields) {
            val decoded = decodeField(spec, body, problems)
            if (decoded != null) fields += decoded
        }

        return DecodedState(
            command = frame.command,
            bodySize = body.size,
            bodyHex = Hex.encode(body),
            fields = fields,
            problems = problems,
        )
    }

    private fun decodeField(
        spec: FieldSpec,
        body: ByteArray,
        problems: MutableList<String>,
    ): DecodedField? {
        fun outOfRange(what: String): DecodedField? {
            problems += "field '${spec.key}' needs $what but the body is only " +
                "${body.size} bytes"
            return null
        }

        return when (spec.type) {
            FieldType.U8, FieldType.BOOL -> {
                if (spec.offset !in body.indices) return outOfRange("byte ${spec.offset}")
                val raw = body[spec.offset].toInt() and 0xFF
                val text = if (spec.type == FieldType.BOOL) {
                    if (raw != 0) "an" else "aus"
                } else {
                    renderNumber(spec, raw)
                }
                field(spec, text, Hex.encode(body, spec.offset, spec.offset + 1), listOf(spec.offset))
            }

            FieldType.ASCII -> {
                val end = spec.offset + spec.length
                if (spec.offset < 0 || end > body.size) {
                    return outOfRange("bytes ${spec.offset}..${end - 1}")
                }
                val sb = StringBuilder(spec.length)
                for (i in spec.offset until end) {
                    val c = body[i].toInt() and 0xFF
                    sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
                }
                field(
                    spec,
                    sb.toString().trim().ifEmpty { "(leer)" },
                    Hex.encode(body, spec.offset, end),
                    (spec.offset until end).toList(),
                )
            }

            FieldType.BYTES -> {
                val end = spec.offset + spec.length
                if (spec.offset < 0 || end > body.size) {
                    return outOfRange("bytes ${spec.offset}..${end - 1}")
                }
                val hex = Hex.encode(body, spec.offset, end)
                field(spec, hex, hex, (spec.offset until end).toList())
            }

            FieldType.EQ_BANDS -> {
                val missing = spec.indices.filter { it !in body.indices }
                if (missing.isNotEmpty()) {
                    return outOfRange("bytes ${missing.joinToString(", ")}")
                }
                val values = spec.indices.map { body[it].toInt() and 0xFF }
                val text = values.joinToString(", ") { renderNumber(spec, it) }
                val raw = spec.indices.joinToString(" ") {
                    Hex.encode(body, it, it + 1)
                }
                field(spec, text, raw, spec.indices)
            }
        }
    }

    private fun field(
        spec: FieldSpec,
        value: String,
        raw: String,
        offsets: List<Int>,
    ): DecodedField = DecodedField(
        key = spec.key,
        label = spec.label,
        value = value,
        raw = raw,
        offsets = offsets,
        verified = spec.verified,
        note = spec.note,
    )

    /**
     * Decodes a frame the speaker sent on its own.
     *
     * Returns null when the profile does not describe this command, which is the signal
     * to log it as unknown rather than to invent a meaning for it.
     */
    fun decodeNotification(profile: DeviceProfile, frame: Frame): DecodedField? {
        val notification = profile.state.notifications
            .firstOrNull { it.commandAddress == frame.command } ?: return null
        val spec = profile.state.fields.firstOrNull { it.key == notification.field }
            ?: return null
        if (notification.offset !in frame.body.indices) return null
        val raw = frame.body[notification.offset].toInt() and 0xFF
        val text = when (spec.type) {
            FieldType.BOOL -> if (raw != 0) "an" else "aus"
            else -> renderNumber(spec, raw)
        }
        return DecodedField(
            key = spec.key,
            label = spec.label,
            value = text,
            raw = Hex.encode(byteArrayOf(raw.toByte())),
            offsets = listOf(notification.offset),
            verified = spec.verified && notification.verified,
            note = notification.note ?: spec.note,
        )
    }

    private fun renderNumber(spec: FieldSpec, raw: Int): String {
        val dbOffset = spec.dbOffset
        val dbScale = spec.dbScale
        if (dbOffset != null && dbScale != null && dbScale != 0.0) {
            return formatDb((raw - dbOffset) / dbScale)
        }
        val max = spec.scaleMax
        if (max != null && max > 0) {
            val percent = (raw * 100 + max / 2) / max
            return "$raw/$max (ca. $percent %)"
        }
        return raw.toString()
    }

    /** Formats with one decimal without pulling in a locale. */
    internal fun formatDb(value: Double): String {
        val tenths = (value * 10).roundToInt()
        val sign = if (tenths < 0) "-" else ""
        val a = abs(tenths)
        return "$sign${a / 10}.${a % 10} dB"
    }
}
