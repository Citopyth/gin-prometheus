package de.privat.boom2local.protocol

/** Where an audit entry came from. */
enum class AuditKind { SENT, RECEIVED, BLOCKED, INFO, ERROR }

data class AuditEntry(
    val seq: Long,
    val timestampMs: Long,
    val kind: AuditKind,
    val summary: String,
    val hex: String? = null,
    val rule: String? = null,
)

/**
 * A bounded, in-memory log of every byte the app sent or received and every command the
 * policy refused.
 *
 * In memory only, and never written to disk unless you export it yourself: a privacy
 * tool that quietly keeps a persistent record of your device would be a poor one.
 */
class AuditLog(private val capacity: Int = 500) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = ArrayDeque<AuditEntry>()
    private var nextSeq = 1L
    private val lock = Any()

    fun add(
        kind: AuditKind,
        summary: String,
        hex: String? = null,
        rule: String? = null,
        timestampMs: Long = 0L,
    ): AuditEntry {
        synchronized(lock) {
            val entry = AuditEntry(nextSeq++, timestampMs, kind, summary, hex, rule)
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
            return entry
        }
    }

    fun snapshot(): List<AuditEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) {
        entries.clear()
    }

    fun count(kind: AuditKind): Int = synchronized(lock) { entries.count { it.kind == kind } }

    /** Plain text dump, for sharing a capture or filing a protocol note. */
    fun export(): String = buildString {
        appendLine("# Boom2Local audit log")
        appendLine("# ${entries.size} Eintraege, Obergrenze $capacity")
        for (e in snapshot()) {
            append(e.seq).append('\t').append(e.timestampMs).append('\t').append(e.kind)
            append('\t').append(e.summary)
            e.rule?.let { append("\t[").append(it).append(']') }
            e.hex?.let { append('\t').append(it) }
            appendLine()
        }
    }
}
