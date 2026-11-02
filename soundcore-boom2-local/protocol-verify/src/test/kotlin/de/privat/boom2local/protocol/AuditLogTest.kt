package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuditLogTest {

    @Test
    fun `keeps entries in order with rising sequence numbers`() {
        val log = AuditLog(capacity = 10)
        log.add(AuditKind.SENT, "01:01", hex = "08 ee 00 00 00 01 01 0a 00 02")
        log.add(AuditKind.RECEIVED, "01:01", hex = "09 ff")
        val entries = log.snapshot()
        assertEquals(listOf(1L, 2L), entries.map { it.seq })
        assertEquals(AuditKind.SENT, entries[0].kind)
    }

    @Test
    fun `drops the oldest entries past capacity`() {
        val log = AuditLog(capacity = 3)
        repeat(5) { log.add(AuditKind.INFO, "entry $it") }
        val entries = log.snapshot()
        assertEquals(3, entries.size)
        assertEquals(listOf(3L, 4L, 5L), entries.map { it.seq })
        assertEquals("entry 4", entries.last().summary)
    }

    @Test
    fun `counts by kind and clears`() {
        val log = AuditLog()
        log.add(AuditKind.BLOCKED, "nope", rule = "not-allowlisted")
        log.add(AuditKind.BLOCKED, "nope again", rule = "blocklist")
        log.add(AuditKind.SENT, "ok")
        assertEquals(2, log.count(AuditKind.BLOCKED))
        assertEquals(1, log.count(AuditKind.SENT))
        log.clear()
        assertEquals(0, log.snapshot().size)
    }

    @Test
    fun `export contains the rule that refused a command`() {
        val log = AuditLog()
        log.add(AuditKind.BLOCKED, "0f:ff refused", rule = "blocklist", hex = "0f ff")
        val text = log.export()
        assertTrue(text.contains("blocklist"), text)
        assertTrue(text.contains("0f:ff refused"), text)
        assertTrue(text.startsWith("# Boom2Local audit log"), text)
    }

    @Test
    fun `a snapshot does not change when the log does`() {
        val log = AuditLog()
        log.add(AuditKind.INFO, "first")
        val snapshot = log.snapshot()
        log.add(AuditKind.INFO, "second")
        assertEquals(1, snapshot.size)
        assertEquals(2, log.snapshot().size)
    }

    @Test
    fun `a non positive capacity is rejected`() {
        assertFailsWith<IllegalArgumentException> { AuditLog(capacity = 0) }
    }
}
