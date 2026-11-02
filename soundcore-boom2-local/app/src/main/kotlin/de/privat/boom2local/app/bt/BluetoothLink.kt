package de.privat.boom2local.app.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import de.privat.boom2local.protocol.AuditKind
import de.privat.boom2local.protocol.AuditLog
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.DecodeResult
import de.privat.boom2local.protocol.DeviceProfile
import de.privat.boom2local.protocol.Frame
import de.privat.boom2local.protocol.FrameSplitter
import de.privat.boom2local.protocol.Hex
import de.privat.boom2local.protocol.SplitEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * The one and only channel this app opens: a classic Bluetooth RFCOMM socket to the
 * speaker.
 *
 * Everything that goes in or out passes through the audit log on its way, in raw hex, so
 * nothing the app does with your speaker is invisible to you.
 */
class BluetoothLink(
    private val profile: DeviceProfile,
    private val audit: AuditLog,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val codec = profile.codec()
    private val splitter = FrameSplitter(codec)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frames = MutableSharedFlow<Frame>(replay = 0, extraBufferCapacity = 64)
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    @Volatile
    private var socket: BluetoothSocket? = null

    private var readJob: Job? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Opens the socket and returns the UUID that worked.
     *
     * Two reasons for the loop shape. The vendor UUID is only known for certain when the
     * speaker advertises it, so the profile may offer several candidates and exactly one
     * of them is right. And Soundcore devices accept one control client at a time, with
     * the first attempt in a process reported to fail routinely. So each round tries
     * every candidate once — the right UUID is found on the first round if the device is
     * ready — and the rounds themselves are the retry.
     */
    suspend fun connect(device: BluetoothDevice, candidates: List<UUID>): UUID =
        withContext(Dispatchers.IO) {
            require(candidates.isNotEmpty()) { "keine Dienst-UUID zum Verbinden" }
            closeSocket()
            splitter.reset()
            val rounds = profile.transport.connectAttempts.coerceAtLeast(1)
            var lastMessage: String? = null
            for (round in 1..rounds) {
                for (uuid in candidates) {
                    try {
                        val created = if (profile.transport.insecureSocket) {
                            device.createInsecureRfcommSocketToServiceRecord(uuid)
                        } else {
                            device.createRfcommSocketToServiceRecord(uuid)
                        }
                        created.connect()
                        socket = created
                        readJob = scope.launch { readLoop(created) }
                        audit.add(
                            AuditKind.INFO,
                            "RFCOMM verbunden mit $uuid (Runde $round von $rounds)",
                            timestampMs = clock(),
                        )
                        return@withContext uuid
                    } catch (e: Exception) {
                        lastMessage = e.message ?: e.javaClass.simpleName
                        audit.add(
                            AuditKind.ERROR,
                            "Runde $round, $uuid: $lastMessage",
                            timestampMs = clock(),
                        )
                    }
                }
                if (round < rounds) delay(500L * round)
            }
            throw IOException(
                "RFCOMM-Verbindung fehlgeschlagen nach $rounds Runden ueber " +
                    "${candidates.size} Dienst-UUID(s). Letzter Fehler: $lastMessage",
            )
        }

    /** Encodes, logs and writes one frame. */
    suspend fun send(command: Command, payload: ByteArray) {
        withContext(Dispatchers.IO) {
            val active = socket ?: throw IOException("nicht verbunden")
            val frame = codec.encode(command, payload)
            audit.add(
                AuditKind.SENT,
                "$command, ${payload.size} Byte Payload",
                hex = Hex.encode(frame),
                timestampMs = clock(),
            )
            val stream = active.outputStream
            stream.write(frame)
            stream.flush()
        }
    }

    private suspend fun readLoop(active: BluetoothSocket) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            val input = active.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                audit.add(
                    AuditKind.RECEIVED,
                    "$read Byte gelesen",
                    hex = Hex.encode(buffer, 0, read),
                    timestampMs = clock(),
                )
                for (event in splitter.offer(buffer, read)) {
                    handle(event)
                }
            }
            audit.add(AuditKind.INFO, "Gegenseite hat den Datenstrom beendet", timestampMs = clock())
        } catch (e: Exception) {
            // Closing the socket from close() lands here too; that is the normal way out.
            audit.add(
                AuditKind.INFO,
                "Leseschleife beendet: ${e.message ?: e.javaClass.simpleName}",
                timestampMs = clock(),
            )
        }
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

    fun close() {
        readJob?.cancel()
        readJob = null
        closeSocket()
    }

    /** Call when the link is finished with for good; the read loop's scope dies with it. */
    fun shutdown() {
        close()
        scope.cancel()
    }

    private fun closeSocket() {
        val current = socket ?: return
        socket = null
        try {
            current.close()
        } catch (e: Exception) {
            audit.add(
                AuditKind.INFO,
                "Socket-Schliessen meldete: ${e.message ?: e.javaClass.simpleName}",
                timestampMs = clock(),
            )
        }
    }

    private companion object {
        const val READ_BUFFER = 1024
    }
}
