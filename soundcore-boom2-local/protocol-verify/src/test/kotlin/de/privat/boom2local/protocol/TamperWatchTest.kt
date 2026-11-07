package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TamperWatchTest {

    private fun field(key: String, value: String, label: String = key) =
        DecodedField(key, label, value, raw = "00", offsets = listOf(0), verified = true)

    @Test
    fun `our own change is not reported as foreign`() {
        // The failure that would make the whole feature worthless: crying wolf at the
        // user's own tap. Everything else here is secondary to this.
        val origin = TamperWatch.classify(
            field = field("bassUp", "an"),
            previous = "aus",
            lastOwnChangeAtMs = 1_000,
            nowMs = 1_200,
        )
        assertEquals(ChangeOrigin.Ours, origin)
    }

    @Test
    fun `a change long after our own is foreign again`() {
        val origin = TamperWatch.classify(
            field = field("bassUp", "an"),
            previous = "aus",
            lastOwnChangeAtMs = 1_000,
            nowMs = 1_000 + TamperWatch.OWN_WINDOW_MS + 1,
        )
        assertTrue(origin is ChangeOrigin.Foreign, origin.toString())
    }

    @Test
    fun `a change with nothing of ours behind it is foreign`() {
        val origin = TamperWatch.classify(
            field = field("bassUp", "an", label = "BassUp"),
            previous = "aus",
            lastOwnChangeAtMs = null,
            nowMs = 5_000,
        )
        val change = (origin as ChangeOrigin.Foreign).change
        assertEquals("bassUp", change.field)
        assertEquals("BassUp", change.label)
        assertEquals("aus", change.from)
        assertEquals("an", change.to)
        assertEquals(5_000, change.atMs)
    }

    @Test
    fun `the same value again is no change at all`() {
        assertEquals(
            ChangeOrigin.Unchanged,
            TamperWatch.classify(field("volume", "31"), "31", null, 1),
        )
    }

    @Test
    fun `the first value seen is a change, because it was not known before`() {
        assertTrue(
            TamperWatch.classify(field("volume", "31"), null, null, 1) is ChangeOrigin.Foreign,
        )
    }

    @Test
    fun `battery is not watched, or it would bury everything else`() {
        assertTrue("batteryLevel" !in TamperWatch.WATCHED)
        assertEquals(
            ChangeOrigin.Unchanged,
            TamperWatch.classify(field("batteryLevel", "1/5"), "2/5", null, 1),
        )
    }

    @Test
    fun `a clock that runs backwards does not count as ours`() {
        // A notification stamped before our own write cannot have been caused by it.
        val origin = TamperWatch.classify(
            field = field("bassUp", "an"),
            previous = "aus",
            lastOwnChangeAtMs = 9_000,
            nowMs = 1_000,
        )
        assertTrue(origin is ChangeOrigin.Foreign, origin.toString())
    }

    @Test
    fun `the record keeps the newest entries`() {
        var log = emptyList<ForeignChange>()
        for (i in 1..105) {
            log = TamperWatch.record(log, ForeignChange("volume", "Lautstaerke", null, "$i", i.toLong()), limit = 100)
        }
        assertEquals(100, log.size)
        assertEquals("105", log.last().to)
        assertEquals("6", log.first().to)
    }

    @Test
    fun `three falling volumes in a row look like the speaker protecting itself`() {
        // The user's report: bass overdrives and the volume drops by itself. If the
        // speaker lowers its own setting it says so, and that is what this finds. A
        // limiter inside the DSP would not appear here at all, which is the point.
        val log = listOf("25", "22", "18").mapIndexed { i, v ->
            ForeignChange("volume", "Lautstaerke", null, v, i.toLong())
        }
        assertTrue(TamperWatch.looksLikeSelfProtection(log))
    }

    @Test
    fun `volume going up and down is a person, not a limiter`() {
        val log = listOf("18", "25", "20").mapIndexed { i, v ->
            ForeignChange("volume", "Lautstaerke", null, v, i.toLong())
        }
        assertTrue(!TamperWatch.looksLikeSelfProtection(log))
    }

    @Test
    fun `two readings are not enough to call it self protection`() {
        val log = listOf("25", "22").mapIndexed { i, v ->
            ForeignChange("volume", "Lautstaerke", null, v, i.toLong())
        }
        assertTrue(!TamperWatch.looksLikeSelfProtection(log))
    }

    @Test
    fun `a foreign change carries the battery level it happened at`() {
        // Without it there is no answering "does it sound the same at 20 percent as at
        // 100": a portable amplifier's peak output falls with its rail voltage, so the
        // limiter engages earlier as the charge drops. The log cannot change that. It
        // can say where the ceiling was.
        val origin = TamperWatch.classify(
            field = field("volume", "22", label = "Lautstaerke"),
            previous = "25",
            lastOwnChangeAtMs = null,
            nowMs = 5_000,
            battery = "1/5 (ca. 20 %)",
        )
        assertEquals("1/5 (ca. 20 %)", (origin as ChangeOrigin.Foreign).change.batteryAtChange)
    }

    @Test
    fun `self protection reports the charge it happened at, or nothing`() {
        val falling = listOf("25" to "2/5", "22" to "2/5", "18" to "1/5")
            .mapIndexed { i, (v, b) ->
                ForeignChange("volume", "Lautstaerke", null, v, i.toLong(), b)
            }
        assertEquals("1/5", TamperWatch.batteryWhileProtecting(falling))
        // And no claim at all when the pattern is not there.
        val steady = listOf("18", "25", "20").mapIndexed { i, v ->
            ForeignChange("volume", "Lautstaerke", null, v, i.toLong(), "3/5")
        }
        assertEquals(null, TamperWatch.batteryWhileProtecting(steady))
    }

    @Test
    fun `unparsable volumes are not guessed at`() {
        val log = listOf("laut", "mittel", "leise").mapIndexed { i, v ->
            ForeignChange("volume", "Lautstaerke", null, v, i.toLong())
        }
        assertTrue(!TamperWatch.looksLikeSelfProtection(log))
    }
}
