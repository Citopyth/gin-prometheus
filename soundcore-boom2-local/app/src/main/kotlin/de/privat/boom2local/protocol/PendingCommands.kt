package de.privat.boom2local.protocol

/** A command that was sent and has not been answered yet. */
data class PendingCommand(
    val command: Command,
    val sentAtMs: Long,
    val kind: CommandKind = CommandKind.WRITE,
    /** The payload as hex, so a repeat sends exactly what the original did. */
    val payloadHex: String = "",
    /** 1 for the original send, 2 for the first repeat, and so on. */
    val attempt: Int = 1,
)

/** How to treat a command the speaker never answered. */
data class SilencePolicy(
    /**
     * How often an unanswered read may be sent again.
     *
     * Only reads. A read asks a question and asking twice changes nothing, while a
     * write might not be idempotent — repeating an equaliser write because an
     * acknowledgement was slow could leave the speaker somewhere nobody asked for.
     */
    val readResends: Int = 2,
)

/** What to do about one unanswered command. */
sealed interface SilenceAction {
    data class Resend(val pending: PendingCommand) : SilenceAction
    data class GiveUp(val pending: PendingCommand) : SilenceAction
}

/**
 * Decides what an unanswered command deserves.
 *
 * The case this is for: the speaker regularly misses the very first frame after a
 * connection comes up — in four captures the first request went out 0 to 2 ms after
 * the socket opened and was answered twice and ignored twice, while commands sent
 * later on the same connection were all answered. A read that goes unanswered is
 * therefore worth repeating; a write is not.
 */
object SilenceResponse {

    fun decide(
        overdue: List<PendingCommand>,
        policy: SilencePolicy = SilencePolicy(),
    ): List<SilenceAction> = overdue.map { pending ->
        val mayResend = pending.kind == CommandKind.READ &&
            pending.attempt <= policy.readResends
        if (mayResend) SilenceAction.Resend(pending) else SilenceAction.GiveUp(pending)
    }
}

/**
 * Tracks which commands are still waiting for an answer.
 *
 * The reason this exists: a Soundcore speaker can stop answering without closing the
 * socket. In one capture, twelve frames went out over half a minute and nothing came
 * back, the read loop never ended, and the app reported every one of them as sent. From
 * the outside that is indistinguishable from working. Noticing it takes nothing more
 * than remembering what was sent and when.
 */
data class PendingCommands(
    val entries: List<PendingCommand> = emptyList(),
    /** How long an answer may take before the silence counts as an answer in itself. */
    val timeoutMs: Long = 3_000,
) {
    val size: Int get() = entries.size

    fun sent(
        command: Command,
        atMs: Long,
        kind: CommandKind = CommandKind.WRITE,
        payloadHex: String = "",
        attempt: Int = 1,
    ): PendingCommands =
        copy(entries = entries + PendingCommand(command, atMs, kind, payloadHex, attempt))

    /**
     * Clears the oldest entry for [command].
     *
     * Oldest first, because the speaker answers in order and the same command can be in
     * flight more than once — the brightness slider sends four in a second.
     */
    fun answered(command: Command): PendingCommands {
        val index = entries.indexOfFirst { it.command == command }
        if (index < 0) return this
        return copy(entries = entries.filterIndexed { at, _ -> at != index })
    }

    /** Anything the speaker has stayed quiet about for too long. */
    fun overdue(nowMs: Long): List<PendingCommand> =
        entries.filter { nowMs - it.sentAtMs >= timeoutMs }

    /** The same list with the overdue entries removed, so each is reported once. */
    fun withoutOverdue(nowMs: Long): PendingCommands =
        copy(entries = entries.filterNot { nowMs - it.sentAtMs >= timeoutMs })

    fun cleared(): PendingCommands = copy(entries = emptyList())
}
