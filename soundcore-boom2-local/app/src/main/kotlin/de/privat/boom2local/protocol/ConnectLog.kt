package de.privat.boom2local.protocol

/** One failed attempt at one service. */
data class AttemptFailure(val uuid: String, val message: String)

/**
 * Turns a round of identical connection failures into something readable.
 *
 * When the speaker is simply off, every candidate fails the same way. A capture of a
 * real session has 34 of its 337 entries as byte-identical "read failed, socket might
 * closed or timeout" lines - six services times three rounds, twice over - which is the
 * log telling you one thing thirty-four times while the entries that matter scroll out
 * of the buffer behind it.
 *
 * The distinction worth keeping is between *different* failures, which say the services
 * differ, and *repeated* ones, which say the device is gone. So distinct messages are
 * kept in full and repeats are counted.
 */
object ConnectLog {

    /**
     * The lines to write for one round, in order.
     *
     * A failure whose message differs from the one before it is written out with its
     * service; a run of identical ones becomes a single counted line. Nothing is
     * dropped - the count is exact - and the first occurrence always survives, so the
     * actual error text is never lost.
     */
    fun summarise(failures: List<AttemptFailure>): List<String> {
        if (failures.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var index = 0
        while (index < failures.size) {
            val current = failures[index]
            var runEnd = index + 1
            while (runEnd < failures.size && failures[runEnd].message == current.message) {
                runEnd++
            }
            val repeats = runEnd - index - 1
            lines += if (repeats == 0) {
                "${current.uuid}: ${current.message}"
            } else {
                "${current.uuid}: ${current.message} " +
                    "(und $repeats weitere Dienst(e) mit demselben Fehler - " +
                    "die Box ist vermutlich aus oder ausser Reichweite)"
            }
            index = runEnd
        }
        return lines
    }
}
