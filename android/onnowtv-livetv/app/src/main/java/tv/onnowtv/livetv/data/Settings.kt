package tv.onnowtv.livetv.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

/** One store for every user-facing preference in Live TV Settings. */
object Settings {
    private const val FILE = "livetv_settings"
    fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Channels & groups ------------------------------------------
    fun groupOrder(ctx: Context): List<String> = list(ctx, "group_order")
    fun setGroupOrder(ctx: Context, ids: List<String>) { putList(ctx, "group_order", ids); dirty = true }
    fun hiddenGroups(ctx: Context): Set<String> = prefs(ctx).getStringSet("hidden_groups", emptySet()) ?: emptySet()
    fun setHiddenGroups(ctx: Context, ids: Set<String>) { prefs(ctx).edit().putStringSet("hidden_groups", ids).apply(); dirty = true }
    fun favouritesOrder(ctx: Context): List<String> = list(ctx, "fav_order")
    fun setFavouritesOrder(ctx: Context, ids: List<String>) { putList(ctx, "fav_order", ids); dirty = true }
    var channelSort by str("channel_sort", "playlist")        // playlist | az
    var showNumbers by bool("show_numbers", true)
    var showLogos by bool("show_logos", true)

    // ---- Playlist & EPG -----------------------------------------------
    var epgIntervalHours by int("epg_interval_h", 6)            // 3 | 6 | 12 | 24
    var epgOffsetMinutes by int("epg_offset_min", 0)            // -120 .. 120

    // ---- Player ---------------------------------------------------------
    var audioLanguage by str("audio_lang", "eng")               // eng | spa | fra | deu | ita | por | ara | hin | off(=any)
    var subtitlesOn by bool("subs_on", false)
    var subtitleLanguage by str("subs_lang", "eng")
    var defaultAspect by int("aspect_default", 0)               // index into PlayerActivity.aspectModes
    var bufferSize by str("buffer", "normal")                   // small | normal | large
    var softwareDecoding by bool("sw_decode", false)

    // ---- Appearance ----------------------------------------------------
    var fontScale by str("font_scale", "normal")                // small | normal | large
    var panelTransparency by int("panel_alpha", 0)              // 0 | 20 | 40  (% see-through)
    var clock24 by bool("clock_24", false)

    // ---- Startup ---------------------------------------------------------
    var openLastChannel by bool("start_last_channel", false)
    var autoStartOnBoot by bool("start_on_boot", false)
    var defaultCategoryId by str("default_category", "__whatson__")
    var lastChannelId: String
        get() = p().getString("last_channel_id", "") ?: ""
        set(v) { p().edit().putString("last_channel_id", v).apply() }

    // ---- Remote buttons -------------------------------------------------
    var okAction by str("ok_action", "play")                    // play | menu
    var longOkAction by str("long_ok_action", "menu")           // menu | favourite
    var playerUpDown by str("player_updown", "mixed")           // mixed | channels

    // ---- Channel name clean-up ---------------------------------------
    var nameCleanup by bool("name_cleanup", false)
    var namePrefixes by str("name_prefixes", "UK|, UK:, UK -, US|, US:, US -, HD:, FHD:, 4K:, VIP|, VIP:")

    // ---- Parental ---------------------------------------------------------
    var pinForSettings by bool("pin_for_settings", false)

    // ---- helpers -----------------------------------------------------------
    fun fontScaleValue(ctx: Context): Float = when (prefs(ctx).getString("font_scale", "normal")) {
        "small" -> 0.88f; "large" -> 1.15f; else -> 1f
    }

    /** Wrap an Activity base context so every sp/dp text size follows the chosen font scale. */
    fun wrap(base: Context): Context {
        val scale = fontScaleValue(base)
        if (scale == 1f) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.fontScale = scale
        return base.createConfigurationContext(cfg)
    }

    fun timeFmt(ctx: Context, withDay: Boolean = false): SimpleDateFormat {
        val t = if (prefs(ctx).getBoolean("clock_24", false)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(if (withDay) "EEE $t" else t, Locale.UK)
    }

    fun hourFmt(ctx: Context): SimpleDateFormat =
        SimpleDateFormat(if (prefs(ctx).getBoolean("clock_24", false)) "HH:mm" else "h:mm", Locale.UK)

    fun cleanName(ctx: Context, name: String): String {
        if (!prefs(ctx).getBoolean("name_cleanup", false)) return name
        var n = name.trim()
        val prefixes = (prefs(ctx).getString("name_prefixes", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (n.length > p.length && n.startsWith(p, ignoreCase = true)) { n = n.substring(p.length).trim(); changed = true }
            }
        }
        return n.ifEmpty { name }
    }

    fun bufferMs(ctx: Context): IntArray = when (prefs(ctx).getString("buffer", "normal")) {
        "small" -> intArrayOf(10_000, 30_000, 1_000, 2_500)
        "large" -> intArrayOf(60_000, 120_000, 4_000, 8_000)
        else -> intArrayOf(30_000, 60_000, 2_000, 5_000)
    }

    val audioLanguages = listOf("eng" to "English", "spa" to "Spanish", "fra" to "French", "deu" to "German",
        "ita" to "Italian", "por" to "Portuguese", "ara" to "Arabic", "hin" to "Hindi", "any" to "Stream default")

    /** Everything above as one JSON object — used by Backup & restore. */
    fun export(ctx: Context): String {
        val o = org.json.JSONObject()
        for ((k, v) in prefs(ctx).all) {
            when (v) { is Set<*> -> o.put(k, JSONArray(v.toList())); else -> o.put(k, v) }
        }
        return o.toString()
    }

    fun import(ctx: Context, json: String) {
        val o = org.json.JSONObject(json)
        val e = prefs(ctx).edit()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is JSONArray -> if (k == "hidden_groups") e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet()) else e.putString(k, v.toString())
                else -> e.putString(k, v.toString())
            }
        }
        e.apply()
    }

    /** Set whenever something that shapes the guide changed; SettingsActivity reloads the guide on exit. */
    @JvmStatic @Volatile var dirty = false

    // ---- delegates -------------------------------------------------------------
    private var appCtx: Context? = null
    fun init(ctx: Context) { appCtx = ctx.applicationContext }
    private fun p(): SharedPreferences = appCtx?.let { prefs(it) } ?: NoPrefs

    /** Used before Application.onCreate (unit tests): every read returns its default, writes are dropped. */
    private object NoPrefs : SharedPreferences {
        override fun getAll() = emptyMap<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = NoEditor
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
    private object NoEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = true
        override fun apply() {}
    }

    fun timeFmtApp(): SimpleDateFormat = SimpleDateFormat(if (p().getBoolean("clock_24", false)) "HH:mm" else "h:mm a", Locale.UK)

    private fun list(ctx: Context, key: String): List<String> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return try { val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) } } catch (_: Throwable) { emptyList() }
    }
    private fun putList(ctx: Context, key: String, ids: List<String>) { prefs(ctx).edit().putString(key, JSONArray(ids).toString()).apply() }

    private fun str(key: String, def: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = p().getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) { p().edit().putString(key, value).apply(); dirty = true }
    }
    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = p().getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) { p().edit().putBoolean(key, value).apply(); dirty = true }
    }
    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = p().getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) { p().edit().putInt(key, value).apply(); dirty = true }
    }
}
