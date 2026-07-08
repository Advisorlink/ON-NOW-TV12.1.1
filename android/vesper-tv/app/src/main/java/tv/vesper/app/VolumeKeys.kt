package tv.vesper.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * USER SPEC — volume up/down must behave like a real TV remote on
 * EVERY screen and EVERY stream.  Some streams (bitstream/passthrough
 * audio, or when a Compose overlay / WebView held key focus) ended up
 * swallowing the volume keys, so presses only worked "sometimes".
 * Handling the keys explicitly at the top of dispatchKeyEvent —
 * before ANY other consumer — guarantees each press (and each
 * auto-repeat while held) steps STREAM_MUSIC with the system volume
 * UI, everywhere.
 *
 * Returns true when the event was a volume key (consumed).
 */
fun handleGlobalVolumeKey(activity: Activity, event: KeyEvent): Boolean {
    val dir = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
        KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
        else -> return false
    }
    if (event.action == KeyEvent.ACTION_DOWN) {
        try {
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        } catch (_: Throwable) {
            /* AudioManager unavailable — swallow, never crash playback */
        }
    }
    return true
}
