package de.privat.boom2local.protocol

/** A Bluetooth service worth connecting to, together with why it is being tried. */
data class ServiceCandidate(val uuid: String, val origin: String)

/**
 * Decides which services to try, and in which order.
 *
 * This is the logic that found the speaker's real control service after the UUID
 * guessed from other Soundcore models turned out to be wrong, so it belongs where it
 * can be tested: the ordering is a pure function of what the device advertises and what
 * the profile guesses, with no Android types in sight.
 *
 * The order puts what the device says about itself ahead of what the profile assumes.
 */
object ServiceCandidates {

    /** Registered Bluetooth profile UUIDs all end in this. */
    const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    const val VENDOR_PREFIX_MATCH = "Dienst der Box, passt zum Hersteller-Praefix"
    const val CUSTOM_SERVICE = "herstellerspezifischer Dienst der Box"
    const val PROFILE_GUESS = "Rueckfall-Kandidat aus dem Profil (geraten)"
    const val SERIAL_PORT = "serieller Standarddienst (SPP) der Box"
    const val STANDARD_PROFILE = "Standardprofil der Box (letzter Versuch)"

    private val UUID_PATTERN =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /**
     * True when nobody registered this UUID with the Bluetooth SIG, which makes it the
     * only plausible home for a proprietary protocol.
     */
    fun isCustom(uuid: String): Boolean = !uuid.lowercase().endsWith(BLUETOOTH_BASE_SUFFIX)

    /** The standard Serial Port Profile, a common carrier for vendor protocols. */
    fun isSerialPort(uuid: String): Boolean = uuid.lowercase().startsWith("00001101")

    /**
     * @param advertised what the device's SDP record offers
     * @param includeStandardProfiles last resort: also offer A2DP, AVRCP and the rest
     */
    fun order(
        advertised: List<String>,
        transport: TransportSpec,
        includeStandardProfiles: Boolean = false,
    ): List<ServiceCandidate> {
        val chosen = LinkedHashMap<String, ServiceCandidate>()

        fun add(raw: String, origin: String) {
            val uuid = raw.trim().lowercase()
            if (!UUID_PATTERN.matches(uuid)) return
            chosen.putIfAbsent(uuid, ServiceCandidate(uuid, origin))
        }

        val real = advertised
            .map { it.trim().lowercase() }
            .filter { UUID_PATTERN.matches(it) }
        val prefix = transport.uuidPrefix.trim().lowercase()

        // 1. A guess the device itself confirms.
        if (prefix.isNotEmpty()) {
            real.filter { it.startsWith(prefix) }.forEach { add(it, VENDOR_PREFIX_MATCH) }
        }
        // 2. Anything else the manufacturer invented: real, and the only place a
        //    proprietary protocol can live.
        real.filter { isCustom(it) }.forEach { add(it, CUSTOM_SERVICE) }
        // 3. Only now the profile's guesses from other models.
        transport.fallbackUuids.forEach { add(it, PROFILE_GUESS) }
        // 4. Serial port, if the device offers one.
        real.filter { isSerialPort(it) }.forEach { add(it, SERIAL_PORT) }
        // 5. Everything else, on request.
        if (includeStandardProfiles) real.forEach { add(it, STANDARD_PROFILE) }

        return chosen.values.toList()
    }
}
