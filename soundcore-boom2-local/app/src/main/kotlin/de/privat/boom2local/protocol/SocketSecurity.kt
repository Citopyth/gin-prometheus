package de.privat.boom2local.protocol

/** How much the Bluetooth link itself is required to prove. */
enum class SocketSecurity {
    /**
     * Authentication and encryption required.
     *
     * The link key is used, which is the one thing an impostor cannot copy. A device
     * that spoofs the speaker's address but was never paired has no key and cannot
     * complete this handshake, so this is what distinguishes the real speaker from
     * something answering in its name.
     */
    AUTHENTICATED,

    /**
     * No requirement from this side.
     *
     * Works against services that refuse the authenticated variant, and against
     * firmware that registers its vendor channel as open. It also means an impostor on
     * the same address is indistinguishable from the real device.
     */
    OPEN,
}

/** What the user chose in the UI. */
enum class SecurityPreference { AUTO, FORCE_AUTHENTICATED, FORCE_OPEN }

/**
 * Decides which socket flavours to try, and in which order.
 *
 * The Boom 2 accepts both, and that is exactly the problem: a capture shows successful
 * connections marked "ohne Authentifizierung" and one marked "mit", so the speaker
 * requires nothing and takes whatever it is offered. The profile's `insecureSocket: true`
 * then meant this app routinely chose the weaker one, for no better reason than that it
 * was the first to work.
 *
 * So AUTO asks for the authenticated link first and only falls back when it is refused.
 * Nothing is lost - the fallback is what used to happen immediately - and when the
 * stronger one does come up, the link is encrypted and the speaker has proved it holds
 * the pairing key.
 *
 * It is worth being precise about what this does and does not buy. It protects *this
 * phone* from talking to an impostor. It does nothing to stop another device from
 * opening the speaker's channel, because that is the speaker's decision and nothing
 * running here can change it.
 */
object SocketPlan {

    fun order(
        preference: SecurityPreference,
        profileAllowsOpen: Boolean,
    ): List<SocketSecurity> = when (preference) {
        // An explicit choice is honoured even when it looks hopeless, so a failure
        // reports the real reason instead of being quietly worked around.
        SecurityPreference.FORCE_AUTHENTICATED -> listOf(SocketSecurity.AUTHENTICATED)
        SecurityPreference.FORCE_OPEN -> listOf(SocketSecurity.OPEN)

        SecurityPreference.AUTO -> buildList {
            add(SocketSecurity.AUTHENTICATED)
            if (profileAllowsOpen) add(SocketSecurity.OPEN)
        }
    }

    /** What the audit log and the connection card say about a link that came up. */
    fun describe(security: SocketSecurity): String = when (security) {
        SocketSecurity.AUTHENTICATED ->
            "mit Authentifizierung - die Box hat den Kopplungsschluessel bewiesen"
        SocketSecurity.OPEN ->
            "ohne Authentifizierung - ein Geraet mit der Adresse deiner Box koennte " +
                "sich hier ausgeben"
    }

    /** True when the link that came up is the weaker of the two. */
    fun isWeak(security: SocketSecurity): Boolean = security == SocketSecurity.OPEN
}
