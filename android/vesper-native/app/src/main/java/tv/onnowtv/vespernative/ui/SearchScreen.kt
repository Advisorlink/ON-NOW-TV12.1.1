package tv.onnowtv.vespernative.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONArray
import tv.onnowtv.vespernative.data.*

class SearchScreen(c:Context,private val repository:CatalogRepository,open:(Title)->Unit,back:()->Unit,
    private val failed:(Throwable)->Unit,nav:NavigationBar?=null,scopeKey:String?=null):NativeScreen(c) {
    private val input=TvUi.identify(EditText(c),"search-query").apply {
        hint="Search movies, shows…"; contentDescription="Search titles"; setSingleLine()
        inputType=InputType.TYPE_CLASS_TEXT; imeOptions=EditorInfo.IME_ACTION_SEARCH
        setTextColor(TvUi.text); setHintTextColor(TvUi.muted); V2Ui.field(this); textSize=14f
    }
    private val status=StatusBand(c,"search-status")
    private val adapter=TitleAdapter("search",164,open)
    private val results=TvUi.identify(RecyclerView(c),"search-results").apply {
        layoutManager=GridLayoutManager(c,((c.resources.displayMetrics.widthPixels/TvUi.dp(c,1)-124)/120).coerceAtLeast(3))
        adapter=this@SearchScreen.adapter; itemAnimator=null; FocusMotion.prepareRail(this)
    }
    private val recent=TvUi.column(c)
    private val history=scopeKey?.let { c.getSharedPreferences("search-history-$it",Context.MODE_PRIVATE) }
    private var queryJob:Job?=null; private var debounce:Job?=null
    private var matches=emptyList<Title>(); private var type="all"
    private val tabs=mutableListOf<TextView>()
    init {
        val content=TvUi.column(c).apply { setPadding(TvUi.dp(c,62),TvUi.dp(c,if(nav==null)28 else 66),TvUi.dp(c,62),TvUi.dp(c,16)) }
        val row=TvUi.row(c)
        val backButton=V2Ui.pill(c,"search-back","","back",click=back)
        row.addView(backButton,LinearLayout.LayoutParams(TvUi.dp(c,30),TvUi.dp(c,30)).apply { marginEnd=TvUi.dp(c,12) })
        val clear=V2Ui.pill(c,"search-clear","","close") { input.text.clear(); input.requestFocus() }.apply { contentDescription="Clear search"; setPadding(6,0,6,0) }
        row.addView(V2Ui.fieldShell(c,"search-field-shell",input,"search",clear),LinearLayout.LayoutParams(0,TvUi.dp(c,40),1f))
        val submit=V2Ui.pill(c,"search-submit","Search",primary=true) { search() }
        row.addView(submit,LinearLayout.LayoutParams(-2,TvUi.dp(c,30)).apply { marginStart=TvUi.dp(c,10) })
        content.addView(row); content.addView(TvUi.gap(c,12))
        val filters=TvUi.row(c)
        listOf("all" to "All","series" to "TV Shows","movie" to "Movies").forEach { (value,label) ->
            val button=V2Ui.pill(c,"search-filter-$value",label) { type=value; filter() }.apply { isSelected=value==type }
            tabs.add(button); filters.addView(button,LinearLayout.LayoutParams(-2,TvUi.dp(c,27)).apply { marginEnd=TvUi.dp(c,6) })
        }
        content.addView(filters); content.addView(status); content.addView(TvUi.gap(c,14)); content.addView(recent)
        content.addView(results,LinearLayout.LayoutParams(-1,0,1f)); root.addView(content,FrameLayout.LayoutParams(-1,-1))
        nav?.let { root.addView(it,FrameLayout.LayoutParams(-2,TvUi.dp(c,58),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=TvUi.dp(c,7) }) }
        status.clear(); status.onRetry={ search() }; showRecent()
        input.nextFocusRightId=submit.id; submit.nextFocusLeftId=input.id
        input.setOnEditorActionListener { _,action,_->if(action==EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        input.doAfterTextChanged {
            debounce?.cancel()
            if(input.text.toString().trim().length<2) { queryJob?.cancel(); matches=emptyList(); adapter.replace(emptyList()); status.clear(); recent.visibility=View.VISIBLE }
            else debounce=scope.launch { delay(350); search(false) }
        }
    }
    private fun filter() {
        adapter.replace(matches.filter { type=="all" || it.type==type })
        tabs.forEach { it.isSelected=it.getTag(tv.onnowtv.vespernative.R.id.test_id)=="search-filter-$type" }
    }
    private fun showRecent() {
        recent.removeAllViews(); recent.addView(V2Ui.eyebrow(context,"search-recent-heading","Recent searches")); recent.addView(TvUi.gap(context,8))
        val entries=runCatching { JSONArray(history?.getString("queries","[]") ?: "[]") }.getOrDefault(JSONArray())
        val row=TvUi.row(context)
        (0 until minOf(entries.length(),6)).forEach { i ->
            val value=entries.optString(i)
            row.addView(V2Ui.pill(context,"search-recent-$i",value,"search") { input.setText(value); search() }.apply { maxWidth=TvUi.dp(context,180) },LinearLayout.LayoutParams(-2,TvUi.dp(context,28)).apply { marginEnd=TvUi.dp(context,6) })
        }
        recent.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(row) })
    }
    private fun search(moveFocus:Boolean=true) {
        val query=input.text.toString().trim()
        if(query.length<2) { status.show("Enter at least two characters."); input.requestFocus(); return }
        if(moveFocus)debounce?.cancel()
        if(moveFocus)(context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        queryJob?.cancel(); recent.visibility=View.GONE
        queryJob=scope.launch {
            status.show("Searching…"); matches=emptyList(); adapter.replace(emptyList())
            try {
                matches=repository.search(query); filter()
                if(matches.isEmpty())status.show("No titles found for “$query”.") else {
                    status.clear(); results.scrollToPosition(0)
                    if(moveFocus)results.post { results.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                    val old=runCatching { JSONArray(history?.getString("queries","[]") ?: "[]") }.getOrDefault(JSONArray())
                    val values=(listOf(query)+(0 until old.length()).map(old::optString)).distinct().take(12)
                    history?.edit()?.putString("queries",JSONArray(values).toString())?.apply()
                    showRecent()
                }
            } catch(e:CancellationException) { throw e } catch(e:Exception) { status.show("Search is unavailable. Please try again.",true); failed(e) }
        }
    }
    override fun initialFocus():View=input
}