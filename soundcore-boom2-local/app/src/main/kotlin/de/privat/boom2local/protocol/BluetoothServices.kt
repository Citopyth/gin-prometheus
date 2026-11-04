package de.privat.boom2local.protocol

/** What a service is for, from the point of view of someone who cares about privacy. */
enum class ServiceRisk {
    /** Plays music. The reason the speaker exists. */
    MUSIC,

    /** Play, pause, skip, volume. Harmless. */
    REMOTE,

    /** Carries a microphone channel. The one to look at twice. */
    MICROPHONE,

    /** Reaches into the phone: phonebook, messages, SIM. */
    PERSONAL_DATA,

    /** Networking over Bluetooth. */
    NETWORK,

    /** Housekeeping: device name, battery, firmware version. */
    HOUSEKEEPING,

    /** Non-standard, so made up by the manufacturer. The control channel lives here. */
    VENDOR,

    /** Standard-shaped but not in this catalogue. */
    UNKNOWN,
}

/** One advertised service, explained. */
data class ServiceDescription(
    val uuid: String,
    val name: String,
    val risk: ServiceRisk,
    val meaning: String,
    /** True for a UUID registered with the Bluetooth SIG. */
    val standard: Boolean,
    /** The 16 bit short form, for a registered service. */
    val shortUuid: String?,
)

/**
 * Turns the UUIDs a device advertises into something readable.
 *
 * A speaker's SDP record is a list of hex strings, and "Hands-Free Audio Gateway" means
 * nothing to most people until someone says "that is the microphone channel". The point
 * of this catalogue is to let the app say which of the services on your speaker can
 * carry a microphone and which can reach into your phonebook, instead of showing a
 * column of UUIDs and leaving you to guess.
 *
 * It is a best effort: the registered numbers below are from the Bluetooth SIG's
 * assigned-numbers list, and anything not in it is reported as unknown rather than
 * guessed at.
 */
object BluetoothServices {

    private class Entry(val name: String, val risk: ServiceRisk, val meaning: String)

    private val REGISTERED: Map<String, Entry> = mapOf(
        "1101" to Entry(
            "Serial Port (SPP)",
            ServiceRisk.VENDOR,
            "Serielle Verbindung. Haeufiger Traeger fuer Hersteller-Protokolle.",
        ),
        "1103" to Entry("Dial-Up Networking", ServiceRisk.NETWORK, "Einwahl ueber das Telefon."),
        "1105" to Entry(
            "OBEX Object Push",
            ServiceRisk.PERSONAL_DATA,
            "Dateien senden und empfangen.",
        ),
        "1106" to Entry("OBEX File Transfer", ServiceRisk.PERSONAL_DATA, "Dateizugriff."),
        "1108" to Entry(
            "Headset (HSP)",
            ServiceRisk.MICROPHONE,
            "Aeltere Headset-Verbindung. Enthaelt einen Mikrofon-Kanal.",
        ),
        "110a" to Entry("Audio Source", ServiceRisk.MUSIC, "Sendet Musik."),
        "110b" to Entry("Audio Sink (A2DP)", ServiceRisk.MUSIC, "Empfaengt Musik. Das ist der Weg, ueber den der Lautsprecher spielt."),
        "110c" to Entry("AVRCP Target", ServiceRisk.REMOTE, "Nimmt Play/Pause/Lautstaerke entgegen."),
        "110d" to Entry("Advanced Audio (A2DP)", ServiceRisk.MUSIC, "Sammeleintrag fuer Musikwiedergabe."),
        "110e" to Entry("AVRCP Control", ServiceRisk.REMOTE, "Fernbedienung."),
        "110f" to Entry("AVRCP Controller", ServiceRisk.REMOTE, "Fernbedienung, andere Richtung."),
        "1112" to Entry(
            "Headset Audio Gateway",
            ServiceRisk.MICROPHONE,
            "Gegenstueck zum Headset-Profil. Mikrofon-Kanal.",
        ),
        "1115" to Entry("PAN User", ServiceRisk.NETWORK, "Netzwerk ueber Bluetooth."),
        "1116" to Entry("Network Access Point", ServiceRisk.NETWORK, "Netzwerkzugang."),
        "111e" to Entry(
            "Hands-Free (HFP)",
            ServiceRisk.MICROPHONE,
            "Freisprechen. Enthaelt den Mikrofon-Kanal.",
        ),
        "111f" to Entry(
            "Hands-Free Audio Gateway",
            ServiceRisk.MICROPHONE,
            "Freisprech-Gegenstelle. Hierueber laeuft das Mikrofon und die Anrufsteuerung.",
        ),
        "1124" to Entry("Human Interface Device", ServiceRisk.PERSONAL_DATA, "Tastatur/Maus-Profil."),
        "112d" to Entry("SIM Access", ServiceRisk.PERSONAL_DATA, "Zugriff auf die SIM-Karte."),
        "112e" to Entry("Phonebook Access (Client)", ServiceRisk.PERSONAL_DATA, "Liest Telefonbuecher."),
        "112f" to Entry(
            "Phonebook Access (Server)",
            ServiceRisk.PERSONAL_DATA,
            "Gibt das Telefonbuch heraus.",
        ),
        "1130" to Entry("Phonebook Access", ServiceRisk.PERSONAL_DATA, "Telefonbuch-Profil."),
        "1132" to Entry("Message Access Server", ServiceRisk.PERSONAL_DATA, "Gibt Nachrichten heraus."),
        "1133" to Entry("Message Notification", ServiceRisk.PERSONAL_DATA, "Meldet neue Nachrichten."),
        "1200" to Entry(
            "PnP Information",
            ServiceRisk.HOUSEKEEPING,
            "Hersteller- und Produkt-ID.",
        ),
        "1203" to Entry("Generic Audio", ServiceRisk.MUSIC, "Sammeleintrag Audio."),
        "180a" to Entry("Device Information", ServiceRisk.HOUSEKEEPING, "Name, Firmware, Seriennummer."),
        "180f" to Entry("Battery Service", ServiceRisk.HOUSEKEEPING, "Akkustand."),
        "1812" to Entry("HID over GATT", ServiceRisk.PERSONAL_DATA, "Eingabegeraet ueber BLE."),
        "fe59" to Entry(
            "Nordic Secure DFU",
            ServiceRisk.VENDOR,
            "Firmware-Update. In dieser App hart gesperrt.",
        ),
    )

    /** Risks worth pointing at, in the order they deserve attention. */
    val CONCERNING: List<ServiceRisk> = listOf(
        ServiceRisk.MICROPHONE,
        ServiceRisk.PERSONAL_DATA,
        ServiceRisk.NETWORK,
        ServiceRisk.UNKNOWN,
    )

    fun describe(uuid: String): ServiceDescription {
        val normalised = uuid.trim().lowercase()
        if (!ServiceCandidates.isCustom(normalised)) {
            val short = normalised.take(8).removePrefix("0000").ifEmpty { normalised.take(8) }
            val entry = REGISTERED[short]
            return ServiceDescription(
                uuid = normalised,
                name = entry?.name ?: "Unbekanntes Standardprofil",
                risk = entry?.risk ?: ServiceRisk.UNKNOWN,
                meaning = entry?.meaning
                    ?: "Registrierte Kennung $short, in diesem Katalog nicht enthalten.",
                standard = true,
                shortUuid = short,
            )
        }
        return ServiceDescription(
            uuid = normalised,
            name = "Herstellereigener Dienst",
            risk = ServiceRisk.VENDOR,
            meaning = "Nicht bei der Bluetooth SIG registriert, also vom Hersteller " +
                "erfunden. Hier liegt der Steuerkanal - und alles andere, was der " +
                "Hersteller nicht dokumentiert.",
            standard = false,
            shortUuid = null,
        )
    }

    fun describeAll(uuids: List<String>): List<ServiceDescription> = uuids.map { describe(it) }

    /** The ones worth a second look, most pressing first. */
    fun concerning(uuids: List<String>): List<ServiceDescription> =
        describeAll(uuids)
            .filter { it.risk in CONCERNING }
            .sortedBy { CONCERNING.indexOf(it.risk) }
}
