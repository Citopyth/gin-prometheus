package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the exact bytes every shipped command puts on the wire.
 *
 * The profile is data, and data is easy to change by accident — a stray default, a
 * reordered argument, a changed template. These expectations were computed from the
 * frame layout by a separate script rather than copied from the implementation, so they
 * fail if either the profile or the codec drifts.
 *
 * Worth recording: the handshake checksums below were first worked out in my head and
 * six of the seven were wrong, which the test caught. Arithmetic that looks easy is
 * exactly the kind that needs checking against something other than itself.
 */
class CommandBytesTest {

    private val profile = TestFixtures.boom2Profile()
    private val codec = profile.codec()
    private val policy = CommandPolicy(profile)

    private fun defaults(spec: CommandSpec): Map<String, Int> =
        spec.arguments.associate { argument ->
            argument.key to (argument.default ?: argument.choices.firstOrNull()?.value ?: argument.min)
        }

    private fun frameFor(key: String, arguments: Map<String, Int>? = null): String {
        val spec = requireNotNull(profile.command(key)) { "no command '$key'" }
        val values = arguments ?: defaults(spec)
        require(policy.evaluateSpec(spec, values).isAllowed) { "'$key' refused by the policy" }
        return Hex.encode(codec.encode(spec.commandAddress, policy.buildPayload(spec, values)))
    }

    @Test
    fun `every shipped command produces exactly these bytes with its defaults`() {
        val expected = mapOf(
            "readState" to "08 ee 00 00 00 01 01 0a 00 02",
            "bassUp" to "08 ee 00 00 00 02 84 0b 00 00 87",
            "eqPreset" to "08 ee 00 00 00 02 88 0b 00 00 8b",
            "customEqBand" to "08 ee 00 00 00 02 88 10 00 fe 00 00 78 00 0a 10",
            "lightOn" to "08 ee 00 00 00 0e 08 0b 00 00 17",
            "lightBrightness" to "08 ee 00 00 00 0e 05 0b 00 80 94",
            "lightMode" to "08 ee 00 00 00 0e 07 0b 00 00 16",
        )
        assertEquals(
            expected.keys,
            profile.commands.map { it.key }.toSet(),
            "the profile gained or lost a command; update the expected bytes deliberately",
        )
        for ((key, bytes) in expected) {
            assertEquals(bytes, frameFor(key), "command '$key'")
        }
    }

    @Test
    fun `arguments move exactly the bytes they are supposed to move`() {
        assertEquals(
            "08 ee 00 00 00 02 84 0b 00 01 88",
            frameFor("bassUp", mapOf("value" to 1)),
        )
        assertEquals(
            "08 ee 00 00 00 0e 05 0b 00 ff 13",
            frameFor("lightBrightness", mapOf("value" to 255)),
        )
    }

    @Test
    fun `the fixed bytes of the eq template cannot be moved by an argument`() {
        val frame = frameFor(
            "customEqBand",
            mapOf("maskHigh" to 0xAA, "maskLow" to 0xBB, "gain" to 0xCC, "band" to 0xDD),
        )
        val body = Hex.encode(
            (codec.decode(Hex.decode(frame)) as DecodeResult.Ok).frame.body,
        )
        assertEquals("fe aa bb cc dd 0a", body)
        assertTrue(body.startsWith("fe"), "the leading marker is fixed by the template")
        assertTrue(body.endsWith("0a"), "the trailing marker is fixed by the template")
    }

    @Test
    fun `every frame decodes back to the command it was built from`() {
        for (spec in profile.commands) {
            val raw = Hex.decode(frameFor(spec.key))
            val decoded = codec.decode(raw)
            assertTrue(decoded is DecodeResult.Ok, "'${spec.key}': $decoded")
            assertEquals(spec.commandAddress, decoded.frame.command, "'${spec.key}'")
            assertEquals(Direction.TO_DEVICE, decoded.frame.direction)
        }
    }

    @Test
    fun `the handshake puts these bytes on the wire`() {
        val steps = profile.framing.handshake
        val bytes = steps.map {
            Hex.encode(codec.encode(Command.parse(it.command), Hex.decode(it.payload)))
        }
        assertEquals(
            listOf(
                "08 ee 00 00 00 01 01 0a 00 02",
                "08 ee 00 00 00 05 01 0b 00 01 08",
                "08 ee 00 00 00 05 81 0a 00 86",
                "08 ee 00 00 00 05 81 0a 00 86",
                "08 ee 00 00 00 05 81 0b 00 01 88",
                "08 ee 00 00 00 05 81 0a 00 86",
                "08 ee 00 00 00 18 85 0b 00 01 9f",
            ),
            bytes,
        )
    }
}
