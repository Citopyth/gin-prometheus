package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadTemplateTest {

    @Test
    fun `empty template produces an empty payload`() {
        val t = PayloadTemplate.parse("")
        assertEquals(0, t.size)
        assertTrue(t.isEmpty)
        assertEquals(0, t.build().size)
    }

    @Test
    fun `fixed bytes pass through`() {
        val t = PayloadTemplate.parse("fe 0a")
        assertEquals(emptyList(), t.placeholderIndices)
        assertTrue(byteArrayOf(0xFE.toByte(), 0x0A).contentEquals(t.build()))
    }

    @Test
    fun `placeholders are filled by index`() {
        val t = PayloadTemplate.parse("fe ?? ?? ?? ?? 0a")
        assertEquals(listOf(1, 2, 3, 4), t.placeholderIndices)
        val payload = t.build(mapOf(1 to 0x01, 2 to 0x02, 3 to 0x78, 4 to 0x00))
        assertEquals("fe 01 02 78 00 0a", Hex.encode(payload))
    }

    @Test
    fun `a missing placeholder value is an error, not a zero`() {
        val t = PayloadTemplate.parse("fe ?? 0a")
        val e = assertFailsWith<IllegalArgumentException> { t.build() }
        assertTrue(e.message!!.contains("placeholder"), e.message!!)
    }

    @Test
    fun `a fixed byte cannot be overridden`() {
        val t = PayloadTemplate.parse("fe ?? 0a")
        val e = assertFailsWith<IllegalArgumentException> { t.build(mapOf(1 to 1, 0 to 0x00)) }
        assertTrue(e.message!!.contains("fixed"), e.message!!)
    }

    @Test
    fun `values outside a byte are rejected`() {
        val t = PayloadTemplate.parse("??")
        assertFailsWith<IllegalArgumentException> { t.build(mapOf(0 to 256)) }
        assertFailsWith<IllegalArgumentException> { t.build(mapOf(0 to -1)) }
    }

    @Test
    fun `an index past the template is rejected`() {
        val t = PayloadTemplate.parse("??")
        assertFailsWith<IllegalArgumentException> { t.build(mapOf(0 to 1, 5 to 1)) }
    }

    @Test
    fun `a multi byte token is rejected`() {
        assertFailsWith<IllegalArgumentException> { PayloadTemplate.parse("fe0a ??") }
    }

    @Test
    fun `toString shows the holes`() {
        assertEquals("fe ?? 0a", PayloadTemplate.parse("fe ?? 0a").toString())
    }
}
