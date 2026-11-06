package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkLivenessTest {

    private val policy = LivenessPolicy()

    private fun verdict(received: Int, sent: Int, elapsed: Long) =
        LinkLiveness.verdict(policy, connectedAtMs = 1_000, received, sent, nowMs = 1_000 + elapsed)

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
            verdict(received = 0, sent = 11, elapsed = 200_000),
        )
    }

    @Test
    fun `a stricter policy can demand more than one frame`() {
        val strict = LivenessPolicy(graceMs = 2_000, minFrames = 2)
        assertEquals(
            Liveness.Waiting,
            LinkLiveness.verdict(strict, 0, framesReceived = 1, framesSent = 1, nowMs = 1_000),
        )
        assertEquals(
            Liveness.Alive,
            LinkLiveness.verdict(strict, 0, framesReceived = 2, framesSent = 1, nowMs = 1_000),
        )
        assertTrue(
            LinkLiveness.verdict(strict, 0, framesReceived = 1, framesSent = 1, nowMs = 9_000)
                is Liveness.Dead,
        )
    }

    @Test
    fun `the default grace period outlasts the speaker's own reporting interval`() {
        // The Boom 2 sends its battery level unasked roughly every 30 seconds, but the
        // grace period must not wait for that: a wrong service has to be dropped while
        // the user is still looking at the screen. One answered request is the signal.
        assertTrue(policy.graceMs in 3_000..15_000, "grace=${policy.graceMs}")
    }
}
