package de.privat.boom2local.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import de.privat.boom2local.protocol.AuditKind
import de.privat.boom2local.protocol.AuditLog
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.DecodeResult
import de.privat.boom2local.protocol.DeviceProfile
import de.privat.boom2local.protocol.Frame
import de.privat.boom2local.protocol.FrameSplitter
import de.privat.boom2local.protocol.GattCharacteristic
import de.privat.boom2local.protocol.GattChoice
import de.privat.boom2local.protocol.GattSelection
import de.privat.boom2local.protocol.GattSelector
import de.privat.boom2local.protocol.GattService
import de.privat.boom2local.protocol.Hex
import de.privat.boom2local.protocol.SplitEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * The Bluetooth Low Energy path: the same frames, carried over GATT.
 *
 * It connects straight to a device that is already paired, so it never scans and the app
 * still needs no location permission.
 *
 * Which service carries the protocol is decided by [GattSelector] in the protocol core,
 * where it is unit tested, rather than here. Firmware update services are refused there
 * before anything else is considered.
 */
@SuppressLint("MissingPermission") // The caller checks BLUETOOTH_CONNECT before getting here.
class GattTransport(
    private val context: Context,
    private val profile: DeviceProfile,
    private val audit: AuditLog,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ControlTransport {

    override val name: String get() = "BLE (GATT)"

    private val codec = profile.codec()
    private val splitter = FrameSplitter(codec)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _frames = MutableSharedFlow<Frame>(replay = 0, extraBufferCapacity = 64)
    override val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    /** Inbound chunks, so the splitter is only ever touched by one coroutine. */
    private val inbound = Channel<ByteArray>(Channel.BUFFERED)

    private val writeLock = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var writeWithoutResponse = false

    @Volatile
    private var ready = false

    @Volatile
    private var mtu = DEFAULT_MTU

    /** Everything the last connection attempt found, kept for the diagnostics screen. */
    @Volatile
    var discoveredServices: List<GattService> = emptyList()
        private set

    private var connectSignal: CompletableDeferred<Unit>? = null
    private var discoverSignal: CompletableDeferred<Boolean>? = null
    private var mtuSignal: CompletableDeferred<Unit>? = null
    private var descriptorSignal: CompletableDeferred<Boolean>? = null
    private var writeSignal: CompletableDeferred<Boolean>? = null

    override val isConnected: Boolean get() = ready && gatt != null

    init {
        scope.launch {
            for (chunk in inbound) {
                for (event in splitter.offer(chunk)) handle(event)
            }
        }
    }

    // --- callbacks ---------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                connectSignal?.complete(Unit)
                return
            }
            ready = false
            val message = "GATT-Verbindung beendet (status=$status, Zustand=$newState)"
            audit.add(AuditKind.INFO, message, timestampMs = clock())
            connectSignal?.completeExceptionally(IOException(message))
            discoverSignal?.complete(false)
            descriptorSignal?.complete(false)
            writeSignal?.complete(false)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discoverSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuSignal?.complete(Unit)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            descriptorSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 12 and older deliver notifications here.
        @Deprecated("Superseded by the three argument overload on API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            deliver(characteristic.value ?: ByteArray(0))
        }

        // Android 13 and newer. Deliberately does not call super, which would hand the
        // same bytes to the deprecated overload a second time.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(value)
        }
    }

    private fun deliver(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        audit.add(
            AuditKind.RECEIVED,
            "${bytes.size} Byte gemeldet",
            hex = Hex.encode(bytes),
            timestampMs = clock(),
        )
        inbound.trySend(bytes.copyOf())
    }

    private suspend fun handle(event: SplitEvent) {
        when (event) {
            is SplitEvent.Discarded -> audit.add(
                AuditKind.ERROR,
                "Bytes verworfen (${event.reason})",
                hex = Hex.encode(event.raw),
                timestampMs = clock(),
            )

            is SplitEvent.Complete -> when (val result = codec.decode(event.raw)) {
                is DecodeResult.Ok -> _frames.emit(result.frame)
                is DecodeResult.Err -> audit.add(
                    AuditKind.ERROR,
                    "Frame unlesbar (${result.rule}): ${result.message}",
                    hex = Hex.encode(event.raw),
                    timestampMs = clock(),
                )
            }
        }
    }

    // --- connect -----------------------------------------------------------------------

    override suspend fun connect(device: BluetoothDevice): TransportTarget =
        withContext(Dispatchers.IO) {
            if (!profile.ble.enabled) throw IOException("BLE ist im Profil abgeschaltet")
            close()
            splitter.reset()
            val timeout = profile.transport.connectTimeoutMs.coerceIn(3_000L, 60_000L)

            val connected = CompletableDeferred<Unit>().also { connectSignal = it }
            val discovered = CompletableDeferred<Boolean>().also { discoverSignal = it }

            val opened = try {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                throw IOException("Keine Berechtigung fuer GATT: ${e.message}")
            } ?: throw IOException("connectGatt lieferte keine Verbindung")
            gatt = opened

            if (withTimeoutOrNull(timeout) { connected.await() } == null) {
                close()
                throw IOException(
                    "GATT-Verbindung kam nicht zustande (${timeout} ms). Die Box muss " +
                        "eingeschaltet und in Reichweite sein.",
                )
            }
            audit.add(AuditKind.INFO, "GATT verbunden, suche Dienste", timestampMs = clock())

            if (!opened.discoverServices()) {
                close()
                throw IOException("Dienstsuche konnte nicht gestartet werden")
            }
            if (withTimeoutOrNull(timeout) { discovered.await() } != true) {
                close()
                throw IOException("Dienstsuche fehlgeschlagen")
            }

            val services = opened.services.orEmpty().map { it.toModel() }
            discoveredServices = services
            audit.add(
                AuditKind.INFO,
                "GATT: ${services.size} Dienst(e) gefunden",
                hex = services.joinToString(" ") { it.uuid },
                timestampMs = clock(),
            )

            val selection = GattSelector.select(services, profile.ble)
            for (note in selectionNotes(selection)) {
                audit.add(AuditKind.INFO, "GATT: $note", timestampMs = clock())
            }
            if (selection is GattSelection.None) {
                close()
                throw IOException(
                    "Kein GATT-Dienst taugt als Steuerkanal. " + selection.notes.joinToString(" "),
                )
            }
            val choice = (selection as GattSelection.Ok).choice
            enable(opened, choice, timeout)

            TransportTarget(
                label = choice.serviceUuid,
                detail = "BLE/GATT, ${choice.reason}. Schreibt auf ${choice.writeUuid}, " +
                    "hoert auf ${choice.notifyUuid}, MTU $mtu.",
            )
        }

    private fun selectionNotes(selection: GattSelection): List<String> = when (selection) {
        is GattSelection.Ok -> selection.notes + selection.choice.reason
        is GattSelection.None -> selection.notes
    }

    private suspend fun enable(g: BluetoothGatt, choice: GattChoice, timeout: Long) {
        val service = g.getService(UUID.fromString(choice.serviceUuid))
            ?: throw IOException("Dienst ${choice.serviceUuid} ist verschwunden")
        val write = service.getCharacteristic(UUID.fromString(choice.writeUuid))
            ?: throw IOException("Eigenschaft ${choice.writeUuid} ist verschwunden")
        val notify = service.getCharacteristic(UUID.fromString(choice.notifyUuid))
            ?: throw IOException("Eigenschaft ${choice.notifyUuid} ist verschwunden")

        // A larger MTU means a frame usually fits into a single write.
        val mtuDone = CompletableDeferred<Unit>().also { mtuSignal = it }
        if (g.requestMtu(profile.ble.requestMtu)) {
            withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuDone.await() }
        }

        if (!g.setCharacteristicNotification(notify, true)) {
            throw IOException("Meldungen konnten nicht eingeschaltet werden")
        }
        val cccd = notify.getDescriptor(CCCD)
        if (cccd == null) {
            audit.add(
                AuditKind.ERROR,
                "GATT: ${choice.notifyUuid} hat keinen CCCD-Deskriptor; Meldungen kommen " +
                    "moeglicherweise nicht an.",
                timestampMs = clock(),
            )
        } else {
            val value = if (choice.useIndication) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val signal = CompletableDeferred<Boolean>().also { descriptorSignal = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    g.writeDescriptor(cccd)
                }
            }
            if (!started) {
                throw IOException("CCCD konnte nicht geschrieben werden")
            }
            if (withTimeoutOrNull(timeout) { signal.await() } != true) {
                audit.add(
                    AuditKind.ERROR,
                    "GATT: CCCD-Schreiben nicht bestaetigt; es wird trotzdem weitergemacht.",
                    timestampMs = clock(),
                )
            }
        }

        writeCharacteristic = write
        writeWithoutResponse = choice.writeWithoutResponse
        ready = true
    }

    // --- send --------------------------------------------------------------------------

    override suspend fun send(command: Command, payload: ByteArray) {
        val g = gatt ?: throw IOException("nicht verbunden")
        val characteristic = writeCharacteristic ?: throw IOException("nicht verbunden")
        val frame = codec.encode(command, payload)
        audit.add(
            AuditKind.SENT,
            "$command, ${payload.size} Byte Payload",
            hex = Hex.encode(frame),
            timestampMs = clock(),
        )
        writeLock.withLock {
            // Three bytes of every packet are ATT overhead.
            val chunkSize = (mtu - 3).coerceAtLeast(MIN_CHUNK)
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + chunkSize, frame.size)
                writeChunk(g, characteristic, frame.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    private suspend fun writeChunk(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        val type = if (writeWithoutResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val signal = CompletableDeferred<Boolean>().also { writeSignal = it }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = type
                characteristic.value = chunk
                g.writeCharacteristic(characteristic)
            }
        }
        if (!started) throw IOException("Schreiben konnte nicht gestartet werden")
        if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { signal.await() } == null) {
            throw IOException("Schreiben wurde nicht bestaetigt")
        }
    }

    // --- teardown ----------------------------------------------------------------------

    override fun close() {
        ready = false
        writeCharacteristic = null
        val current = gatt ?: return
        gatt = null
        try {
            current.disconnect()
        } catch (e: Exception) {
            // Already gone.
        }
        try {
            current.close()
        } catch (e: Exception) {
            // Already gone.
        }
    }

    override fun shutdown() {
        close()
        inbound.close()
        scope.cancel()
    }

    private fun BluetoothGattService.toModel(): GattService = GattService(
        uuid = uuid.toString().lowercase(),
        characteristics = characteristics.orEmpty().map { it.toModel() },
    )

    private fun BluetoothGattCharacteristic.toModel(): GattCharacteristic {
        val flags = properties
        return GattCharacteristic(
            uuid = uuid.toString().lowercase(),
            write = flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
            writeNoResponse =
                flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
            notify = flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
            indicate = flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
        )
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val DEFAULT_MTU = 23
        const val MIN_CHUNK = 20
        const val MTU_TIMEOUT_MS = 3_000L
        const val WRITE_TIMEOUT_MS = 5_000L
    }
}
