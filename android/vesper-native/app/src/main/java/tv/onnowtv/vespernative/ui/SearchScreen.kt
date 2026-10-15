package tv.onnowtv.vespernative.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.R
import tv.onnowtv.vespernative.data.*

class SearchScreen(c: Context, private val repository: CatalogRepository, open: (Title) -> Unit,
    back: () -> Unit, private val failed: (Throwable) -> Unit) : NativeScreen(c) {
    private val input = TvUi.identify(EditText(c), "search-query").apply {
        hint = "Movies & TV shows"; contentDescription = "Search titles"; setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
        setTextColor(TvUi.text); setHintTextColor(TvUi.muted); textSize = 18f
        background = TvUi.focus(c, false); setPadding(TvUi.dp(c, 16), 0, TvUi.dp(c, 16), 0)
    }
    private val status = StatusBand(c, "search-status")
    private val adapter = TitleAdapter("search", 164, open)
    private val results = TvUi.identify(RecyclerView(c), "search-results").apply {
        layoutManager = GridLayoutManager(c, (c.resources.displayMetrics.widthPixels / TvUi.dp(c, 145)).coerceAtLeast(3))
        adapter = this@SearchScreen.adapter; itemAnimator = null
        clipToPadding = false; setPadding(4, 4, 4, 4)
    }
    private var job: Job? = null
    init {
        val content = TvUi.column(c).apply { setPadding(TvUi.dp(c, 40), TvUi.dp(c, 24), TvUi.dp(c, 40), TvUi.dp(c, 16)) }
        val heading = TvUi.row(c)
        heading.addView(TvUi.icon(c, "search-back", "Back", R.drawable.ic_back, back), LinearLayout.LayoutParams(TvUi.dp(c, 42), TvUi.dp(c, 42)))
        heading.addView(TvUi.heading(c, "search-title", "  Search"))
        content.addView(heading); content.addView(TvUi.gap(c, 20))
        val row = TvUi.row(c)
        row.addView(input, LinearLayout.LayoutParams(0, TvUi.dp(c, 48), 1f))
        val submit = TvUi.button(c, "search-submit", "Search") { search() }
        row.addView(submit)
        content.addView(row); content.addView(status)
        content.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(content)
        status.clear(); status.onRetry = { search() }
        input.nextFocusRightId = submit.id; submit.nextFocusLeftId = input.id
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
    }
    private fun search() {
        val query = input.text.toString().trim()
        if (query.length < 2) { status.show("Enter at least two characters."); input.requestFocus(); return }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        job?.cancel()
        job = scope.launch {
            status.show("Searching…"); adapter.replace(emptyList())
            try {
                val matches = repository.search(query)
                adapter.replace(matches)
                if (matches.isEmpty()) status.show("No titles found for “$query”.") else {
                    status.clear(); results.scrollToPosition(0)
                    results.post { results.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { status.show("Search is unavailable. Please try again.", true); failed(e) }
        }
    }
    override fun initialFocus(): View = input
}