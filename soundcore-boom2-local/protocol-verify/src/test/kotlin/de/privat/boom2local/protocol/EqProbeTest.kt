package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqProbeTest {

    private val profile = TestFixtures.boom2Profile()
    private val bandOffsets = profile.state.fields.single { it.key == "customEq" }.indices

    @Test
    fun `the probe gain is fixed in the payload and cannot be an argument`() {
        // The whole reason this file exists. On 2026-11-05 the gain was an argument over
        // 0..240, someone picked the top of it, and nine bands went to +12 dB.
        val spec = profile.commands.single { it.key == EqProbe.COMMAND_KEY }
        assertEquals("fe ?? ?? 8c ?? 0a", spec.payloadTemplate)
        assertTrue(
            spec.arguments.none { it.key == "gain" },
            "the gain must not be settable on the probe command",
        )
        assertEquals(0x8c, EqProbe.PROBE_RAW)
        assertEquals("+2.0 dB", Decibels.format(Decibels.fromRaw(EqProbe.PROBE_RAW)))
        assertTrue(EqProbe.PROBE_RAW in Decibels.SAFE_RAW_RANGE)
    }

    @Test
    fun `the mask bytes take single bits only`() {
        val spec = profile.commands.single { it.key == EqProbe.COMMAND_KEY }
        val low = spec.arguments.single { it.key == "maskLow" }
        val high = spec.arguments.single { it.key == "maskHigh" }
        // Every allowed value is zero or one bit, so a step moves one band at most.
        for (argument in listOf(low, high)) {
            for (value in argument.allowedValues()) {
                assertTrue(
                    value == 0 || value.countOneBits() == 1,
                    "${argument.key} allows $value, which is not a single bit",
                )
            }
        }
        // And the byte that caused the damage is not even in range here.
        assertTrue(!low.accepts(0xff))
        assertTrue(!low.accepts(0xf0))
        assertTrue(!low.accepts(3))
    }

    @Test
    fun `the plan walks nine bits, each exactly one`() {
        val plan = EqProbe.plan()
        assertEquals(EqProbe.STEPS, plan.size)
        assertEquals((1..9).toList(), plan.map { it.number })
        for (step in plan) {
            val bits = step.maskHigh.countOneBits() + step.maskLow.countOneBits()
            assertEquals(1, bits, "step ${step.number} sets $bits bits")
        }
        // No two steps set the same bit.
        assertEquals(plan.size, plan.map { it.maskHigh to it.maskLow }.distinct().size)
        assertEquals(0 to 1, plan.first().let { it.maskHigh to it.maskLow })
        assertEquals(1 to 0, plan.last().let { it.maskHigh to it.maskLow })
    }

    @Test
    fun `every planned step is a value the profile will actually send`() {
        // A plan the allowlist refuses is a plan that fails on the ninth step at the
        // user's expense, so the two are checked against each other here.
        val spec = profile.commands.single { it.key == EqProbe.COMMAND_KEY }
        val low = spec.arguments.single { it.key == "maskLow" }
        val high = spec.arguments.single { it.key == "maskHigh" }
        for (step in EqProbe.plan()) {
            assertTrue(low.accepts(step.maskLow), "maskLow ${step.maskLow} refused")
            assertTrue(high.accepts(step.maskHigh), "maskHigh ${step.maskHigh} refused")
        }
    }

    @Test
    fun `one moved band is read as that band`() {
        val before = TestFixtures.boom2StateBody()
        val after = before.copyOf()
        after[bandOffsets[3]] = EqProbe.PROBE_RAW.toByte()
        val result = EqProbe.compare(before, after, bandOffsets)
        val band = result as ProbeResult.Band
        assertEquals(3, band.index)
        assertEquals(bandOffsets[3], band.bodyOffset)
        assertEquals(EqProbe.PROBE_RAW, band.raw)
        assertTrue(EqProbe.describe(EqProbe.plan()[0], result).contains("Band 4"))
    }

    @Test
    fun `several moved bands are not read as one`() {
        // The mistake this guards: taking the first difference and calling it the band.
        val before = TestFixtures.boom2StateBody()
        val after = before.copyOf()
        after[bandOffsets[1]] = EqProbe.PROBE_RAW.toByte()
        after[bandOffsets[5]] = EqProbe.PROBE_RAW.toByte()
        val result = EqProbe.compare(before, after, bandOffsets)
        assertEquals(listOf(1, 5), (result as ProbeResult.Several).indices)
        assertTrue(EqProbe.describe(EqProbe.plan()[0], result).contains("mehrere"))
    }

    @Test
    fun `nothing moving is its own answer, not a failure`() {
        val before = TestFixtures.boom2StateBody()
        assertEquals(ProbeResult.Nothing, EqProbe.compare(before, before.copyOf(), bandOffsets))
    }

    @Test
    fun `frames that cannot be compared say so instead of guessing`() {
        val before = TestFixtures.boom2StateBody()
        assertTrue(
            EqProbe.compare(before, ByteArray(10), bandOffsets) is ProbeResult.Unusable,
        )
        assertTrue(
            EqProbe.compare(ByteArray(4), ByteArray(4), bandOffsets) is ProbeResult.Unusable,
        )
        assertTrue(
            EqProbe.compare(before, before.copyOf(), emptyList()) is ProbeResult.Unusable,
        )
    }

    @Test
    fun `a change outside the bands is ignored`() {
        // The battery or the volume moving between two reads must not look like a band.
        val before = TestFixtures.boom2StateBody()
        val after = before.copyOf()
        val notABand = (before.indices).first { it !in bandOffsets }
        after[notABand] = (before[notABand] + 1).toByte()
        assertEquals(ProbeResult.Nothing, EqProbe.compare(before, after, bandOffsets))
    }

    @Test
    fun `the shipped profile carries the command the probe needs`() {
        val spec = profile.commands.single { it.key == EqProbe.COMMAND_KEY }
        assertEquals(CommandKind.WRITE, spec.kind)
        assertEquals("02 88", spec.command)
        assertTrue(!spec.verified)
        assertTrue(spec.note?.contains("f0") == true, "the note must record why the gain is fixed")
        assertEquals(9, bandOffsets.size)
    }
}
