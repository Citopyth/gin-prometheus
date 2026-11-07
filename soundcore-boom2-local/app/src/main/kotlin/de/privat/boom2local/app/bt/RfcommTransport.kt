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
import de.privat.boom2local.protocol.SecurityPreference
import de.privat.boom2local.protocol.SocketPlan
import de.privat.boom2local.protocol.SocketSecurity
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
    /**
     * Overrides the profile's `insecureSocket` when set.
     *
     * An insecure socket skips authentication. Some vendor services only answer over an
     * authenticated link, and a speaker that accepts the connection and then stays
     * silent is exactly what that looks like from the outside, so it is worth being able
     * to try the other one without editing the profile.
     */
    private val security: SecurityPreference = SecurityPreference.AUTO,
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

    private val _disconnects = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    override val disconnects: SharedFlow<String> = _disconnects.asSharedFlow()

    /**
     * Set when the read loop ends. `BluetoothSocket.isConnected` keeps reporting true
     * after the remote end hangs up, and a write into that socket can succeed locally,
     * so without this the app reports sending commands into a dead link.
     */
    @Volatile
    private var streamEnded = false

    @Volatile
    private var socket: BluetoothSocket? = null

    private var readJob: Job? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true && !streamEnded

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
            streamEnded = false
            val rounds = profile.transport.connectAttempts.coerceAtLeast(1)
            var lastMessage: String? = null
            // Strongest first. The order is decided in SocketPlan, where it is tested:
            // the speaker takes both and demands neither, so asking for the
            // authenticated link costs nothing, and the fallback is exactly what used
            // to happen immediately.
            val securities = SocketPlan.order(security, profile.transport.insecureSocket)
            for (round in 1..rounds) {
                for (candidate in candidates) {
                    for (level in securities) {
                        try {
                            val created = when (level) {
                                SocketSecurity.OPEN ->
                                    device.createInsecureRfcommSocketToServiceRecord(
                                        candidate.uuid,
                                    )

                                SocketSecurity.AUTHENTICATED ->
                                    device.createRfcommSocketToServiceRecord(candidate.uuid)
                            }
                            created.connect()
                            socket = created
                            readJob = scope.launch { readLoop(created) }
                            val weak = SocketPlan.isWeak(level)
                            audit.add(
                                if (weak) AuditKind.BLOCKED else AuditKind.INFO,
                                "RFCOMM verbunden mit ${candidate.uuid} (Runde $round " +
                                    "von $rounds, ${SocketPlan.describe(level)})",
                                rule = if (weak) "schwache Verbindung" else null,
                                timestampMs = clock(),
                            )
                            return@withContext TransportTarget(
                                label = candidate.uuid.toString(),
                                serviceUuid = candidate.uuid.toString(),
                                detail = "RFCOMM ${SocketPlan.describe(level)}, " +
                                    candidate.origin,
                            )
                        } catch (e: Exception) {
                            lastMessage = e.message ?: e.javaClass.simpleName
                            val how =
                                if (level == SocketSecurity.AUTHENTICATED) "mit" else "ohne"
                            audit.add(
                                AuditKind.ERROR,
                                "Runde $round, ${candidate.uuid}, $how " +
                                    "Authentifizierung: $lastMessage",
                                timestampMs = clock(),
                            )
                        }
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
        val frame = codec.encode(command, payload)
        audit.add(
            AuditKind.SENT,
            "$command, ${payload.size} Byte Payload",
            hex = Hex.encode(frame),
            timestampMs = clock(),
        )
        write(frame)
    }

    override suspend fun sendFrame(raw: ByteArray) {
        audit.add(
            AuditKind.SENT,
            "Roh-Frame, ${raw.size} Byte",
            hex = Hex.encode(raw),
            timestampMs = clock(),
        )
        write(raw)
    }

    private suspend fun write(frame: ByteArray) {
        withContext(Dispatchers.IO) {
            if (streamEnded) throw IOException("Die Box hat die Verbindung bereits beendet")
            val active = socket ?: throw IOException("nicht verbunden")
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
            endStream("Die Box hat den Datenstrom beendet.")
        } catch (e: Exception) {
            // Closing the socket from close() lands here too; that is the normal way out.
            endStream("Leseschleife beendet: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun endStream(reason: String) {
        if (streamEnded) return
        streamEnded = true
        audit.add(AuditKind.INFO, reason, timestampMs = clock())
        _disconnects.tryEmit(reason)
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
