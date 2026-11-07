package de.privat.boom2local.protocol

/** What the watch thinks about the speaker being open to new devices. */
sealed interface PairingVerdict {
    /** No state frame yet, so nothing to say. */
    data object Unknown : PairingVerdict

    /** Both candidate flags are clear. */
    data object Closed : PairingVerdict

    /**
     * At least one candidate flag is set.
     *
     * [confirmed] stays false until the test in the profile has been run, because this
     * rests on a single observation: two bytes flipped in the one frame read after the
     * speaker's Bluetooth button was pressed.
     */
    data class Open(val fields: List<DecodedField>, val confirmed: Boolean) : PairingVerdict
}

/**
 * Watches whether the speaker has opened itself to new devices.
 *
 * The problem this answers cannot be solved: pressing the speaker's Bluetooth button
 * makes it discoverable, that is the button's entire job, and nothing running on a
 * phone can stop a radio in another device from advertising. No source describes a
 * command for it, and guessing codes in the pairing category is how someone locks
 * themselves out of their own speaker.
 *
 * What can be done is notice. A capture taken right after that button was pressed shows
 * two bytes flipped that had been zero in every earlier frame: body[2], which sits
 * beside the volume and the battery in the run of operating states, and body[67]. One
 * of them is almost certainly "open for pairing".
 *
 * Almost. It is one observation, and this class says so rather than raising a siren on
 * a hunch - a warning that cries wolf is worse than none, because the next real one
 * gets ignored. The fields are named as candidates, the verdict carries whether it has
 * been confirmed, and the profile carries the one-minute test that would settle it.
 */
object PairingWatch {

    /**
     * How often to re-read the state while watching.
     *
     * Ten seconds is a compromise with a reason on each side: the question is "is my
     * speaker open right now", which is worthless if the answer is a minute old, and
     * 01:01 is a read that costs one frame and about 30 ms of the speaker's attention.
     * Faster would buy little and add traffic to a link that has gone silent before.
     */
    const val POLL_MS = 10_000L

    /**
     * The state field suspected of meaning "open for pairing".
     *
     * There were two. Bytes 2 and 67 both flipped in the frame read right after the
     * button was pressed, and both were watched. Then a later session had byte 67 back
     * at 00 while byte 2 stayed at 01, with the battery climbing and the charging cable
     * confirmed in - byte 2 is charging, and watching it here would have raised the
     * alarm every time the speaker was plugged in. Exactly the wolf this class was
     * written not to cry.
     */
    val CANDIDATE_KEYS = listOf("pairingOpen")

    fun verdict(state: DecodedState?): PairingVerdict {
        if (state == null) return PairingVerdict.Unknown
        val candidates = CANDIDATE_KEYS.mapNotNull { state.field(it) }
        if (candidates.isEmpty()) return PairingVerdict.Unknown
        val set = candidates.filter { it.raw.trim() != "00" }
        return if (set.isEmpty()) {
            PairingVerdict.Closed
        } else {
            PairingVerdict.Open(set, confirmed = set.all { it.verified })
        }
    }

    /** What the card says, in words that match how sure this is. */
    fun describe(verdict: PairingVerdict): String = when (verdict) {
        PairingVerdict.Unknown ->
            "Noch kein Zustandsrahmen gelesen - ueber den Kopplungsmodus ist damit " +
                "nichts bekannt."

        PairingVerdict.Closed ->
            "Byte 67 steht auf 0. Wenn die Deutung stimmt, ist die Box gerade NICHT " +
                "fuer neue Geraete offen."

        is PairingVerdict.Open -> buildString {
            append("ACHTUNG: ")
            append(verdict.fields.joinToString(", ") { "${it.label} = ${it.value}" })
            append(". ")
            if (verdict.confirmed) {
                append(
                    "Deine Box ist gerade fuer neue Geraete offen. Druecke die " +
                        "Bluetooth-Taste erneut oder schalte sie kurz aus.",
                )
            } else {
                append(
                    "Das ist ein VERDACHT aus einer einzigen Beobachtung, kein Beweis. " +
                        "Diese Bytes kippten in dem Rahmen, der direkt nach einem Druck " +
                        "auf die Bluetooth-Taste gelesen wurde. Wenn die Deutung stimmt, " +
                        "ist die Box jetzt offen.",
                )
            }
        }
    }

    /** The test that would turn the suspicion into a fact. */
    val TEST: List<String> = listOf(
        "1. An einem zweiten Geraet (PC, Tablet) die Bluetooth-Suche oeffnen.",
        "2. Bluetooth-Taste an der Box druecken. Die Box muss dort auftauchen, und " +
            "Byte 67 muss auf 01 stehen (Status lesen).",
        "3. Kopplungsmodus beenden oder abwarten. Die Box muss aus der Suche " +
            "verschwinden, und Byte 67 muss wieder auf 00 zurueckfallen.",
        "Schritt 3 ist der wichtige - ein Byte, das auf 01 bleibt, war es nicht. " +
            "Byte 2 ist so schon ausgeschieden: es ist das Laden.",
    )
}
