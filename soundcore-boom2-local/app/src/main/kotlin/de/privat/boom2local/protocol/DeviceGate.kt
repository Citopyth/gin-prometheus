package de.privat.boom2local.protocol

/** Whether the app may talk to a given device at all. */
sealed interface GateDecision {
    data object Allowed : GateDecision
    data class Refused(val rule: String, val message: String) : GateDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Binds the app to one speaker, by address.
 *
 * Matching on the Bluetooth name is a convenience, not a guarantee: a name can be set to
 * anything, and two speakers of the same model carry the same one. Pinning the address
 * makes it exact — once pinned, the app talks to that one device and refuses every
 * other, including another Boom 2 in the same room.
 *
 * What this cannot do, and the app says so in plain words: it binds **this app** to the
 * speaker. It does not bind the speaker to your phone. Which phones the speaker accepts
 * is decided by its own firmware, and nothing an app sends over Bluetooth changes that.
 */
object DeviceGate {

    /** Upper case, no spaces, so `aa:bb:…` and `AA:BB:…` are the same device. */
    fun normalise(address: String?): String? =
        address?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    fun isPinned(pinned: String?, address: String?): Boolean {
        val wanted = normalise(pinned) ?: return false
        return normalise(address) == wanted
    }

    /**
     * @param recognised whether any profile in the library recognised this device.
     *   With a pin set this does not matter: the pin is the stronger statement.
     */
    fun check(
        pinnedAddress: String?,
        address: String?,
        name: String?,
        recognised: Boolean,
    ): GateDecision {
        val pinned = normalise(pinnedAddress)
        val candidate = normalise(address)

        if (candidate == null) {
            return GateDecision.Refused(
                "no-address",
                "Das Geraet hat keine lesbare Adresse.",
            )
        }
        if (pinned != null) {
            // The pin is the stronger statement, so it decides on its own: a pinned
            // device is accepted even if it was renamed, and an unpinned one is refused
            // even if its name fits perfectly.
            return if (candidate == pinned) {
                GateDecision.Allowed
            } else {
                GateDecision.Refused(
                    "not-the-pinned-device",
                    "Die App ist an $pinned gebunden. $candidate ist ein anderes Geraet.",
                )
            }
        }
        if (!recognised) {
            return GateDecision.Refused(
                "no-profile",
                "Kein Profil erkennt \"${name ?: "(ohne Namen)"}\".",
            )
        }
        return GateDecision.Allowed
    }
}
