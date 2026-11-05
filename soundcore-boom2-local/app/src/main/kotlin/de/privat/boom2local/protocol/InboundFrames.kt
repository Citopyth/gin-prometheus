package de.privat.boom2local.protocol

/** What an incoming frame turned out to be. */
sealed interface Inbound {

    /** The full state blob, in answer to a state request. */
    data class State(val decoded: DecodedState) : Inbound

    /** A value the speaker announced on its own. */
    data class Notification(val field: DecodedField) : Inbound

    /**
     * An empty frame echoing a command back: the speaker understood it.
     *
     * The Boom 2 answers every write within a fraction of a second with a frame
     * carrying the same command address and no body. That is worth recognising for two
     * reasons: it tells you a command exists rather than being ignored, and its absence
     * tells you one does not.
     */
    data class Acknowledgement(val command: Command, val label: String?) : Inbound

    /** Nothing in the profile describes this. */
    data class Unknown(val command: Command, val body: ByteArray) : Inbound {
        override fun equals(other: Any?): Boolean =
            other is Unknown && command == other.command && body.contentEquals(other.body)

        override fun hashCode(): Int = 31 * command.hashCode() + body.contentHashCode()
    }
}

/**
 * Sorts an incoming frame into one of the four things it can be.
 *
 * The app used to decide this inline in three places; having it here means the order of
 * the checks is written down once and can be tested, including the case that is easy to
 * get backwards — an empty frame whose command happens to be the state request is an
 * acknowledgement, not an empty state.
 */
object InboundClassifier {

    fun classify(profile: DeviceProfile, frame: Frame): Inbound {
        if (frame.command == profile.stateResponseCommand && frame.body.isNotEmpty()) {
            return Inbound.State(StateDecoder.decode(profile, frame))
        }
        StateDecoder.decodeNotification(profile, frame)?.let {
            return Inbound.Notification(it)
        }
        if (frame.body.isEmpty()) {
            val known = profile.commands.firstOrNull { it.commandAddress == frame.command }
            if (known != null) return Inbound.Acknowledgement(frame.command, known.label)
        }
        return Inbound.Unknown(frame.command, frame.body)
    }
}
