package de.privat.boom2local.protocol

/** A command that was sent and has not been answered yet. */
data class PendingCommand(val command: Command, val sentAtMs: Long)

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

    fun sent(command: Command, atMs: Long): PendingCommands =
        copy(entries = entries + PendingCommand(command, atMs))

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
