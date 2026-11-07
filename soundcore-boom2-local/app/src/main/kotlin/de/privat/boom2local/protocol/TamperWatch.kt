package de.privat.boom2local.protocol

/** A change to the speaker that this app did not make. */
data class ForeignChange(
    val field: String,
    val label: String,
    val from: String?,
    val to: String,
    val atMs: Long,
    /**
     * What the speaker last said its battery was, when this happened.
     *
     * Recorded because the question "does it sound the same at 20 percent as at 100"
     * has no answer without it. A portable amplifier runs off a falling rail voltage,
     * so its peak output falls with the charge and the protection limiter engages
     * earlier; if the speaker also pulls its own volume setting down, it says so, and
     * that report pinned to a battery level is the measurement. Nothing on a phone can
     * change the physics - it can write down where the ceiling is.
     */
    val batteryAtChange: String? = null,
)

/** What a change the speaker reported turned out to be. */
sealed interface ChangeOrigin {
    /** Same value as before; nothing happened. */
    data object Unchanged : ChangeOrigin

    /** This app asked for it, just now. */
    data object Ours : ChangeOrigin

    /**
     * Nobody here asked for it.
     *
     * Not the same as an attack, and the UI must not pretend otherwise: the buttons on
     * the speaker produce exactly this, so does the phone's own volume rocker, and so
     * does the speaker protecting its driver. What it does mean is that something other
     * than this app touched the speaker, with a timestamp.
     */
    data class Foreign(val change: ForeignChange) : ChangeOrigin
}

/**
 * Notices when something other than this app changes the speaker.
 *
 * Blocking foreign access is not possible from here. The control channel is a service
 * the *speaker* offers, and who may open it is decided by its firmware; no command in
 * any source changes that, and nothing running on a phone can. Claiming otherwise would
 * be the most dangerous kind of feature: one that makes someone feel safe.
 *
 * What is possible is the other half of security, and it is not a consolation prize:
 * the speaker announces its own volume, BassUp and light state unasked, confirmed on
 * real hardware. So every change to those is visible here, whether this app caused it
 * or not - and subtracting the ones it did cause leaves a timestamped record of
 * everything else that touched the speaker.
 *
 * The same record answers a different question. If the speaker turns itself down to
 * protect its driver, that shows up as its own volume falling with nobody asking. A
 * limiter working inside the DSP would not: it reduces the output, not the setting. One
 * log tells those two apart, and that is the difference between knowing and guessing.
 */
object TamperWatch {

    /**
     * How long after one of our own writes a report still counts as ours.
     *
     * Acknowledgements in the captures came back in 28 to 205 ms, and a speaker that
     * then reports the new state takes a little longer. Four seconds is generous on
     * purpose: calling our own change foreign would cry wolf, which is the one failure
     * that makes a warning worthless.
     */
    const val OWN_WINDOW_MS = 4_000L

    /**
     * Fields worth watching.
     *
     * Battery is left out: it changes by itself constantly and would bury everything
     * else. Volume is in, but it is the noisy one - the phone's volume keys reach the
     * speaker through AVRCP and look exactly like this.
     */
    val WATCHED = setOf("volume", "bassUp", "lightOn", "eqPreset", "lightMode")

    /**
     * @param field the value the speaker just announced
     * @param previous what this app last knew that field to be
     * @param lastOwnChangeAtMs when this app last sent something that could cause it
     * @param nowMs when the announcement arrived
     */
    fun classify(
        field: DecodedField,
        previous: String?,
        lastOwnChangeAtMs: Long?,
        nowMs: Long,
        battery: String? = null,
    ): ChangeOrigin = when {
        field.key !in WATCHED -> ChangeOrigin.Unchanged
        previous == field.value -> ChangeOrigin.Unchanged
        lastOwnChangeAtMs != null && nowMs - lastOwnChangeAtMs in 0..OWN_WINDOW_MS ->
            ChangeOrigin.Ours
        else -> ChangeOrigin.Foreign(
            ForeignChange(
                field = field.key,
                label = field.label,
                from = previous,
                to = field.value,
                atMs = nowMs,
                batteryAtChange = battery,
            ),
        )
    }

    /** Keeps the newest [limit] entries, newest last. */
    fun record(
        log: List<ForeignChange>,
        change: ForeignChange,
        limit: Int = 100,
    ): List<ForeignChange> {
        val next = log + change
        return if (next.size <= limit) next else next.takeLast(limit)
    }

    /** What the UI writes above the list. */
    fun describe(log: List<ForeignChange>): String = when {
        log.isEmpty() ->
            "Seit dem Verbinden hat nichts ausser dieser App etwas an der Box geaendert."
        log.size == 1 ->
            "Eine Aenderung kam nicht von dieser App."
        else ->
            "${log.size} Aenderungen kamen nicht von dieser App."
    }

    /**
     * True when the speaker appears to be turning itself down.
     *
     * Three consecutive foreign volume reports, each lower than the one before, with
     * nobody asking: that is the speaker protecting its driver, not a person at the
     * dial. Worth naming separately because it answers a question people actually ask -
     * "why does it get quiet by itself" - and because the answer is "by design", which
     * is only convincing with the log to back it.
     */
    /** The battery level the speaker last reported while turning itself down. */
    fun batteryWhileProtecting(log: List<ForeignChange>): String? =
        if (looksLikeSelfProtection(log)) {
            log.last { it.field == "volume" }.batteryAtChange
        } else {
            null
        }

    fun looksLikeSelfProtection(log: List<ForeignChange>): Boolean {
        val volumes = log.filter { it.field == "volume" }.takeLast(3)
        if (volumes.size < 3) return false
        val numbers = volumes.mapNotNull { entry ->
            entry.to.takeWhile { it.isDigit() }.toIntOrNull()
        }
        if (numbers.size < 3) return false
        return numbers.zipWithNext().all { (before, after) -> after < before }
    }
}
