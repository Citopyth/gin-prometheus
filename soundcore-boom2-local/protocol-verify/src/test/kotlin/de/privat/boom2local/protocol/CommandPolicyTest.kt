package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandPolicyTest {

    private val readState = CommandSpec(
        key = "readState",
        label = "Status lesen",
        command = "01 01",
        kind = CommandKind.READ,
        payloadTemplate = "",
    )

    private val bassUp = CommandSpec(
        key = "bassUp",
        label = "BassUp",
        command = "02 84",
        kind = CommandKind.WRITE,
        payloadTemplate = "??",
        arguments = listOf(
            ArgumentSpec(
                key = "value",
                label = "BassUp",
                payloadIndex = 0,
                min = 0,
                max = 1,
                choices = listOf(Choice(0, "aus"), Choice(1, "an")),
            ),
        ),
    )

    private val forbidden = CommandSpec(
        key = "someFirmwareThing",
        label = "Firmware",
        command = "0f ff",
        kind = CommandKind.WRITE,
        payloadTemplate = "??",
        arguments = listOf(ArgumentSpec("value", "Wert", 0)),
    )

    private val handshakeStep = HandshakeStep(command = "05 81", payload = "01")

    private val profile = DeviceProfile(
        id = "test",
        displayName = "Test",
        framing = FramingSpec(handshake = listOf(handshakeStep)),
        commands = listOf(readState, bassUp, forbidden),
        blockedCommands = listOf("0f ff"),
    )

    private fun policy(readOnly: Boolean = false, raw: RawMode = RawMode.OFF) =
        CommandPolicy(profile, readOnly = readOnly, rawMode = raw)

    @Test
    fun `the shipped test profile is self consistent`() {
        assertEquals(emptyList(), profile.validate())
    }

    @Test
    fun `an allowlisted command with a valid argument passes`() {
        assertTrue(policy().evaluateSpec(bassUp, mapOf("value" to 1)).isAllowed)
        assertEquals("01", Hex.encode(policy().buildPayload(bassUp, mapOf("value" to 1))))
    }

    @Test
    fun `an argument outside its range is refused`() {
        val d = assertIs<PolicyDecision.Denied>(policy().evaluateSpec(bassUp, mapOf("value" to 2)))
        assertEquals("argument-range", d.rule)
    }

    @Test
    fun `a value that is in range but not a listed choice is refused`() {
        val narrow = bassUp.copy(
            arguments = listOf(
                ArgumentSpec(
                    key = "value",
                    label = "BassUp",
                    payloadIndex = 0,
                    min = 0,
                    max = 9,
                    choices = listOf(Choice(0, "aus"), Choice(1, "an")),
                ),
            ),
        )
        val p = CommandPolicy(profile.copy(commands = listOf(narrow)))
        val d = assertIs<PolicyDecision.Denied>(p.evaluateSpec(narrow, mapOf("value" to 5)))
        assertEquals("argument-range", d.rule)
    }

    @Test
    fun `a missing or unknown argument is refused`() {
        assertEquals(
            "argument-missing",
            assertIs<PolicyDecision.Denied>(policy().evaluateSpec(bassUp, emptyMap())).rule,
        )
        assertEquals(
            "argument-unknown",
            assertIs<PolicyDecision.Denied>(
                policy().evaluateSpec(bassUp, mapOf("value" to 1, "ghost" to 1)),
            ).rule,
        )
    }

    @Test
    fun `a command that did not come from the loaded profile is refused`() {
        val lookalike = bassUp.copy(label = "BassUp (gefaelscht)")
        val d = assertIs<PolicyDecision.Denied>(
            policy().evaluateSpec(lookalike, mapOf("value" to 1)),
        )
        assertEquals("not-allowlisted", d.rule)
    }

    @Test
    fun `the blocklist wins over the allowlist`() {
        val d = assertIs<PolicyDecision.Denied>(
            policy(raw = RawMode.UNRESTRICTED).evaluateSpec(forbidden, mapOf("value" to 1)),
        )
        assertEquals("blocklist", d.rule)
        val raw = assertIs<PolicyDecision.Denied>(
            policy(raw = RawMode.UNRESTRICTED).evaluateRaw(Command(0x0F, 0xFF), byteArrayOf(1)),
        )
        assertEquals("blocklist", raw.rule)
    }

    @Test
    fun `read only mode blocks writes but not reads`() {
        val p = policy(readOnly = true)
        assertEquals(
            "read-only",
            assertIs<PolicyDecision.Denied>(p.evaluateSpec(bassUp, mapOf("value" to 1))).rule,
        )
        assertTrue(p.evaluateSpec(readState, emptyMap()).isAllowed)
        assertEquals(
            "read-only",
            assertIs<PolicyDecision.Denied>(
                CommandPolicy(profile, readOnly = true, rawMode = RawMode.UNRESTRICTED)
                    .evaluateRaw(Command(2, 0x84), byteArrayOf(1)),
            ).rule,
        )
    }

    @Test
    fun `raw sending is off by default`() {
        val d = assertIs<PolicyDecision.Denied>(
            policy().evaluateRaw(Command(1, 1), ByteArray(0)),
        )
        assertEquals("expert-mode-off", d.rule)
    }

    @Test
    fun `known categories mode fences off unknown categories`() {
        val p = policy(raw = RawMode.KNOWN_CATEGORIES)
        assertTrue(p.evaluateRaw(Command(0x02, 0x99), byteArrayOf(1)).isAllowed)
        assertTrue(p.evaluateRaw(Command(0x01, 0x05), ByteArray(0)).isAllowed)
        val d = assertIs<PolicyDecision.Denied>(p.evaluateRaw(Command(0x33, 0x01), ByteArray(0)))
        assertEquals("unknown-category", d.rule)
        assertTrue(
            CommandPolicy(profile, rawMode = RawMode.UNRESTRICTED)
                .evaluateRaw(Command(0x33, 0x01), ByteArray(0)).isAllowed,
        )
    }

    @Test
    fun `payload and frame ceilings hold`() {
        val p = policy(raw = RawMode.UNRESTRICTED)
        assertTrue(p.evaluateRaw(Command(1, 1), ByteArray(64)).isAllowed)
        assertEquals(
            "payload-too-large",
            assertIs<PolicyDecision.Denied>(p.evaluateRaw(Command(1, 1), ByteArray(65))).rule,
        )
        val tight = CommandPolicy(
            profile.copy(framing = FramingSpec(maxFrameSize = 12)),
            rawMode = RawMode.UNRESTRICTED,
        )
        assertEquals(
            "frame-too-large",
            assertIs<PolicyDecision.Denied>(tight.evaluateRaw(Command(1, 1), ByteArray(10))).rule,
        )
    }

    @Test
    fun `handshake steps are allowed only when they come from the profile`() {
        assertTrue(policy().evaluateHandshake(handshakeStep).isAllowed)
        val d = assertIs<PolicyDecision.Denied>(
            policy().evaluateHandshake(HandshakeStep(command = "05 81", payload = "01")),
        )
        assertEquals("not-allowlisted", d.rule)
    }

    @Test
    fun `describe reports the active rules`() {
        val lines = policy(readOnly = true, raw = RawMode.KNOWN_CATEGORIES).describe()
        assertTrue(lines.any { it.contains("Default-Deny") }, lines.toString())
        assertTrue(lines.any { it.contains("0f:ff") }, lines.toString())
        assertTrue(lines.any { it.contains("Nur-Lesen-Modus: aktiv") }, lines.toString())
        assertTrue(lines.any { it.contains("Handshake: 1") }, lines.toString())
    }
}
