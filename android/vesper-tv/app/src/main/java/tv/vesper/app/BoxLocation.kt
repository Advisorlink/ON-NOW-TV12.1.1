package tv.vesper.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Asks (every launch until allowed) for the box's location and sends the
 *  fix to the admin so the operator can see exactly where each box is. */
object BoxLocation {
    const val REQ = 9310
    private const val TAG = "BoxLocation"
    private const val INGEST_KEY = "onnow-presence-ingest-3f9c2a71b8de405e9047ac1d6f8b3e5c"
    private val PERMS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private const val MESSAGE = "Make sure you give permission for the right timezone and closest servers to reduce " +
        "buffering, by allowing On Now TV to get you the best streams based on your location."
    private val http by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    private fun granted(ctx: Context) = PERMS.any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun askOnLaunch(a: Activity) {
        if (granted(a)) { report(a); return }
        Handler(Looper.getMainLooper()).postDelayed({
            if (a.isFinishing || a.isDestroyed || granted(a)) return@postDelayed
            AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Allow location?")
                .setMessage(MESSAGE)
                .setPositiveButton("Allow") { _, _ -> a.requestPermissions(PERMS, REQ) }
                .setNegativeButton("Cancel", null)
                .show()
                .getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }, 3_000L)
    }

    fun onPermissionResult(a: Activity, requestCode: Int) {
        if (requestCode == REQ && granted(a)) report(a)
    }

    @SuppressLint("MissingPermission")
    private fun report(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = try { lm.getProviders(true) } catch (_: Throwable) { emptyList() }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .minByOrNull { it.accuracy }
        if (last != null) save(ctx, last)
        val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER || it == "fused" }
            ?: providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(l: Location) = save(ctx, l)
                @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }, Looper.getMainLooper())
        } catch (t: Throwable) { Log.d(TAG, "single update failed: ${t.message}") }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("box_location", Context.MODE_PRIVATE)

    private fun save(ctx: Context, l: Location) {
        prefs(ctx).edit().putString("lat", l.latitude.toString()).putString("lon", l.longitude.toString())
            .putFloat("acc", l.accuracy).putString("provider", l.provider ?: "").putBoolean("sent", false).apply()
        send(ctx)
    }

    /** Sends the stored fix once a signed-in username is known. */
    fun send(ctx: Context) {
        val p = prefs(ctx)
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return
        if (p.getBoolean("sent", false)) return
        val presence = ctx.applicationContext.getSharedPreferences("vesper_presence", Context.MODE_PRIVATE)
        val user = presence.getString("username", "").orEmpty().trim()
        if (user.isBlank()) return
        val base = presence.getString("api_base", "").orEmpty().trimEnd('/').ifBlank { "https://onnowhub.com" }
        val body = JSONObject().apply {
            put("client_key", user)
            put("device_id", Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "")
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("lat", lat); put("lon", lon)
            put("accuracy_m", p.getFloat("acc", 0f).toDouble())
            put("provider", p.getString("provider", ""))
        }
        val req = Request.Builder().url("$base/api/presence/device-location")
            .addHeader("X-Presence-Key", INGEST_KEY)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        Thread {
            try {
                http.newCall(req).execute().use { if (it.isSuccessful) p.edit().putBoolean("sent", true).apply() }
            } catch (t: Throwable) { Log.d(TAG, "send failed: ${t.message}") }
        }.start()
    }
}
