package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HexTest {

    @Test
    fun `encodes lowercase with a separator`() {
        assertEquals("08 ee 00", Hex.encode(byteArrayOf(0x08, 0xEE.toByte(), 0x00)))
        assertEquals("08ee00", Hex.encode(byteArrayOf(0x08, 0xEE.toByte(), 0x00), ""))
        assertEquals("", Hex.encode(ByteArray(0)))
    }

    @Test
    fun `encodes a range`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals("02 03", Hex.encode(bytes, 1, 3))
        assertEquals("", Hex.encode(bytes, 2, 2))
    }

    @Test
    fun `decodes every separator style to the same bytes`() {
        val expected = byteArrayOf(0x08, 0xEE.toByte(), 0x00)
        for (text in listOf("08ee00", "08 ee 00", "08:ee:00", "08-ee-00", "0x08 0xEE 0x00", " 08\tEE\n00 ")) {
            assertTrue(expected.contentEquals(Hex.decode(text)), "failed for '$text'")
        }
    }

    @Test
    fun `round trips`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertTrue(bytes.contentEquals(Hex.decode(Hex.encode(bytes))))
    }

    @Test
    fun `rejects an odd digit count`() {
        val e = assertFailsWith<IllegalArgumentException> { Hex.decode("08e") }
        assertTrue(e.message!!.contains("odd"), e.message!!)
    }

    @Test
    fun `rejects a non hex character`() {
        val e = assertFailsWith<IllegalArgumentException> { Hex.decode("08 zz") }
        assertTrue(e.message!!.contains("not a hex digit"), e.message!!)
    }

    @Test
    fun `tokenize keeps placeholders whole`() {
        assertEquals(listOf("fe", "??", "??", "0a"), Hex.tokenize(" fe ?? ??  0a "))
        assertEquals(emptyList(), Hex.tokenize("   "))
    }
}
