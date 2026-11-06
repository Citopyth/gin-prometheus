package de.privat.boom2local.app.bt

import android.bluetooth.BluetoothDevice
import de.privat.boom2local.protocol.Command
import de.privat.boom2local.protocol.Frame
import kotlinx.coroutines.flow.SharedFlow

/**
 * What a transport ended up talking to.
 *
 * [serviceUuid] is the one field that is not just for display: the speaker advertises
 * more than one vendor service and only one of them answers, so the app has to be able
 * to record which one did. See [de.privat.boom2local.protocol.ProvenServices].
 */
class TransportTarget(
    val label: String,
    val detail: String,
    val serviceUuid: String? = null,
)

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

    /**
     * Emits once when the link goes away on its own.
     *
     * Without this a dropped connection is invisible until the next write, and writes
     * into a dead socket can still appear to succeed — so the app would happily report
     * sending commands to a speaker that hung up minutes ago.
     */
    val disconnects: SharedFlow<String>

    val isConnected: Boolean

    suspend fun connect(device: BluetoothDevice): TransportTarget

    suspend fun send(command: Command, payload: ByteArray)

    /**
     * Writes bytes that are already framed.
     *
     * Only for probing the framing itself: [send] always encodes with the profile's
     * codec, which is no use when the question is whether that codec is right.
     */
    suspend fun sendFrame(raw: ByteArray)

    /** Drops the connection but keeps the object usable for inspection. */
    fun close()

    /** Finished with for good: also ends the coroutine scope behind the read loop. */
    fun shutdown()
}
