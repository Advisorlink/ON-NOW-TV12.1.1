package tv.onnow.launcher.vpn

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tv.onnow.launcher.R

/**
 * v2.8.19 — On Now TV V2 VPN control screen.
 *
 * Important: building a fully-functional VPN tunnel from scratch is
 * a multi-week project (WireGuard / OpenVPN protocol, cert mgmt,
 * VpnService permission flow).  Instead, this screen:
 *
 *   1. Shows the LIVE VPN status via ConnectivityManager.NetworkCallback
 *      — flips the big indicator the moment ANY VPN is active.
 *   2. Has a focusable "Open VPN settings" pill that launches the
 *      Android system VPN page (Settings.ACTION_VPN_SETTINGS) — the
 *      same screen any VPN app uses to add a profile.
 *   3. Lays everything out with the same eyebrow / headline / chip
 *      / pill vocabulary as the Speed Test screen so the launcher
 *      feels cohesive.
 *
 * When the user installs a VPN client APK from the App Store, that
 * app's foreground UI handles connect/disconnect — and this screen
 * reflects the result in real time.  The "Coming soon — built-in
 * VPN" copy is honest about the bigger feature roadmap.
 */
class VpnControlActivity : AppCompatActivity() {

    private lateinit var statusBigText: TextView
    private lateinit var statusSubText: TextView
    private lateinit var statusDot: View
    private lateinit var openSettingsBtn: TextView
    private val cm by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { refresh() } }
        override fun onLost(network: Network) { runOnUiThread { refresh() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread { refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        cm.registerDefaultNetworkCallback(callback)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()  // catch any change while we were away in Settings
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cm.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
    }

    /* ──────────────────  UI  ────────────────── */

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(64), dp(56), dp(64), dp(56))
        }

        column.addView(TextView(this).apply {
            text = "ON NOW TV V2 · VPN"
            textSize = 12f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        })
        column.addView(spacer(dp(12)))

        column.addView(TextView(this).apply {
            text = "Stay private on every box"
            textSize = 42f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        })
        column.addView(spacer(dp(36)))

        // Big status card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#FF0F2138"),
                    Color.parseColor("#FF06101D"),
                )
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(1), Color.parseColor("#33B3D4FF"))
            }
            setPadding(dp(56), dp(40), dp(56), dp(40))
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFFF5573"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        card.addView(statusDot)
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        statusBigText = TextView(this).apply {
            text = "Disconnected"
            textSize = 38f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        statusSubText = TextView(this).apply {
            text = "Your traffic is not protected by a VPN."
            textSize = 14f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            setPadding(0, dp(6), 0, 0)
        }
        rightCol.addView(statusBigText)
        rightCol.addView(statusSubText)
        card.addView(rightCol)
        column.addView(card)

        // Subtle "coming soon" note
        column.addView(spacer(dp(28)))
        column.addView(TextView(this).apply {
            text = "Built-in On Now TV V2 VPN — coming soon.\n" +
                "Today you can connect any sideloaded VPN client " +
                "(WireGuard, OpenVPN, NordVPN, …); we'll show its " +
                "status live above the moment it activates."
            textSize = 13f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            gravity = Gravity.CENTER_HORIZONTAL
        })
        column.addView(spacer(dp(40)))

        // Action pill — opens system VPN settings.
        openSettingsBtn = TextView(this).apply {
            text = "Open VPN settings"
            textSize = 16f
            setTextColor(Color.parseColor("#FF04060B"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.10f
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#FF2BB6FF"))
            }
            setPadding(dp(36), dp(16), dp(36), dp(16))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (_: Throwable) {
                    // Fallback for boxes without the VPN settings screen.
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
        }
        column.addView(openSettingsBtn)
        openSettingsBtn.post { openSettingsBtn.requestFocus() }

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        return root
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /* ──────────────────  Status detection  ────────────────── */

    private fun refresh() {
        val active = isVpnActive()
        if (active) {
            statusBigText.text = "Connected"
            statusSubText.text = "Your traffic is encrypted via VPN."
            (statusDot.background as? GradientDrawable)?.setColor(
                Color.parseColor("#FF2EEAC2")
            )
        } else {
            statusBigText.text = "Disconnected"
            statusSubText.text = "Your traffic is not protected by a VPN."
            (statusDot.background as? GradientDrawable)?.setColor(
                Color.parseColor("#FFFF5573")
            )
        }
    }

    private fun isVpnActive(): Boolean {
        return try {
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (_: Throwable) { false }
    }
}
