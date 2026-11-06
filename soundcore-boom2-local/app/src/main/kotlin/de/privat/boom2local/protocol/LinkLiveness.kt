package de.privat.boom2local.protocol

/**
 * How long a new connection may stay silent before it is treated as the wrong pipe.
 *
 * @param graceMs how long after the link came up to keep waiting for a first frame
 * @param minFrames how many inbound frames count as a sign of life
 */
data class LivenessPolicy(
    val graceMs: Long = 6_000,
    val minFrames: Int = 1,
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
 * Silence alone is not enough to condemn a link, though: the app may simply not have
 * asked anything yet. The verdict needs both a question and no answer.
 */
object LinkLiveness {

    const val NO_ANSWER =
        "Dieser Dienst nimmt die Verbindung an, antwortet aber nicht. " +
            "Er ist nicht der Steuerkanal der Box."

    /**
     * @param framesReceived inbound frames over *this* link, including unsolicited ones
     * @param framesSent outbound frames over this link, handshake included
     * @param connectedAtMs when this link came up
     */
    fun verdict(
        policy: LivenessPolicy,
        connectedAtMs: Long,
        framesReceived: Int,
        framesSent: Int,
        nowMs: Long,
    ): Liveness = when {
        framesReceived >= policy.minFrames -> Liveness.Alive
        // Nothing was asked, so silence says nothing about the link.
        framesSent <= 0 -> Liveness.Waiting
        nowMs - connectedAtMs < policy.graceMs -> Liveness.Waiting
        else -> Liveness.Dead(NO_ANSWER)
    }
}
