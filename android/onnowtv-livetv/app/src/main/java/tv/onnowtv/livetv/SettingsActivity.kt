package tv.onnowtv.livetv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import tv.onnowtv.livetv.data.Settings
import tv.onnowtv.livetv.ui.ActionSheetDialog

/** TiviMate-style two-pane Settings: sections on the left, rows on the right. */
class SettingsActivity : AppCompatActivity() {

    private class Row(val title: String, val subtitle: String? = null, val value: () -> String? = { null }, val onClick: () -> Unit)
    private class Section(val id: String, val glyph: String, val label: String, val blurb: String, val rows: () -> List<Row>)

    private lateinit var rightTitle: TextView
    private lateinit var rightBlurb: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var sections: List<Section>
    private var currentSection = 0
    private val sectionViews = ArrayList<View>()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Settings.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        sections = buildSections()
        setContentView(buildUi())
        currentSection = intent.getIntExtra(EXTRA_SECTION, 0).coerceIn(0, sections.size - 1)
        showSection(currentSection)
        sectionViews[currentSection].requestFocus()
    }

    override fun onResume() { super.onResume(); if (::rowsBox.isInitialized) showSection(currentSection, keepFocus = true) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Settings.dirty) { Settings.dirty = false; toast("Applying your changes…"); RefreshActions.reopenGuide(this) } else finish()
    }

    // ---- UI ----------------------------------------------------------------
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(BG) }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(30f), dp(18f), dp(24f))
            setBackgroundColor(0xFF0D1426.toInt())
        }
        left.addView(text("LIVE TV", 11f, ACCENT, true).apply { letterSpacing = 0.3f })
        left.addView(text("Settings", 28f, Color.WHITE, true).apply { setPadding(0, dp(2f), 0, dp(22f)) })
        sections.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                isFocusable = true; isClickable = true
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                addView(text(s.glyph, 16f, ACCENT).apply { width = dp(30f) })
                addView(text(s.label, 15f, Color.WHITE, true))
                setOnFocusChangeListener { v, f -> paintSection(v, f, i == currentSection); if (f) showSection(i) }
                setOnClickListener { showSection(i); rowsBox.getChildAt(0)?.requestFocus() }
            }
            paintSection(row, false, false)
            sectionViews.add(row)
            left.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        }
        root.addView(left, LinearLayout.LayoutParams(dp(300f), -1))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(40f), dp(34f), dp(40f), dp(24f)) }
        rightTitle = text("", 24f, Color.WHITE, true)
        rightBlurb = text("", 13f, DIM).apply { setPadding(0, dp(4f), 0, dp(18f)) }
        right.addView(rightTitle); right.addView(rightBlurb)
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(ScrollView(this).apply { isFillViewport = true; addView(rowsBox); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        return root
    }

    private fun paintSection(v: View, focused: Boolean, active: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(when { focused -> ACCENT; active -> 0x22FFFFFF; else -> Color.TRANSPARENT })
        }
        ((v as LinearLayout).getChildAt(1) as TextView).setTextColor(if (focused) 0xFF06080F.toInt() else Color.WHITE)
        (v.getChildAt(0) as TextView).setTextColor(if (focused) 0xFF06080F.toInt() else ACCENT)
    }

    private fun showSection(i: Int, keepFocus: Boolean = false) {
        val prev = currentSection
        currentSection = i
        if (prev != i) { paintSection(sectionViews[prev], sectionViews[prev].hasFocus(), false); paintSection(sectionViews[i], sectionViews[i].hasFocus(), true) }
        val s = sections[i]
        rightTitle.text = s.label; rightBlurb.text = s.blurb
        val focusedIdx = if (keepFocus) rowsBox.indexOfChild(rowsBox.focusedChild) else -1
        rowsBox.removeAllViews()
        s.rows().forEach { r -> rowsBox.addView(rowView(r), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) }) }
        if (focusedIdx >= 0) rowsBox.getChildAt(focusedIdx.coerceAtMost(rowsBox.childCount - 1))?.requestFocus()
    }

    private fun rowView(r: Row): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text(r.title, 16f, Color.WHITE, true)
        texts.addView(title)
        r.subtitle?.let { texts.addView(text(it, 12f, DIM).apply { setPadding(0, dp(3f), 0, 0) }) }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val v = r.value()
        val valueView = text(v ?: "›", if (v == null) 20f else 13f, ACCENT, true).apply {
            if (v != null) {
                setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
                background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0x1F5DC8FF); setStroke(dp(1f), 0x555DC8FF) }
            }
        }
        row.addView(valueView)
        fun paint(f: Boolean) {
            row.background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(if (f) 0x2EFFFFFF else 0x14FFFFFF); if (f) setStroke(dp(2f), ACCENT) }
            row.scaleX = if (f) 1.01f else 1f; row.scaleY = row.scaleX
        }
        paint(false)
        row.setOnFocusChangeListener { _, f -> paint(f) }
        row.setOnClickListener { r.onClick() }
        return row
    }

    private fun refresh() = showSection(currentSection, keepFocus = true)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun pick(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val sheet = ActionSheetDialog(this).title(title)
        for ((key, label) in options) sheet.item(label = label, icon = if (key == current) "✓" else "○") { onPick(key); refresh() }
        sheet.show()
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    // ---- Sections --------------------------------------------------------------
    private fun buildSections(): List<Section> {
        val ctx = this
        val lock = tv.onnowtv.livetv.data.ParentalLock
        return listOf(
            Section("channels", "☰", "Channels & groups", "Order, hide and sort what shows in the guide.") {
                listOf(
                    Row("Reorder groups", "Pick up a group with OK, move it with Up/Down, drop with OK") { ListEditActivity.open(ctx, ListEditActivity.MODE_GROUPS) },
                    Row("Hide groups", "Tick the groups you never want to see", { "${Settings.hiddenGroups(ctx).size} HIDDEN" }) { ListEditActivity.open(ctx, ListEditActivity.MODE_HIDE) },
                    Row("Reorder favourites", "Set the order of your Favourites list") { ListEditActivity.open(ctx, ListEditActivity.MODE_FAVOURITES) },
                    Row("Channel order inside a group", null, { if (Settings.channelSort == "az") "A – Z" else "PLAYLIST" }) {
                        pick("Channel order", listOf("playlist" to "Playlist order (as the provider sends them)", "az" to "A – Z"), Settings.channelSort) { Settings.channelSort = it }
                    },
                    Row("Show channel numbers", null, { onOff(Settings.showNumbers) }) { Settings.showNumbers = !Settings.showNumbers; refresh() },
                    Row("Show channel logos", null, { onOff(Settings.showLogos) }) { Settings.showLogos = !Settings.showLogos; refresh() },
                )
            },
            Section("epg", "⟳", "Playlist & EPG", "Refresh your channels and guide, and how often it updates itself.") {
                listOf(
                    Row("Refresh EPG", "Re-download the programme guide in the background") { RefreshActions.refreshEpg(ctx); toast("Guide refreshing in the background") },
                    Row("Refresh playlist", "Re-scan your channels and groups") { RefreshActions.refreshPlaylist(ctx) },
                    Row("Refresh everything", "Channels and the full guide — takes a minute") { RefreshActions.refreshEverything(ctx) },
                    Row("EPG auto-update", "How often the guide refreshes on its own", { "EVERY ${Settings.epgIntervalHours} H" }) {
                        pick("EPG auto-update", listOf("3" to "Every 3 hours", "6" to "Every 6 hours", "12" to "Every 12 hours", "24" to "Once a day"), Settings.epgIntervalHours.toString()) {
                            Settings.epgIntervalHours = it.toInt(); tv.onnowtv.livetv.data.EpgRefreshWorker.schedulePeriodic(ctx)
                        }
                    },
                    Row("EPG time offset", "Shift guide times if programmes show at the wrong hour", { offsetLabel(Settings.epgOffsetMinutes) }) {
                        pick("EPG time offset", listOf(-120, -60, -30, 0, 30, 60, 120).map { it.toString() to offsetLabel(it) }, Settings.epgOffsetMinutes.toString()) { Settings.epgOffsetMinutes = it.toInt() }
                    },
                )
            },
            Section("player", "▶", "Player", "Playback engine, buffering, audio and subtitles.") {
                listOf(
                    Row("Video engine", "ExoPlayer is fastest; VLC plays unusual streams", { tv.onnowtv.livetv.data.PlayerPrefs.getBackend(ctx).label.uppercase() }) {
                        val b = tv.onnowtv.livetv.data.PlayerPrefs.Backend.values()
                        pick("Video engine", b.map { it.token to it.label }, tv.onnowtv.livetv.data.PlayerPrefs.getBackend(ctx).token) { t -> tv.onnowtv.livetv.data.PlayerPrefs.setBackend(ctx, tv.onnowtv.livetv.data.PlayerPrefs.Backend.fromToken(t)) }
                    },
                    Row("Buffer size", "Larger buffers survive weak Wi-Fi but take longer to start", { Settings.bufferSize.uppercase() }) {
                        pick("Buffer size", listOf("small" to "Small — fastest start", "normal" to "Normal", "large" to "Large — smoothest on weak Wi-Fi"), Settings.bufferSize) { Settings.bufferSize = it }
                    },
                    Row("Decoding", "Try Software if video is green, black or stuttering", { if (Settings.softwareDecoding) "SOFTWARE" else "HARDWARE" }) {
                        pick("Decoding", listOf("hw" to "Hardware (recommended)", "sw" to "Software"), if (Settings.softwareDecoding) "sw" else "hw") { Settings.softwareDecoding = it == "sw" }
                    },
                    Row("Preferred audio language", null, { Settings.audioLanguages.firstOrNull { it.first == Settings.audioLanguage }?.second?.uppercase() }) {
                        pick("Preferred audio language", Settings.audioLanguages, Settings.audioLanguage) { Settings.audioLanguage = it }
                    },
                    Row("Subtitles", "Turn on by default when a channel has them", { onOff(Settings.subtitlesOn) }) { Settings.subtitlesOn = !Settings.subtitlesOn; refresh() },
                    Row("Subtitle language", null, { Settings.audioLanguages.firstOrNull { it.first == Settings.subtitleLanguage }?.second?.uppercase() }) {
                        pick("Subtitle language", Settings.audioLanguages.filter { it.first != "any" }, Settings.subtitleLanguage) { Settings.subtitleLanguage = it }
                    },
                    Row("Default aspect ratio", null, { ASPECTS[Settings.defaultAspect].uppercase() }) {
                        pick("Default aspect ratio", ASPECTS.mapIndexed { i, s -> i.toString() to s }, Settings.defaultAspect.toString()) { Settings.defaultAspect = it.toInt() }
                    },
                )
            },
            Section("look", "◐", "Appearance", "Text size, clock and screen fit.") {
                listOf(
                    Row("Text size", "Applies the next time a screen opens", { Settings.fontScale.uppercase() }) {
                        pick("Text size", listOf("small" to "Small", "normal" to "Normal", "large" to "Large"), Settings.fontScale) { Settings.fontScale = it; recreate() }
                    },
                    Row("Clock format", null, { if (Settings.clock24) "24-HOUR" else "12-HOUR" }) {
                        pick("Clock format", listOf("12" to "12-hour (7:30 pm)", "24" to "24-hour (19:30)"), if (Settings.clock24) "24" else "12") { Settings.clock24 = it == "24" }
                    },
                    Row("Screen fit", "Shrink the picture if your TV cuts off the edges", { "${ScreenFit.get(ctx)}%" }) {
                        pick("Screen fit", ScreenFit.options.map { it.toString() to (if (it == 100) "100% — full screen" else if (it == 95) "95% — most TVs" else "$it%") }, ScreenFit.get(ctx).toString()) { ScreenFit.set(ctx, it.toInt()); ScreenFit.apply(this) }
                    },
                )
            },
            Section("start", "⏻", "Startup", "What happens when Live TV opens.") {
                listOf(
                    Row("Open on last channel", "Go straight to full screen on whatever you watched last", { onOff(Settings.openLastChannel) }) { Settings.openLastChannel = !Settings.openLastChannel; refresh() },
                    Row("Start when the box turns on", "Launch Live TV automatically after boot", { onOff(Settings.autoStartOnBoot) }) { Settings.autoStartOnBoot = !Settings.autoStartOnBoot; refresh() },
                    Row("Default group", "The group selected when the guide opens", { defaultGroupLabel() }) {
                        val cats = listOf("__whatson__" to "What's on live", "__favourites__" to "Favourites", "__all__" to "All channels") +
                            (BundleHolder.current?.categories.orEmpty().filter { !it.name.contains("#####") && !it.name.matches(Regex("^={3,}.*={3,}$")) }.map { it.id to it.name })
                        pick("Default group", cats, Settings.defaultCategoryId) { Settings.defaultCategoryId = it }
                    },
                )
            },
            Section("remote", "⌘", "Remote buttons", "Choose what the buttons on your remote do.") {
                listOf(
                    Row("OK on a channel", null, { if (Settings.okAction == "menu") "CHANNEL MENU" else "PLAY" }) {
                        pick("OK on a channel", listOf("play" to "Play full screen", "menu" to "Open the channel menu"), Settings.okAction) { Settings.okAction = it }
                    },
                    Row("Long-press OK on a channel", null, { if (Settings.longOkAction == "favourite") "ADD TO FAVOURITES" else "CHANNEL MENU" }) {
                        pick("Long-press OK", listOf("menu" to "Open the channel menu", "favourite" to "Add / remove favourite"), Settings.longOkAction) { Settings.longOkAction = it }
                    },
                    Row("Up / Down while watching", null, { if (Settings.playerUpDown == "channels") "CHANNEL UP / DOWN" else "UP = CHANNEL, DOWN = CONTROLS" }) {
                        pick("Up / Down while watching", listOf("mixed" to "Up = previous channel, Down = show controls", "channels" to "Up / Down = channel up / down"), Settings.playerUpDown) { Settings.playerUpDown = it }
                    },
                )
            },
            Section("names", "✂", "Channel names", "Tidy up names like “UK| BBC One HD”.") {
                listOf(
                    Row("Clean up channel names", "Strip the prefixes below from every channel", { onOff(Settings.nameCleanup) }) { Settings.nameCleanup = !Settings.nameCleanup; refresh() },
                    Row("Prefixes to remove", Settings.namePrefixes) {
                        showTextInput("Prefixes to remove", "SEPARATE WITH COMMAS", Settings.namePrefixes) { Settings.namePrefixes = it; refresh() }
                    },
                    Row("Preview", null, { Settings.cleanName(ctx, "UK| BBC One HD").uppercase() }) { toast("“UK| BBC One HD” becomes “${Settings.cleanName(ctx, "UK| BBC One HD")}”") },
                )
            },
            Section("parental", "▣", "Parental control", "Lock groups behind a 4-digit PIN.") {
                listOf(
                    Row(if (lock.hasPin(ctx)) "Change parental PIN" else "Set parental PIN", "4 digits") { promptNewPin { refresh() } },
                    Row("Locked groups", "Lock or unlock groups", { "${lock.lockedIds(ctx).size} LOCKED" }) { ListEditActivity.open(ctx, ListEditActivity.MODE_LOCK) },
                    Row("Ask for PIN to open Settings", null, { onOff(Settings.pinForSettings) }) {
                        if (!lock.hasPin(ctx)) promptNewPin { Settings.pinForSettings = true; refresh() } else { Settings.pinForSettings = !Settings.pinForSettings; refresh() }
                    },
                )
            },
            Section("tools", "⚙", "Tools", "Speed, memory and backups.") {
                listOf(
                    Row("Boost box", "Free up memory so the box runs faster", { "${BoxBoost.memory(ctx).availMb} MB FREE" }) { BoxBoost.show(this, auto = false) },
                    Row("Speed test", "Check your internet for HD and 4K") { SpeedTest.show(this) },
                    Row("Back up now", "Save settings, favourites, collections and group order to your account") {
                        tv.onnowtv.livetv.data.SyncManager.forcePush(ctx) { ok -> runOnUiThread { toast(if (ok) "Backed up" else "Backup failed — check your internet") } }
                    },
                    Row("Restore from backup", "Bring back everything saved from another box") {
                        tv.onnowtv.livetv.data.SyncManager.pullOnce(ctx) { snap ->
                            runOnUiThread {
                                if (snap != null && tv.onnowtv.livetv.data.SyncManager.isRestorableSnapshot(snap)) {
                                    tv.onnowtv.livetv.data.SyncManager.applySnapshot(ctx, snap); toast("Restored — reopening the guide"); RefreshActions.reopenGuide(ctx)
                                } else toast("No backup found for this account")
                            }
                        }
                    },
                    Row("Export settings to a file", "Writes a .json file you can copy off the box") { exportFile() },
                    Row("Import settings from a file", "Reads the newest export on this box") { importFile() },
                )
            },
            Section("about", "ⓘ", "About", "Version and account.") {
                listOf(
                    Row("Version", null, { BuildConfig.VERSION_NAME }) { },
                    Row("Sign out", "Return to the login screen") {
                        tv.onnowtv.livetv.data.AuthStore.signOut(ctx); startActivity(Intent(ctx, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finishAffinity()
                    },
                )
            },
        )
    }

    private fun offsetLabel(m: Int) = when { m == 0 -> "NONE"; m > 0 -> "+${m / 60.0}".removeSuffix(".0") + " H"; else -> "${m / 60.0}".removeSuffix(".0") + " H" }
    private fun defaultGroupLabel(): String {
        val id = Settings.defaultCategoryId
        return when (id) { "__whatson__" -> "WHAT'S ON LIVE"; "__favourites__" -> "FAVOURITES"; "__all__" -> "ALL CHANNELS"
            else -> BundleHolder.current?.categories?.firstOrNull { it.id == id }?.name?.uppercase() ?: "WHAT'S ON LIVE" }
    }

    private fun promptNewPin(onSet: () -> Unit) {
        showTextInput("Set a parental PIN", "4 DIGITS", "", pin = true) { first ->
            if (!first.matches(Regex("\\d{4}"))) { toast("The PIN must be 4 digits"); return@showTextInput }
            showTextInput("Enter the PIN again", "CONFIRM YOUR PIN", "", pin = true) { second ->
                if (second != first) { toast("The PINs didn't match"); return@showTextInput }
                tv.onnowtv.livetv.data.ParentalLock.setPin(this, first); toast("Parental PIN saved"); onSet()
            }
        }
    }

    private fun showTextInput(title: String, subtitle: String, initial: String, pin: Boolean = false, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial); setTextColor(Color.WHITE); setSelection(text.length)
            inputType = if (pin) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT
        }
        val box = FrameLayout(this).apply { setPadding(dp(24f), dp(8f), dp(24f), 0); addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setMessage(subtitle).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }.setNegativeButton("Cancel", null).show()
    }

    private fun backupDir() = java.io.File(getExternalFilesDir(null) ?: filesDir, "backups").apply { mkdirs() }

    private fun exportFile() {
        try {
            val f = java.io.File(backupDir(), "livetv-settings-${System.currentTimeMillis()}.json")
            val o = org.json.JSONObject().put("settings", org.json.JSONObject(Settings.export(this))).put("snapshot", tv.onnowtv.livetv.data.SyncManager.buildSnapshot(this))
            f.writeText(o.toString())
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Exported").setMessage("Saved to:\n${f.absolutePath}\n\nCopy it off the box with a file manager, or use Restore from backup on your other box signed in to the same account.").setPositiveButton("OK", null).show()
        } catch (t: Throwable) { toast("Export failed: ${t.message}") }
    }

    private fun importFile() {
        val f = backupDir().listFiles { x -> x.name.endsWith(".json") }?.maxByOrNull { it.lastModified() }
        if (f == null) { toast("No export found in ${backupDir().absolutePath}"); return }
        try {
            val o = org.json.JSONObject(f.readText())
            Settings.import(this, o.getJSONObject("settings").toString())
            o.optJSONObject("snapshot")?.let { tv.onnowtv.livetv.data.SyncManager.applySnapshot(this, it) }
            toast("Imported ${f.name} — reopening the guide"); RefreshActions.reopenGuide(this)
        } catch (t: Throwable) { toast("Import failed: ${t.message}") }
    }

    companion object {
        const val EXTRA_SECTION = "section"
        val ASPECTS = listOf("Fit", "Zoom", "Fill", "16:9")
        private const val BG = 0xFF0B1020.toInt()
        private const val ACCENT = 0xFF5DC8FF.toInt()
        private const val DIM = 0xFF9DA5B5.toInt()

        fun open(ctx: Context) {
            if (Settings.pinForSettings && tv.onnowtv.livetv.data.ParentalLock.hasPin(ctx) && ctx is EpgActivity && run { Settings.dirty = false; true }) ctx.askPinThen { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }
            else { Settings.dirty = false; ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }
        }
    }
}
