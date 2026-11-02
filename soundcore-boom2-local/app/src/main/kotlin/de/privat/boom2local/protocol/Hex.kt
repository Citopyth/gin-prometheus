package de.privat.boom2local.protocol

/**
 * Hex helpers.
 *
 * Pure Kotlin on purpose: everything in this package must compile and run without the
 * Android SDK, so it can be unit tested on a plain JVM (see `protocol-verify/`).
 */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    /** Formats [bytes] as lowercase hex, e.g. `08 ee 00`. */
    fun encode(bytes: ByteArray, separator: String = " "): String =
        encode(bytes, 0, bytes.size, separator)

    fun encode(bytes: ByteArray, from: Int, to: Int, separator: String = " "): String {
        require(from in 0..bytes.size) { "from out of range: $from" }
        require(to in from..bytes.size) { "to out of range: $to" }
        if (from == to) return ""
        val sb = StringBuilder((to - from) * (2 + separator.length))
        for (i in from until to) {
            if (i > from) sb.append(separator)
            val v = bytes[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Parses a hex string. Whitespace, `:`, `-`, `_` and `,` are ignored, and a `0x`
     * prefix in front of a byte is tolerated, so all of these parse to the same bytes:
     * `08ee00`, `08 ee 00`, `08:ee:00`, `0x08 0xee 0x00`.
     *
     * @throws IllegalArgumentException on a non-hex character or an odd digit count.
     */
    fun decode(text: String): ByteArray {
        val digits = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() || c == ':' || c == '-' || c == '_' || c == ',' -> i++
                c == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X') -> i += 2
                else -> {
                    digits.append(c)
                    i++
                }
            }
        }
        require(digits.length % 2 == 0) {
            "hex string has an odd number of digits (${digits.length}): '$text'"
        }
        val out = ByteArray(digits.length / 2)
        for (j in out.indices) {
            val hi = digit(digits[j * 2], text)
            val lo = digit(digits[j * 2 + 1], text)
            out[j] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Splits a template-style hex string into whitespace/comma separated tokens.
     * Used by [PayloadTemplate], which needs to see `??` placeholders as single tokens.
     */
    fun tokenize(text: String): List<String> =
        text.split(' ', '\t', '\n', '\r', ',', ':').filter { it.isNotEmpty() }

    private fun digit(c: Char, context: String): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("'$c' is not a hex digit, in '$context'")
    }
}
