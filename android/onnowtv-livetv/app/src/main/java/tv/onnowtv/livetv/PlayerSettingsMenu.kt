package tv.onnowtv.livetv

import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import tv.onnowtv.livetv.data.PlayerPrefs
import tv.onnowtv.livetv.data.Settings
import tv.onnowtv.livetv.data.VlcPlayerController
import tv.onnowtv.livetv.ui.ActionSheetDialog

/** The in-player settings cog: subtitles, audio, aspect, engine, buffer, decoding, sleep timer. */
class PlayerSettingsMenu(
    private val activity: PlayerActivity,
    private val exo: () -> ExoPlayer?,
    private val vlc: () -> VlcPlayerController?,
    private val backend: () -> PlayerPrefs.Backend,
    private val aspectLabel: () -> String,
    private val onAspect: (Int) -> Unit,
    private val onBackend: (PlayerPrefs.Backend) -> Unit,
    private val onRebuild: () -> Unit,
    private val onSleep: (Int) -> Unit,
    private val sleepMinutes: () -> Int,
) {
    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    fun show() {
        ActionSheetDialog(activity)
            .title("Player settings")
            .item(label = "Subtitles", icon = "▭", trailing = subtitleState()) { showSubtitles() }
            .item(label = "Audio track", icon = "♪", trailing = audioState()) { showAudio() }
            .item(label = "Aspect ratio", icon = "⛶", trailing = aspectLabel().uppercase()) { showAspect() }
            .item(label = "Video engine", icon = "▶", trailing = backend().label.uppercase()) { showEngine() }
            .item(label = "Buffer size", icon = "≋", trailing = Settings.bufferSize.uppercase()) { showBuffer() }
            .item(label = "Decoding", icon = "⚙", trailing = if (Settings.softwareDecoding) "SOFTWARE" else "HARDWARE") { showDecoding() }
            .item(label = "Sleep timer", icon = "☾", trailing = sleepMinutes().let { if (it <= 0) "OFF" else "$it MIN" }) { showSleep() }
            .show()
    }

    // ---- tracks ------------------------------------------------------------------
    private data class Track(val label: String, val select: () -> Unit, val selected: Boolean)

    private fun textTracks(): List<Track> = tracks(C.TRACK_TYPE_TEXT) { it.spuTracks() }
    private fun audioTracks(): List<Track> = tracks(C.TRACK_TYPE_AUDIO) { it.audioTracks() }

    private fun tracks(type: Int, vlcTracks: (VlcPlayerController) -> List<Triple<Int, String, Boolean>>): List<Track> {
        val p = exo()
        if (backend() == PlayerPrefs.Backend.VLC || p == null) {
            val v = vlc() ?: return emptyList()
            return vlcTracks(v).map { (id, name, sel) ->
                Track(name, { if (type == C.TRACK_TYPE_TEXT) v.setSpuTrack(id) else v.setAudioTrack(id) }, sel)
            }
        }
        val out = ArrayList<Track>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.let { java.util.Locale(it).displayLanguage.ifBlank { it } }
                val label = listOfNotNull(f.label, lang, f.sampleMimeType?.substringAfter('/')?.uppercase(), if (f.channelCount > 0) "${f.channelCount}ch" else null)
                    .distinct().joinToString(" · ").ifBlank { "Track ${out.size + 1}" }
                out.add(Track(label, {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }, g.isTrackSelected(i)))
            }
        }
        return out
    }

    private fun subtitleState(): String {
        val t = textTracks()
        return when { t.isEmpty() -> "NONE"; t.any { it.selected } -> "ON"; else -> "OFF" }
    }

    private fun audioState(): String = audioTracks().firstOrNull { it.selected }?.label?.uppercase()?.take(18) ?: "DEFAULT"

    private fun showSubtitles() {
        val t = textTracks()
        val sheet = ActionSheetDialog(activity).title("Subtitles")
        if (t.isEmpty()) sheet.subtitle("THIS CHANNEL HAS NO SUBTITLE TRACKS")
        sheet.item(label = "Off", icon = if (t.none { it.selected }) "✓" else "○") {
            exo()?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
            vlc()?.setSpuTrack(-1)
            toast("Subtitles off")
        }
        t.forEach { tr -> sheet.item(label = tr.label, icon = if (tr.selected) "✓" else "○") { tr.select(); toast("Subtitles: ${tr.label}") } }
        sheet.show()
    }

    private fun showAudio() {
        val t = audioTracks()
        val sheet = ActionSheetDialog(activity).title("Audio track")
        if (t.size <= 1) sheet.subtitle("THIS CHANNEL HAS ONLY ONE AUDIO TRACK")
        t.forEach { tr -> sheet.item(label = tr.label, icon = if (tr.selected) "✓" else "○") { tr.select(); toast("Audio: ${tr.label}") } }
        if (t.isEmpty()) sheet.item(label = "Stream default", icon = "✓") { }
        sheet.show()
    }

    private fun showAspect() {
        val sheet = ActionSheetDialog(activity).title("Aspect ratio")
        SettingsActivity.ASPECTS.forEachIndexed { i, s ->
            sheet.item(label = s, icon = if (aspectLabel() == s) "✓" else "○") { onAspect(i) }
        }
        sheet.show()
    }

    private fun showEngine() {
        val sheet = ActionSheetDialog(activity).title("Video engine")
        PlayerPrefs.Backend.values().forEach { b -> sheet.item(label = b.label, icon = if (b == backend()) "✓" else "○") { onBackend(b) } }
        sheet.show()
    }

    private fun showBuffer() {
        val sheet = ActionSheetDialog(activity).title("Buffer size").subtitle("APPLIES STRAIGHT AWAY")
        listOf("small" to "Small — fastest start", "normal" to "Normal", "large" to "Large — smoothest on weak Wi-Fi").forEach { (k, l) ->
            sheet.item(label = l, icon = if (Settings.bufferSize == k) "✓" else "○") { if (Settings.bufferSize != k) { Settings.bufferSize = k; onRebuild() } }
        }
        sheet.show()
    }

    private fun showDecoding() {
        val sheet = ActionSheetDialog(activity).title("Decoding").subtitle("TRY SOFTWARE IF VIDEO IS GREEN, BLACK OR STUTTERING")
        listOf(false to "Hardware (recommended)", true to "Software").forEach { (sw, l) ->
            sheet.item(label = l, icon = if (Settings.softwareDecoding == sw) "✓" else "○") { if (Settings.softwareDecoding != sw) { Settings.softwareDecoding = sw; onRebuild() } }
        }
        sheet.show()
    }

    private fun showSleep() {
        val sheet = ActionSheetDialog(activity).title("Sleep timer").subtitle("STOPS PLAYBACK AND CLOSES THE PLAYER")
        listOf(0 to "Off", 30 to "30 minutes", 60 to "1 hour", 90 to "1½ hours", 120 to "2 hours").forEach { (m, l) ->
            sheet.item(label = l, icon = if (sleepMinutes() == m) "✓" else "○") { onSleep(m); toast(if (m == 0) "Sleep timer off" else "Sleeping in $l") }
        }
        sheet.show()
    }
}
