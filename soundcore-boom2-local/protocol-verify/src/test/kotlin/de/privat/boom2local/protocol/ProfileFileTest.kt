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
    fun `nothing in it claims to be verified`() {
        assertTrue(profile.state.fields.none { it.verified })
        assertTrue(profile.commands.none { it.verified })
        assertTrue(
            profile.notes.isNotEmpty(),
            "the profile should say in writing that it is unverified",
        )
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
        assertEquals(8, eq.indices.size)
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
