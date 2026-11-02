package de.privat.boom2local.protocol

/**
 * A Soundcore command address.
 *
 * Soundcore addresses commands with two bytes, `[category, code]` — not a flat 16 bit
 * word. In the captures published for the Space 2, codes `00..7f` are reads and
 * `80..ff` are writes within a category; see PROTOCOL.md for provenance and for how
 * far that generalises (it is a pattern, not a guarantee).
 */
data class Command(val category: Int, val code: Int) {

    init {
        require(category in 0..0xFF) { "command category out of range: $category" }
        require(code in 0..0xFF) { "command code out of range: $code" }
    }

    val bytes: ByteArray get() = byteArrayOf(category.toByte(), code.toByte())

    /** True when the code is in the `80..ff` half that published captures use for writes. */
    val looksLikeWrite: Boolean get() = code >= 0x80

    override fun toString(): String = hex(category) + ":" + hex(code)

    private fun hex(v: Int): String {
        val d = "0123456789abcdef"
        return "" + d[(v ushr 4) and 0x0F] + d[v and 0x0F]
    }

    companion object {
        /** Parses `"01 01"`, `"0101"` or `"01:01"`. */
        fun parse(text: String): Command {
            val b = Hex.decode(text)
            require(b.size == 2) { "a command is exactly 2 bytes, got ${b.size} from '$text'" }
            return Command(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF)
        }
    }
}
