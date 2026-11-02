package tv.onnowtv.livetv

import android.app.Activity
import android.content.Context
import android.content.Intent
import tv.onnowtv.livetv.data.BundleCache
import tv.onnowtv.livetv.data.EpgRefreshWorker
import tv.onnowtv.livetv.ui.ActionSheetDialog

/** The three refresh flavours, shared by the rail button and Settings. */
object RefreshActions {
    fun refreshEpg(ctx: Context) = EpgRefreshWorker.refreshNow(ctx)

    fun refreshPlaylist(ctx: Context) {
        BundleCache.delete(ctx)
        reopenGuide(ctx)
    }

    fun refreshEverything(ctx: Context) = restart(ctx, full = true)

    fun reopenGuide(ctx: Context) = restart(ctx, full = false)

    private fun restart(ctx: Context, full: Boolean) {
        LivePreviewSession.release()
        BundleHolder.current = null
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_FULL_RELOAD, full)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        if (ctx is Activity) { @Suppress("DEPRECATION") ctx.overridePendingTransition(0, 0); ctx.finishAffinity() }
    }

    fun showMenu(activity: Activity) {
        ActionSheetDialog(activity)
            .title("Refresh")
            .subtitle("WHAT DO YOU WANT TO UPDATE?")
            .item(label = "Refresh EPG — guide only, in the background", icon = "▦") {
                refreshEpg(activity); android.widget.Toast.makeText(activity, "Guide refreshing in the background", android.widget.Toast.LENGTH_SHORT).show()
            }
            .item(label = "Refresh playlist — re-scan channels and groups", icon = "☰") { refreshPlaylist(activity) }
            .item(label = "Refresh everything — channels and full guide", icon = "⟳") { refreshEverything(activity) }
            .show()
    }
}
