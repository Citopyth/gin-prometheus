package de.privat.boom2local.app

import android.content.Context
import de.privat.boom2local.protocol.PresetNames
import de.privat.boom2local.protocol.ProvenServices

/**
 * Remembers which speaker the app is bound to, and which service answered.
 *
 * Almost the only thing this app writes to disk besides an edited profile, and
 * deliberately so: one Bluetooth address and a short list of address-to-service pairs,
 * in the app's private storage, removable with one tap. No history, no timestamps,
 * nothing about what was sent.
 */
class PinStore(context: Context) {

    private val preferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): String? = preferences.getString(KEY_ADDRESS, null)

    fun write(address: String?) {
        val editor = preferences.edit()
        if (address.isNullOrBlank()) {
            editor.remove(KEY_ADDRESS)
        } else {
            editor.putString(KEY_ADDRESS, address.trim().uppercase())
        }
        editor.apply()
    }

    /**
     * The service each speaker has actually answered over.
     *
     * Worth a few bytes on disk: without it every connect is a coin toss between the
     * speaker's two vendor services, one of which accepts the socket and then says
     * nothing. The rules live in [ProvenServices], which is tested.
     */
    fun readProvenServices(): Map<String, String> =
        ProvenServices.decode(preferences.getString(KEY_PROVEN, null))

    fun writeProvenServices(known: Map<String, String>) {
        val editor = preferences.edit()
        if (known.isEmpty()) {
            editor.remove(KEY_PROVEN)
        } else {
            editor.putString(KEY_PROVEN, ProvenServices.encode(known))
        }
        editor.apply()
    }

    /**
     * What the person named each equaliser preset after listening to it.
     *
     * Theirs, not the manufacturer's: nothing says which index means what on this
     * model, so the names come from the ear that heard them. See [PresetNames].
     */
    fun readPresetNames(): Map<Int, String> =
        PresetNames.decode(preferences.getString(KEY_PRESET_NAMES, null))

    fun writePresetNames(names: Map<Int, String>) {
        val editor = preferences.edit()
        if (names.isEmpty()) {
            editor.remove(KEY_PRESET_NAMES)
        } else {
            editor.putString(KEY_PRESET_NAMES, PresetNames.encode(names))
        }
        editor.apply()
    }

    private companion object {
        const val FILE_NAME = "boom2local"
        const val KEY_ADDRESS = "pinned-address"
        const val KEY_PROVEN = "proven-services"
        const val KEY_PRESET_NAMES = "preset-names"
    }
}
