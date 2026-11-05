package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionMachineTest {

    private val off = ReconnectPolicy(enabled = false)
    private val on = ReconnectPolicy(enabled = true, maxAttempts = 3)

    private fun run(
        policy: ReconnectPolicy = off,
        vararg events: SessionEvent,
    ): SessionState = events.fold(SessionState()) { state, event ->
        SessionMachine.next(state, event, policy)
    }

    // --- the ordinary path --------------------------------------------------------

    @Test
    fun `a straightforward connect ends up connected`() {
        val state = run(
            off,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "0cf1…3138", "ohne Authentifizierung"),
        )
        assertEquals(SessionPhase.CONNECTED, state.phase)
        assertEquals("RFCOMM", state.transport)
        assertEquals("0cf1…3138", state.label)
        assertNull(state.reason)
    }

    @Test
    fun `a transport that fails is remembered until another one succeeds`() {
        val state = run(
            off,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.AttemptFailed("RFCOMM", "read return: -1"),
            SessionEvent.Attempting("BLE"),
            SessionEvent.Established("BLE", "service", "GATT"),
        )
        assertEquals(SessionPhase.CONNECTED, state.phase)
        assertEquals(
            emptyList(),
            state.failures,
            "a link that came up must not keep reporting the errors from before it",
        )
    }

    @Test
    fun `when nothing gets through, every transport is named`() {
        val state = run(
            off,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.AttemptFailed("RFCOMM", "read return: -1"),
            SessionEvent.Attempting("BLE"),
            SessionEvent.AttemptFailed("BLE", "Dienstsuche fehlgeschlagen"),
            SessionEvent.AllAttemptsFailed("Erst SDP neu abfragen."),
        )
        assertEquals(SessionPhase.FAILED, state.phase)
        val reason = state.reason.orEmpty()
        assertTrue(reason.contains("RFCOMM: read return: -1"), reason)
        assertTrue(reason.contains("BLE: Dienstsuche fehlgeschlagen"), reason)
        assertTrue(reason.endsWith("Erst SDP neu abfragen."), reason)
    }

    // --- the awkward orderings ----------------------------------------------------

    @Test
    fun `a disconnect notice arriving after the user hung up changes nothing`() {
        // The case this machine exists for: a socket that is already gone still gets
        // to report that it is gone, and that must not resurrect an error state.
        val state = run(
            on,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"),
            SessionEvent.UserDisconnected,
            SessionEvent.LinkLost("RFCOMM", "bt socket closed, read return: -1"),
        )
        assertEquals(SessionPhase.IDLE, state.phase)
        assertNull(state.reason)
        assertNull(state.retryInMs, "and it must not schedule a reconnect either")
    }

    @Test
    fun `a disconnect from the abandoned transport does not disturb the live one`() {
        // RFCOMM was given up on, BLE got through. RFCOMM's read loop then notices it
        // is dead and says so. That notice belongs to a socket nobody is using.
        val state = run(
            on,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.AttemptFailed("RFCOMM", "weg"),
            SessionEvent.Attempting("BLE"),
            SessionEvent.Established("BLE", "x", "y"),
            SessionEvent.LinkLost("RFCOMM", "Leseschleife beendet"),
        )
        assertEquals(SessionPhase.CONNECTED, state.phase)
        assertNull(state.retryInMs)
        assertEquals("BLE", state.transport)
    }

    @Test
    fun `the live transport losing its link does count`() {
        val state = run(
            on,
            SessionEvent.Attempting("BLE"),
            SessionEvent.Established("BLE", "x", "y"),
            SessionEvent.LinkLost("BLE", "GATT-Verbindung beendet"),
        )
        assertEquals(SessionPhase.FAILED, state.phase)
        assertTrue(state.reason.orEmpty().contains("GATT-Verbindung beendet"))
    }

    @Test
    fun `a loss while still connecting is ignored`() {
        val state = run(
            on,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.LinkLost("RFCOMM", "irgendwas"),
        )
        assertEquals(SessionPhase.CONNECTING, state.phase)
        assertNull(state.retryInMs)
    }

    @Test
    fun `a second loss after failing does not fail twice or retry twice`() {
        val once = run(
            on,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"),
            SessionEvent.LinkLost("RFCOMM", "weg"),
        )
        val twice = SessionMachine.next(once, SessionEvent.LinkLost("RFCOMM", "auch weg"), on)
        assertEquals(once, twice)
    }

    // --- retrying -------------------------------------------------------------------

    @Test
    fun `nothing is retried unless retrying is switched on`() {
        val state = run(
            off,
            SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"),
            SessionEvent.LinkLost("RFCOMM", "weg"),
        )
        assertEquals(SessionPhase.FAILED, state.phase)
        assertNull(state.retryInMs)
    }

    @Test
    fun `the wait before each retry doubles and then stops growing`() {
        val policy = ReconnectPolicy(enabled = true, firstDelayMs = 2_000, maxDelayMs = 30_000)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..6).map { policy.delayFor(it) })
        assertEquals(30_000L, policy.delayFor(1000), "no overflow on a long outage")
    }

    @Test
    fun `retries stop at the limit`() {
        var state = run(on, SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"))
        val waits = mutableListOf<Long?>()
        repeat(4) {
            state = SessionMachine.next(state, SessionEvent.LinkLost("RFCOMM", "weg"), on)
            waits += state.retryInMs
            if (state.retryInMs != null) {
                state = SessionMachine.next(state, SessionEvent.RetryStarted, on)
                state = SessionMachine.next(
                    state, SessionEvent.Established("RFCOMM", "x", "y"), on,
                )
                // a successful reconnect resets the counter, so undo that for this test
                state = state.copy(retries = waits.count { w -> w != null })
            }
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, null), waits)
    }

    @Test
    fun `a link that came back resets the retry counter`() {
        var state = run(on, SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"))
        state = SessionMachine.next(state, SessionEvent.LinkLost("RFCOMM", "weg"), on)
        state = SessionMachine.next(state, SessionEvent.RetryStarted, on)
        assertEquals(1, state.retries)
        state = SessionMachine.next(state, SessionEvent.Established("RFCOMM", "x", "y"), on)
        assertEquals(0, state.retries, "an outage later must get the full allowance again")
    }

    @Test
    fun `hanging up cancels a retry that was already scheduled`() {
        var state = run(on, SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"),
            SessionEvent.LinkLost("RFCOMM", "weg"))
        assertEquals(2_000L, state.retryInMs)
        state = SessionMachine.next(state, SessionEvent.UserDisconnected, on)
        assertEquals(SessionState(), state)
    }

    @Test
    fun `a retry clears the errors of the attempt before it`() {
        var state = run(on, SessionEvent.Attempting("RFCOMM"),
            SessionEvent.Established("RFCOMM", "x", "y"),
            SessionEvent.LinkLost("RFCOMM", "weg"))
        state = SessionMachine.next(state, SessionEvent.RetryStarted, on)
        assertEquals(SessionPhase.CONNECTING, state.phase)
        assertEquals(emptyList(), state.failures)
        assertNull(state.reason)
    }

    // --- the message -----------------------------------------------------------------

    @Test
    fun `the failure text reads sensibly in every shape`() {
        assertEquals("Verbindung fehlgeschlagen.", SessionMachine.failureText(emptyList(), null))
        assertEquals("a\n\nb", SessionMachine.failureText(listOf("a", "b"), null))
        assertEquals("a\n\nTipp", SessionMachine.failureText(listOf("a"), "Tipp"))
        assertEquals("a", SessionMachine.failureText(listOf("a"), "   "))
    }
}
