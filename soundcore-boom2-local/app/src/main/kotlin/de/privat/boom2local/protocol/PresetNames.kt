package de.privat.boom2local.protocol

/**
 * The names of the speaker's equaliser presets, as the person using it discovers them.
 *
 * The obvious thing would be to ship a list - Signature, Bass Booster, Treble Booster
 * and so on - because every Soundcore app has one. That list is for *other* models.
 * Nothing in any source says which index means what on a Boom 2, and ten confident
 * labels over ten unknown numbers would be the same inherited claim that put nine
 * equaliser bands at offsets that turned out to be wrong, except this time the person
 * would hear one thing and read another.
 *
 * So the indices ship as indices, and the names come from the ear that heard them. One
 * tap plays a preset; one line of typing says what it was. That is slower than guessing
 * and it is the only version that ends up true.
 */
object PresetNames {

    /** The speaker accepts more, but these are the ones the profile offers. */
    const val COUNT = 10

    const val MAX_LENGTH = 24

    /** What an unnamed preset is called. */
    fun fallback(index: Int): String = "Voreinstellung $index"

    /** The name to show for [index]: what was typed, or the placeholder. */
    fun label(names: Map<Int, String>, index: Int): String =
        names[index]?.takeIf { it.isNotBlank() } ?: fallback(index)

    /**
     * Records a name.
     *
     * Blank clears it back to the placeholder, because the way to undo a wrong guess
     * has to be as easy as making it. Anything outside the offered range is refused
     * rather than stored under an index nothing will ever show.
     */
    fun rename(names: Map<Int, String>, index: Int, name: String): Map<Int, String> {
        if (index !in 0 until COUNT) return names
        val trimmed = name.trim().take(MAX_LENGTH)
        val next = LinkedHashMap(names)
        if (trimmed.isEmpty()) next.remove(index) else next[index] = trimmed
        return next
    }

    /** One `index=name` per line, for a preferences file. Newlines cannot survive. */
    fun encode(names: Map<Int, String>): String = names.entries
        .sortedBy { it.key }
        .joinToString("\n") { "${it.key}=${it.value.replace('\n', ' ')}" }

    /** Reads back what [encode] wrote, skipping anything damaged. */
    fun decode(stored: String?): Map<Int, String> {
        if (stored.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        for (line in stored.lineSequence()) {
            val cut = line.indexOf('=')
            if (cut <= 0) continue
            val index = line.substring(0, cut).trim().toIntOrNull() ?: continue
            if (index !in 0 until COUNT) continue
            val name = line.substring(cut + 1).trim().take(MAX_LENGTH)
            if (name.isNotEmpty()) out[index] = name
        }
        return out
    }

    /** What the card says about how far the naming has got. */
    fun describe(names: Map<Int, String>): String {
        val known = (0 until COUNT).count { names[it]?.isNotBlank() == true }
        return when (known) {
            0 -> "Keine der $COUNT Voreinstellungen hat bisher einen Namen. Eine " +
                "antippen, hinhoeren, hier eintragen - dann steht sie kuenftig im Menue."
            COUNT -> "Alle $COUNT Voreinstellungen sind benannt."
            else -> "$known von $COUNT Voreinstellungen benannt."
        }
    }
}
