package tv.onnow.launcher.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v2.12.12 — Device-owner admin component.
 *
 * This receiver is the "hook" that lets ON NOW TV be provisioned as
 * the box's **Device Owner** (one-time, at setup, via
 * `adb shell dpm set-device-owner tv.onnow.launcher/.admin.OnNowDeviceAdminReceiver`).
 *
 * Once the launcher is device owner, it can install / update APKs
 * SILENTLY through `PackageInstaller` — no root, no superuser prompt,
 * no "unknown sources" dialog, no system confirmation.  See
 * [tv.onnow.launcher.install.SilentInstaller].
 *
 * We don't enforce any restrictive device policies here — the receiver
 * exists purely to grant the launcher the device-owner privilege that
 * unlocks silent installs.  Keeping it policy-free means provisioning
 * the box can never lock the customer out of anything.
 */
class OnNowDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "device admin enabled — launcher is now device owner")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "device admin disabled")
    }

    companion object {
        private const val TAG = "OnNowDeviceAdmin"
    }
}
