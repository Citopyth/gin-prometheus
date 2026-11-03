package de.privat.boom2local.app.bt

import android.bluetooth.BluetoothDevice
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.Frame
import kotlinx.coroutines.flow.SharedFlow

/** What a transport ended up talking to, for display. */
class TransportTarget(val label: String, val detail: String)

/**
 * A way to carry frames to and from the speaker.
 *
 * The framing, the command allowlist and the decoders do not care how bytes reach the
 * device, so the choice of pipe is isolated here. Every published description of the
 * Soundcore protocol concerns classic RFCOMM, but all of them are about headphones and
 * the Space 2; if this speaker turns out to use GATT, only the implementation behind
 * this interface changes.
 */
interface ControlTransport {

    /** Short name for the UI and the audit log. */
    val name: String

    val frames: SharedFlow<Frame>

    val isConnected: Boolean

    suspend fun connect(device: BluetoothDevice): TransportTarget

    suspend fun send(command: Command, payload: ByteArray)

    /** Drops the connection but keeps the object usable for inspection. */
    fun close()

    /** Finished with for good: also ends the coroutine scope behind the read loop. */
    fun shutdown()
}
