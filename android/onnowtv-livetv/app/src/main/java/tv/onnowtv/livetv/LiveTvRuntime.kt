package tv.onnowtv.livetv

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Shared start-up for Live TV, whether it runs as its own app or embedded inside ON NOW (one app). */
object LiveTvRuntime {
    @Volatile var embedded = false
    @Volatile private var ready = false

    fun init(app: Application, embedded: Boolean) {
        if (ready) return
        ready = true
        this.embedded = embedded
        LiveTVApp.instance = app
        tv.onnowtv.livetv.data.Settings.init(app)
        BoxBoost.trimOwnCaches = { coil.Coil.imageLoader(app).memoryCache?.clear() }
        BoxBoost.isPlayback = { a -> a is PlayerActivity || a is StatsPlayerActivity || a is GameActivity || a is SportsReplayActivity || a is SportsOfficialReplayActivity }
        BoxBoost.install(app)
        ScreenFit.install(app)
    }
}

/** Declared only in the embedded (library) manifest — boots Live TV inside the host app's process. */
class LiveTvInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let { LiveTvRuntime.init(it, embedded = true) }
        return true
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
