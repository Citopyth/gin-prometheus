package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceCandidatesTest {

    private val base = ServiceCandidates.BLUETOOTH_BASE_SUFFIX

    /** The service the real Boom 2 turned out to advertise. */
    private val boom2 = "0cf12d31-fac3-4553-bd80-d6832e7b3138"
    private val a2dp = "0000110b$base"
    private val avrcp = "0000110c$base"
    private val spp = "00001101$base"

    private val profile = TestFixtures.boom2Profile().transport

    /** The decoy: same vendor prefix, accepts a connection, never answers. */
    private val decoy = "0cf12d31-fac3-4553-bd80-d6832e7c3138"

    /** A third custom service the real speaker turned out to advertise. */
    private val other = "df21fe2c-2515-4fdb-8886-f12c4d67927c"

    private val known = TestFixtures.boom2Profile().match.serviceUuids

    @Test
    fun `the model's own service beats another with the same prefix`() {
        // The bug this test exists for: both services match the vendor prefix, and
        // SDP handed the decoy over first. The app connected to it and went deaf.
        val order = ServiceCandidates.order(
            advertised = listOf(decoy, other, boom2, a2dp),
            transport = profile,
            knownUuids = known,
        )
        assertEquals(boom2, order.first().uuid)
        assertEquals(ServiceCandidates.MODEL_SERVICE, order.first().origin)
        assertTrue(
            order.indexOfFirst { it.uuid == boom2 } < order.indexOfFirst { it.uuid == decoy },
            order.toString(),
        )
    }

    @Test
    fun `without the profile's knowledge the prefix order is all there is`() {
        // Shows that the fix really is the profile's knowledge, not luck: drop it and
        // the decoy comes first again, exactly as it did on the real speaker.
        val order = ServiceCandidates.order(
            advertised = listOf(decoy, boom2),
            transport = profile,
        )
        assertEquals(decoy, order.first().uuid)
    }

    @Test
    fun `a service that already answered beats everything else`() {
        val order = ServiceCandidates.order(
            advertised = listOf(decoy, boom2, other),
            transport = profile,
            knownUuids = known,
            provenUuid = other,
        )
        assertEquals(other, order.first().uuid)
        assertEquals(ServiceCandidates.PROVEN_SERVICE, order.first().origin)
        // And it is still offered only once, further down.
        assertEquals(1, order.count { it.uuid == other })
    }

    @Test
    fun `a proven service is offered even when the device stops advertising it`() {
        val order = ServiceCandidates.order(
            advertised = listOf(a2dp),
            transport = profile,
            provenUuid = boom2,
        )
        assertEquals(boom2, order.first().uuid)
    }

    @Test
    fun `a nonsense proven service cannot poison the order`() {
        val order = ServiceCandidates.order(
            advertised = listOf(boom2),
            transport = profile,
            knownUuids = known,
            provenUuid = "not-a-uuid",
        )
        assertEquals(boom2, order.first().uuid)
        assertTrue(order.none { it.origin == ServiceCandidates.PROVEN_SERVICE })
    }

    @Test
    fun `the model's service is still tried when the device does not advertise it`() {
        val order = ServiceCandidates.order(
            advertised = listOf(a2dp),
            transport = profile.copy(fallbackUuids = emptyList()),
            knownUuids = known,
        )
        assertEquals(boom2, order.single().uuid)
        assertEquals(ServiceCandidates.MODEL_SERVICE_UNADVERTISED, order.single().origin)
    }

    @Test
    fun `an excluded service is dropped no matter how well it ranks`() {
        val order = ServiceCandidates.order(
            advertised = listOf(decoy, boom2),
            transport = profile,
            knownUuids = known,
            provenUuid = boom2,
            excluded = setOf(boom2.uppercase()),
        )
        assertTrue(order.none { it.uuid == boom2 }, order.toString())
        assertEquals(decoy, order.first().uuid)
    }

    @Test
    fun `excluding everything leaves an empty order rather than a wrong one`() {
        val all = ServiceCandidates.order(
            advertised = listOf(decoy, boom2, other),
            transport = profile,
            knownUuids = known,
        )
        val none = ServiceCandidates.order(
            advertised = listOf(decoy, boom2, other),
            transport = profile,
            knownUuids = known,
            excluded = all.map { it.uuid }.toSet(),
        )
        assertEquals(emptyList(), none)
    }

    @Test
    fun `the device's own service beats the profile's guesses`() {
        val order = ServiceCandidates.order(listOf(a2dp, boom2, avrcp), profile)
        assertEquals(boom2, order.first().uuid)
        assertEquals(ServiceCandidates.VENDOR_PREFIX_MATCH, order.first().origin)
        assertTrue(
            order.indexOfFirst { it.origin == ServiceCandidates.PROFILE_GUESS } > 0,
            "guesses must come after what the device advertises",
        )
    }

    @Test
    fun `a vendor service that does not match the prefix is still tried early`() {
        // This is the case that broke before: the prefix was one character too long, so
        // the device's own service was discarded and only guesses were left.
        val unexpected = "11112222-3333-4444-5555-666677778888"
        val order = ServiceCandidates.order(listOf(a2dp, unexpected), profile)
        assertEquals(unexpected, order.first().uuid)
        assertEquals(ServiceCandidates.CUSTOM_SERVICE, order.first().origin)
    }

    @Test
    fun `standard profiles are left out unless asked for`() {
        val advertised = listOf(a2dp, avrcp)
        val without = ServiceCandidates.order(advertised, profile)
        assertTrue(
            without.none { it.uuid == a2dp || it.uuid == avrcp },
            without.toString(),
        )
        val with = ServiceCandidates.order(advertised, profile, includeStandardProfiles = true)
        assertTrue(with.any { it.uuid == a2dp })
        assertEquals(ServiceCandidates.STANDARD_PROFILE, with.last { it.uuid == avrcp }.origin)
    }

    @Test
    fun `serial port comes after the guesses but before the rest`() {
        val order = ServiceCandidates.order(listOf(a2dp, spp), profile)
        val sppIndex = order.indexOfFirst { it.uuid == spp }
        assertTrue(sppIndex >= 0, "SPP is worth trying")
        assertEquals(ServiceCandidates.SERIAL_PORT, order[sppIndex].origin)
        assertTrue(order.none { it.uuid == a2dp }, "A2DP is not a control channel")
    }

    @Test
    fun `nothing is offered twice`() {
        val order = ServiceCandidates.order(
            listOf(boom2, boom2.uppercase(), " $boom2 ", a2dp),
            profile,
        )
        assertEquals(order.map { it.uuid }.distinct().size, order.size)
        assertEquals(1, order.count { it.uuid == boom2 })
    }

    @Test
    fun `the first reason given for a service is the one kept`() {
        // boom2 matches the prefix and is also custom, and it appears among the
        // profile's fallbacks as well. It must be listed once, first, and reported as
        // the prefix match, which is the stronger statement.
        val order = ServiceCandidates.order(listOf(boom2), profile)
        assertEquals(boom2, order.first().uuid)
        assertEquals(ServiceCandidates.VENDOR_PREFIX_MATCH, order.first().origin)
        assertEquals(1, order.count { it.uuid == boom2 })
        // The remaining entries are the other guesses from the profile.
        assertTrue(
            order.drop(1).all { it.origin == ServiceCandidates.PROFILE_GUESS },
            order.toString(),
        )
    }

    @Test
    fun `malformed entries are dropped rather than crashing the connect`() {
        val order = ServiceCandidates.order(
            listOf("", "not-a-uuid", "0000110b", boom2),
            profile,
        )
        assertEquals(boom2, order.first().uuid)
        assertTrue(order.all { it.uuid.length == 36 })
    }

    @Test
    fun `an empty advertisement still offers the profile's guesses`() {
        val order = ServiceCandidates.order(emptyList(), profile)
        assertEquals(profile.fallbackUuids.size, order.size)
        assertTrue(order.all { it.origin == ServiceCandidates.PROFILE_GUESS })
    }

    @Test
    fun `an empty prefix does not match everything`() {
        val order = ServiceCandidates.order(
            listOf(a2dp),
            profile.copy(uuidPrefix = "", fallbackUuids = emptyList()),
        )
        assertEquals(emptyList(), order)
    }

    @Test
    fun `custom and serial port are recognised`() {
        assertTrue(ServiceCandidates.isCustom(boom2))
        assertTrue(!ServiceCandidates.isCustom(a2dp))
        assertTrue(ServiceCandidates.isSerialPort(spp))
        assertTrue(!ServiceCandidates.isSerialPort(a2dp))
    }
}
