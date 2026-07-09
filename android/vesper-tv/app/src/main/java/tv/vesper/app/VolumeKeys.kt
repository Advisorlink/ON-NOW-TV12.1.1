package tv.vesper.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.widget.Toast

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
 * v2.13.x — Some HDMI boxes (HK1 class) report a FIXED volume: the
 * system slider moves but the DAC ignores it, so audio is either
 * mute or full blast.  Player activities can now pass `softAdjust`
 * — when the device reports fixed volume we scale the PLAYER's own
 * decoded-PCM output instead and show a lightweight "Volume NN%"
 * toast as feedback.
 *
 * Returns true when the event was a volume key (consumed).
 */
private var volumeToast: Toast? = null

fun handleGlobalVolumeKey(
    activity: Activity,
    event: KeyEvent,
    softAdjust: ((raise: Boolean) -> Int)? = null,
): Boolean {
    val raise = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> true
        KeyEvent.KEYCODE_VOLUME_DOWN -> false
        else -> return false
    }
    if (event.action == KeyEvent.ACTION_DOWN) {
        try {
            val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (softAdjust != null && am.isVolumeFixed) {
                val pct = softAdjust(raise)
                try {
                    volumeToast?.cancel()
                    volumeToast = Toast.makeText(activity, "Volume $pct%", Toast.LENGTH_SHORT)
                    volumeToast?.show()
                } catch (_: Throwable) { /* toast is cosmetic */ }
            } else {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
        } catch (_: Throwable) {
            /* AudioManager unavailable — swallow, never crash playback */
        }
    }
    return true
}
