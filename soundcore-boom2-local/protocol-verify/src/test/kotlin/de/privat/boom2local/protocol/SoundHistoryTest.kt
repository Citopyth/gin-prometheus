package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundHistoryTest {

    private fun preset(i: Int) =
        SoundSetting("Voreinstellung $i", "eqPreset", mapOf("preset" to i))

    @Test
    fun `undoing nothing still lands somewhere chosen`() {
        // The button must never be a no-op that leaves the speaker in a state the user
        // did not pick - that is how the equaliser got wrecked in the first place.
        val plan = SoundHistory.undo(emptyList())
        assertEquals(SoundHistory.NEUTRAL, plan.reapply)
        assertEquals(emptyList(), plan.remaining)
        assertTrue(plan.atNeutral)
    }

    @Test
    fun `undoing the first change goes to neutral`() {
        val history = SoundHistory.push(emptyList(), preset(3))
        val plan = SoundHistory.undo(history)
        assertEquals(SoundHistory.NEUTRAL, plan.reapply)
        assertTrue(plan.atNeutral)
    }

    @Test
    fun `undoing walks back one step at a time`() {
        var history = emptyList<SoundSetting>()
        for (i in 1..3) history = SoundHistory.push(history, preset(i))

        val first = SoundHistory.undo(history)
        assertEquals(preset(2), first.reapply)
        assertTrue(!first.atNeutral)

        val second = SoundHistory.undo(first.remaining)
        assertEquals(preset(1), second.reapply)
        assertTrue(!second.atNeutral)

        val third = SoundHistory.undo(second.remaining)
        assertEquals(SoundHistory.NEUTRAL, third.reapply)
        assertTrue(third.atNeutral)

        // And one more does no harm.
        assertEquals(SoundHistory.NEUTRAL, SoundHistory.undo(third.remaining).reapply)
    }

    @Test
    fun `reset forgets the session and returns to neutral`() {
        val plan = SoundHistory.reset()
        assertEquals(SoundHistory.NEUTRAL, plan.reapply)
        assertEquals(emptyList(), plan.remaining)
        assertTrue(plan.atNeutral)
    }

    @Test
    fun `an evening of listening does not grow the stack without bound`() {
        var history = emptyList<SoundSetting>()
        for (i in 1..SoundHistory.MAX_DEPTH + 20) {
            history = SoundHistory.push(history, preset(i))
        }
        assertEquals(SoundHistory.MAX_DEPTH, history.size)
        // The newest is kept and the oldest dropped, so undo stays useful.
        assertEquals(preset(SoundHistory.MAX_DEPTH + 20), history.last())
    }

    @Test
    fun `a replayed setting is a profile command, not raw bytes`() {
        // The point of the design: undo goes through the allowlist and the argument
        // ranges, so it works with the raw mode off, which is the default.
        val setting = preset(2)
        assertTrue(setting.commandKey.isNotBlank())
        assertEquals(mapOf("preset" to 2), setting.arguments)
    }

    @Test
    fun `the shipped profile really has the command the floor of the stack names`() {
        val profile = TestFixtures.boom2Profile()
        val neutral = profile.commands.single { it.key == SoundHistory.NEUTRAL_COMMAND_KEY }
        assertEquals(CommandKind.WRITE, neutral.kind)
        assertEquals(emptyList(), neutral.arguments)
        // Fixed payload, every band at 0 dB: the gain byte is 0x78 = 120.
        assertEquals("fe ff ff 78 ff 0a", neutral.payloadTemplate)
        assertEquals(Decibels.NEUTRAL_RAW, 0x78)
    }
}
