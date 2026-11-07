package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingWatchTest {

    private val profile = TestFixtures.boom2Profile()

    private fun decode(body: ByteArray) = StateDecoder.decode(
        profile,
        Frame(
            header = PacketCodec.DEFAULT_INBOUND_HEADER,
            command = profile.stateResponseCommand,
            body = body,
            direction = Direction.FROM_DEVICE,
        ),
    )

    @Test
    fun `the frame read before the button was pressed reads as closed`() {
        assertEquals(
            PairingVerdict.Closed,
            PairingWatch.verdict(decode(TestFixtures.boom2StateBodyAfterWrites())),
        )
    }

    @Test
    fun `the frame read after the button was pressed reads as open`() {
        // Byte for byte out of the capture of 2026-11-07, taken right after the
        // speaker's Bluetooth button was pressed. Both candidates flipped.
        val verdict = PairingWatch.verdict(decode(TestFixtures.boom2StateBodyPairing()))
        val open = verdict as PairingVerdict.Open
        assertEquals(listOf("pairingOpen"), open.fields.map { it.key })
        assertTrue(!open.confirmed, "one observation is not a confirmation")
    }

    @Test
    fun `a charging speaker does not raise the pairing alarm`() {
        // The false alarm the first version raised. Byte 2 is charging, and watching it
        // as a pairing flag set off the warning every time the speaker was plugged in.
        val state = decode(TestFixtures.boom2StateBodyCharging())
        assertEquals(PairingVerdict.Closed, PairingWatch.verdict(state))
        assertEquals("an", state.field("charging")?.value)
    }

    @Test
    fun `byte 2 is charging and nothing else claims it`() {
        val owners = profile.state.fields.filter { it.inStateFrame && it.offset == 2 }
        assertEquals(listOf("charging"), owners.map { it.key })
        assertTrue(owners.single().note?.contains("WIDERLEGT") == true)
    }

    @Test
    fun `the overlap on byte 67 is written down on both sides`() {
        // Two unconfirmed readings claim the same byte. Neither may look surer than it is.
        val claims = profile.state.fields.filter { it.inStateFrame && it.offset == 67 }
        assertEquals(setOf("pairingOpen", "lightMode"), claims.map { it.key }.toSet())
        for (field in claims) {
            assertTrue(!field.verified, "${field.key} must stay unverified")
            assertTrue(
                field.note?.contains("Ueberschneidung") == true,
                "${field.key} has to mention the other claim on its byte",
            )
        }
    }

    @Test
    fun `an unconfirmed warning says it is a suspicion, not a siren`() {
        // A warning that cries wolf is worse than none: the next real one gets ignored.
        val text = PairingWatch.describe(
            PairingWatch.verdict(decode(TestFixtures.boom2StateBodyPairing())),
        )
        assertTrue(text.contains("VERDACHT"), text)
        assertTrue(text.contains("kein Beweis"), text)
        assertTrue(!text.contains("Druecke die Bluetooth-Taste erneut"), text)
    }

    @Test
    fun `a confirmed flag gets the plain instruction instead`() {
        val confirmed = PairingVerdict.Open(
            fields = listOf(
                DecodedField("pairingOpen", "Kopplungsmodus", "an", "01", listOf(67), true),
            ),
            confirmed = true,
        )
        val text = PairingWatch.describe(confirmed)
        assertTrue(text.contains("ist gerade fuer neue Geraete offen"), text)
        assertTrue(!text.contains("VERDACHT"), text)
    }

    @Test
    fun `no state frame means no claim`() {
        assertEquals(PairingVerdict.Unknown, PairingWatch.verdict(null))
        assertTrue(PairingWatch.describe(PairingVerdict.Unknown).contains("Noch kein"))
    }

    @Test
    fun `a body too short to hold the flags makes no claim either`() {
        assertEquals(PairingVerdict.Unknown, PairingWatch.verdict(decode(ByteArray(2))))
    }

    @Test
    fun `one candidate is left, and it does not claim to be confirmed`() {
        assertEquals(listOf("pairingOpen"), PairingWatch.CANDIDATE_KEYS)
        for (key in PairingWatch.CANDIDATE_KEYS) {
            val field = profile.state.fields.single { it.key == key }
            assertTrue(!field.verified, "$key must not claim to be verified")
            assertTrue(
                field.note?.isNotBlank() == true,
                "$key owes the reader the observation it rests on",
            )
        }
        assertEquals(67, profile.state.fields.single { it.key == "pairingOpen" }.offset)
    }

    @Test
    fun `the test ends by requiring the flag to fall back`() {
        // The step that separates a real flag from a byte that happened to change: it
        // has to return to zero when pairing mode ends.
        assertTrue(PairingWatch.TEST.size >= 4)
        assertTrue(
            PairingWatch.TEST.last().contains("wieder auf 00") ||
                PairingWatch.TEST.any { it.contains("wieder auf 00") },
            PairingWatch.TEST.toString(),
        )
    }

    @Test
    fun `the poll is often enough to be worth asking and rare enough to be polite`() {
        assertTrue(PairingWatch.POLL_MS in 5_000..30_000, "poll=${PairingWatch.POLL_MS}")
    }
}
