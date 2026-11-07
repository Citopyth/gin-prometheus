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
            setOf("volume", "batteryLevel", "bassUp", "firmware", "serial"),
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
    fun `exactly one write command is verified, and for a bad reason`() {
        // This test used to assert that no write was verified. That changed on
        // 2026-11-06, and not happily: eqAllBands went out with gain 0xf0 - +12 dB on
        // every band - and the sound was audibly ruined afterwards. An observed effect
        // is an observed effect, so the command is verified. Nothing else is: the other
        // writes are acknowledged, which only means the speaker knows the address.
        assertEquals(
            setOf("eqAllBands"),
            profile.commands.filter { it.verified }.map { it.key }.toSet(),
        )
        val verified = profile.commands.single { it.verified }
        assertTrue(
            verified.note?.contains("Wirkung") == true,
            "a verified write has to say which effect was seen",
        )
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
                "01 04" to "charging",
            ),
            profile.state.notifications.associate { it.command to it.field },
        )
    }

    @Test
    fun `a field outside the state frame exists for a notification and nothing else`() {
        // Without this, "inStateFrame: false" would be a way to park a field nobody
        // ever reads, and the profile would quietly grow values the app cannot show.
        // Possibly none: charging used to be the only one, until a state frame read
        // while the cable was in showed where it lives. The rule still holds for any
        // field that ends up out here.
        val outside = profile.state.fields.filter { !it.inStateFrame }
        for (field in outside) {
            assertTrue(
                profile.state.notifications.any { it.field == field.key },
                "field '${field.key}' is in no state frame and in no notification either",
            )
            assertTrue(
                !field.note.isNullOrBlank(),
                "field '${field.key}' has no offset, so it owes the reader an explanation",
            )
        }
    }

    @Test
    fun `a notification claims confirmation only where the capture gave it`() {
        // Three were pinned down by matching their values against a state byte over a
        // long capture. The other two only ever appeared with one value, so their
        // reading is plausible and nothing more.
        assertEquals(
            setOf("01 03", "01 09", "02 04"),
            profile.state.notifications.filter { it.verified }.map { it.command }.toSet(),
        )
        assertEquals(
            setOf("0e 09", "01 04"),
            profile.state.notifications.filterNot { it.verified }.map { it.command }.toSet(),
        )
    }

    @Test
    fun `an acknowledged command says so, and that is not the same as verified`() {
        val acknowledged = profile.commands.filter { it.acknowledged }.map { it.key }.toSet()
        assertEquals(
            setOf(
                "bassUp", "eqPreset", "eqFlat", "eqAllBands",
                "lightOn", "lightBrightness",
            ),
            acknowledged,
        )
        // The speaker confirms it knows these addresses; for all but one, what they do
        // is still a label borrowed from another model.
        assertEquals(1, profile.commands.count { it.verified })
        for (spec in profile.commands.filter { it.acknowledged }) {
            assertTrue(
                spec.note?.contains("quittiert") == true,
                "command '${spec.key}' claims acknowledgement but does not say how",
            )
        }
        val notAcknowledged = profile.commands
            .filter { it.kind == CommandKind.WRITE && !it.acknowledged }
            .map { it.key }
        // lightMode was sent and drew no acknowledgement. eqProbeBand has never been
        // sent at all: it is the research command, and marking it acknowledged because
        // a *different* payload on the same address was acknowledged would be exactly
        // the kind of inherited claim this profile exists to avoid.
        assertEquals(listOf("eqProbeBand", "lightMode"), notAcknowledged)
        for (key in notAcknowledged) {
            val spec = profile.commands.single { it.key == key }
            assertTrue(
                spec.note?.contains("NICHT quittiert") == true ||
                    spec.note?.contains("nicht quittiert") == true,
                "command '$key' is not acknowledged and should say so plainly",
            )
        }
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
    fun `the profile no longer claims nine equaliser bands at fixed offsets`() {
        // It did, with a stride of three from offset 34, and a test asserted it. The
        // first state frame this project ever received disproved it: the block is a
        // list of three-byte entries whose length depends on what was last written.
        // Deleting the claim is the point; this test is what stops it coming back.
        assertTrue(
            profile.state.fields.none { it.type == FieldType.EQ_BANDS },
            "the nine-band reading was disproven on 2026-11-07",
        )
        val gain = profile.state.fields.single { it.key == "eqGain" }
        assertEquals(34, gain.offset)
        assertEquals(FieldType.U8, gain.type)
        assertTrue(!gain.verified)
        assertTrue(
            gain.note?.contains("WIDERLEGT") == true,
            "a field that replaced a disproven one has to say so",
        )
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
    fun `the profile waits before sending its first frame`() {
        // The speaker ignored the first frame in two of four captures when it went out
        // within 2 ms of the socket opening.
        assertTrue(
            profile.transport.settleAfterConnectMs >= 200,
            "got ${profile.transport.settleAfterConnectMs} ms",
        )
        assertTrue(profile.transport.settleAfterConnectMs <= 2_000, "and not so long it drags")
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
            setOf(
                "bassUp", "eqPreset", "eqFlat", "eqAllBands", "eqProbeBand",
                "lightOn", "lightBrightness", "lightMode",
            ),
            profile.commands.filter { it.kind == CommandKind.WRITE }.map { it.key }.toSet(),
        )
    }

    @Test
    fun `no sound command can reach a gain that ruins the speaker`() {
        // The whole point of the 2026-11-06 changes. Not a convention the UI follows:
        // every value these commands will accept is checked here, and the policy
        // enforces the same grid, so a hand-set argument cannot get past it either.
        for (spec in profile.commands.filter { it.command == "02 88" }) {
            for (argument in spec.arguments) {
                if (!argument.isDecibels) continue
                for (value in argument.allowedValues()) {
                    assertTrue(
                        value in Decibels.SAFE_RAW_RANGE,
                        "${spec.key}/${argument.key} allows raw $value " +
                            "(${argument.render(value)})",
                    )
                    assertTrue(argument.accepts(value))
                }
                // And the byte that caused the damage is refused outright.
                assertTrue(!argument.accepts(0xf0), "${spec.key} still accepts 0xf0")
            }
        }
    }

    @Test
    fun `the preset index that was sent in error can no longer be chosen`() {
        val preset = profile.commands.single { it.key == "eqPreset" }.arguments.single()
        assertTrue(!preset.accepts(0xff), "preset 255 is reachable again")
        assertEquals(0, preset.min)
        assertEquals(9, preset.max)
        assertEquals(0, preset.default)
    }
}
