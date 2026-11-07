package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqProbeTest {

    private val profile = TestFixtures.boom2Profile()

    @Test
    fun `the probe gain is fixed in the payload and cannot be an argument`() {
        // The whole reason this file exists. On 2026-11-05 the gain was an argument over
        // 0..240, someone picked the top of it, and the sound was ruined.
        val spec = profile.commands.single { it.key == EqProbe.COMMAND_KEY }
        assertEquals("fe ff ff 8c ?? 0a", spec.payloadTemplate)
        assertTrue(spec.arguments.none { it.key == "gain" })
        assertEquals(listOf("band"), spec.arguments.map { it.key })
        assertEquals(0x8c, EqProbe.PROBE_RAW)
        assertEquals("+2.0 dB", Decibels.format(Decibels.fromRaw(EqProbe.PROBE_RAW)))
        assertTrue(EqProbe.PROBE_RAW in Decibels.SAFE_RAW_RANGE)
    }

    @Test
    fun `every planned step is a value the profile will actually send`() {
        val band = profile.commands
            .single { it.key == EqProbe.COMMAND_KEY }
            .arguments
            .single { it.key == "band" }
        assertEquals(EqProbe.STEPS, EqProbe.plan().size)
        for (step in EqProbe.plan()) {
            assertTrue(band.accepts(step.band), "band ${step.band} refused by the policy")
        }
        assertEquals((0..8).toList(), EqProbe.plan().map { it.band })
    }

    @Test
    fun `the two captures differ exactly where the equaliser list is`() {
        // The finding that redesigned this whole search, asserted rather than described.
        // Nine entries became one, and the one is what eqFlat sends.
        val before = TestFixtures.boom2StateBody()
        val after = TestFixtures.boom2StateBodyAfterWrites()
        val result = EqProbe.compare(before, after)
        val moved = (result as ProbeResult.Several).bytes.map { it.offset }
        assertTrue(moved.isNotEmpty())
        assertTrue(
            moved.all { it in 3..60 },
            "the difference should sit in the equaliser block, got $moved",
        )
        // The surviving entry is gain 0x78, band 0xff, terminator 0x0a.
        assertEquals(0x78, after[34].toInt() and 0xFF)
        assertEquals(0xff, after[35].toInt() and 0xFF)
        assertEquals(0x0a, after[36].toInt() and 0xFF)
    }

    @Test
    fun `one moved byte is reported with where and what`() {
        val before = TestFixtures.boom2StateBodyAfterWrites()
        val after = before.copyOf()
        after[34] = EqProbe.PROBE_RAW.toByte()
        val one = EqProbe.compare(before, after) as ProbeResult.One
        assertEquals(34, one.byte.offset)
        assertEquals(0x78, one.byte.before)
        assertEquals(EqProbe.PROBE_RAW, one.byte.after)
        val text = EqProbe.describe(EqProbe.plan()[0], one)
        assertTrue(text.contains("Byte 34"), text)
        assertTrue(text.contains("Stelle dieses Bandes"), text)
    }

    @Test
    fun `the battery ticking down is not mistaken for a result`() {
        // It drifts on its own during a session, and reporting it would send the whole
        // search chasing offset 1.
        val before = TestFixtures.boom2StateBodyAfterWrites()
        val after = before.copyOf()
        after[1] = (before[1] - 1).toByte()
        assertEquals(ProbeResult.Nothing, EqProbe.compare(before, after))
        assertTrue(1 in EqProbe.DRIFTS)
    }

    @Test
    fun `nothing moving is its own answer, not a failure`() {
        val before = TestFixtures.boom2StateBodyAfterWrites()
        val result = EqProbe.compare(before, before.copyOf())
        assertEquals(ProbeResult.Nothing, result)
        assertTrue(EqProbe.describe(EqProbe.plan()[0], result).contains("kam nicht an"))
    }

    @Test
    fun `frames that cannot be compared say so instead of guessing`() {
        val body = TestFixtures.boom2StateBodyAfterWrites()
        assertTrue(EqProbe.compare(body, ByteArray(10)) is ProbeResult.Unusable)
        assertTrue(EqProbe.compare(ByteArray(0), body) is ProbeResult.Unusable)
        assertTrue(EqProbe.compare(body, ByteArray(0)) is ProbeResult.Unusable)
    }

    @Test
    fun `the comparison does not depend on the profile's offsets`() {
        // The bug this replaced: comparing only the nine offsets the profile named would
        // have reported nothing nine times, because those offsets were wrong.
        val before = TestFixtures.boom2StateBodyAfterWrites()
        val after = before.copyOf()
        after[91] = (before[91] + 1).toByte()
        val one = EqProbe.compare(before, after) as ProbeResult.One
        assertEquals(91, one.byte.offset)
    }

    @Test
    fun `only an unusable comparison blocks the search`() {
        // The bug this replaced would have stuck on step one forever. The equaliser
        // block is a list, so writing an entry moves more than one byte, and "several"
        // is therefore the expected answer - not a failure to stand still on.
        val body = TestFixtures.boom2StateBodyAfterWrites()
        val two = body.copyOf().also { it[34] = 0x8c.toByte(); it[35] = 0x00 }
        assertTrue(EqProbe.isConclusive(EqProbe.compare(body, two)))
        assertTrue(EqProbe.isConclusive(EqProbe.compare(body, body.copyOf())))
        assertTrue(
            EqProbe.isConclusive(
                EqProbe.compare(body, body.copyOf().also { it[34] = 0x8c.toByte() }),
            ),
        )
        assertTrue(!EqProbe.isConclusive(EqProbe.compare(body, ByteArray(3))))
    }

    @Test
    fun `the volume and the battery are both excluded from the comparison`() {
        // Both move without anyone asking - the battery by draining, the volume through
        // the phone's rocker over AVRCP - and either would be reported as the byte the
        // band value selects.
        val before = TestFixtures.boom2StateBodyAfterWrites()
        val after = before.copyOf()
        after[0] = (before[0] + 3).toByte()
        after[1] = (before[1] - 1).toByte()
        assertEquals(ProbeResult.Nothing, EqProbe.compare(before, after))
        assertEquals(setOf(0, 1), EqProbe.DRIFTS)
    }

    @Test
    fun `the settle delay is long enough for the speaker to apply a value`() {
        // Acknowledgements in the captures came back in 28 to 205 ms, and applying the
        // value takes at least that again. A baseline read before the flat curve lands
        // is answered from the wrong state, and nothing about the result looks wrong.
        assertTrue(EqProbe.SETTLE_MS >= 2 * 205, "settle=${EqProbe.SETTLE_MS} ms")
    }

    @Test
    fun `the shipped profile carries the command the probe needs`() {
        val spec = profile.commands.single { it.key == EqProbe.COMMAND_KEY }
        assertEquals(CommandKind.WRITE, spec.kind)
        assertEquals("02 88", spec.command)
        assertTrue(!spec.verified)
        assertTrue(!spec.acknowledged)
        assertTrue(spec.note?.contains("f0") == true)
    }
}
