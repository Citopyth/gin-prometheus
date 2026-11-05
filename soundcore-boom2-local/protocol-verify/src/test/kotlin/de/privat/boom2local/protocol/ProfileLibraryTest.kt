package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLibraryTest {

    private val shipped = TestFixtures.profileFile.readText()

    private fun minimal(id: String, name: String = "Test") =
        """{"id":"$id","displayName":"$name"}"""

    private fun source(
        label: String,
        text: String?,
        removable: Boolean = false,
        fileName: String? = null,
    ) = ProfileSource(label, text, removable, fileName)

    @Test
    fun `a shipped profile parses into a usable entry`() {
        val entry = ProfileLibrary.parse(source("mitgeliefert (boom2.json)", shipped))
        assertTrue(entry.isUsable)
        assertEquals("soundcore-boom-2", entry.id)
        assertEquals(emptyList(), entry.problems)
        assertNull(entry.error)
        assertEquals(shipped, entry.text, "the text is kept so it can be copied out")
        assertTrue(!entry.removable)
    }

    @Test
    fun `your own profile wins over a shipped one with the same id`() {
        // The whole point: a correction has to survive an app update.
        val mine = minimal("soundcore-boom-2", "Meine Boom 2")
        val library = ProfileLibrary.assemble(
            listOf(
                source("eigenes Profil (soundcore-boom-2.json)", mine, true, "soundcore-boom-2.json"),
                source("mitgeliefert (boom2.json)", shipped),
            ),
        )
        assertEquals(1, library.size)
        assertEquals("Meine Boom 2", library.single().profile?.displayName)
        assertTrue(library.single().removable)
    }

    @Test
    fun `order decides, so the caller puts its own files first`() {
        val mine = minimal("soundcore-boom-2", "Meine Boom 2")
        val shippedFirst = ProfileLibrary.assemble(
            listOf(
                source("mitgeliefert (boom2.json)", shipped),
                source("eigenes", mine, true, "x.json"),
            ),
        )
        assertEquals("Soundcore Boom 2", shippedFirst.single().profile?.displayName)
    }

    @Test
    fun `different ids live side by side`() {
        val library = ProfileLibrary.assemble(
            listOf(source("a", minimal("eins")), source("b", minimal("zwei"))),
        )
        assertEquals(listOf("eins", "zwei"), library.map { it.id })
    }

    @Test
    fun `an unreadable file becomes a visible entry, not a silence`() {
        // Dropping it would leave someone staring at a profile they edited that is
        // simply not there.
        val entry = ProfileLibrary.parse(source("eigenes Profil (kaputt.json)", null, true, "kaputt.json"))
        assertTrue(!entry.isUsable)
        assertEquals("Datei nicht lesbar", entry.error)
        assertEquals("kaputt.json", entry.fileName)
        assertTrue(entry.removable, "it must still be removable")
    }

    @Test
    fun `broken json is reported with the reason and keeps its text`() {
        val entry = ProfileLibrary.parse(source("eigenes", "{das ist kein json", true, "x.json"))
        assertTrue(!entry.isUsable)
        assertNotNull(entry.error)
        assertTrue(entry.error!!.startsWith("Profil nicht lesbar"), entry.error!!)
        assertEquals("{das ist kein json", entry.text, "so it can be copied out and fixed")
    }

    @Test
    fun `valid json that fails validation lists the problems`() {
        val broken = """{"id":"x","displayName":"X","framing":{"outboundHeader":"08 ee"}}"""
        val entry = ProfileLibrary.parse(source("eigenes", broken, true, "x.json"))
        assertNull(entry.error, "it parsed; it just does not hold together")
        assertTrue(!entry.isUsable)
        assertTrue(entry.problems.isNotEmpty())
        assertTrue(
            entry.problems.any { it.contains("outboundHeader") },
            entry.problems.toString(),
        )
    }

    @Test
    fun `entries that could not be parsed are all kept, having no id to collide on`() {
        val library = ProfileLibrary.assemble(
            listOf(source("a", "kaputt"), source("b", null), source("c", "auch kaputt")),
        )
        assertEquals(3, library.size)
        assertTrue(library.all { !it.isUsable })
    }

    @Test
    fun `an imported profile is checked before anything is written`() {
        assertTrue(ProfileLibrary.validateForImport(shipped).isUsable)
        assertTrue(!ProfileLibrary.validateForImport("{kaputt").isUsable)
        assertTrue(!ProfileLibrary.validateForImport("").isUsable)
        assertTrue(ProfileLibrary.validateForImport(shipped).removable)
    }

    @Test
    fun `a hand-edited id cannot turn into a path`() {
        assertEquals("boom2.json", ProfileLibrary.fileNameFor("boom2"))
        assertEquals("etcpasswd.json", ProfileLibrary.fileNameFor("../../etc/passwd"))
        assertEquals("ab.json", ProfileLibrary.fileNameFor("a/b"))
        assertEquals("profil.json", ProfileLibrary.fileNameFor(""))
        assertEquals("profil.json", ProfileLibrary.fileNameFor("///"))
        assertEquals("my-profile_2.json", ProfileLibrary.fileNameFor("my-profile_2"))
    }

    @Test
    fun `a very long id is cut to something a filesystem accepts`() {
        val name = ProfileLibrary.fileNameFor("x".repeat(500))
        assertTrue(name.length <= 69, name.length.toString())
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun `an empty library is empty, not a crash`() {
        assertEquals(emptyList(), ProfileLibrary.assemble(emptyList()))
    }

    @Test
    fun `the two shipped profiles assemble into a library of two`() {
        val library = ProfileLibrary.assemble(
            TestFixtures.profileDirectory.listFiles { f -> f.extension == "json" }!!
                .sortedBy { it.name }
                .map { source("mitgeliefert (${it.name})", it.readText()) },
        )
        assertEquals(2, library.size)
        assertTrue(library.all { it.isUsable })
        assertEquals(
            listOf("soundcore-boom-2", "soundcore-generic"),
            library.mapNotNull { it.id }.sorted(),
        )
    }
}
