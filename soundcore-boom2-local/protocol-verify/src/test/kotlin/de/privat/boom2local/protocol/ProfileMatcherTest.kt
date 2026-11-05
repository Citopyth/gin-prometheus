package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileMatcherTest {

    private val library = TestFixtures.allProfiles()
    private val boom2Service = "0cf12d31-fac3-4553-bd80-d6832e7b3138"
    private val otherSoundcore = "0cf12d31-fac3-4553-bd80-d6832e7d1402"
    private val a2dp = "0000110b${ServiceCandidates.BLUETOOTH_BASE_SUFFIX}"

    @Test
    fun `the app ships more than one profile`() {
        assertTrue(library.size >= 2, library.map { it.id }.toString())
        assertEquals(
            listOf("soundcore-boom-2", "soundcore-generic"),
            library.map { it.id }.sorted(),
        )
    }

    @Test
    fun `the real Boom 2 is recognised by the service it advertises`() {
        val match = assertNotNull(
            ProfileMatcher.best(library, "soundcore Boom 2", listOf(a2dp, boom2Service)),
        )
        assertEquals("soundcore-boom-2", match.profile.id)
        assertTrue(match.reason.contains(boom2Service), match.reason)
        // Name and exact service both hit, so it beats a prefix-only match outright.
        assertEquals(ProfileMatcher.SERVICE_EXACT + ProfileMatcher.NAME, match.score)
    }

    @Test
    fun `a renamed Boom 2 is still recognised`() {
        // The whole point of matching on the service rather than the name.
        val match = assertNotNull(
            ProfileMatcher.best(library, "Kueche", listOf(boom2Service)),
        )
        assertEquals("soundcore-boom-2", match.profile.id)
        assertEquals(ProfileMatcher.SERVICE_EXACT, match.score)
    }

    @Test
    fun `an unknown Soundcore device falls back to the generic profile`() {
        val match = assertNotNull(
            ProfileMatcher.best(library, "soundcore Motion X600", listOf(otherSoundcore)),
        )
        assertEquals("soundcore-generic", match.profile.id)
        assertTrue(match.reason.contains("Praefix"), match.reason)
    }

    @Test
    fun `the generic profile cannot write anything`() {
        // Its safety property: on a model nobody has mapped, every write is a guess, so
        // the profile lists none and default-deny does the rest.
        val generic = TestFixtures.genericProfile()
        assertTrue(generic.commands.none { it.kind == CommandKind.WRITE })
        val policy = CommandPolicy(generic)
        for (address in listOf(Command(0x02, 0x84), Command(0x0E, 0x08), Command(0x02, 0x88))) {
            val decision = policy.evaluateRaw(address, byteArrayOf(1))
            assertTrue(!decision.isAllowed, "$address must not be sendable")
        }
    }

    @Test
    fun `a device from another manufacturer matches nothing`() {
        assertNull(ProfileMatcher.best(library, "JBL Wave Beam 2", listOf(a2dp)))
        assertEquals(emptyList(), ProfileMatcher.rank(library, "Galaxy Watch4", emptyList()))
    }

    @Test
    fun `the exact model beats the generic profile even when both fit`() {
        val ranked = ProfileMatcher.rank(library, "soundcore Boom 2", listOf(boom2Service))
        assertEquals(2, ranked.size, "both profiles recognise it")
        assertEquals("soundcore-boom-2", ranked[0].profile.id)
        assertEquals("soundcore-generic", ranked[1].profile.id)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `a name alone is a weak match, and says so`() {
        val match = assertNotNull(ProfileMatcher.best(library, "soundcore Boom 2", emptyList()))
        assertEquals(ProfileMatcher.NAME, match.score)
        assertEquals("Name", match.reason)
    }

    @Test
    fun `case and padding in the advertised list do not matter`() {
        val match = assertNotNull(
            ProfileMatcher.best(library, null, listOf("  ${boom2Service.uppercase()}  ")),
        )
        assertEquals("soundcore-boom-2", match.profile.id)
    }

    @Test
    fun `the ranking is stable for equal scores`() {
        val twins = listOf(TestFixtures.genericProfile(), TestFixtures.genericProfile())
        val ranked = ProfileMatcher.rank(twins, "soundcore", emptyList())
        assertEquals(2, ranked.size)
        assertEquals(ranked.map { it.profile.id }, ranked.map { it.profile.id }.sorted())
    }

    @Test
    fun `every shipped profile is internally consistent`() {
        for (profile in library) {
            assertEquals(emptyList(), profile.validate(), "profile '${profile.id}'")
        }
    }
}
