package de.privat.boom2local.protocol

/** Where a connection attempt currently stands. */
enum class SessionPhase { IDLE, CONNECTING, CONNECTED, FAILED }

/**
 * How hard to try again after the speaker drops the link.
 *
 * Off by default. A speaker that hangs up usually has a reason, and an app that
 * reconnects by itself in a loop is a good way to keep a device busy and a battery
 * draining without anyone noticing.
 */
data class ReconnectPolicy(
    val enabled: Boolean = false,
    val maxAttempts: Int = 3,
    val firstDelayMs: Long = 2_000,
    val maxDelayMs: Long = 30_000,
) {
    /** Delay before the [attempt]-th try, counting from 1. Doubles, then caps. */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return firstDelayMs
        var delay = firstDelayMs
        repeat(attempt - 1) {
            if (delay < maxDelayMs) delay *= 2
        }
        return delay.coerceAtMost(maxDelayMs)
    }

    fun allows(retriesSoFar: Int): Boolean = enabled && retriesSoFar < maxAttempts
}

/** Everything the UI needs to know about the link, and nothing about sockets. */
data class SessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    /** Name of the transport in play, e.g. "RFCOMM (klassisch)". */
    val transport: String? = null,
    /** What it connected to, for display. */
    val label: String? = null,
    val detail: String? = null,
    /** Why it is not connected. */
    val reason: String? = null,
    /** One entry per transport that was tried and failed, in order. */
    val failures: List<String> = emptyList(),
    /** Automatic retries used since the last successful link. */
    val retries: Int = 0,
    /** Set when the caller should try again after this many milliseconds. */
    val retryInMs: Long? = null,
)

/** What happened. */
sealed interface SessionEvent {
    /** A transport is being tried. */
    data class Attempting(val transport: String) : SessionEvent

    /** That transport did not get through; there may be another. */
    data class AttemptFailed(val transport: String, val reason: String) : SessionEvent

    /** Every transport has been tried and none worked. */
    data class AllAttemptsFailed(val hint: String? = null) : SessionEvent

    data class Established(
        val transport: String,
        val label: String,
        val detail: String,
    ) : SessionEvent

    /**
     * A transport reports that its link is gone.
     *
     * It names itself, because a transport that was abandoned earlier still gets to
     * report that it is gone, and that notice must not be mistaken for the live link
     * breaking.
     */
    data class LinkLost(val transport: String, val reason: String) : SessionEvent

    /** The user pressed disconnect. */
    data object UserDisconnected : SessionEvent

    /** An automatic retry is starting now. */
    data object RetryStarted : SessionEvent
}

/**
 * The rules for what a connection does when it breaks.
 *
 * A pure function of state and event, which is the point: the awkward cases here are
 * about ordering, not about sockets, and ordering is exactly what is hard to reproduce
 * against real hardware and easy to get wrong in a coroutine. The case that matters
 * most is a disconnect notice arriving *after* the user already pressed disconnect, or
 * from a transport that was abandoned when a second one succeeded — a late message from
 * a dead socket must not pull a working session back into an error state.
 */
object SessionMachine {

    fun next(
        state: SessionState,
        event: SessionEvent,
        policy: ReconnectPolicy = ReconnectPolicy(),
    ): SessionState = when (event) {
        is SessionEvent.Attempting -> state.copy(
            phase = SessionPhase.CONNECTING,
            transport = event.transport,
            reason = null,
            retryInMs = null,
        )

        is SessionEvent.AttemptFailed -> state.copy(
            phase = SessionPhase.CONNECTING,
            failures = state.failures + "${event.transport}: ${event.reason}",
            retryInMs = null,
        )

        is SessionEvent.AllAttemptsFailed -> fail(
            state = state,
            reason = failureText(state.failures, event.hint),
            policy = policy,
        )

        is SessionEvent.Established -> SessionState(
            phase = SessionPhase.CONNECTED,
            transport = event.transport,
            label = event.label,
            detail = event.detail,
            // A link that came up clears the history: the next failure should not
            // report errors from before it worked.
            failures = emptyList(),
            retries = 0,
            retryInMs = null,
        )

        is SessionEvent.LinkLost ->
            // Only the link that is actually up can be lost. Anything else is a late
            // message from a socket that is already gone — a transport abandoned
            // earlier in the same connect, or one the user hung up on — and acting on
            // it would overwrite a newer, truer state.
            if (state.phase == SessionPhase.CONNECTED && event.transport == state.transport) {
                fail(state, "Verbindung verloren: ${event.reason}", policy)
            } else {
                state
            }

        SessionEvent.UserDisconnected -> SessionState()

        SessionEvent.RetryStarted -> state.copy(
            phase = SessionPhase.CONNECTING,
            reason = null,
            failures = emptyList(),
            retries = state.retries + 1,
            retryInMs = null,
        )
    }

    private fun fail(
        state: SessionState,
        reason: String,
        policy: ReconnectPolicy,
    ): SessionState = state.copy(
        phase = SessionPhase.FAILED,
        reason = reason,
        retryInMs = if (policy.allows(state.retries)) {
            policy.delayFor(state.retries + 1)
        } else {
            null
        },
    )

    /** The message shown when nothing got through, naming every transport tried. */
    fun failureText(failures: List<String>, hint: String?): String {
        val body = when {
            failures.isEmpty() -> "Verbindung fehlgeschlagen."
            else -> failures.joinToString("\n\n")
        }
        return if (hint.isNullOrBlank()) body else "$body\n\n$hint"
    }
}
