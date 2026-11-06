package de.privat.boom2local.protocol

/**
 * One sound setting the app applied, in terms the profile can replay.
 *
 * Deliberately a command key and its arguments rather than raw bytes: replaying it then
 * goes through [CommandPolicy] like any other write - allowlist, read-only, argument
 * ranges and all - instead of needing the raw mode that is off by default. An undo
 * button that only works with the safety catch released is not an undo button.
 */
data class SoundSetting(
    val label: String,
    val commandKey: String,
    val arguments: Map<String, Int> = emptyMap(),
)

/** What to send to go one step back, and what the history looks like afterwards. */
data class UndoPlan(
    val reapply: SoundSetting,
    val remaining: List<SoundSetting>,
    /** True when this put the speaker back on the neutral curve. */
    val atNeutral: Boolean,
)

/**
 * Makes every sound change the app applies reversible with one tap.
 *
 * The honest limit, stated here because the UI has to state it too: nobody ever read
 * this speaker's equaliser *before* the app first wrote to it, so there is no "as you
 * found it" to go back to. What the floor of this stack offers is the one setting there
 * is evidence for - every band at 0 dB, the value all nine bands held in the only state
 * frame ever captured. Undo walks back through what this app did and ends there, not
 * wherever the speaker left the factory.
 *
 * Kept out of Android because the awkward parts are off-by-one and ordering: undoing
 * the first change has to land on neutral, undoing nothing must still be safe, and an
 * evening of listening must not grow the stack without bound.
 */
object SoundHistory {

    /** Deep enough for an evening of listening, shallow enough to stay honest. */
    const val MAX_DEPTH = 32

    /** The profile command that puts every equaliser band back to 0 dB. */
    const val NEUTRAL_COMMAND_KEY = "eqFlat"

    val NEUTRAL = SoundSetting(
        label = "Alle Baender 0.0 dB",
        commandKey = NEUTRAL_COMMAND_KEY,
    )

    /** Records a setting as applied. The newest is last. */
    fun push(history: List<SoundSetting>, setting: SoundSetting): List<SoundSetting> {
        val next = history + setting
        return if (next.size <= MAX_DEPTH) next else next.takeLast(MAX_DEPTH)
    }

    /**
     * One step back.
     *
     * Re-applies the setting *below* the newest one, or neutral when there is nothing
     * below it. An empty history also yields neutral, so the button never leaves the
     * speaker somewhere nobody chose.
     */
    fun undo(history: List<SoundSetting>): UndoPlan {
        if (history.isEmpty()) return UndoPlan(NEUTRAL, emptyList(), atNeutral = true)
        val remaining = history.dropLast(1)
        return UndoPlan(
            reapply = remaining.lastOrNull() ?: NEUTRAL,
            remaining = remaining,
            atNeutral = remaining.isEmpty(),
        )
    }

    /** All the way back to the neutral curve, forgetting the session. */
    fun reset(): UndoPlan = UndoPlan(NEUTRAL, emptyList(), atNeutral = true)

    /** What the UI writes above the undo button. */
    fun describe(history: List<SoundSetting>): String = when {
        history.isEmpty() ->
            "Nichts geaendert. Rueckgaengig schickt trotzdem den neutralen EQ."
        history.size == 1 ->
            "Eine Aenderung: ${history.last().label}. " +
                "Rueckgaengig stellt den neutralen EQ her."
        else ->
            "${history.size} Aenderungen, zuletzt ${history.last().label}. " +
                "Rueckgaengig geht einen Schritt zurueck."
    }
}
