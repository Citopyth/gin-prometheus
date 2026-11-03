package de.privat.boom2local.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GattSelectorTest {

    private val base = GattSelector.BLUETOOTH_BASE_SUFFIX

    private fun standard(short: String) = "0000$short$base"

    private val vendorService = GattService(
        uuid = "0cf12d31-fac3-4553-bd80-d6832e7d3138",
        characteristics = listOf(
            GattCharacteristic("0cf12d32-fac3-4553-bd80-d6832e7d3138", write = true),
            GattCharacteristic("0cf12d33-fac3-4553-bd80-d6832e7d3138", notify = true),
        ),
    )

    private val deviceInfo = GattService(
        uuid = standard("180a"),
        characteristics = listOf(GattCharacteristic(standard("2a29"))),
    )

    private val battery = GattService(
        uuid = standard("180f"),
        characteristics = listOf(GattCharacteristic(standard("2a19"), notify = true)),
    )

    private val dfu = GattService(
        uuid = "0000fe59$base",
        characteristics = listOf(
            GattCharacteristic("8ec90001-f315-4f60-9fb8-838830daea50", write = true, notify = true),
        ),
    )

    @Test
    fun `picks the vendor service over the standard ones`() {
        val result = assertIs<GattSelection.Ok>(
            GattSelector.select(listOf(deviceInfo, battery, vendorService), BleSpec()),
        )
        assertEquals(vendorService.uuid, result.choice.serviceUuid)
        assertEquals("0cf12d32-fac3-4553-bd80-d6832e7d3138", result.choice.writeUuid)
        assertEquals("0cf12d33-fac3-4553-bd80-d6832e7d3138", result.choice.notifyUuid)
        assertTrue(!result.choice.writeWithoutResponse)
        assertTrue(!result.choice.useIndication)
    }

    @Test
    fun `a firmware service is never chosen, even when it fits perfectly`() {
        val result = GattSelector.select(listOf(dfu), BleSpec())
        assertIs<GattSelection.None>(result)
        assertTrue(
            result.notes.any { it.contains("Firmware") },
            result.notes.toString(),
        )
    }

    @Test
    fun `a firmware service does not win against a real control service`() {
        val result = assertIs<GattSelection.Ok>(
            GattSelector.select(listOf(dfu, vendorService), BleSpec()),
        )
        assertEquals(vendorService.uuid, result.choice.serviceUuid)
    }

    @Test
    fun `the blocklist is configurable and still absolute`() {
        val spec = BleSpec(blockedServiceUuids = listOf(vendorService.uuid))
        val result = GattSelector.select(listOf(vendorService), spec)
        assertIs<GattSelection.None>(result)
    }

    /** A service using a 16 bit alias rather than a full custom UUID, but still usable. */
    private val aliasService = GattService(
        uuid = standard("abf0"),
        characteristics = listOf(
            GattCharacteristic(standard("abf1"), write = true),
            GattCharacteristic(standard("abf2"), notify = true),
        ),
    )

    @Test
    fun `an explicitly named service wins over the custom-uuid bonus`() {
        val spec = BleSpec(serviceUuids = listOf(standard("abf0")))
        val result = assertIs<GattSelection.Ok>(
            GattSelector.select(listOf(aliasService, vendorService), spec),
        )
        assertEquals(standard("abf0"), result.choice.serviceUuid)
        assertTrue(result.choice.reason.contains("vorgegeben"), result.choice.reason)
    }

    @Test
    fun `naming a service that cannot send does not force it`() {
        // The battery service notifies but has nothing writable, so preferring it in the
        // profile must not override that: you cannot send a command on it.
        val spec = BleSpec(serviceUuids = listOf(standard("180f")))
        val result = assertIs<GattSelection.Ok>(
            GattSelector.select(listOf(battery, vendorService), spec),
        )
        assertEquals(vendorService.uuid, result.choice.serviceUuid)
    }

    @Test
    fun `the preference order among named services is respected`() {
        val spec = BleSpec(serviceUuids = listOf(vendorService.uuid, standard("abf0")))
        val result = assertIs<GattSelection.Ok>(
            GattSelector.select(listOf(aliasService, vendorService), spec),
        )
        assertEquals(vendorService.uuid, result.choice.serviceUuid)
    }

    @Test
    fun `one characteristic doing both directions is reused rather than split`() {
        val single = GattService(
            uuid = "11112222-3333-4444-5555-666677778888",
            characteristics = listOf(
                GattCharacteristic("aaaa0001-0000-1000-8000-00805f9b34fb", write = true),
                GattCharacteristic(
                    "99990001-3333-4444-5555-666677778888",
                    writeNoResponse = true,
                    notify = true,
                ),
            ),
        )
        val result = assertIs<GattSelection.Ok>(GattSelector.select(listOf(single), BleSpec()))
        assertEquals(result.choice.writeUuid, result.choice.notifyUuid)
        assertTrue(result.choice.writeWithoutResponse)
        assertTrue(result.choice.reason.contains("eine Eigenschaft fuer beides"))
    }

    @Test
    fun `a service that cannot answer is rejected with the reason`() {
        val writeOnly = GattService(
            uuid = "11112222-3333-4444-5555-666677778888",
            characteristics = listOf(GattCharacteristic("aaaa0001-1111-2222-3333-444455556666", write = true)),
        )
        val result = assertIs<GattSelection.None>(GattSelector.select(listOf(writeOnly), BleSpec()))
        assertTrue(result.notes.any { it.contains("meldet") }, result.notes.toString())
    }

    @Test
    fun `indication is used when the characteristic cannot notify`() {
        val service = GattService(
            uuid = "11112222-3333-4444-5555-666677778888",
            characteristics = listOf(
                GattCharacteristic("aaaa0001-1111-2222-3333-444455556666", write = true),
                GattCharacteristic("aaaa0002-1111-2222-3333-444455556666", indicate = true),
            ),
        )
        val result = assertIs<GattSelection.Ok>(GattSelector.select(listOf(service), BleSpec()))
        assertTrue(result.choice.useIndication)
    }

    @Test
    fun `no services at all is reported, not crashed on`() {
        val result = assertIs<GattSelection.None>(GattSelector.select(emptyList(), BleSpec()))
        assertTrue(result.notes.isNotEmpty())
    }

    @Test
    fun `housekeeping services are skipped with a note`() {
        val result = GattSelector.select(listOf(deviceInfo, battery), BleSpec())
        assertIs<GattSelection.None>(result)
        assertTrue(
            result.notes.any { it.contains("Standarddienst") },
            result.notes.toString(),
        )
    }

    @Test
    fun `custom beats standard when both could work`() {
        val customish = GattService(
            uuid = "abcd1234-0000-1000-8000-00805f9b34fb",
            characteristics = listOf(
                GattCharacteristic("aaaa0001-1111-2222-3333-444455556666", write = true, notify = true),
            ),
        )
        val result = assertIs<GattSelection.Ok>(
            GattSelector.select(listOf(customish, vendorService), BleSpec()),
        )
        assertEquals(vendorService.uuid, result.choice.serviceUuid)
    }
}
