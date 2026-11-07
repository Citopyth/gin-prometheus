package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairedQueryTest {

    private val reply = PairedReply(Command(0x0b, 0x01), "01 02 03", 1_000)

    @Test
    fun `never asked is its own state, not silence`() {
        // The bug this file exists for: the card showed the same nothing whether the
        // query had never run, was running, had answered, or had been ignored.
        val fresh = PairedQuery()
        assertEquals(PairedQueryPhase.NEVER_ASKED, fresh.phase)
        assertTrue(fresh.summary.contains("Noch nicht gefragt"))
    }

    @Test
    fun `asking moves to waiting and clears older answers`() {
        val q = PairedQuery().answered(reply).asked(2_000)
        assertEquals(PairedQueryPhase.WAITING, q.phase)
        assertEquals(2_000, q.askedAtMs)
        assertEquals(emptyList(), q.replies)
        assertTrue(q.summary.contains("warte"))
    }

    @Test
    fun `an answer is kept with its bytes and says nothing is decoded`() {
        val q = PairedQuery().asked(1_000).answered(reply)
        assertEquals(PairedQueryPhase.ANSWERED, q.phase)
        assertEquals(listOf(reply), q.replies)
        assertTrue(q.summary.contains("geantwortet"))
        assertTrue(
            q.summary.contains("Gedeutet ist davon nichts"),
            "an undecoded answer must not look like a decoded one",
        )
    }

    @Test
    fun `an unexpected answer is kept rather than discarded`() {
        // The speaker may volunteer one. Throwing it away because nobody asked is how
        // the only evidence of a format gets lost.
        val q = PairedQuery().answered(reply)
        assertEquals(PairedQueryPhase.ANSWERED, q.phase)
        assertEquals(listOf(reply), q.replies)
    }

    @Test
    fun `silence is only declared after the deadline`() {
        val q = PairedQuery().asked(1_000)
        assertEquals(PairedQueryPhase.WAITING, q.checked(1_000).phase)
        assertEquals(
            PairedQueryPhase.WAITING,
            q.checked(1_000 + PairedQuery.PATIENCE_MS - 1).phase,
        )
        val silent = q.checked(1_000 + PairedQuery.PATIENCE_MS)
        assertEquals(PairedQueryPhase.SILENT, silent.phase)
        assertTrue(silent.summary.contains("Der Knopf ist in Ordnung"))
    }

    @Test
    fun `an answered query is never downgraded to silent`() {
        val q = PairedQuery().asked(1_000).answered(reply)
        assertEquals(PairedQueryPhase.ANSWERED, q.checked(1_000_000).phase)
    }

    @Test
    fun `a query that was never asked cannot time out`() {
        assertEquals(PairedQueryPhase.NEVER_ASKED, PairedQuery().checked(1_000_000).phase)
    }

    @Test
    fun `the replies do not grow without bound`() {
        var q = PairedQuery().asked(0)
        for (i in 1..PairedQuery.MAX_REPLIES + 5) {
            q = q.answered(PairedReply(Command(0x0b, 0x01), "%02x".format(i), i.toLong()))
        }
        assertEquals(PairedQuery.MAX_REPLIES, q.replies.size)
        assertEquals("%02x".format(PairedQuery.MAX_REPLIES + 5), q.replies.last().bodyHex)
    }

    @Test
    fun `the patience outlasts everything this speaker answers quickly`() {
        // Acknowledgements in the captures run 28 to 205 ms, state frames about 35 ms.
        assertTrue(PairedQuery.PATIENCE_MS >= 2_000, "patience=${PairedQuery.PATIENCE_MS}")
    }
}
