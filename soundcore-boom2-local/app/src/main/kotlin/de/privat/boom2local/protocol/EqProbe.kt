package de.privat.boom2local.protocol

/** One step of the search for which mask bit means which equaliser band. */
data class ProbeStep(
    /** 1-based, for the UI. */
    val number: Int,
    val maskHigh: Int,
    val maskLow: Int,
) {
    val label: String get() = "Schritt $number von ${EqProbe.STEPS}: Maske " +
        "${Hex.encode(byteArrayOf(maskHigh.toByte(), maskLow.toByte()))}"
}

/** What a probe step revealed. */
sealed interface ProbeResult {
    /** Exactly one band moved: the mask bit means that band. */
    data class Band(val index: Int, val bodyOffset: Int, val raw: Int) : ProbeResult

    /** Several bands moved, so the bit is not one band. */
    data class Several(val indices: List<Int>) : ProbeResult

    /** Nothing moved. The bit may be unused, or the write did not arrive. */
    data object Nothing : ProbeResult

    /** The two frames cannot be compared. */
    data class Unusable(val reason: String) : ProbeResult
}

/**
 * Finds out which bit of the equaliser mask means which band, safely.
 *
 * The mask is the last unknown standing between this app and a real nine-band
 * equaliser - which is also the thing that would help most with a speaker whose bass
 * eats its own headroom: pull the low bands down a little, and the limiter engages
 * later.
 *
 * This exists because the alternative is typing hex by hand, and typing hex by hand is
 * how 0xf0 - every band at +12 dB - reached a real speaker and ruined its sound. The
 * rules that should have been followed then are therefore in code now, not in anyone's
 * discipline:
 *
 *  - the gain is not a parameter. It is fixed at [PROBE_RAW], +2.0 dB, which is audible
 *    in a state frame and inaudible in the room;
 *  - a step sets exactly one bit, so a result that moves several bands is reported as
 *    such instead of being read as one;
 *  - each step is bracketed by the flat curve, so the speaker starts every round from
 *    the one state there is evidence for;
 *  - the comparison is byte-exact against the profile's own band offsets, so "nothing
 *    moved" and "I could not tell" are different answers.
 */
object EqProbe {

    /** Nine bands, so nine bits: the low byte's eight, then the high byte's first. */
    const val STEPS = 9

    /** +2.0 dB on this scale: 120 + 2 * 10. */
    const val PROBE_RAW = 140

    /** The command the probe needs, which the shipped profile must carry. */
    const val COMMAND_KEY = "eqProbeBand"

    init {
        require(PROBE_RAW in Decibels.SAFE_RAW_RANGE)
    }

    /** The steps, in order. */
    fun plan(): List<ProbeStep> = (0 until STEPS).map { bit ->
        ProbeStep(
            number = bit + 1,
            maskHigh = if (bit >= 8) 1 shl (bit - 8) else 0,
            maskLow = if (bit < 8) 1 shl bit else 0,
        )
    }

    /**
     * Compares the band bytes before and after one step.
     *
     * @param before the state body read while the curve was flat
     * @param after the state body read after the probe write
     * @param bandOffsets the profile's band offsets, i.e. [FieldSpec.indices]
     */
    fun compare(
        before: ByteArray,
        after: ByteArray,
        bandOffsets: List<Int>,
    ): ProbeResult {
        if (bandOffsets.isEmpty()) {
            return ProbeResult.Unusable("Das Profil kennt keine Band-Offsets.")
        }
        if (before.size != after.size) {
            return ProbeResult.Unusable(
                "Die beiden Zustandsrahmen sind unterschiedlich lang " +
                    "(${before.size} und ${after.size} Byte).",
            )
        }
        val missing = bandOffsets.filter { it !in before.indices }
        if (missing.isNotEmpty()) {
            return ProbeResult.Unusable(
                "Die Baender liegen laut Profil bei ${missing.joinToString(",")}, " +
                    "der Rahmen ist aber nur ${before.size} Byte lang.",
            )
        }
        val moved = bandOffsets.withIndex().filter { (_, offset) ->
            before[offset] != after[offset]
        }
        return when (moved.size) {
            0 -> ProbeResult.Nothing
            1 -> {
                val (index, offset) = moved.single()
                ProbeResult.Band(
                    index = index,
                    bodyOffset = offset,
                    raw = after[offset].toInt() and 0xFF,
                )
            }

            else -> ProbeResult.Several(moved.map { it.index })
        }
    }

    /** What the UI writes under a finished step. */
    fun describe(step: ProbeStep, result: ProbeResult): String = when (result) {
        is ProbeResult.Band ->
            "Bit der Maske ${Hex.encode(byteArrayOf(step.maskHigh.toByte(), step.maskLow.toByte()))} " +
                "ist Band ${result.index + 1} (Byte ${result.bodyOffset}, jetzt ${result.raw} = " +
                "${Decibels.format(Decibels.fromRaw(result.raw))})"

        is ProbeResult.Several ->
            "Dieses Bit bewegt mehrere Baender: " +
                result.indices.joinToString(", ") { "${it + 1}" } +
                ". Es ist also keine Einzelband-Maske."

        ProbeResult.Nothing ->
            "Kein Band hat sich bewegt. Entweder ist dieses Bit unbenutzt, oder der " +
                "Schreibbefehl kam nicht an - der Mitschnitt sagt, welches von beiden."

        is ProbeResult.Unusable -> result.reason
    }
}
