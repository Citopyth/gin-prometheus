package de.privat.boom2local.protocol

/** One answer the speaker gave to a pairing-list query. */
data class PairedReply(val command: Command, val bodyHex: String, val atMs: Long)

/** Where a pairing-list query stands. */
enum class PairedQueryPhase { NEVER_ASKED, WAITING, ANSWERED, SILENT }

/**
 * Tracks the question "who else may use this speaker", which has three possible
 * outcomes and used to show none of them.
 *
 * The button existed before this did: it sent `0b 01` and `0b 02` and that was the end
 * of it. An answer landed in the audit log as an undescribed frame; silence landed in
 * the audit log as a timeout. The card itself never changed, so pressing it looked
 * exactly like pressing a dead control - which is what the user reported.
 *
 * A query whose outcome is invisible is worse than no query: it teaches the person that
 * the feature is broken even on the occasions it works. So the three states are
 * distinct here - never asked, waiting, answered, and asked-but-silent - and the UI has
 * something to render for each.
 */
data class PairedQuery(
    val phase: PairedQueryPhase = PairedQueryPhase.NEVER_ASKED,
    val askedAtMs: Long? = null,
    val replies: List<PairedReply> = emptyList(),
) {
    /** Records that both queries went out. */
    fun asked(atMs: Long): PairedQuery =
        PairedQuery(PairedQueryPhase.WAITING, atMs, emptyList())

    /**
     * Records an answer.
     *
     * Accepted even when nothing was asked: the speaker may volunteer one, and throwing
     * away a frame because it arrived unexpectedly is how evidence gets lost.
     */
    fun answered(reply: PairedReply): PairedQuery = copy(
        phase = PairedQueryPhase.ANSWERED,
        replies = (replies + reply).takeLast(MAX_REPLIES),
    )

    /** Gives up waiting once the deadline has passed. Answers already in stay. */
    fun checked(nowMs: Long): PairedQuery = when {
        phase != PairedQueryPhase.WAITING -> this
        askedAtMs == null -> this
        nowMs - askedAtMs < PATIENCE_MS -> this
        else -> copy(phase = PairedQueryPhase.SILENT)
    }

    /** What the card says above whatever it has. */
    val summary: String
        get() = when (phase) {
            PairedQueryPhase.NEVER_ASKED ->
                "Noch nicht gefragt."

            PairedQueryPhase.WAITING ->
                "Abfrage ist raus, warte auf Antwort..."

            PairedQueryPhase.ANSWERED ->
                "Die Box hat geantwortet: ${replies.size} Rahmen. Gedeutet ist davon " +
                    "nichts - keine Quelle beschreibt das Format fuer dieses Modell. " +
                    "Die Rohdaten stehen unten und im Protokoll."

            PairedQueryPhase.SILENT ->
                "Keine Antwort. Diese Box hat auf 0b:01 und 0b:02 in keinem einzigen " +
                    "Mitschnitt geantwortet - gemeldet sind die beiden fuer die " +
                    "Space 2, nicht fuer die Boom 2. Der Knopf ist in Ordnung, die " +
                    "Box sagt nichts."
        }

    companion object {
        /**
         * How long to wait before calling it silence.
         *
         * Everything this speaker does answer comes back in well under a second;
         * acknowledgements in the captures run from 28 to 205 ms. Five seconds is
         * generous enough that a busy moment is not mistaken for a refusal.
         */
        const val PATIENCE_MS = 5_000L

        const val MAX_REPLIES = 8
    }
}
