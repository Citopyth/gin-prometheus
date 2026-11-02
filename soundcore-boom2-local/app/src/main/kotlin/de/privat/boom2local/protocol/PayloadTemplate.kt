package de.privat.boom2local.protocol

/**
 * A command payload with holes in it.
 *
 * A profile writes a payload as space separated hex tokens, where `??` marks a byte that
 * an argument fills in: `"fe ?? ?? ?? ?? 0a"`. Fixed bytes cannot be overridden at
 * runtime, which is the point — a UI control can only move the bytes the profile opened
 * up for it.
 */
class PayloadTemplate private constructor(private val slots: List<Int?>) {

    val size: Int get() = slots.size

    val placeholderIndices: List<Int> = slots.indices.filter { slots[it] == null }

    val isEmpty: Boolean get() = slots.isEmpty()

    /**
     * Fills the template. [values] is keyed by byte index and must cover exactly
     * [placeholderIndices].
     */
    fun build(values: Map<Int, Int> = emptyMap()): ByteArray {
        for (index in placeholderIndices) {
            require(values.containsKey(index)) {
                "payload byte $index is a placeholder but no value was supplied"
            }
        }
        for ((index, value) in values) {
            require(index in slots.indices) {
                "payload index $index is outside the template of $size bytes"
            }
            require(slots[index] == null) {
                "payload byte $index is fixed to ${slots[index]} and cannot be set"
            }
            require(value in 0..0xFF) { "payload byte $index out of range: $value" }
        }
        val out = ByteArray(size)
        for (i in slots.indices) {
            out[i] = (slots[i] ?: values.getValue(i)).toByte()
        }
        return out
    }

    override fun toString(): String =
        slots.joinToString(" ") { slot ->
            if (slot == null) "??" else Hex.encode(byteArrayOf(slot.toByte()))
        }

    companion object {
        fun parse(text: String): PayloadTemplate {
            val tokens = Hex.tokenize(text)
            val slots = tokens.map { token ->
                if (token == "??") {
                    null
                } else {
                    val bytes = Hex.decode(token)
                    require(bytes.size == 1) {
                        "payload token '$token' must be one hex byte or '??'"
                    }
                    bytes[0].toInt() and 0xFF
                }
            }
            return PayloadTemplate(slots)
        }
    }
}
