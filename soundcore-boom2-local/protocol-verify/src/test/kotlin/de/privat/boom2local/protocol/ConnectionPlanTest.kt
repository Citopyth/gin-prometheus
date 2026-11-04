package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionPlanTest {

    @Test
    fun `automatic tries classic first, then ble`() {
        assertEquals(
            listOf(TransportKind.RFCOMM, TransportKind.BLE),
            ConnectionPlan.plan(TransportPreference.AUTO, rfcommCandidates = 3, bleEnabled = true),
        )
    }

    @Test
    fun `automatic skips classic when the device offers nothing to connect to`() {
        assertEquals(
            listOf(TransportKind.BLE),
            ConnectionPlan.plan(TransportPreference.AUTO, rfcommCandidates = 0, bleEnabled = true),
        )
    }

    @Test
    fun `automatic skips ble when the profile disables it`() {
        assertEquals(
            listOf(TransportKind.RFCOMM),
            ConnectionPlan.plan(TransportPreference.AUTO, rfcommCandidates = 1, bleEnabled = false),
        )
    }

    @Test
    fun `an explicit choice is honoured even when it looks hopeless`() {
        // Otherwise the attempt is skipped silently and the user is told nothing, which
        // is indistinguishable from the speaker refusing to answer.
        assertEquals(
            listOf(TransportKind.RFCOMM),
            ConnectionPlan.plan(TransportPreference.RFCOMM, rfcommCandidates = 0, bleEnabled = true),
        )
        assertEquals(
            listOf(TransportKind.BLE),
            ConnectionPlan.plan(TransportPreference.BLE, rfcommCandidates = 5, bleEnabled = false),
        )
    }

    @Test
    fun `nothing to try is only possible on automatic`() {
        assertEquals(
            emptyList(),
            ConnectionPlan.plan(TransportPreference.AUTO, rfcommCandidates = 0, bleEnabled = false),
        )
        for (preference in listOf(TransportPreference.RFCOMM, TransportPreference.BLE)) {
            assertTrue(
                ConnectionPlan.plan(preference, 0, false).isNotEmpty(),
                "$preference must still be attempted",
            )
        }
    }

    @Test
    fun `the explanation names the actual obstacle`() {
        assertTrue(ConnectionPlan.whyEmpty(0, false).contains("RFCOMM"))
        assertTrue(ConnectionPlan.whyEmpty(0, false).contains("BLE"))
        assertTrue(!ConnectionPlan.whyEmpty(0, true).contains("BLE"))
        assertTrue(!ConnectionPlan.whyEmpty(2, false).contains("RFCOMM"))
    }

    @Test
    fun `the shipped profile yields both transports once a service is known`() {
        val profile = TestFixtures.boom2Profile()
        val candidates = ServiceCandidates.order(
            listOf("0cf12d31-fac3-4553-bd80-d6832e7b3138"),
            profile.transport,
        )
        assertEquals(
            listOf(TransportKind.RFCOMM, TransportKind.BLE),
            ConnectionPlan.plan(TransportPreference.AUTO, candidates.size, profile.ble.enabled),
        )
    }
}
