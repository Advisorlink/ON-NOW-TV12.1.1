package tv.onnowtv.livetv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.ReminderStore

/**
 * Background watcher that keeps an eye on saved reminders and
 * pops a banner at the TOP-RIGHT of the current activity when a
 * programme is about to start (configurable lead time, default
 * 60 seconds before start, then keeps the banner up until 90 s
 * after start so a quick channel-flip is enough to catch the
 * opening).
 *
 * The watcher polls in-process on a 15 s Handler tick — no
 * AlarmManager / WorkManager, no broadcast receiver, no system
 * permissions.  Reminders only fire while the app is in the
 * foreground; that's the agreed scope for v1.
 *
 * Usage from any Activity:
 *
 *     // in onCreate, AFTER setContentView:
 *     ReminderWatcher.attach(this, rootFrameLayout) { reminder ->
 *         // user pressed OK on the banner → tune to channel
 *         launchPlayerFor(reminder)
 *     }
 *
 *     // in onDestroy:
 *     ReminderWatcher.detach(this)
 */
object ReminderWatcher {

    private const val TAG = "ReminderWatcher"

    /** Show the banner up to this long BEFORE the programme starts. */
    private const val LEAD_MS = 60_000L

    /** Keep the banner up to this long AFTER start, so the user
     *  who's just walked back into the room still catches it. */
    private const val POST_START_MS = 90_000L

    /** How often to re-evaluate the reminder set. */
    private const val POLL_INTERVAL_MS = 15_000L

    /** Avoid hammering the user with the same reminder more than once
     *  inside this window after they dismiss / engage with it. */
    private const val FIRE_COOLDOWN_MS = 5L * 60_000L

    /** Map activity → its installed banner + ticker.  We store the
     *  banner View instead of a WeakReference so the activity can
     *  detach explicitly in onDestroy without leaking.  Only ONE
     *  banner per activity. */
    private data class State(
        val banner: View,
        val ticker: Runnable,
        val handler: Handler,
        val onActivate: (ReminderStore.Reminder) -> Unit,
    )

    private val states = mutableMapOf<Activity, State>()

    /** Attach a banner to [activity].  Returns immediately if the
     *  activity already has one installed. */
    fun attach(
        activity: Activity,
        root: ViewGroup,
        onActivate: (ReminderStore.Reminder) -> Unit,
    ) {
        if (states.containsKey(activity)) return

        val inflater = LayoutInflater.from(activity)
        val banner = inflater.inflate(R.layout.reminder_banner, root, false)

        // Position top-right.  Both EpgActivity's main layout and
        // PlayerActivity's root are FrameLayout-compatible so we can
        // just append.
        when (root) {
            is FrameLayout -> {
                val lp = FrameLayout.LayoutParams(
                    activity.resources.getDimensionPixelSize(R.dimen.reminder_banner_width),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = dp(activity, 22)
                    rightMargin = dp(activity, 22)
                }
                root.addView(banner, lp)
            }
            else -> {
                root.addView(banner)
            }
        }

        val handler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                evaluate(activity, banner, onActivate)
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        states[activity] = State(banner, ticker, handler, onActivate)
        // Kick the first evaluation immediately so the banner can
        // appear for a reminder that's already inside the lead window.
        handler.post(ticker)
    }

    fun detach(activity: Activity) {
        val state = states.remove(activity) ?: return
        state.handler.removeCallbacksAndMessages(null)
        (state.banner.parent as? ViewGroup)?.removeView(state.banner)
    }

    /**
     * Re-evaluate persistent reminders against wall-clock time and
     * either show or hide the banner.
     */
    private fun evaluate(
        activity: Activity,
        banner: View,
        onActivate: (ReminderStore.Reminder) -> Unit,
    ) {
        val ctx: Context = activity.applicationContext
        val map = ReminderStore.load(ctx)
        ReminderStore.pruneExpired(ctx, map)
        val now = System.currentTimeMillis()
        // Pick the soonest reminder inside the active window:
        // either it's about to start (within LEAD_MS) OR just
        // started (within POST_START_MS).
        val due = map.values
            .filter { r ->
                (r.startMs > now && r.startMs - now <= LEAD_MS) ||
                (r.startMs <= now && now - r.startMs <= POST_START_MS)
            }
            // If multiple are due, prefer the one starting soonest
            // (or just-started over future).
            .minByOrNull { kotlin.math.abs(it.startMs - now) }

        if (due == null) {
            hideBanner(banner)
            return
        }

        // Cooldown — if the user already dismissed this reminder in
        // the last 5 minutes, don't keep nagging them.
        if (now - due.firedAt < FIRE_COOLDOWN_MS && due.firedAt > 0L) {
            hideBanner(banner)
            return
        }

        renderBanner(activity, banner, due, now)
        // Wire OK / tap → tune in.
        banner.setOnClickListener {
            // Mark as engaged so cooldown takes effect.
            val fresh = ReminderStore.load(ctx)
            fresh[due.key]?.firedAt = now
            ReminderStore.save(ctx, fresh)
            hideBanner(banner)
            onActivate(due)
        }
    }

    private fun renderBanner(
        activity: Activity,
        banner: View,
        r: ReminderStore.Reminder,
        now: Long,
    ) {
        val logo: ImageView   = banner.findViewById(R.id.banner_logo)
        val title: TextView   = banner.findViewById(R.id.banner_title)
        val channel: TextView = banner.findViewById(R.id.banner_channel)
        val eyebrow: TextView = banner.findViewById(R.id.banner_eyebrow)

        title.text = r.title
        channel.text = (r.channelLcn?.let { "CH $it · " } ?: "") + r.channelName
        eyebrow.text = when {
            r.startMs > now -> {
                val secs = ((r.startMs - now) / 1000L).toInt().coerceAtLeast(0)
                "REMINDER · STARTING IN ${secs}s"
            }
            else -> "REMINDER · STARTED — TUNE IN"
        }

        if (!r.channelLogo.isNullOrBlank()) {
            logo.load(r.channelLogo)
        } else {
            logo.setImageDrawable(null)
        }

        if (banner.visibility != View.VISIBLE) {
            banner.visibility = View.VISIBLE
            banner.translationX = banner.width.toFloat() + 80f
            banner.alpha = 0f
            banner.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(280)
                .start()
        }
        banner.bringToFront()
    }

    private fun hideBanner(banner: View) {
        if (banner.visibility == View.GONE) return
        banner.animate()
            .alpha(0f)
            .translationX(banner.width.toFloat() + 80f)
            .setDuration(220)
            .withEndAction { banner.visibility = View.GONE }
            .start()
    }

    private fun dp(ctx: Context, dp: Int): Int =
        (dp * ctx.resources.displayMetrics.density).toInt()

    /* ──────────────── Helpers for EpgActivity / PlayerActivity
       to build a Channel from a stored reminder so they can
       drive PlaybackQueue without re-resolving from the bundle. ──────────────── */

    fun buildChannelFromBundle(channelId: String): Channel? {
        return BundleHolder.current?.channels?.firstOrNull { it.id == channelId }
    }

    /**
     * Convenience: from any Activity, launch the player on the
     * reminder's channel.  Builds the PlaybackQueue from the
     * channel's category so D-pad up/down inside the player works.
     */
    fun launchPlayerFor(ctx: Context, reminder: ReminderStore.Reminder) {
        val channel = buildChannelFromBundle(reminder.channelId) ?: return
        val bundle = BundleHolder.current ?: return
        val siblings = bundle.channels.filter {
            it.categoryId == channel.categoryId && it.categoryId != null
        }.ifEmpty { bundle.channels }
        PlaybackQueue.setQueue(siblings, channel.id)

        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, channel.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, reminder.title)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        ctx.startActivity(intent)
    }
}
