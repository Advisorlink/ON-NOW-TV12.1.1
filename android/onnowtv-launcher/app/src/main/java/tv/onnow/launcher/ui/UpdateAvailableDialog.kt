package tv.onnow.launcher.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnow.launcher.DockItem
import tv.onnow.launcher.R
import tv.onnow.launcher.install.ApkInstaller

/**
 * v2.10.56 — "Update available" dialog.
 *
 * Shown by [MainActivity.onTileSelected] when the user taps a tile
 * whose installed `targetPackage` reports a `versionCode` lower
 * than the admin-uploaded `apk_version_code`.  Three actions:
 *
 *   • Update now              → download + install the new APK,
 *                               then auto-launch the package.
 *   • Backup my profiles first → opens Vesper deep-linked to
 *                                Settings → Backup section so the
 *                                user can save a backup code before
 *                                the install runs.
 *   • Skip for now            → launches the currently-installed
 *                               older version (original behaviour).
 *
 * Designed to feel like the Vesper popups — large branded card,
 * cyan accent ribbon, three large buttons.  No system AlertDialog
 * (those look "cheap on a TV" — direct user feedback).
 */
object UpdateAvailableDialog {

    fun show(
        activity: AppCompatActivity,
        item: DockItem,
        installedVersionCode: Long,
        onSkip: () -> Unit,
    ): Dialog {
        val dialog = Dialog(activity, R.style.Theme_AppCompat_Dialog_Alert).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_update_available)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val title = dialog.findViewById<TextView>(R.id.update_dialog_title)
        val versions = dialog.findViewById<TextView>(R.id.update_dialog_versions)
        val install = dialog.findViewById<Button>(R.id.update_dialog_install)
        val backup = dialog.findViewById<Button>(R.id.update_dialog_backup)
        val skip = dialog.findViewById<Button>(R.id.update_dialog_skip)

        title.text = item.label.ifBlank { "Update available" }
        val installedLabel = if (installedVersionCode > 0) "v$installedVersionCode" else "Unknown"
        val remoteLabel = item.apkVersion?.takeIf { it.isNotBlank() }
            ?.let { "v$it" }
            ?: item.apkVersionCode?.let { "v$it" }
            ?: "newer build"
        versions.text = "Installed: $installedLabel  →  New: $remoteLabel"

        install.setOnClickListener {
            triggerInstall(activity, item)
            dialog.dismiss()
        }

        backup.setOnClickListener {
            openVesperBackup(activity)
            // Don't dismiss — the user may come back via BACK; show
            // the dialog still anchored.  But Android will pause the
            // dialog when Vesper takes over the foreground.  We
            // dismiss so they can re-tap the tile when they're done.
            dialog.dismiss()
        }

        skip.setOnClickListener {
            dialog.dismiss()
            onSkip()
        }

        dialog.setOnShowListener { install.requestFocus() }
        dialog.show()
        return dialog
    }

    /**
     * Download the APK to local cache + hand to the system installer.
     * Mirrors the existing path in [AppsDrawerActivity.installApk]
     * but adapted for the dialog flow.  We don't show a progress bar
     * in the launcher overlay — the system installer takes the user
     * to its own confirm + progress UI.
     */
    private fun triggerInstall(activity: AppCompatActivity, item: DockItem) {
        val url = item.apkUrl ?: return
        if (!ApkInstaller.canInstallNow(activity)) {
            ApkInstaller.requestInstallPermission(activity)
            return
        }
        activity.lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                ApkInstaller.downloadAndInstall(
                    ctx = activity.applicationContext,
                    apkUrl = url,
                    suggestedName = "${item.label}.apk",
                    onProgress = { /* no UI hook — system installer takes over */ },
                )
            }
            if (err != null) {
                android.widget.Toast.makeText(
                    activity,
                    "Install failed: $err",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Open Vesper directly on the Settings → Backup section so the
     * user can save a backup code before the new APK installs.
     *
     * Implementation: launch Vesper's MAIN intent with an extra
     * `vesper_route=?screen=backup`.  Vesper's MainActivity reads
     * this in [onCreate] / [onNewIntent] and navigates the WebView
     * to /settings#backup-section.
     *
     * Falls back to a toast if Vesper isn't installed.
     */
    private fun openVesperBackup(activity: AppCompatActivity) {
        val pkg = "tv.vesper.app"
        val launch = activity.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            android.widget.Toast.makeText(
                activity,
                "Vesper isn't installed — please install Vesper first.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        launch.putExtra("vesper_route", "?screen=backup")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(launch)
    }
}
