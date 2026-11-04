import os
os.chdir('/app/android')

# 1. Live TV MainActivity: prefetch-only mode (load bundle, then return to host)
p = 'onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/MainActivity.kt'; s = open(p).read()
old = """        const val EXTRA_FULL_RELOAD = "full_reload\""""
new = """        const val EXTRA_FULL_RELOAD = "full_reload"
        /** Reborn Play: load channels + guide after sign-in, then hand back to the host (no guide). */
        const val EXTRA_PREFETCH_ONLY = "reborn_prefetch_only\""""
assert old in s; s = s.replace(old, new, 1)
old = """        val fullReload = intent.getBooleanExtra(EXTRA_FULL_RELOAD, false)
        // App still in memory (just exited and came back): open the guide straight away.
        val held = BundleHolder.current
        if (held != null && held.channels.isNotEmpty() && !fullReload) {"""
new = """        val fullReload = intent.getBooleanExtra(EXTRA_FULL_RELOAD, false)
        prefetchOnly = intent.getBooleanExtra(EXTRA_PREFETCH_ONLY, false)
        // App still in memory (just exited and came back): open the guide straight away.
        val held = BundleHolder.current
        if (held != null && held.channels.isNotEmpty() && !fullReload) {
            if (prefetchOnly) { finish(); return }"""
assert old in s; s = s.replace(old, new, 1)
# the two places that start EpgActivity after loading
old = """                            startActivity(Intent(this, EpgActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                intent?.getStringExtra("companion_stream_id")?.let {"""
new = """                            if (prefetchOnly) { finish(); return }
                            startActivity(Intent(this, EpgActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                intent?.getStringExtra("companion_stream_id")?.let {"""
assert old in s; s = s.replace(old, new, 1)
old = """    private var loaderJob: Job? = null"""
new = """    private var loaderJob: Job? = null
    private var prefetchOnly = false"""
assert old in s; s = s.replace(old, new, 1)
open(p, 'w').write(s)

# 2. Reborn string overrides for the embedded Live TV module
p = 'onnow-one/app/src/main/res/values/livetv_reskin.xml'; s = open(p).read()
if 'brand_v2' not in s:
    s = s.replace('</resources>', """    <string name="brand_v2">REBORN</string>
    <string name="brand_suffix">PLAY · LIVE TV</string>
</resources>""")
open(p, 'w').write(s)

# 3. Host bridge: prefetch + open
p = 'vesper-tv/app/src/main/java/tv/vesper/app/LiveTvEntry.kt'; s = open(p).read()
old = """    fun onLogin(ctx: Context, username: String, password: String, iptvJson: String?) {"""
new = """    /** Sign-in: load channels + guide now (shows the Live TV loading screen, then returns). */
    fun prefetch(activity: Activity) {
        val i = intent(activity, ACTION_OPEN) ?: return
        i.putExtra("reborn_prefetch_only", true)
        activity.startActivity(i)
    }

    fun onLogin(ctx: Context, username: String, password: String, iptvJson: String?) {"""
assert old in s; s = s.replace(old, new, 1)
open(p, 'w').write(s)

p = 'vesper-tv/app/src/main/java/tv/vesper/app/WebAppInterface.kt'; s = open(p).read()
old = """    // ---- Live TV preview window (Reborn Play web guide) ---------------------------"""
new = """    @JavascriptInterface fun prefetchLiveTv() { activity.runOnUiThread { LiveTvEntry.prefetch(activity) } }

    // ---- Live TV preview window (Reborn Play web guide) ---------------------------"""
assert old in s; s = s.replace(old, new, 1)
open(p, 'w').write(s)
print('ok')
