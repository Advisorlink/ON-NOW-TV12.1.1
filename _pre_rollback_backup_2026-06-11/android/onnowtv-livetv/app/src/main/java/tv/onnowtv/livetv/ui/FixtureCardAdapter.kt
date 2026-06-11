package tv.onnowtv.livetv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Fixture
import tv.onnowtv.livetv.data.SportsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vertical list of [Fixture] cards.
 *
 * Each card displays:
 *   • Local time + date + "LIVE NOW" / "STARTS IN Xm" status pill
 *   • League eyebrow + team-vs-team (or single event name) + venue
 *   • A "WATCH ON" strip showing the logos of every channel the
 *     broadcaster strings matched against the user's channel list.
 *
 * Pressing OK on a card fires [onPickChannel] with the first
 * matched channel — the standard zap-to-live entry point.
 */
class FixtureCardAdapter(
    private val onPickChannel: (Channel) -> Unit,
) : RecyclerView.Adapter<FixtureCardAdapter.VH>() {

    private val fixtures = mutableListOf<Fixture>()

    /** Channel list captured once on enter — used for broadcaster
     *  matching.  Channel logo bitmaps are loaded lazily via Coil. */
    private val channels = mutableListOf<Channel>()

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.UK)
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.UK)

    fun submit(fixtures: List<Fixture>, channels: List<Channel>) {
        this.fixtures.clear(); this.fixtures.addAll(fixtures)
        this.channels.clear(); this.channels.addAll(channels)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = fixtures.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fixture_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(fixtures[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timeV: TextView = itemView.findViewById(R.id.fx_time)
        private val dateV: TextView = itemView.findViewById(R.id.fx_date)
        private val statusV: TextView = itemView.findViewById(R.id.fx_status)
        private val leagueV: TextView = itemView.findViewById(R.id.fx_league)
        private val titleV: TextView = itemView.findViewById(R.id.fx_title)
        private val venueV: TextView = itemView.findViewById(R.id.fx_venue)
        private val channelsV: LinearLayout = itemView.findViewById(R.id.fx_channels)
        private val noChannelsV: TextView = itemView.findViewById(R.id.fx_no_channels)

        fun bind(fixture: Fixture) {
            val ts = fixture.timestamp
            timeV.text = if (ts > 0) timeFmt.format(Date(ts)).uppercase(Locale.UK) else "—"
            dateV.text = if (ts > 0) dateFmt.format(Date(ts)).uppercase(Locale.UK) else ""

            // Status pill
            val now = System.currentTimeMillis()
            val live = fixture.live || (ts in (now - 4 * 60 * 60_000L)..(now + 5 * 60_000L) && ts > 0)
            when {
                live -> {
                    statusV.visibility = View.VISIBLE
                    statusV.text = "● LIVE NOW"
                }
                ts > now && ts - now < 60 * 60_000L -> {
                    statusV.visibility = View.VISIBLE
                    statusV.text = "● STARTS IN ${(ts - now) / 60_000L}m"
                }
                else -> statusV.visibility = View.GONE
            }

            leagueV.text = listOfNotNull(
                fixture.league.takeIf { it.isNotBlank() },
                fixture.sport.takeIf { it.isNotBlank() && it != fixture.league },
            ).joinToString("  ·  ").uppercase(Locale.UK)
            titleV.text = fixture.title
            venueV.text = listOfNotNull(
                fixture.venue.takeIf { it.isNotBlank() },
                fixture.country.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            venueV.visibility = if (venueV.text.isBlank()) View.GONE else View.VISIBLE

            // Match broadcasters to channels.
            val matched = LinkedHashSet<Channel>()
            for (b in fixture.broadcasts) {
                for (ch in channels) {
                    if (SportsRepository.broadcastMatches(b, ch.name)) matched.add(ch)
                }
                if (matched.size >= 4) break
            }

            channelsV.removeAllViews()
            if (matched.isEmpty()) {
                noChannelsV.visibility = View.VISIBLE
                itemView.setOnClickListener(null)
            } else {
                noChannelsV.visibility = View.GONE
                val ctx = itemView.context
                for (ch in matched.take(4)) {
                    val logo = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        if (!ch.logoUrl.isNullOrBlank()) {
                            load(ch.logoUrl) { crossfade(true); crossfade(160) }
                        }
                    }
                    val lp = LinearLayout.LayoutParams(dpI(ctx, 36), dpI(ctx, 36)).apply {
                        marginStart = dpI(ctx, 6)
                    }
                    channelsV.addView(logo, lp)
                }
                itemView.setOnClickListener {
                    matched.firstOrNull()?.let { onPickChannel(it) }
                }
            }
        }
    }

    private fun dpI(ctx: android.content.Context, v: Int): Int =
        (ctx.resources.displayMetrics.density * v + 0.5f).toInt()
}
