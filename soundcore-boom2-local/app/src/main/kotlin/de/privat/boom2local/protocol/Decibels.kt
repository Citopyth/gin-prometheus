package de.privat.boom2local.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The speaker's equaliser scale: a raw byte that means a gain in decibels.
 *
 * Q4 gives the relation as `raw = 120 + dB * 10` for the Space 2, and the Boom 2's own
 * state frame agrees as far as a flat curve can: all nine bands read 0x78 = 120 at rest.
 *
 * This lives on its own because getting it wrong is expensive. A gain byte of 0xf0 was
 * sent to a real speaker during this project's write tests - +12 dB on every band at
 * once - and the sound was audibly wrecked until the equaliser was reset. The numbers
 * here are the difference between a 1 dB nudge and that.
 */
object Decibels {

    /** The raw byte that means no change at all. */
    const val NEUTRAL_RAW = 120

    /** Raw units per decibel on this scale. */
    const val RAW_PER_DB = 10

    /**
     * The widest gain this app will offer without a deliberate profile edit.
     *
     * Not a limit of the hardware: the byte goes to 0xf0, and the speaker accepts it.
     * It is a limit on how far a single tap can move something nobody has measured.
     * Beyond about +6 dB on every band the amplifier is into its limiter, and what the
     * limiter takes first is the bass - which sounds exactly like a dead subwoofer.
     */
    const val SAFE_DB = 6

    val SAFE_RAW_RANGE = (NEUTRAL_RAW - SAFE_DB * RAW_PER_DB)..(NEUTRAL_RAW + SAFE_DB * RAW_PER_DB)

    fun fromRaw(raw: Int, offset: Int = NEUTRAL_RAW, scale: Double = RAW_PER_DB.toDouble()): Double =
        if (scale == 0.0) 0.0 else (raw - offset) / scale

    fun toRaw(db: Double, offset: Int = NEUTRAL_RAW, scale: Double = RAW_PER_DB.toDouble()): Int =
        (offset + db * scale).roundToInt()

    /** Formats with one decimal and an explicit sign, without pulling in a locale. */
    fun format(value: Double): String {
        val tenths = (value * 10).roundToInt()
        val sign = when {
            tenths < 0 -> "-"
            tenths > 0 -> "+"
            else -> ""
        }
        val a = abs(tenths)
        return "$sign${a / 10}.${a % 10} dB"
    }

    /** As [format], but without the leading plus, for values read back off the device. */
    fun formatUnsigned(value: Double): String {
        val tenths = (value * 10).roundToInt()
        val sign = if (tenths < 0) "-" else ""
        val a = abs(tenths)
        return "$sign${a / 10}.${a % 10} dB"
    }
}
