package com.smartiptv.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.LinkedHashMap

data class CardData(val item: Content, val favorite: Boolean, val progress: Progress?)
data class CardPage(val cards: List<CardData>, val total: Int)

/** SQL-backed virtual grid: at most five pages of metadata and visible posters. */
class CatalogAdapter(
    private val images: ArtworkLoader,
    private val open: (Content) -> Unit,
    private val options: (Content) -> Unit,
    private val loadPage: (Int, Int, (CardPage) -> Unit) -> Unit,
    private val horizontal: Boolean = false,
) : RecyclerView.Adapter<CatalogAdapter.Holder>() {
    private val pages = object : LinkedHashMap<Int, List<CardData>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<CardData>>?): Boolean = size > 5
    }
    private val pending = mutableSetOf<Int>()
    private var generation = 0
    private var total = 0
    private var live = false
    private var recycler: RecyclerView? = null
    val retainedItems: Int get() = pages.values.sumOf { it.size }
    val count: Int get() = total
    fun cachedItems(): List<Content> = pages.values.flatten().map { it.item }.distinctBy { it.uid }
    fun updateIndicators(values: Map<String, CardData>) {
        pages.entries.forEach { entry -> entry.setValue(entry.value.map { values[it.item.uid] ?: it }) }
        recycler?.let { view -> for (index in 0 until view.childCount) {
            val holder = view.getChildViewHolder(view.getChildAt(index)) as? Holder ?: continue
            holder.card?.let { previous -> values[previous.item.uid]?.let { holder.indicators(it) } }
        } }
    }
    companion object { const val PAGE_SIZE = 40 }

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getItemCount() = total

    fun reset(first: CardPage = CardPage(emptyList(), 0), compact: Boolean = false) {
        generation++; pending.clear(); pages.clear(); total = first.total; live = compact
        if (first.cards.isNotEmpty()) pages[0] = first.cards
        notifyDataSetChanged()
    }
    fun refreshVisible() { recycler?.let { view -> for (index in 0 until view.childCount) (view.getChildViewHolder(view.getChildAt(index)) as? Holder)?.let { bind(it, it.bindingAdapterPosition) } } }
    fun reloadPosters() { recycler?.let { view -> for (index in 0 until view.childCount) (view.getChildViewHolder(view.getChildAt(index)) as? Holder)?.loadArtwork() } }
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { recycler = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { recycler = null }

    private fun tileHeight(context: Context): Int {
        val preferred = context.dp(if (live) 151 else 217)
        val available = recycler?.let { it.height - it.paddingTop - it.paddingBottom } ?: 0
        return if (!horizontal && available > 0) minOf(preferred, available) else preferred
    }
    fun fitVisibleCards() {
        recycler?.let { view -> for (index in 0 until view.childCount) {
            val tile = view.getChildAt(index)
            val height = tileHeight(tile.context)
            if (tile.layoutParams.height != height) tile.layoutParams = tile.layoutParams.apply { this.height = height }
        } }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val tile = Tile(parent.context)
        tile.layoutParams = RecyclerView.LayoutParams(if (horizontal) parent.context.dp(132) else ViewGroup.LayoutParams.MATCH_PARENT, tileHeight(parent.context))
        return Holder(tile)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder, position)
    private fun bind(holder: Holder, position: Int) {
        if (position < 0) return
        val page = position / PAGE_SIZE
        holder.bind(pages[page]?.getOrNull(position % PAGE_SIZE))
        if (!pages.containsKey(page) && pending.add(page)) {
            val current = generation
            loadPage(page * PAGE_SIZE, PAGE_SIZE) { response ->
                if (current != generation) return@loadPage
                pending.remove(page)
                pages[page] = response.cards
                val start = page * PAGE_SIZE
                if (start < total) recycler?.post {
                    if (current == generation) notifyItemRangeChanged(start, minOf(response.cards.size, total - start))
                }
            }
        }
    }
    override fun onViewRecycled(holder: Holder) { holder.bind(null) }
    override fun onViewDetachedFromWindow(holder: Holder) { images.clear(holder.tile.poster) }
    override fun onViewAttachedToWindow(holder: Holder) { holder.tile.post { holder.loadArtwork() } }

    inner class Holder(val tile: Tile) : RecyclerView.ViewHolder(tile) {
        var card: CardData? = null
        init {
            tile.setOnClickListener { card?.item?.let(open) }
            tile.setOnLongClickListener { card?.item?.let(options); true }
            tile.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { card?.item?.let(options); true } else false
            }
        }
        fun bind(value: CardData?) {
            card = value
            val height = tileHeight(tile.context)
            if (tile.layoutParams.height != height) tile.layoutParams = tile.layoutParams.apply { this.height = height }
            images.clear(tile.poster)
            tile.title.text = value?.item?.name ?: "Carregando…"
            indicators(value)
            tile.post { loadArtwork() }
        }
        fun indicators(value: CardData?) {
            card = value
            tile.heart.visibility = if (value?.favorite == true) View.VISIBLE else View.GONE
            val progress = value?.progress
            val resume = if (value?.item?.kind == "movie" && progress != null) "Parou em ${stamp(progress.position)}" else ""
            tile.description.text = resume.ifEmpty { value?.item?.let { if (it.kind == "episode") "T${it.season} · E${it.episode}" else it.year.ifEmpty { if (it.kind == "live") "AO VIVO" else "" } }.orEmpty() }
            tile.description.setTextColor(if (resume.isNotEmpty()) Palette.green else Palette.muted)
            tile.contentDescription = (value?.item?.displayName ?: "Carregando conteúdo") + if (resume.isNotEmpty()) ", $resume" else ""
            tile.progress.visibility = if (progress != null && progress.duration > 0) View.VISIBLE else View.GONE
            tile.progress.progress = if (progress != null && progress.duration > 0) (progress.position * 1000 / progress.duration).toInt() else 0
        }
        fun loadArtwork() {
            val value = card ?: return
            if (tile.poster.tag == null && tile.isShown && tile.getGlobalVisibleRect(android.graphics.Rect())) images.load(value.item.artwork, tile.poster)
        }
    }

    class Tile(context: Context) : FrameLayout(context) {
        val poster = context.poster()
        val title = context.text("", 12f)
        val description = context.text("", 10f, Palette.muted)
        val heart = context.text("♡", 23f, Palette.green)
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Palette.green)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Palette.line)
        }
        init {
            isFocusable = true; isFocusableInTouchMode = true
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            background = context.focusBackground()
            val column = context.column()
            val artwork = FrameLayout(context)
            artwork.addView(ImageView(context).apply { setImageDrawable(Glyph("film", Color.rgb(82, 122, 96), context.dp(32))); scaleType = ImageView.ScaleType.CENTER; setBackgroundColor(Color.rgb(29, 51, 37)) }, LayoutParams(-1, -1))
            artwork.addView(poster, LayoutParams(-1, -1))
            artwork.addView(heart, LayoutParams(context.dp(28), context.dp(30), Gravity.TOP or Gravity.END).apply { rightMargin = context.dp(3) })
            artwork.addView(progress, LayoutParams(-1, context.dp(4), Gravity.BOTTOM))
            column.addView(artwork, LinearLayout.LayoutParams(-1, 0, 1f))
            title.maxLines = 2; title.ellipsize = android.text.TextUtils.TruncateAt.END; title.pad(5, 4)
            column.addView(title, LinearLayout.LayoutParams(-1, context.dp(38)))
            description.pad(5, 0); description.singleLineEllipsis()
            column.addView(description, LinearLayout.LayoutParams(-1, context.dp(16)))
            addView(column, LayoutParams(-1, -1))
            setOnFocusChangeListener { _, focused -> elevation = if (focused) context.dp(5).toFloat() else 0f }
        }
    }
}
