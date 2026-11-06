package de.privat.boom2local.app.bt

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * A remote control that works with every Bluetooth speaker, not only this one.
 *
 * There is no universal remote for the *vendor* protocol - lights, equaliser and
 * battery live behind codes each manufacturer invents and nobody publishes. Transport
 * control is the opposite: play, pause, skip and volume are standardised (AVRCP), every
 * Bluetooth audio device implements them, and Android already speaks them.
 *
 * So this sends media keys to whichever app on the phone holds audio focus, and moves
 * the phone's media volume. The speaker follows, because that is what the standard is
 * for. Worth being precise about: nothing here goes to the speaker directly, which is
 * exactly why it works with any of them, and why it keeps working when the vendor
 * channel will not connect.
 *
 * Needs no permission at all: [AudioManager.dispatchMediaKeyEvent] and
 * [AudioManager.adjustStreamVolume] on the music stream are both open to any app.
 */
class MediaRemote(context: Context) {

    private val audio = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    val available: Boolean get() = audio != null

    fun playPause() = press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun play() = press(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause() = press(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun next() = press(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = press(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun stop() = press(KeyEvent.KEYCODE_MEDIA_STOP)

    fun volumeUp() = adjust(AudioManager.ADJUST_RAISE)
    fun volumeDown() = adjust(AudioManager.ADJUST_LOWER)
    fun mute() = adjust(AudioManager.ADJUST_MUTE)
    fun unmute() = adjust(AudioManager.ADJUST_UNMUTE)

    /** Current media volume, or null when the system would not say. */
    fun volume(): Int? = try {
        audio?.getStreamVolume(AudioManager.STREAM_MUSIC)
    } catch (e: Exception) {
        null
    }

    fun volumeMax(): Int? = try {
        audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    } catch (e: Exception) {
        null
    }

    /**
     * Sets the media volume outright.
     *
     * Returns false when the system refused, which it may: on some versions changing
     * the volume is blocked while Do Not Disturb is on unless the app holds policy
     * access, and this app deliberately does not ask for that.
     */
    fun setVolume(value: Int): Boolean {
        val manager = audio ?: return false
        val max = volumeMax() ?: return false
        return try {
            manager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value.coerceIn(0, max),
                0,
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * A key press is a down and an up event.
     *
     * Sending only the down event leaves the receiving media session waiting for the
     * release, and some players then ignore the press entirely.
     */
    private fun press(keyCode: Int): Boolean {
        val manager = audio ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun adjust(direction: Int): Boolean {
        val manager = audio ?: return false
        return try {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
