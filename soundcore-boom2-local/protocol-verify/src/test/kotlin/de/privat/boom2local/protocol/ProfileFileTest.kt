package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileFileTest {

    private val profile = TestFixtures.boom2Profile()

    @Test
    fun `the shipped profile exists and parses`() {
        assertTrue(TestFixtures.profileFile.isFile, "missing ${TestFixtures.profileFile.path}")
        assertEquals("soundcore-boom-2", profile.id)
        assertEquals("Soundcore Boom 2", profile.displayName)
    }

    @Test
    fun `the shipped profile has no internal inconsistencies`() {
        assertEquals(emptyList(), profile.validate())
    }

    @Test
    fun `it survives a serialise and parse round trip`() {
        val again = DeviceProfile.parse(DeviceProfile.write(profile))
        assertEquals(profile, again)
    }

    @Test
    fun `only the fields confirmed against hardware claim to be verified`() {
        assertEquals(
            setOf("volume", "batteryLevel", "bassUp", "firmware", "serial", "customEq"),
            profile.state.fields.filter { it.verified }.map { it.key }.toSet(),
        )
        // Every confirmed field has to say what confirmed it; "verified" without a
        // reason is how a guess quietly turns into a fact.
        for (field in profile.state.fields.filter { it.verified }) {
            assertTrue(
                field.note?.contains("bestaetigt") == true,
                "field '${field.key}' claims to be verified but does not say why",
            )
        }
    }

    @Test
    fun `no write command claims to be verified`() {
        // Writes have never been tried against the speaker: the codes come from a
        // capture of a different model.
        assertTrue(profile.commands.none { it.verified })
        assertTrue(profile.notes.isNotEmpty())
    }

    @Test
    fun `every notification points at a field that exists`() {
        val fieldKeys = profile.state.fields.map { it.key }.toSet()
        for (notification in profile.state.notifications) {
            Command.parse(notification.command)
            assertTrue(
                notification.field in fieldKeys,
                "'${notification.command}' points at unknown field '${notification.field}'",
            )
            assertTrue(
                !notification.note.isNullOrBlank(),
                "'${notification.command}' needs to say where its reading comes from",
            )
        }
        assertEquals(
            mapOf(
                "01 03" to "batteryLevel",
                "01 09" to "volume",
                "02 04" to "bassUp",
                "0e 09" to "lightOn",
            ),
            profile.state.notifications.associate { it.command to it.field },
        )
    }

    @Test
    fun `a notification claims confirmation only where the capture gave it`() {
        // Three were pinned down by matching their values against a state byte over a
        // long capture. The fourth only ever appeared with one value, so its reading is
        // plausible and nothing more.
        assertEquals(
            setOf("01 03", "01 09", "02 04"),
            profile.state.notifications.filter { it.verified }.map { it.command }.toSet(),
        )
        assertEquals(
            setOf("0e 09"),
            profile.state.notifications.filterNot { it.verified }.map { it.command }.toSet(),
        )
    }

    @Test
    fun `an acknowledged command says so, and that is not the same as verified`() {
        val acknowledged = profile.commands.filter { it.acknowledged }.map { it.key }.toSet()
        assertEquals(
            setOf("bassUp", "eqPreset", "customEqBand", "lightOn", "lightBrightness"),
            acknowledged,
        )
        // The speaker confirms it knows these commands; what they do is still a label
        // borrowed from another model, so none of them is verified.
        assertTrue(profile.commands.none { it.verified })
        for (spec in profile.commands.filter { it.acknowledged }) {
            assertTrue(
                spec.note?.contains("quittiert") == true,
                "command '${spec.key}' claims acknowledgement but does not say how",
            )
        }
        val notAcknowledged = profile.commands
            .filter { it.kind == CommandKind.WRITE && !it.acknowledged }
            .map { it.key }
        assertEquals(listOf("lightMode"), notAcknowledged)
    }

    @Test
    fun `every fallback uuid matches the vendor prefix`() {
        assertTrue(profile.transport.fallbackUuids.isNotEmpty())
        for (uuid in profile.transport.fallbackUuids) {
            assertTrue(
                uuid.startsWith(profile.transport.uuidPrefix),
                "$uuid does not start with ${profile.transport.uuidPrefix}",
            )
        }
    }

    @Test
    fun `the device filter is strict and needs no scanning`() {
        assertTrue(profile.match.requireBonded, "pairing-only keeps the scan permission away")
        assertTrue(profile.match.matches("soundcore Boom 2"))
        assertTrue(profile.match.matches("SOUNDCORE BOOM 2-1234"))
        assertTrue(!profile.match.matches("soundcore Motion+"))
        assertTrue(!profile.match.matches("Pixel Buds"))
        assertTrue(!profile.match.matches(null))
    }

    @Test
    fun `the state read is allowlisted and declared as a read`() {
        val spec = profile.command("readState")
        assertTrue(spec != null)
        assertEquals(CommandKind.READ, spec!!.kind)
        assertEquals(profile.stateRequestCommand, spec.commandAddress)
        assertEquals(Command(1, 1), profile.stateRequestCommand)
    }

    @Test
    fun `every command carries a payload template its arguments agree with`() {
        for (spec in profile.commands) {
            val template = spec.template
            assertEquals(
                template.placeholderIndices.sorted(),
                spec.arguments.map { it.payloadIndex }.sorted(),
                "command '${spec.key}'",
            )
        }
    }

    @Test
    fun `the eq band indices are ascending and evenly spaced`() {
        val eq = profile.state.fields.first { it.key == "customEq" }
        assertEquals(9, eq.indices.size)
        assertEquals(34, eq.indices.first())
        assertEquals(eq.indices.sorted(), eq.indices)
        val steps = eq.indices.zipWithNext { a, b -> b - a }.toSet()
        assertEquals(setOf(3), steps, "the profile documents a stride of 3")
    }

    @Test
    fun `the ble section blocks the firmware services and guesses nothing`() {
        assertTrue(profile.ble.enabled)
        // No service is named: on an unmapped device the selector has to work it out,
        // and a guess here would send writes to whatever happened to be listed.
        assertEquals(emptyList(), profile.ble.serviceUuids)
        assertEquals(null, profile.ble.writeCharacteristicUuid)
        assertEquals(null, profile.ble.notifyCharacteristicUuid)
        assertTrue(
            profile.ble.blockedServiceUuids.any { it.startsWith("0000fe59") },
            "Nordic Secure DFU must be blocked",
        )
        assertTrue(
            profile.ble.blockedServiceUuids.any { it.startsWith("00001530") },
            "the legacy DFU service must be blocked",
        )
    }

    @Test
    fun `a firmware service is unreachable with the shipped profile`() {
        val dfu = GattService(
            uuid = "0000fe59-0000-1000-8000-00805f9b34fb",
            characteristics = listOf(
                GattCharacteristic("8ec90001-f315-4f60-9fb8-838830daea50", write = true, notify = true),
            ),
        )
        assertIs<GattSelection.None>(GattSelector.select(listOf(dfu), profile.ble))
    }

    @Test
    fun `the confirmed service uuid comes first and the prefix matches it`() {
        val confirmed = "0cf12d31-fac3-4553-bd80-d6832e7b3138"
        assertEquals(confirmed, profile.transport.fallbackUuids.first())
        assertTrue(
            confirmed.startsWith(profile.transport.uuidPrefix),
            "the prefix must match the UUID the device actually advertises",
        )
        // The Space 2's UUID differs in the byte before the model number, so a prefix
        // that includes that byte would not match both.
        assertTrue(
            "0cf12d31-fac3-4553-bd80-d6832e7d1402".startsWith(profile.transport.uuidPrefix),
        )
    }

    @Test
    fun `the handshake parses and stays opt-in`() {
        // Present in the profile so it is documented and auditable, but every step is a
        // write of unknown meaning, so the app only sends it when asked.
        assertEquals(7, profile.framing.handshake.size)
        for (step in profile.framing.handshake) {
            Command.parse(step.command)
            Hex.decode(step.payload)
            assertTrue(step.delayMsAfter >= 0)
        }
        assertEquals(Command(0x01, 0x01), Command.parse(profile.framing.handshake.first().command))
    }

    @Test
    fun `a malformed handshake step is caught at load time`() {
        val broken = profile.copy(
            framing = profile.framing.copy(
                handshake = listOf(HandshakeStep(command = "nonsense", payload = "zz")),
            ),
        )
        val problems = broken.validate()
        assertTrue(
            problems.any { it.contains("handshake[0].command") },
            problems.toString(),
        )
        assertTrue(
            problems.any { it.contains("handshake[0].payload") },
            problems.toString(),
        )
    }

    /**
     * The notes are what the app shows as its own account of what is and is not known,
     * so a note that contradicts the data is worse than no note. This pins the two
     * claims that have actually gone stale rather than trying to police prose in
     * general: a note once said the handshake was empty after seven steps were added.
     */
    @Test
    fun `the notes do not contradict the data`() {
        val notes = profile.notes.joinToString(" ").lowercase()
        if (profile.framing.handshake.isNotEmpty()) {
            assertTrue(
                !notes.contains("handshake ist leer"),
                "the profile carries ${profile.framing.handshake.size} handshake steps " +
                    "but a note still claims there are none",
            )
            assertTrue(notes.contains("handshake"), "a non-empty handshake needs explaining")
        }
        if (profile.blockedCommands.isEmpty()) {
            assertTrue(
                notes.contains("blockedcommands ist leer"),
                "an empty blocklist needs explaining",
            )
        }
    }

    @Test
    fun `the blocklist is empty but documented`() {
        assertEquals(emptyList(), profile.blockedCommands)
        assertTrue(
            profile.notes.any { it.contains("blockedCommands") },
            "an empty blocklist needs an explanation in the profile",
        )
    }

    @Test
    fun `the write commands are the ones the ui offers and nothing more`() {
        assertEquals(
            setOf("bassUp", "eqPreset", "customEqBand", "lightOn", "lightBrightness", "lightMode"),
            profile.commands.filter { it.kind == CommandKind.WRITE }.map { it.key }.toSet(),
        )
    }
}
