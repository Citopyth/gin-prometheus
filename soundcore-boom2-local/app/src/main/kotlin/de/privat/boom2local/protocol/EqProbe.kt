package de.privat.boom2local.protocol

/** One step of the search for what the equaliser's band byte selects. */
data class ProbeStep(val number: Int, val band: Int) {
    val label: String get() = "Schritt $number von ${EqProbe.STEPS}: Band-Byte " +
        "%02x".format(band)
}

/** One byte of the state frame that changed between two reads. */
data class ChangedByte(val offset: Int, val before: Int, val after: Int)

/** What a probe step revealed. */
sealed interface ProbeResult {
    /** Exactly one byte moved: that byte is what this band value controls. */
    data class One(val byte: ChangedByte) : ProbeResult

    /** Several bytes moved - a list entry was added, or something else drifted. */
    data class Several(val bytes: List<ChangedByte>) : ProbeResult

    /** Nothing moved: the value may be ignored, or the write did not arrive. */
    data object Nothing : ProbeResult

    /** The two frames cannot be compared. */
    data class Unusable(val reason: String) : ProbeResult
}

/**
 * Finds out what the equaliser's band byte selects, safely.
 *
 * The first real state frame, captured 2026-11-07, rewrote what this search is even
 * about. The profile claimed nine equaliser bands at fixed offsets 34, 37, 40 and so
 * on. What the speaker actually reports, from offset 34, is a **list** of three-byte
 * entries - gain, band, 0x0a - and the list is as long as it needs to be. An older
 * capture held nine of them. The new one holds exactly one, `78 ff 0a`, which is byte
 * for byte what this app's own eqFlat writes.
 *
 * Two things follow. The list structure means fixed offsets were never going to work,
 * so [compare] looks at the whole body and lets the bytes say where they are. And the
 * byte worth varying is the **band** byte, the one that came back as `ff` because that
 * is what was sent - not the two bytes this code previously called a mask, which stayed
 * `ff ff` throughout and are so far just constant.
 *
 * The safety rules stay as they were, because the reason for them has not changed: on
 * 2026-11-05 a gain of 0xf0 reached a real speaker and ruined its sound.
 *
 *  - the gain is not a parameter. It is fixed at [PROBE_RAW], +2.0 dB, loud enough to
 *    see in a frame and quiet enough not to matter in the room;
 *  - each step is bracketed by the flat curve;
 *  - "nothing moved" and "I could not compare" are different answers.
 */
object EqProbe {

    /** Band values 0 through 8: as many as the old capture had list entries. */
    const val STEPS = 9

    /** +2.0 dB on this scale: 120 + 2 * 10. */
    const val PROBE_RAW = 140

    /** The command the probe needs, which the shipped profile must carry. */
    const val COMMAND_KEY = "eqProbeBand"

    /**
     * Offsets that move on their own and would otherwise look like a result.
     *
     * The battery is the one that bites: it ticks down during a session and would be
     * reported as the byte the band selects.
     */
    val DRIFTS = setOf(1)

    init {
        require(PROBE_RAW in Decibels.SAFE_RAW_RANGE)
    }

    fun plan(): List<ProbeStep> = (0 until STEPS).map { ProbeStep(it + 1, it) }

    /**
     * Compares two state bodies and reports every byte that moved.
     *
     * The whole body, not a list of offsets the profile believes in. That was the first
     * design, and the first real frame killed it: comparing the nine offsets the profile
     * named would have reported "nothing moved" nine times while the bytes that actually
     * changed sat where this code was not looking.
     */
    fun compare(
        before: ByteArray,
        after: ByteArray,
        ignore: Set<Int> = DRIFTS,
    ): ProbeResult {
        if (before.isEmpty() || after.isEmpty()) {
            return ProbeResult.Unusable("Mindestens einer der beiden Rahmen ist leer.")
        }
        if (before.size != after.size) {
            return ProbeResult.Unusable(
                "Die Rahmen sind unterschiedlich lang (${before.size} und ${after.size} " +
                    "Byte) und damit nicht vergleichbar.",
            )
        }
        val moved = before.indices
            .filter { it !in ignore && before[it] != after[it] }
            .map {
                ChangedByte(it, before[it].toInt() and 0xFF, after[it].toInt() and 0xFF)
            }
        return when (moved.size) {
            0 -> ProbeResult.Nothing
            1 -> ProbeResult.One(moved.single())
            else -> ProbeResult.Several(moved)
        }
    }

    private fun show(byte: ChangedByte) =
        "Byte ${byte.offset}: ${"%02x".format(byte.before)} -> ${"%02x".format(byte.after)}"

    /** What the UI writes under a finished step. */
    fun describe(step: ProbeStep, result: ProbeResult): String {
        val band = "Band-Byte %02x".format(step.band)
        return when (result) {
            is ProbeResult.One ->
                "$band bewegt genau ein Byte - ${show(result.byte)}" +
                    if (result.byte.after == PROBE_RAW) {
                        " (das ist die gesetzte Verstaerkung, also die Stelle dieses Bandes)"
                    } else {
                        ""
                    }

            is ProbeResult.Several ->
                "$band bewegt ${result.bytes.size} Bytes: " +
                    result.bytes.joinToString("; ") { show(it) } +
                    ". Bei einer Liste ist das normal, wenn ein Eintrag dazukommt."

            ProbeResult.Nothing ->
                "$band: kein Byte hat sich bewegt. Entweder wird dieser Wert ignoriert, " +
                    "oder der Schreibbefehl kam nicht an - der Mitschnitt sagt, welches."

            is ProbeResult.Unusable -> result.reason
        }
    }
}
