package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvenServicesTest {

    private val box = "AC:23:3F:11:22:33"
    private val control = "0cf12d31-fac3-4553-bd80-d6832e7b3138"
    private val decoy = "0cf12d31-fac3-4553-bd80-d6832e7c3138"

    @Test
    fun `nothing is remembered to start with`() {
        assertNull(ProvenServices.proven(emptyMap(), box))
        assertEquals(emptyMap(), ProvenServices.decode(null))
        assertEquals(emptyMap(), ProvenServices.decode(""))
        assertEquals(emptyMap(), ProvenServices.decode("   "))
    }

    @Test
    fun `what answered is found again`() {
        val known = ProvenServices.remember(emptyMap(), box, control)
        assertEquals(control, ProvenServices.proven(known, box))
    }

    @Test
    fun `case and spacing in the address do not matter`() {
        val known = ProvenServices.remember(emptyMap(), " ac:23:3f:11:22:33 ", control.uppercase())
        assertEquals(control, ProvenServices.proven(known, box))
        assertEquals(control, ProvenServices.proven(known, box.lowercase()))
    }

    @Test
    fun `a later answer replaces an earlier one`() {
        var known = ProvenServices.remember(emptyMap(), box, decoy)
        known = ProvenServices.remember(known, box, control)
        assertEquals(control, ProvenServices.proven(known, box))
        assertEquals(1, known.size)
    }

    @Test
    fun `a malformed record is refused rather than stored`() {
        // A wrong entry here would send every later connect down the dead pipe, so
        // keeping nothing is strictly better than keeping rubbish.
        val good = ProvenServices.remember(emptyMap(), box, control)
        assertEquals(good, ProvenServices.remember(good, box, "not-a-uuid"))
        assertEquals(good, ProvenServices.remember(good, box, ""))
        assertEquals(good, ProvenServices.remember(good, "AC:23:3F", control))
        assertEquals(good, ProvenServices.remember(good, "", control))
        assertEquals(control, ProvenServices.proven(good, box))
    }

    @Test
    fun `an unchanged record is returned as the very same map`() {
        val known = ProvenServices.remember(emptyMap(), box, control)
        assertTrue(known === ProvenServices.remember(known, box, control))
    }

    @Test
    fun `forgetting one speaker leaves the others alone`() {
        var known = ProvenServices.remember(emptyMap(), box, control)
        known = ProvenServices.remember(known, "11:22:33:44:55:66", decoy)
        val after = ProvenServices.forget(known, box)
        assertNull(ProvenServices.proven(after, box))
        assertEquals(decoy, ProvenServices.proven(after, "11:22:33:44:55:66"))
    }

    @Test
    fun `the oldest record is dropped once the store is full`() {
        var known = emptyMap<String, String>()
        for (i in 1..ProvenServices.MAX_ENTRIES + 3) {
            known = ProvenServices.remember(known, address(i), control)
        }
        assertEquals(ProvenServices.MAX_ENTRIES, known.size)
        assertNull(ProvenServices.proven(known, address(1)))
        assertEquals(control, ProvenServices.proven(known, address(ProvenServices.MAX_ENTRIES + 3)))
    }

    @Test
    fun `a record survives a trip through the preferences file`() {
        var known = ProvenServices.remember(emptyMap(), box, control)
        known = ProvenServices.remember(known, "11:22:33:44:55:66", decoy)
        val back = ProvenServices.decode(ProvenServices.encode(known))
        assertEquals(known, back)
        assertEquals(known.keys.toList(), back.keys.toList())
    }

    @Test
    fun `a damaged preferences file costs a slower connect, not a crash`() {
        val stored = """
            garbage
            =nothing
            AC:23:3F:11:22:33=not-a-uuid
            NOTAMAC=$control
            AC:23:3F:11:22:33=$control
        """.trimIndent()
        val back = ProvenServices.decode(stored)
        assertEquals(mapOf(box to control), back)
    }

    @Test
    fun `an overlong preferences file is trimmed on reading`() {
        val lines = (1..ProvenServices.MAX_ENTRIES + 5).map { "${address(it)}=$control" }
        val back = ProvenServices.decode(lines.joinToString("\n"))
        assertEquals(ProvenServices.MAX_ENTRIES, back.size)
        assertNull(ProvenServices.proven(back, address(1)))
    }

    private fun address(i: Int) = "AA:BB:CC:DD:EE:%02X".format(i)
}
