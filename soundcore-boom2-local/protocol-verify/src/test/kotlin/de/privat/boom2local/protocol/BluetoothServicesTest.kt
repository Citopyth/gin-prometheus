package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BluetoothServicesTest {

    private val base = ServiceCandidates.BLUETOOTH_BASE_SUFFIX
    private fun standard(short: String) = "0000$short$base"

    private val handsFreeAg = standard("111f")
    private val handsFree = standard("111e")
    private val headset = standard("1108")
    private val a2dpSink = standard("110b")
    private val avrcp = standard("110c")
    private val phonebook = standard("112f")
    private val vendor = "0cf12d31-fac3-4553-bd80-d6832e7b3138"

    @Test
    fun `hands-free audio gateway is named and marked as a microphone channel`() {
        val described = BluetoothServices.describe(handsFreeAg)
        assertEquals("Hands-Free Audio Gateway", described.name)
        assertEquals(ServiceRisk.MICROPHONE, described.risk)
        assertTrue(described.standard)
        assertEquals("111f", described.shortUuid)
        assertTrue(described.meaning.contains("Mikrofon"), described.meaning)
    }

    @Test
    fun `every profile that carries a microphone is marked as such`() {
        for (uuid in listOf(handsFree, handsFreeAg, headset, standard("1112"))) {
            assertEquals(ServiceRisk.MICROPHONE, BluetoothServices.describe(uuid).risk, uuid)
        }
    }

    @Test
    fun `music and remote control are not treated as a concern`() {
        assertEquals(ServiceRisk.MUSIC, BluetoothServices.describe(a2dpSink).risk)
        assertEquals(ServiceRisk.REMOTE, BluetoothServices.describe(avrcp).risk)
        assertEquals(
            emptyList(),
            BluetoothServices.concerning(listOf(a2dpSink, avrcp)),
        )
    }

    @Test
    fun `reaching into the phone is its own category`() {
        assertEquals(ServiceRisk.PERSONAL_DATA, BluetoothServices.describe(phonebook).risk)
    }

    @Test
    fun `a non-standard uuid is reported as the manufacturer's own`() {
        val described = BluetoothServices.describe(vendor)
        assertEquals(ServiceRisk.VENDOR, described.risk)
        assertTrue(!described.standard)
        assertEquals(null, described.shortUuid)
    }

    @Test
    fun `a registered uuid that is not catalogued is called unknown, not guessed at`() {
        val described = BluetoothServices.describe(standard("abcd"))
        assertEquals(ServiceRisk.UNKNOWN, described.risk)
        assertTrue(described.standard)
        assertEquals("abcd", described.shortUuid)
        assertTrue(described.meaning.contains("nicht enthalten"), described.meaning)
    }

    @Test
    fun `concerns come back with the microphone first`() {
        val concerns = BluetoothServices.concerning(
            listOf(a2dpSink, phonebook, standard("abcd"), handsFreeAg, avrcp),
        )
        assertEquals(
            listOf(ServiceRisk.MICROPHONE, ServiceRisk.PERSONAL_DATA, ServiceRisk.UNKNOWN),
            concerns.map { it.risk },
        )
    }

    @Test
    fun `case and whitespace do not change the answer`() {
        assertEquals(
            BluetoothServices.describe(handsFreeAg).name,
            BluetoothServices.describe("  ${handsFreeAg.uppercase()}  ").name,
        )
    }

    @Test
    fun `the firmware service the app blocks is named as such`() {
        val described = BluetoothServices.describe("0000fe59$base")
        assertTrue(described.name.contains("DFU"), described.name)
        assertTrue(described.meaning.contains("gesperrt"), described.meaning)
    }
}
