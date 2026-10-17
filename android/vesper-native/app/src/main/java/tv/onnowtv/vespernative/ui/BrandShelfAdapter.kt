package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

class BrandShelfAdapter(private val repository: BrowseRepository, private val scope: CoroutineScope,
    private val height: Int, private val open: (Brand) -> Unit, private val up: Int,
    private val failed: (Throwable) -> Unit) : RecyclerView.Adapter<BrandShelfAdapter.Holder>() {
    private val scroll = mutableMapOf<Int, android.os.Parcelable?>()
    override fun getItemCount() = 2
    class Holder(c: Context, height: Int) : RecyclerView.ViewHolder(TvUi.column(c)) {
        val heading = TvUi.heading(c, "brand-heading", "", 18f)
        val eyebrow=V2Ui.eyebrow(c,"brand-eyebrow","Browse")
        val count = TvUi.text(c, "brand-count", size = 10f, color = TvUi.muted)
        val row = RecyclerView(c).apply { layoutManager = LinearLayoutManager(c, RecyclerView.HORIZONTAL, false); itemAnimator = null; FocusMotion.prepareRail(this) }
        var job: Job? = null
        var rowIndex = -1
        init { (itemView as LinearLayout).apply {
            layoutParams = RecyclerView.LayoutParams(-1,TvUi.dp(c,height))
            setPadding(TvUi.dp(c,62), TvUi.dp(c,16), TvUi.dp(c,28), TvUi.dp(c,16))
            val header = TvUi.row(c); header.addView(heading,LinearLayout.LayoutParams(0,-2,1f)); header.addView(count)
            addView(eyebrow); addView(TvUi.gap(c,5)); addView(header); addView(TvUi.gap(c,8)); addView(row,LinearLayout.LayoutParams(-1,TvUi.dp(c,144)))
        } }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent.context,height)
    override fun onBindViewHolder(h: Holder, position: Int) {
        h.job?.cancel(); h.rowIndex = position
        val kind = if (position == 0) "networks" else "collections"
        val brands = repository.brands(h.itemView.context,kind).filter { position == 0 || it.group == "studio" }
        TvUi.identify(h.heading,"$kind-heading"); TvUi.identify(h.count,"$kind-count"); TvUi.identify(h.row,"$kind-row")
        TvUi.identify(h.eyebrow,"$kind-eyebrow")
        h.heading.text = if (position == 0) "By network" else "Studios"
        h.count.text = "${brands.size} ${if (position == 0) "networks" else "studios"}"
        val adapter = BrandAdapter(brands,open,if (position == 0) up else View.NO_ID); h.row.adapter = adapter
        h.row.layoutManager?.onRestoreInstanceState(scroll[position])
        h.job = scope.launch {
            try { adapter.replace(repository.art(brands)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) /* Bundled wordmarks remain usable if remote art is unavailable. */ }
        }
    }
    override fun onViewRecycled(h: Holder) { h.job?.cancel(); scroll[h.rowIndex] = h.row.layoutManager?.onSaveInstanceState(); h.row.adapter = null }
}