package de.privat.boom2local.protocol

/** A profile that recognised a device, with how strongly and why. */
data class ProfileMatch(
    val profile: DeviceProfile,
    val score: Int,
    val reason: String,
)

/**
 * Works out which device profile belongs to a given speaker.
 *
 * The ranking puts the device's own SDP record ahead of its name, and for a good reason:
 * a Bluetooth name can be changed to anything and is identical across two speakers of
 * the same model, while the vendor service UUID is burned into the firmware. On the
 * Boom 2 it even carries the model number — `…d6832e7b3138` for the A3138 — which is
 * what made the model identifiable in the first place.
 *
 * Keeping this out of the Android layer means a library of profiles can be tested
 * against invented devices rather than against the one speaker in the room.
 */
object ProfileMatcher {

    /** The device advertises exactly the service a profile names. It is that model. */
    const val SERVICE_EXACT = 1000

    /** The device advertises a service from the right manufacturer, model unknown. */
    const val SERVICE_PREFIX = 500

    /** Only the name fits. Weak: names are free-form and duplicated across units. */
    const val NAME = 100

    fun rank(
        profiles: List<DeviceProfile>,
        name: String?,
        advertised: List<String>,
    ): List<ProfileMatch> {
        val offered = advertised.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        return profiles.mapNotNull { profile ->
            var score = 0
            val reasons = mutableListOf<String>()

            val exact = profile.match.serviceUuids
                .map { it.trim().lowercase() }
                .firstOrNull { it in offered }
            if (exact != null) {
                score += SERVICE_EXACT
                reasons += "Dienst $exact"
            } else {
                val prefix = profile.transport.uuidPrefix.trim().lowercase()
                val byPrefix = if (prefix.isEmpty()) {
                    null
                } else {
                    offered.firstOrNull { it.startsWith(prefix) }
                }
                if (byPrefix != null) {
                    score += SERVICE_PREFIX
                    reasons += "Hersteller-Praefix"
                }
            }

            if (profile.match.matches(name)) {
                score += NAME
                reasons += "Name"
            }

            if (score == 0) null else ProfileMatch(profile, score, reasons.joinToString(", "))
        }.sortedWith(
            compareByDescending<ProfileMatch> { it.score }.thenBy { it.profile.id },
        )
    }

    /** The single best profile, or null when nothing recognised the device. */
    fun best(
        profiles: List<DeviceProfile>,
        name: String?,
        advertised: List<String>,
    ): ProfileMatch? = rank(profiles, name, advertised).firstOrNull()
}
