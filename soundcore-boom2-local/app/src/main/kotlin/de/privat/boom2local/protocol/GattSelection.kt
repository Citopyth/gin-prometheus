package de.privat.boom2local.protocol

/** One GATT characteristic, reduced to what matters for picking a control channel. */
data class GattCharacteristic(
    val uuid: String,
    val write: Boolean = false,
    val writeNoResponse: Boolean = false,
    val notify: Boolean = false,
    val indicate: Boolean = false,
) {
    val canSend: Boolean get() = write || writeNoResponse
    val canReceive: Boolean get() = notify || indicate
}

/** One GATT service with its characteristics. */
data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)

/** The channel the app will talk over. */
data class GattChoice(
    val serviceUuid: String,
    val writeUuid: String,
    /** True when the characteristic only supports write-without-response. */
    val writeWithoutResponse: Boolean,
    val notifyUuid: String,
    /** True when the receiving characteristic indicates rather than notifies. */
    val useIndication: Boolean,
    val reason: String,
)

sealed interface GattSelection {
    data class Ok(val choice: GattChoice, val notes: List<String>) : GattSelection
    data class None(val notes: List<String>) : GattSelection
}

/**
 * Works out which GATT service carries the control protocol.
 *
 * Anker documents nothing, so the service has to be recognised by shape rather than by
 * name: a proprietary control channel is a non-standard service holding a characteristic
 * that can be written and one that notifies. This lives in the protocol core, away from
 * Android, so the decision can be unit tested against made-up service layouts instead of
 * only ever being exercised against one speaker.
 *
 * Services that are known to transfer firmware are refused outright, before any scoring.
 * On a device whose protocol nobody has mapped, a stray write into a DFU service is the
 * one mistake that is not recoverable, so it is worth a hard rule of its own rather than
 * relying on the command allowlist one layer up.
 */
object GattSelector {

    /** Registered Bluetooth services all end in this. */
    const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /**
     * Standard services that cannot be the vendor control channel. Generic Access,
     * Generic Attribute, Device Information and Battery Service.
     */
    val HOUSEKEEPING_SERVICES: Set<String> = setOf(
        "00001800$BLUETOOTH_BASE_SUFFIX",
        "00001801$BLUETOOTH_BASE_SUFFIX",
        "0000180a$BLUETOOTH_BASE_SUFFIX",
        "0000180f$BLUETOOTH_BASE_SUFFIX",
    )

    fun isCustom(uuid: String): Boolean = !uuid.lowercase().endsWith(BLUETOOTH_BASE_SUFFIX)

    fun select(services: List<GattService>, spec: BleSpec): GattSelection {
        val notes = mutableListOf<String>()
        if (services.isEmpty()) {
            notes += "Das Geraet meldet keine GATT-Dienste."
            return GattSelection.None(notes)
        }

        val blocked = spec.blockedServiceUuids.map { it.lowercase() }.toSet()
        val preferred = spec.serviceUuids.map { it.lowercase() }

        val usable = mutableListOf<Pair<GattService, Int>>()
        for (service in services) {
            val uuid = service.uuid.lowercase()
            if (uuid in blocked) {
                notes += "$uuid uebersprungen: als Firmware-/Update-Dienst gesperrt."
                continue
            }
            if (uuid in HOUSEKEEPING_SERVICES && uuid !in preferred) {
                notes += "$uuid uebersprungen: Standarddienst, kein Steuerkanal."
                continue
            }
            val sends = service.characteristics.any { it.canSend }
            val receives = service.characteristics.any { it.canReceive }
            if (!sends || !receives) {
                notes += "$uuid uebersprungen: " + when {
                    !sends && !receives -> "weder schreib- noch meldefaehig."
                    !sends -> "keine beschreibbare Eigenschaft."
                    else -> "keine Eigenschaft, die Antworten meldet."
                }
                continue
            }
            var score = 0
            if (uuid in preferred) score += 1000 - preferred.indexOf(uuid)
            if (isCustom(uuid)) score += 100
            usable += service to score
        }

        if (usable.isEmpty()) {
            notes += "Kein Dienst taugt als Steuerkanal."
            return GattSelection.None(notes)
        }

        val (service, score) = usable.maxByOrNull { it.second }!!
        val uuid = service.uuid.lowercase()

        val notifyChar = pick(service, spec.notifyCharacteristicUuid) { it.canReceive }
            ?: run {
                notes += "$uuid hat keine meldefaehige Eigenschaft mehr."
                return GattSelection.None(notes)
            }
        // A single characteristic that both sends and receives is a common shape for
        // vendor protocols, so prefer reusing the notify characteristic before looking
        // for a separate one.
        val writeChar = pick(service, spec.writeCharacteristicUuid) { it.canSend }
            ?: notifyChar.takeIf { it.canSend }
            ?: service.characteristics.firstOrNull { it.canSend }
            ?: run {
                notes += "$uuid hat keine beschreibbare Eigenschaft mehr."
                return GattSelection.None(notes)
            }

        val reason = buildString {
            append(if (uuid in preferred) "im Profil vorgegeben" else "automatisch gewaehlt")
            append(if (isCustom(uuid)) ", herstellerspezifischer Dienst" else ", Standarddienst")
            if (writeChar.uuid == notifyChar.uuid) append(", eine Eigenschaft fuer beides")
            append(" (Punkte: $score)")
        }

        return GattSelection.Ok(
            GattChoice(
                serviceUuid = service.uuid,
                writeUuid = writeChar.uuid,
                // Prefer write-with-response when the characteristic offers both: it
                // keeps writes ordered, which matters for a request/response protocol.
                writeWithoutResponse = !writeChar.write && writeChar.writeNoResponse,
                notifyUuid = notifyChar.uuid,
                useIndication = !notifyChar.notify && notifyChar.indicate,
                reason = reason,
            ),
            notes,
        )
    }

    private fun pick(
        service: GattService,
        configured: String?,
        predicate: (GattCharacteristic) -> Boolean,
    ): GattCharacteristic? {
        if (configured != null) {
            val match = service.characteristics.firstOrNull {
                it.uuid.equals(configured, ignoreCase = true) && predicate(it)
            }
            if (match != null) return match
        }
        // Prefer notify over indicate, and a characteristic that can do both directions.
        return service.characteristics
            .filter(predicate)
            .minByOrNull { characteristic ->
                var rank = 0
                if (characteristic.canSend && characteristic.canReceive) rank -= 2
                if (characteristic.notify) rank -= 1
                rank
            }
    }
}
