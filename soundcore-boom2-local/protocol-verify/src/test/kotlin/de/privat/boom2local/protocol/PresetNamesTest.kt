package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetNamesTest {

    @Test
    fun `an unnamed preset shows its index, not a guess`() {
        // Shipping Signature, Bass Booster and the rest over ten unknown numbers would
        // have the person hear one thing and read another.
        assertEquals("Voreinstellung 3", PresetNames.label(emptyMap(), 3))
        assertEquals("Voreinstellung 0", PresetNames.fallback(0))
    }

    @Test
    fun `a name that was typed is what shows`() {
        val names = PresetNames.rename(emptyMap(), 3, "Kraeftiger Bass")
        assertEquals("Kraeftiger Bass", PresetNames.label(names, 3))
        assertEquals("Voreinstellung 4", PresetNames.label(names, 4))
    }

    @Test
    fun `clearing a name is as easy as making it`() {
        var names = PresetNames.rename(emptyMap(), 2, "Falsch geraten")
        names = PresetNames.rename(names, 2, "   ")
        assertEquals("Voreinstellung 2", PresetNames.label(names, 2))
        assertTrue(names.isEmpty())
    }

    @Test
    fun `names are trimmed and capped rather than refused`() {
        val names = PresetNames.rename(emptyMap(), 1, "  " + "x".repeat(100) + "  ")
        assertEquals(PresetNames.MAX_LENGTH, names.getValue(1).length)
    }

    @Test
    fun `an index outside the offered range changes nothing`() {
        val names = PresetNames.rename(emptyMap(), 99, "nirgendwo")
        assertEquals(emptyMap(), names)
        assertEquals(emptyMap(), PresetNames.rename(emptyMap(), -1, "auch nicht"))
    }

    @Test
    fun `names survive a trip through the preferences file`() {
        var names = PresetNames.rename(emptyMap(), 0, "Signatur?")
        names = PresetNames.rename(names, 7, "Duenn, viel Hoehen")
        assertEquals(names, PresetNames.decode(PresetNames.encode(names)))
    }

    @Test
    fun `a newline in a name cannot split the file`() {
        val names = PresetNames.rename(emptyMap(), 5, "oben\n6=untergeschoben")
        val back = PresetNames.decode(PresetNames.encode(names))
        assertEquals(1, back.size)
        assertEquals("Voreinstellung 6", PresetNames.label(back, 6))
    }

    @Test
    fun `a damaged file costs the names, not a crash`() {
        val back = PresetNames.decode("muell\n=leer\n99=zu hoch\nx=keine Zahl\n4=gut")
        assertEquals(mapOf(4 to "gut"), back)
        assertEquals(emptyMap(), PresetNames.decode(null))
        assertEquals(emptyMap(), PresetNames.decode("  "))
    }

    @Test
    fun `the summary counts honestly`() {
        assertTrue(PresetNames.describe(emptyMap()).contains("Keine"))
        assertTrue(PresetNames.describe(mapOf(1 to "a")).contains("1 von 10"))
        val all = (0 until PresetNames.COUNT).associateWith { "n$it" }
        assertTrue(PresetNames.describe(all).contains("Alle"))
    }

    @Test
    fun `the count matches what the shipped profile offers`() {
        val preset = TestFixtures.boom2Profile()
            .commands.single { it.key == "eqPreset" }
            .arguments.single()
        assertEquals(PresetNames.COUNT, preset.allowedValues().size)
        assertEquals(0, preset.min)
        assertEquals(PresetNames.COUNT - 1, preset.max)
    }
}
