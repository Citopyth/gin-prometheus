package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketPlanTest {

    @Test
    fun `automatic asks for the authenticated link first`() {
        // The change this file exists for. The speaker accepts both and requires
        // neither, and the profile's insecureSocket flag meant the app took the weaker
        // one every time, for no reason beyond it having worked first.
        assertEquals(
            listOf(SocketSecurity.AUTHENTICATED, SocketSecurity.OPEN),
            SocketPlan.order(SecurityPreference.AUTO, profileAllowsOpen = true),
        )
    }

    @Test
    fun `a profile that forbids the open socket gets only the strong one`() {
        assertEquals(
            listOf(SocketSecurity.AUTHENTICATED),
            SocketPlan.order(SecurityPreference.AUTO, profileAllowsOpen = false),
        )
    }

    @Test
    fun `an explicit choice is honoured even when it looks hopeless`() {
        // So a failure reports the real reason instead of being silently worked around.
        assertEquals(
            listOf(SocketSecurity.OPEN),
            SocketPlan.order(SecurityPreference.FORCE_OPEN, profileAllowsOpen = false),
        )
        assertEquals(
            listOf(SocketSecurity.AUTHENTICATED),
            SocketPlan.order(SecurityPreference.FORCE_AUTHENTICATED, profileAllowsOpen = true),
        )
    }

    @Test
    fun `the weak link says what it cannot promise`() {
        assertTrue(SocketPlan.isWeak(SocketSecurity.OPEN))
        assertTrue(!SocketPlan.isWeak(SocketSecurity.AUTHENTICATED))
        assertTrue(SocketPlan.describe(SocketSecurity.OPEN).contains("ausgeben"))
        assertTrue(
            SocketPlan.describe(SocketSecurity.AUTHENTICATED).contains("Kopplungsschluessel"),
        )
    }

    @Test
    fun `the shipped profile still allows the fallback`() {
        // Preferring the strong socket must not cost the connection that works today.
        val profile = TestFixtures.boom2Profile()
        assertTrue(profile.transport.insecureSocket)
        assertEquals(
            listOf(SocketSecurity.AUTHENTICATED, SocketSecurity.OPEN),
            SocketPlan.order(SecurityPreference.AUTO, profile.transport.insecureSocket),
        )
    }
}
