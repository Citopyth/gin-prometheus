package de.privat.boom2local.protocol

/**
 * How long a new connection may stay silent before it is treated as the wrong pipe.
 *
 * @param graceMs how long after the link came up to keep waiting for a first frame
 * @param minFrames how many inbound frames count as a sign of life
 * @param minChecks how many times the app must have looked and found nothing
 */
data class LivenessPolicy(
    val graceMs: Long = 6_000,
    val minFrames: Int = 1,
    val minChecks: Int = 3,
)

/** What the liveness check concluded. */
sealed interface Liveness {
    /** Still inside the grace period, nothing decided yet. */
    data object Waiting : Liveness

    /** The speaker has spoken over this link. */
    data object Alive : Liveness

    /** Nothing came back in time; [reason] is fit to show the user. */
    data class Dead(val reason: String) : Liveness
}

/**
 * Decides whether a connection that came up is actually talking to the speaker.
 *
 * A connected RFCOMM socket proves only that something accepted it. The Boom 2 has a
 * second vendor service that does exactly that and then stays mute: eleven frames went
 * out over it in one session - a state request, a seven-step handshake, two writes - and
 * not one byte came back, while the app reported a healthy connection the whole time.
 *
 * So "connected" is not the same as "reachable", and the difference has to be measured.
 * Silence alone is not enough to condemn a link, though. Three things have to hold
 * before a service is written off, and each rules out a different false accusation:
 *
 *  - something was actually asked of it, or silence says nothing at all;
 *  - enough wall-clock time has passed for an answer to arrive;
 *  - the app has looked that often and found nothing.
 *
 * The last one is not the same as the second, and it is the one that matters on a phone.
 * Android freezes a sleeping app and hands it a backlog on waking - two captures show
 * 396 and 352 bytes of buffered notifications arriving in a single read. A frozen app
 * does not stop the clock, so wall time alone would condemn the *correct* service the
 * moment the screen had been off for a while, leaving the user with no connection at all
 * because of a nap. Counting the app's own observations cannot run while it is frozen,
 * so after waking there are a few more seconds for the backlog to land.
 */
object LinkLiveness {

    const val NO_ANSWER =
        "Dieser Dienst nimmt die Verbindung an, antwortet aber nicht. " +
            "Er ist nicht der Steuerkanal der Box."

    /**
     * @param framesReceived inbound frames over *this* link, including unsolicited ones
     * @param framesSent outbound frames over this link, handshake included
     * @param connectedAtMs when this link came up
     * @param checksWhileSilent how many times this check has run with nothing received,
     *   which only grows while the app is actually running
     */
    fun verdict(
        policy: LivenessPolicy,
        connectedAtMs: Long,
        framesReceived: Int,
        framesSent: Int,
        checksWhileSilent: Int,
        nowMs: Long,
    ): Liveness = when {
        framesReceived >= policy.minFrames -> Liveness.Alive
        // Nothing was asked, so silence says nothing about the link.
        framesSent <= 0 -> Liveness.Waiting
        nowMs - connectedAtMs < policy.graceMs -> Liveness.Waiting
        // The clock ran while the app was frozen; its own observations did not.
        checksWhileSilent < policy.minChecks -> Liveness.Waiting
        else -> Liveness.Dead(NO_ANSWER)
    }
}
