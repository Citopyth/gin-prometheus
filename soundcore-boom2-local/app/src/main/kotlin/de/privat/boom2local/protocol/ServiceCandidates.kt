package de.privat.boom2local.protocol

/** A Bluetooth service worth connecting to, together with why it is being tried. */
data class ServiceCandidate(val uuid: String, val origin: String)

/**
 * Decides which services to try, and in which order.
 *
 * This is the logic that found the speaker's real control service after the UUID
 * guessed from other Soundcore models turned out to be wrong, so it belongs where it
 * can be tested: the ordering is a pure function of what the device advertises, what
 * the profile knows and what has already proved itself, with no Android types in sight.
 *
 * The order it produces, best first:
 *
 *  1. the service that answered on an earlier connection, for this very device;
 *  2. a service the profile names as this model's control channel;
 *  3. an advertised service matching the vendor prefix;
 *  4. any other service the manufacturer invented;
 *  5. the profile's control channel even though the device did not advertise it;
 *  6. guesses carried over from other models;
 *  7. the standard serial port, then every remaining profile.
 *
 * Tiers 1 and 2 exist because a prefix is a weaker statement than it looks. The Boom 2
 * advertises both `…d6832e7b3138` and `…d6832e7c3138`; both match the vendor prefix,
 * only the first one answers, and the order Android returns them in is not fixed. A
 * connection to the second one comes up and then stays silent forever, which looks
 * exactly like a speaker that has stopped talking. See PROTOCOL.md.
 */
object ServiceCandidates {

    /** Registered Bluetooth profile UUIDs all end in this. */
    const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    const val PROVEN_SERVICE = "Dienst, ueber den diese Box schon geantwortet hat"
    const val MODEL_SERVICE = "Steuerdienst dieses Modells, von der Box angeboten"
    const val VENDOR_PREFIX_MATCH = "Dienst der Box, passt zum Hersteller-Praefix"
    const val CUSTOM_SERVICE = "herstellerspezifischer Dienst der Box"
    const val MODEL_SERVICE_UNADVERTISED =
        "Steuerdienst dieses Modells (die Box bietet ihn nicht an)"
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
     * @param transport the profile's transport settings, including its guesses
     * @param knownUuids services the profile names as this model's control channel,
     *   i.e. [DeviceMatch.serviceUuids]: a far stronger statement than a prefix
     * @param provenUuid the service a frame has actually arrived over, if one is known
     * @param excluded services already shown to be useless in this session, which are
     *   dropped entirely rather than retried
     * @param includeStandardProfiles last resort: also offer A2DP, AVRCP and the rest
     */
    fun order(
        advertised: List<String>,
        transport: TransportSpec,
        knownUuids: List<String> = emptyList(),
        provenUuid: String? = null,
        excluded: Set<String> = emptySet(),
        includeStandardProfiles: Boolean = false,
    ): List<ServiceCandidate> {
        val chosen = LinkedHashMap<String, ServiceCandidate>()
        val skip = excluded.map { it.trim().lowercase() }.toSet()

        fun add(raw: String, origin: String) {
            val uuid = raw.trim().lowercase()
            if (!UUID_PATTERN.matches(uuid)) return
            if (uuid in skip) return
            chosen.putIfAbsent(uuid, ServiceCandidate(uuid, origin))
        }

        fun clean(values: List<String>) = values
            .map { it.trim().lowercase() }
            .filter { UUID_PATTERN.matches(it) }

        val real = clean(advertised)
        val known = clean(knownUuids)
        val prefix = transport.uuidPrefix.trim().lowercase()

        // 1. What this device has already done, which beats every kind of reasoning.
        provenUuid?.let { add(it, PROVEN_SERVICE) }
        // 2. The service the profile names for this model, confirmed by the device.
        known.filter { it in real }.forEach { add(it, MODEL_SERVICE) }
        // 3. A guess the device itself confirms, but only by its first few bytes.
        if (prefix.isNotEmpty()) {
            real.filter { it.startsWith(prefix) }.forEach { add(it, VENDOR_PREFIX_MATCH) }
        }
        // 4. Anything else the manufacturer invented: real, and the only place a
        //    proprietary protocol can live.
        real.filter { isCustom(it) }.forEach { add(it, CUSTOM_SERVICE) }
        // 5. The model's service even unadvertised: SDP records are not always complete.
        known.forEach { add(it, MODEL_SERVICE_UNADVERTISED) }
        // 6. Only now the profile's guesses from other models.
        transport.fallbackUuids.forEach { add(it, PROFILE_GUESS) }
        // 7. Serial port, if the device offers one.
        real.filter { isSerialPort(it) }.forEach { add(it, SERIAL_PORT) }
        // 8. Everything else, on request.
        if (includeStandardProfiles) real.forEach { add(it, STANDARD_PROFILE) }

        return chosen.values.toList()
    }
}
