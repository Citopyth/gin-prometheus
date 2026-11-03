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
 * Classic Bluetooth: an RFCOMM socket to the speaker.
 *
 * Everything that goes in or out passes through the audit log on its way, in raw hex, so
 * nothing the app does with your speaker is invisible to you.
 */
class RfcommTransport(
    private val profile: DeviceProfile,
    private val audit: AuditLog,
    /** Service UUIDs to try, best first; see DeviceFinder.resolveServiceUuids. */
    private val candidates: List<UuidCandidate>,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ControlTransport {

    /** A service UUID together with why it is being tried. */
    class UuidCandidate(val uuid: UUID, val origin: String)

    override val name: String get() = "RFCOMM (klassisch)"

    private val codec = profile.codec()
    private val splitter = FrameSplitter(codec)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frames = MutableSharedFlow<Frame>(replay = 0, extraBufferCapacity = 64)
    override val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    @Volatile
    private var socket: BluetoothSocket? = null

    private var readJob: Job? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Opens the socket and reports which service answered.
     *
     * Two reasons for the loop shape. The vendor UUID is only known for certain when the
     * speaker advertises it, so there may be several candidates and exactly one of them
     * is right. And Soundcore devices accept one control client at a time, with the first
     * attempt in a process reported to fail routinely. So each round tries every
     * candidate once — the right UUID is found on the first round if the device is
     * ready — and the rounds themselves are the retry.
     */
    override suspend fun connect(device: BluetoothDevice): TransportTarget =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) {
                throw IOException(
                    "Keine Dienst-UUID zum Verbinden. Die Box bietet keinen " +
                        "herstellerspezifischen Dienst an - erst \"SDP neu abfragen\".",
                )
            }
            closeSocket()
            splitter.reset()
            val rounds = profile.transport.connectAttempts.coerceAtLeast(1)
            var lastMessage: String? = null
            for (round in 1..rounds) {
                for (candidate in candidates) {
                    try {
                        val created = if (profile.transport.insecureSocket) {
                            device.createInsecureRfcommSocketToServiceRecord(candidate.uuid)
                        } else {
                            device.createRfcommSocketToServiceRecord(candidate.uuid)
                        }
                        created.connect()
                        socket = created
                        readJob = scope.launch { readLoop(created) }
                        audit.add(
                            AuditKind.INFO,
                            "RFCOMM verbunden mit ${candidate.uuid} (Runde $round von $rounds)",
                            timestampMs = clock(),
                        )
                        return@withContext TransportTarget(
                            label = candidate.uuid.toString(),
                            detail = "RFCOMM, ${candidate.origin}",
                        )
                    } catch (e: Exception) {
                        lastMessage = e.message ?: e.javaClass.simpleName
                        audit.add(
                            AuditKind.ERROR,
                            "Runde $round, ${candidate.uuid}: $lastMessage",
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
    override suspend fun send(command: Command, payload: ByteArray) {
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

    override fun close() {
        readJob?.cancel()
        readJob = null
        closeSocket()
    }

    /** Call when the link is finished with for good; the read loop's scope dies with it. */
    override fun shutdown() {
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
