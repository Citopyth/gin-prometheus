package de.privat.boom2local.protocol

/**
 * Remembers, per speaker, which Bluetooth service actually answered.
 *
 * The Boom 2 advertises two services under the same vendor prefix. One carries the
 * control protocol; the other accepts a connection and then never says a word. Nothing
 * in the advertisement tells them apart, and the order they arrive in is not stable, so
 * the only reliable signal is empirical: a frame came back over this one.
 *
 * Kept here, away from Android, because the interesting parts are the bookkeeping
 * rules - what counts as valid, what gets evicted - and those deserve tests. The app
 * side is a single string in its private preferences.
 */
object ProvenServices {

    /** More than a handful of remembered speakers is a sign of a bug, not of use. */
    const val MAX_ENTRIES = 8

    private val ADDRESS = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun address(raw: String) = raw.trim().uppercase().takeIf { ADDRESS.matches(it) }

    private fun uuid(raw: String) = raw.trim().lowercase().takeIf { UUID.matches(it) }

    /** The service that answered for [rawAddress], or null if none is known. */
    fun proven(known: Map<String, String>, rawAddress: String): String? {
        val key = address(rawAddress) ?: return null
        return known[key]
    }

    /**
     * Records that [rawUuid] answered for [rawAddress].
     *
     * Rubbish in either argument changes nothing - a bad record would send every later
     * connection down the wrong pipe, which is worse than having no record at all.
     * The newest entry sits last, and the oldest is dropped past [MAX_ENTRIES].
     */
    fun remember(
        known: Map<String, String>,
        rawAddress: String,
        rawUuid: String,
    ): Map<String, String> {
        val key = address(rawAddress) ?: return known
        val value = uuid(rawUuid) ?: return known
        if (known[key] == value) return known
        val next = LinkedHashMap(known)
        next.remove(key)
        next[key] = value
        while (next.size > MAX_ENTRIES) {
            val oldest = next.keys.first()
            next.remove(oldest)
        }
        return next
    }

    /** Drops what was remembered for one speaker, e.g. when the user unpins it. */
    fun forget(known: Map<String, String>, rawAddress: String): Map<String, String> {
        val key = address(rawAddress) ?: return known
        if (!known.containsKey(key)) return known
        return LinkedHashMap(known).apply { remove(key) }
    }

    /** One `ADDRESS=uuid` per line, in order, ready for a preferences file. */
    fun encode(known: Map<String, String>): String =
        known.entries.joinToString("\n") { "${it.key}=${it.value}" }

    /**
     * Reads back what [encode] wrote.
     *
     * Anything unparsable is skipped rather than thrown: this is a cache, and a damaged
     * one must cost at most a slower connect, never a crash on startup.
     */
    fun decode(stored: String?): Map<String, String> {
        if (stored.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in stored.lineSequence()) {
            val cut = line.indexOf('=')
            if (cut <= 0) continue
            val key = address(line.substring(0, cut)) ?: continue
            val value = uuid(line.substring(cut + 1)) ?: continue
            out.remove(key)
            out[key] = value
        }
        while (out.size > MAX_ENTRIES) out.remove(out.keys.first())
        return out
    }
}
