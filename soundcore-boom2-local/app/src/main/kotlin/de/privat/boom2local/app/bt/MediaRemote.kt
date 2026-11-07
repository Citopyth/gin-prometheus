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
     * Sets the media volume outright, and falls back to stepping if it has to.
     *
     * A capture of real use showed why this exists: seventeen taps on "louder" inside
     * two seconds, then sixteen on "quieter", then fifteen, then seventeen again -
     * someone running the scale end to end one step at a time, because one tap is one
     * step and Android has fifteen to seventeen of them.
     *
     * The direct route can fail. Some devices implement a fixed volume policy, and
     * changing the volume while Do Not Disturb is on needs notification-policy access,
     * which this app deliberately does not ask for. So if the direct call throws, this
     * walks there with the same [adjustStreamVolume] the plus and minus buttons use -
     * which the capture proves does work - and gives up rather than spin if the volume
     * stops moving.
     */
    fun setVolume(value: Int): Boolean {
        val manager = audio ?: return false
        val max = volumeMax() ?: return false
        val target = value.coerceIn(0, max)
        try {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            if (volume() == target) return true
        } catch (e: Exception) {
            // Fall through to stepping.
        }
        // At most one step per point on the scale, plus a little slack, so a volume
        // that refuses to move ends the loop instead of hanging the tap.
        var guard = max + 2
        var current = volume() ?: return false
        while (current != target && guard-- > 0) {
            val direction =
                if (current < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            if (!adjust(direction)) return false
            val next = volume() ?: return false
            if (next == current) return false
            current = next
        }
        return current == target
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
