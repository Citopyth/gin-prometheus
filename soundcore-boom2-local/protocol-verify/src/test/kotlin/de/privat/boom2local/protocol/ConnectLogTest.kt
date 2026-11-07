package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectLogTest {

    private val dead = "read failed, socket might closed or timeout, read ret: -1"

    private fun failures(vararg pairs: Pair<String, String>) =
        pairs.map { AttemptFailure(it.first, it.second) }

    @Test
    fun `a round where everything fails the same way collapses to one line`() {
        // The case from the capture: six services, one message, six identical entries.
        val lines = ConnectLog.summarise(
            failures(*Array(6) { "uuid-$it" to dead }),
        )
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("uuid-0: $dead"), lines.single())
        assertTrue(lines.single().contains("5 weitere"), lines.single())
        assertTrue(lines.single().contains("aus oder ausser Reichweite"), lines.single())
    }

    @Test
    fun `different failures are all kept, because they say the services differ`() {
        val lines = ConnectLog.summarise(
            failures(
                "a" to dead,
                "b" to "service discovery failed",
                "c" to dead,
            ),
        )
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("service discovery failed"))
    }

    @Test
    fun `the error text itself is never lost`() {
        val lines = ConnectLog.summarise(failures(*Array(20) { "u$it" to dead }))
        assertTrue(lines.single().contains(dead))
    }

    @Test
    fun `runs are counted separately, not merged across a different message`() {
        val lines = ConnectLog.summarise(
            failures(
                "a" to dead, "b" to dead,
                "c" to "other",
                "d" to dead, "e" to dead, "f" to dead,
            ),
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("1 weitere"))
        assertTrue(lines[1].contains("other"))
        assertTrue(lines[2].contains("2 weitere"))
    }

    @Test
    fun `one failure stays one line with no count`() {
        val lines = ConnectLog.summarise(failures("only" to dead))
        assertEquals(listOf("only: $dead"), lines)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList(), ConnectLog.summarise(emptyList()))
    }
}
