package de.privat.boom2local.app

import android.content.Context

/**
 * Remembers which speaker the app is bound to.
 *
 * The only thing this app writes to disk besides an edited profile, and deliberately so:
 * one Bluetooth address, in the app's private storage, removable with one tap.
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

    private companion object {
        const val FILE_NAME = "boom2local"
        const val KEY_ADDRESS = "pinned-address"
    }
}
