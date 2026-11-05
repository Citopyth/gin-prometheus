package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingCommandsTest {

    private val bassUp = Command(0x02, 0x84)
    private val brightness = Command(0x0E, 0x05)

    @Test
    fun `an answered command stops being pending`() {
        val pending = PendingCommands().sent(bassUp, 1_000).answered(bassUp)
        assertEquals(0, pending.size)
    }

    @Test
    fun `an answer clears the oldest of several in flight`() {
        // The brightness slider sends four in a second; the speaker answers in order.
        var pending = PendingCommands()
        for (at in listOf(100L, 200L, 300L, 400L)) pending = pending.sent(brightness, at)
        pending = pending.answered(brightness)
        assertEquals(3, pending.size)
        assertEquals(200L, pending.entries.first().sentAtMs)
    }

    @Test
    fun `an answer to something that was never sent changes nothing`() {
        val pending = PendingCommands().sent(bassUp, 100)
        assertEquals(pending, pending.answered(brightness))
    }

    @Test
    fun `silence past the timeout is reported`() {
        val pending = PendingCommands(timeoutMs = 3_000).sent(bassUp, 1_000)
        assertEquals(emptyList(), pending.overdue(3_000))
        assertEquals(listOf(bassUp), pending.overdue(4_000).map { it.command })
        assertEquals(listOf(bassUp), pending.overdue(60_000).map { it.command })
    }

    @Test
    fun `each silence is reported once`() {
        var pending = PendingCommands(timeoutMs = 3_000).sent(bassUp, 1_000)
        assertEquals(1, pending.overdue(5_000).size)
        pending = pending.withoutOverdue(5_000)
        assertEquals(emptyList(), pending.overdue(5_000))
        assertEquals(0, pending.size)
    }

    @Test
    fun `a command still within its time is left alone`() {
        var pending = PendingCommands(timeoutMs = 3_000)
            .sent(bassUp, 1_000)
            .sent(brightness, 4_500)
        pending = pending.withoutOverdue(5_000)
        assertEquals(listOf(brightness), pending.entries.map { it.command })
    }

    @Test
    fun `the case from the capture - twelve frames and no answer at all`() {
        // Twelve commands went out over 36 seconds and nothing came back, while the
        // socket stayed open. Every one of them has to show up as unanswered.
        var pending = PendingCommands(timeoutMs = 3_000)
        var at = 0L
        repeat(12) {
            pending = pending.sent(Command(0x0E, 0x07), at)
            at += 3_000
        }
        val overdue = pending.overdue(at)
        assertEquals(12, overdue.size)
        assertTrue(overdue.all { it.command == Command(0x0E, 0x07) })
    }

    @Test
    fun `disconnecting forgets everything that was in flight`() {
        val pending = PendingCommands().sent(bassUp, 1).sent(brightness, 2).cleared()
        assertEquals(0, pending.size)
        assertEquals(emptyList(), pending.overdue(Long.MAX_VALUE))
    }
}
