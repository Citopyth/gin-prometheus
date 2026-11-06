package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkLivenessTest {

    private val policy = LivenessPolicy()

    /** Enough looks to satisfy the observation rule, so each test varies one thing. */
    private fun verdict(received: Int, sent: Int, elapsed: Long, checks: Int = 99) =
        LinkLiveness.verdict(
            policy = policy,
            connectedAtMs = 1_000,
            framesReceived = received,
            framesSent = sent,
            checksWhileSilent = checks,
            nowMs = 1_000 + elapsed,
        )

    @Test
    fun `one inbound frame is enough to call the link alive`() {
        assertEquals(Liveness.Alive, verdict(received = 1, sent = 1, elapsed = 10))
    }

    @Test
    fun `an answer after the grace period still counts`() {
        // The verdict is about whether the speaker ever talks, not how fast.
        assertEquals(Liveness.Alive, verdict(received = 1, sent = 3, elapsed = 60_000))
    }

    @Test
    fun `silence inside the grace period decides nothing`() {
        assertEquals(Liveness.Waiting, verdict(received = 0, sent = 1, elapsed = 10))
        assertEquals(Liveness.Waiting, verdict(received = 0, sent = 1, elapsed = policy.graceMs - 1))
    }

    @Test
    fun `silence after a question and the grace period condemns the service`() {
        val out = verdict(received = 0, sent = 1, elapsed = policy.graceMs)
        assertTrue(out is Liveness.Dead, out.toString())
        assertEquals(LinkLiveness.NO_ANSWER, (out as Liveness.Dead).reason)
    }

    @Test
    fun `a link nobody asked anything is never condemned`() {
        // Without a question, silence is just quiet. Condemning here would kill a
        // perfectly good link whenever the user connects and then puts the phone down.
        assertEquals(Liveness.Waiting, verdict(received = 0, sent = 0, elapsed = 10 * policy.graceMs))
    }

    @Test
    fun `the real session is reproduced`() {
        // Entries 172 to 187 of the captured log: connected to the decoy service, then
        // a state request, a seven-step handshake and two writes, and nothing back.
        // The app called that a healthy connection for over three minutes.
        assertEquals(
            Liveness.Dead(LinkLiveness.NO_ANSWER),
            verdict(received = 0, sent = 11, elapsed = 200_000, checks = 90),
        )
    }

    @Test
    fun `a stricter policy can demand more than one frame`() {
        val strict = LivenessPolicy(graceMs = 2_000, minFrames = 2, minChecks = 1)
        fun strictly(received: Int, now: Long) = LinkLiveness.verdict(
            policy = strict,
            connectedAtMs = 0,
            framesReceived = received,
            framesSent = 1,
            checksWhileSilent = 1,
            nowMs = now,
        )
        assertEquals(Liveness.Waiting, strictly(received = 1, now = 1_000))
        assertEquals(Liveness.Alive, strictly(received = 2, now = 1_000))
        assertTrue(strictly(received = 1, now = 9_000) is Liveness.Dead)
    }

    @Test
    fun `a phone that was asleep does not get its good service written off`() {
        // The case that would hurt most. Android freezes a sleeping app and hands it the
        // backlog on waking; two captures show 396 and 352 bytes arriving in one read.
        // The clock ran the whole time, so wall time alone says "mute for four minutes"
        // about the one service that actually works, and the user ends up with no
        // connection because the screen was off.
        assertEquals(
            Liveness.Waiting,
            verdict(received = 0, sent = 11, elapsed = 240_000, checks = 1),
        )
        // A few seconds of being awake is all it takes to decide properly either way.
        assertEquals(
            Liveness.Alive,
            verdict(received = 36, sent = 11, elapsed = 240_000, checks = 1),
        )
        assertTrue(
            verdict(received = 0, sent = 11, elapsed = 240_000, checks = policy.minChecks)
                is Liveness.Dead,
        )
    }

    @Test
    fun `looking often without the clock moving is not enough either`() {
        // The mirror image: a tight loop must not condemn a service in 50 ms.
        assertEquals(
            Liveness.Waiting,
            verdict(received = 0, sent = 1, elapsed = 50, checks = 1_000),
        )
    }

    @Test
    fun `the three conditions are each necessary on their own`() {
        val sent = 5
        val long = policy.graceMs + 1
        val enough = policy.minChecks
        assertTrue(verdict(0, sent, long, enough) is Liveness.Dead)
        // Drop any one of them and the verdict goes back to waiting.
        assertEquals(Liveness.Waiting, verdict(0, sent = 0, elapsed = long, checks = enough))
        assertEquals(Liveness.Waiting, verdict(0, sent, elapsed = 0, checks = enough))
        assertEquals(Liveness.Waiting, verdict(0, sent, long, checks = enough - 1))
    }

    @Test
    fun `the default grace period outlasts the speaker's own reporting interval`() {
        // The Boom 2 sends its battery level unasked roughly every 30 seconds, but the
        // grace period must not wait for that: a wrong service has to be dropped while
        // the user is still looking at the screen. One answered request is the signal.
        assertTrue(policy.graceMs in 3_000..15_000, "grace=${policy.graceMs}")
    }
}
