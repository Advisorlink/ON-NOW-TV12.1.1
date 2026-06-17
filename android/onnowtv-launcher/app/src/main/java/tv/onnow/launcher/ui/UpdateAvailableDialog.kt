package tv.onnow.launcher.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_update_available)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }

        val title = dialog.findViewById<TextView>(R.id.update_dialog_title)
        val versions = dialog.findViewById<TextView>(R.id.update_dialog_versions)
        val body = dialog.findViewById<TextView>(R.id.update_dialog_body)
        val install = dialog.findViewById<Button>(R.id.update_dialog_install)
        val backup = dialog.findViewById<Button>(R.id.update_dialog_backup)
        val skip = dialog.findViewById<Button>(R.id.update_dialog_skip)

        // v2.10.33 — Per-tile body copy override.  Admin-supplied
        // text from the launcher backend's tile editor; if blank we
        // leave the layout's default body XML untouched.
        item.updatePopupText?.takeIf { it.isNotBlank() }?.let {
            body.text = it
        }

        // v2.10.33 — Per-tile secondary-button text.  Hide the button
        // entirely when the admin hasn't supplied any text — the
        // user explicitly asked to control this per tile instead of
        // having the launcher guess which apps support backups.
        val buttonText = item.updateButtonText?.takeIf { it.isNotBlank() }
        if (buttonText != null) {
            backup.text = buttonText
            backup.visibility = View.VISIBLE
        } else {
            backup.visibility = View.GONE
        }

        title.text = item.label.ifBlank { "Update available" }
        val installedLabel = if (installedVersionCode > 0) "v$installedVersionCode" else "Unknown"
        val remoteLabel = item.apkVersion?.takeIf { it.isNotBlank() }
            ?.let { "v$it" }
            ?: item.apkVersionCode?.let { "v$it" }
            ?: "newer build"
        versions.text = "Installed: $installedLabel  →  New: $remoteLabel"

        install.setOnClickListener {
            triggerInstall(activity, item, dialog)
            // v2.10.33 — do NOT dismiss here.  The dialog stays
            // visible so the user sees the progress UI we're about
            // to swap in.  It will dismiss itself once the system
            // installer takes over the foreground.
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
     * v2.10.33 — Download the APK with VISIBLE PROGRESS, then hand
     * off to the system installer.
     *
     * Previous behaviour: dialog dismissed immediately on Update
     * tap, download ran silently, and the user saw a frozen launcher
     * home screen for 5–30 seconds (depending on APK size and
     * network) before the system installer suddenly popped up.
     * That was confusing on slow networks — felt like nothing was
     * happening, users would re-tap or panic-back out.
     *
     * New behaviour:
     *   1. Swap the body copy + 3 action buttons OUT, swap the
     *      progress group IN.  Status reads "Downloading update…".
     *   2. Lock the dialog (`setCancelable(false)`) so an
     *      accidental BACK press doesn't kill the download.
     *   3. Stream `onProgress(percent)` from ApkInstaller back to
     *      the UI thread; update the progress-bar + percent label
     *      after every chunk.
     *   4. On 100 %: status text flips to "Opening installer…",
     *      we briefly hold the dialog so the user reads it (300 ms),
     *      then ApkInstaller fires the system install prompt and
     *      we dismiss our own dialog.
     *   5. On error: status text shows the error, dialog becomes
     *      cancellable again so the user can dismiss it.
     */
    private fun triggerInstall(
        activity: AppCompatActivity,
        item: DockItem,
        dialog: Dialog,
    ) {
        val url = item.apkUrl ?: return
        if (!ApkInstaller.canInstallNow(activity)) {
            ApkInstaller.requestInstallPermission(activity)
            return
        }

        // ── View references (resolved against the dialog hierarchy) ──
        val body = dialog.findViewById<TextView>(R.id.update_dialog_body)
        val install = dialog.findViewById<Button>(R.id.update_dialog_install)
        val backup = dialog.findViewById<Button>(R.id.update_dialog_backup)
        val skip = dialog.findViewById<Button>(R.id.update_dialog_skip)
        val group = dialog.findViewById<LinearLayout>(R.id.update_dialog_progress_group)
        val status = dialog.findViewById<TextView>(R.id.update_dialog_progress_status)
        val bar = dialog.findViewById<ProgressBar>(R.id.update_dialog_progress_bar)
        val percent = dialog.findViewById<TextView>(R.id.update_dialog_progress_percent)
        val hint = dialog.findViewById<TextView>(R.id.update_dialog_progress_hint)

        // ── Show progress, hide actions ──
        body.visibility = View.GONE
        install.visibility = View.GONE
        backup.visibility = View.GONE
        skip.visibility = View.GONE
        group.visibility = View.VISIBLE
        status.text = "Downloading update…"
        bar.progress = 0
        percent.text = "0%"
        dialog.setCancelable(false)

        activity.lifecycleScope.launch {
            val err = ApkInstaller.downloadAndInstall(
                ctx = activity.applicationContext,
                apkUrl = url,
                suggestedName = "${item.label}.apk",
                onProgress = { pct ->
                    // Callback fires on the IO thread — bounce to UI.
                    activity.runOnUiThread {
                        bar.progress = pct
                        percent.text = "$pct%"
                        if (pct >= 100) status.text = "Preparing installer…"
                    }
                },
            )

            if (err != null) {
                // Download or install handoff failed — keep the
                // dialog open and surface the error so the user can
                // back out / retry.
                status.text = "Update failed"
                percent.text = ""
                hint.text = err
                dialog.setCancelable(true)
                android.widget.Toast.makeText(
                    activity,
                    "Install failed: $err",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            // Success — `downloadAndInstall` has already fired the
            // system install (or uninstall-then-install) intent.
            // Flip the copy so the user sees what's coming next,
            // hold the dialog briefly, then dismiss.
            status.text = "Opening installer…"
            hint.text = "Android will now ask you to confirm. Tap INSTALL when prompted."
            delay(600)
            try { dialog.dismiss() } catch (_: Throwable) { /* swallow */ }
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
