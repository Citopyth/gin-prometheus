package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceGateTest {

    private val mine = "AC:BC:32:11:22:33"
    private val other = "00:0E:9F:9F:90:91"

    @Test
    fun `without a pin it comes down to whether a profile recognised the device`() {
        assertTrue(DeviceGate.check(null, mine, "soundcore Boom 2", recognised = true).isAllowed)
        val refused = assertIs<GateDecision.Refused>(
            DeviceGate.check(null, other, "JBL Wave Beam 2", recognised = false),
        )
        assertEquals("no-profile", refused.rule)
    }

    @Test
    fun `a pin beats recognition in both directions`() {
        // Pinned device accepted even when no profile knows it...
        assertTrue(DeviceGate.check(mine, mine, "Kueche", recognised = false).isAllowed)
        // ...and another speaker refused even with a perfect name.
        val refused = assertIs<GateDecision.Refused>(
            DeviceGate.check(mine, other, "soundcore Boom 2", recognised = true),
        )
        assertEquals("not-the-pinned-device", refused.rule)
        assertTrue(refused.message.contains(mine), refused.message)
        assertTrue(refused.message.contains(other), refused.message)
    }

    @Test
    fun `address comparison ignores case and padding`() {
        assertTrue(DeviceGate.check(mine.lowercase(), " $mine ", null, recognised = true).isAllowed)
        assertTrue(DeviceGate.isPinned(mine.lowercase(), mine.uppercase()))
        assertTrue(!DeviceGate.isPinned(mine, other))
        assertTrue(!DeviceGate.isPinned(null, mine), "no pin means nothing is pinned")
    }

    @Test
    fun `a device without an address is refused rather than guessed at`() {
        val refused = assertIs<GateDecision.Refused>(
            DeviceGate.check(null, null, "soundcore Boom 2", recognised = true),
        )
        assertEquals("no-address", refused.rule)
        assertEquals("no-address", (DeviceGate.check(mine, "  ", "x", recognised = true) as GateDecision.Refused).rule)
    }

    @Test
    fun `two speakers of the same model are told apart`() {
        // The case the name alone cannot handle: same model, same name, next room.
        val neighbour = "AC:BC:32:44:55:66"
        assertTrue(DeviceGate.check(null, neighbour, "soundcore Boom 2", recognised = true).isAllowed)
        assertTrue(!DeviceGate.check(mine, neighbour, "soundcore Boom 2", recognised = true).isAllowed)
    }

    @Test
    fun `normalise is stable`() {
        assertEquals("AA:BB:CC", DeviceGate.normalise(" aa:bb:cc "))
        assertEquals(null, DeviceGate.normalise(null))
        assertEquals(null, DeviceGate.normalise("   "))
    }
}
