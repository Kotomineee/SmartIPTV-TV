package com.smartiptv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Future

class DetailView(
    context: Context, val item: Content, private val api: XtreamApi, private val db: LibraryDb,
    private val jobs: Jobs, private val images: ArtworkLoader, backgroundView: View,
    private val close: () -> Unit, private val play: (Content, Boolean, List<Content>) -> Unit,
    private val returnEpisode: Content? = null, private val cachedSeries: SeriesInfo? = null,
    private val cachedMoviePlot: String? = null,
) : FrameLayout(context) {
    private val account = api.credentials.account
    private val panel = context.column().apply { pad(22, 18); background = shape(Palette.panel, context.dp(13).toFloat(), Color.rgb(66, 102, 83)); isClickable = true }
    private val poster = context.poster()
    private val plot = context.text(item.plot.ifBlank { "Carregando informações…" }, 12f, Color.rgb(178, 196, 187))
    private val caption = context.text(listOf(item.year, item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "Nota $it" } ?: "").filter { it.isNotBlank() }.joinToString(" · "), 10f, Palette.muted)
    private val heading = context.text(item.name, if (item.kind == "series") 22f else 24f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    val favorite = context.button("Adicionar aos favoritos", symbol = "heart") { toggleFavorite() }
    val watch = context.button(if (item.kind == "series") "Assistir episódio" else "Assistir agora", true, "play") { selected()?.let { play(it, false, episodes) } }
    private val feedback = context.text("", 11f, Palette.muted)
    val movieResume = context.text("", 11f, Palette.green).apply { visibility = GONE }
    private var moviePlot: String? = null
    private val retry = context.button("Carregar novamente") { load() }
    val seasons = SeasonFlow(context)
    val episodeList = RecyclerView(context)
    private var episodes = emptyList<Content>()
    private var episodeStates = emptyMap<String, EpisodeState>()
    private var seriesInfo: SeriesInfo? = null
    private var focusEpisodeUid: String? = returnEpisode?.uid
    private var selectedEpisode: Content? = null
    private var checkpoint: Progress? = null
    private var season = 0
    private var future: Future<*>? = null
    private var blurJob: Future<*>? = null
    private var dismissed = false
    private var blurred: Bitmap? = null
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val closeRow = context.row()

    init {
        isClickable = true; isFocusable = true
        addView(backdrop, LayoutParams(-1, -1))
        addView(View(context).apply { setBackgroundColor(0xa6000000.toInt()) }, LayoutParams(-1, -1))
        setOnClickListener { close() }
        addView(panel, LayoutParams(minOf(context.dp(800), resources.displayMetrics.widthPixels - context.dp(48)), if (item.kind == "series") resources.displayMetrics.heightPixels - context.dp(32) else -2, Gravity.CENTER))
        closeRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        closeRow.addView(context.button("", symbol = "close", action = close).apply { contentDescription = "Fechar detalhes" }, LinearLayout.LayoutParams(context.dp(32), context.dp(32)))
        panel.addView(closeRow); panel.gap(7)
        val top = context.row().apply { gravity = Gravity.TOP }
        val series = item.kind == "series"
        top.addView(poster, LinearLayout.LayoutParams(context.dp(if (series) 108 else 143), context.dp(if (series) 151 else if (item.kind == "live") 115 else 204)).apply { rightMargin = context.dp(20) })
        val info = context.column()
        top.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(context.eyebrow(when (item.kind) { "series" -> "SÉRIE"; "live" -> "TV AO VIVO"; else -> "FILME" })); info.gap(9)
        info.addView(heading); info.gap(8)
        info.addView(caption); info.gap(10)
        plot.maxLines = if (series) 3 else 5; plot.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(plot)
        info.addView(movieResume, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(7) })
        info.gap(11)
        val actions = context.row()
        actions.addView(watch, LinearLayout.LayoutParams(-2, context.dp(40)).apply { rightMargin = context.dp(8) })
        actions.addView(favorite, LinearLayout.LayoutParams(-2, context.dp(40)))
        info.addView(actions)
        val secondary = context.row()
        info.addView(secondary)
        panel.addView(top); panel.gap(12)
        panel.addView(feedback); panel.addView(retry, LinearLayout.LayoutParams(-2, context.dp(38))); retry.visibility = GONE
        if (series) {
            panel.addView(seasons, LinearLayout.LayoutParams(-1, -2)); panel.gap(8)
            episodeList.layoutManager = LinearLayoutManager(context); episodeList.itemAnimator = null
            panel.addView(episodeList, LinearLayout.LayoutParams(-1, 0, 1f)); watch.isEnabled = false
        }
        jobs.run(work = { Triple(db.isFavorite(account, item), db.checkpoint(account, item), if (series) db.episodeStates(account, item.id) else emptyMap()) }, done = { (active, progress, states) ->
            if (dismissed) return@run
            favorite.text = if (active) "Remover dos favoritos" else "Adicionar aos favoritos"
            checkpoint = progress
            if (item.kind == "movie") {
                movieResume.text = progress?.let { "Parou em ${stamp(it.position)}" }.orEmpty()
                movieResume.visibility = if (progress != null) VISIBLE else GONE
            }
            episodeStates = states
            (episodeList.adapter as? EpisodeAdapter)?.let { adapter -> episodeList.post { if (!dismissed && episodeList.adapter === adapter) adapter.notifyItemRangeChanged(0, adapter.itemCount) } }
            if (progress != null) {
                secondary.addView(context.button(if (series) "Continuar episódio" else "Assistir do início") { play(if (series) progress.item else item, !series, episodes) }, LinearLayout.LayoutParams(-2, context.dp(40)).apply { topMargin = context.dp(8) })
                if (!series) watch.text = "Continuar assistindo"
                else if (returnEpisode == null && episodes.isNotEmpty()) selectSeason(progress.item.season, progress.item.uid)
            }
        })
        post { if (!dismissed) images.load(item.artwork, poster) }
        val width = 320
        if (backgroundView.width > 0 && backgroundView.height > 0) {
            val height = (width.toFloat() * backgroundView.height / backgroundView.width).toInt().coerceAtLeast(1)
            val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(snapshot).apply { scale(width.toFloat() / backgroundView.width, height.toFloat() / backgroundView.height); backgroundView.draw(this) }
            blurJob = jobs.run(work = { blur(snapshot) }, done = { bitmap -> if (!dismissed) { blurred = bitmap; backdrop.setImageBitmap(bitmap) } })
        }
        if (series && cachedSeries != null) showSeries(cachedSeries)
        else if (item.kind == "movie" && cachedMoviePlot != null) showMovie(cachedMoviePlot)
        else load()
        if (returnEpisode == null) post { if (!dismissed) watch.requestFocus() }
    }

    private fun selected(): Content? = if (item.kind == "series") selectedEpisode else item
    private fun toggleFavorite() {
        favorite.isEnabled = false
        jobs.run(work = { db.toggleFavorite(account, item) }, done = { active -> if (!dismissed) { favorite.isEnabled = true; favorite.text = if (active) "Remover dos favoritos" else "Adicionar aos favoritos" } }, failed = { if (!dismissed) { favorite.isEnabled = true; feedback.text = "Não foi possível salvar o favorito neste aparelho."; feedback.visibility = VISIBLE } })
    }
    private fun load() {
        future?.cancel(true); retry.visibility = GONE
        if (item.kind == "live") { plot.text = "Transmissão ao vivo do seu provedor."; feedback.visibility = GONE; return }
        feedback.visibility = if (item.kind == "series") VISIBLE else GONE
        feedback.text = "Carregando temporadas e episódios…"
        if (item.kind == "series") future = jobs.run(network = true, work = { api.seriesInfo(item) }, done = ::showSeries, failed = ::failed)
        else future = jobs.run(network = true, work = { api.movieInfo(item) }, done = ::showMovie, failed = { showMovie(item.plot) })
    }
    fun moviePlotSnapshot(): String? = moviePlot
    private fun showMovie(value: String) {
        if (!dismissed) { moviePlot = value; plot.text = value.ifBlank { "Este título não possui sinopse informada pelo provedor." } }
    }
    fun seriesSnapshot(): SeriesInfo? = seriesInfo
    private fun showSeries(result: SeriesInfo) {
        if (dismissed) return
        seriesInfo = result; episodes = result.episodes
        plot.text = result.plot.ifBlank { "Sinopse não informada pelo provedor." }
        val values = episodes.map { it.season }.distinct().sorted()
        caption.text = listOf(item.year, "${values.size} temporada${if (values.size == 1) "" else "s"}").filter { it.isNotBlank() }.joinToString(" · ")
        seasons.removeAllViews()
        values.forEach { number ->
            val button = context.button("T$number") { selectSeason(number) }.apply { contentDescription = "Temporada $number"; tag = number; minHeight = context.dp(32); minimumHeight = context.dp(32); pad(11, 5) }
            seasons.addView(button, ViewGroup.LayoutParams(-2, context.dp(32)))
        }
        watch.isEnabled = episodes.isNotEmpty()
        feedback.visibility = if (episodes.isEmpty()) VISIBLE else GONE
        feedback.text = if (episodes.isEmpty()) "O provedor ainda não disponibilizou episódios para esta série." else ""
        val target = returnEpisode ?: checkpoint?.item
        if (values.isNotEmpty()) selectSeason(target?.season?.takeIf { it in values } ?: values.first(), target?.uid)
        if (returnEpisode == null && (findFocus() == null || findFocus() === this)) watch.requestFocus()
    }
    private fun failed(message: String) { if (!dismissed) { feedback.text = message; feedback.visibility = VISIBLE; retry.visibility = VISIBLE; retry.requestFocus() } }
    private fun selectSeason(number: Int, selectedUid: String? = null) {
        season = number
        for (index in 0 until seasons.childCount) {
            val button = seasons.getChildAt(index) as Button
            button.background = context.focusBackground(primary = button.tag == number)
            button.setTextColor(if (button.tag == number) Palette.background else Palette.text)
        }
        val list = episodes.filter { it.season == season }
        selectedEpisode = list.firstOrNull { it.uid == selectedUid } ?: list.firstOrNull()
        episodeList.adapter = EpisodeAdapter(list)
        val selectedIndex = list.indexOfFirst { it.uid == selectedEpisode?.uid }
        if (selectedIndex >= 0) episodeList.scrollToPosition(selectedIndex)
    }
    fun dismiss() {
        dismissed = true; future?.cancel(true); blurJob?.cancel(false); api.cancelDetails()
        // HWUI may still own the last display list. Release references; do not recycle a displayed bitmap.
        images.clear(poster); backdrop.setImageDrawable(null); blurred = null
    }
    fun restoreArtwork() { if (!dismissed) images.load(item.artwork, poster) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        // Size the children before measuring them, including the first frame after a display change.
        if (w > 0 && h > 0) (panel.layoutParams as LayoutParams).apply {
            width = minOf(context.dp(800), w - context.dp(48)); height = if (item.kind == "series") h - context.dp(32) else -2
        }
        val compact = h < context.dp(450)
        val closeVisibility = if (compact) GONE else VISIBLE
        if (closeRow.visibility != closeVisibility) closeRow.visibility = closeVisibility
        val horizontal = if (compact) 18 else 22; val vertical = if (compact) 12 else 18
        if (panel.paddingLeft != context.dp(horizontal) || panel.paddingTop != context.dp(vertical)) panel.pad(horizontal, vertical)
        val compactSeries = compact && item.kind == "series"
        val titleLines = if (compactSeries) 1 else 2
        if (heading.maxLines != titleLines) heading.maxLines = titleLines
        val titleSize = if (compactSeries) 18f else if (item.kind == "series") 22f else 24f
        if (kotlin.math.abs(heading.textSize - titleSize * resources.displayMetrics.scaledDensity) > .1f) heading.textSize = titleSize
        val plotLines = if (compactSeries) 1 else if (compact) 2 else if (item.kind == "series") 3 else 5
        if (plot.maxLines != plotLines) plot.maxLines = plotLines
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private inner class EpisodeAdapter(val list: List<Content>) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(val row: EpisodeRow) : RecyclerView.ViewHolder(row)
        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(EpisodeRow(context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, context.dp(52)).apply { bottomMargin = context.dp(4) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = list[position]
            val state = episodeStates[episode.uid]
            val completed = state?.watched == true
            val resume = state?.resumeText.orEmpty()
            holder.row.title.text = "${episode.episode}     ${episode.name}"
            holder.row.progress.text = resume
            holder.row.progress.visibility = if (resume.isNotEmpty()) VISIBLE else GONE
            holder.row.badge.visibility = if (completed) VISIBLE else GONE
            holder.row.tag = episode.uid
            holder.row.contentDescription = "Episódio ${episode.episode}: ${episode.name}" + (if (completed) ", Assistido" else "") + (if (resume.isNotEmpty()) ", $resume" else "")
            holder.row.setOnFocusChangeListener { _, focused -> if (focused) selectedEpisode = episode }
            holder.row.setOnClickListener { play(episode, false, episodes) }
            if (focusEpisodeUid == episode.uid) holder.row.post {
                if (!dismissed && focusEpisodeUid == episode.uid && holder.row.tag == episode.uid && holder.row.isAttachedToWindow) {
                    focusEpisodeUid = null; holder.row.requestFocus()
                }
            }
        }
    }

    class EpisodeRow(context: Context) : FrameLayout(context) {
        val title = context.text("", 12f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        val progress = context.text("", 10f, Palette.muted).apply { visibility = GONE; maxLines = 1 }
        val badge = context.text("Assistido", 10f, Palette.green, true).apply {
            pad(8, 4); background = shape(Palette.selected, context.dp(10).toFloat(), width = 0)
            setCompoundDrawablesWithIntrinsicBounds(Glyph("check", Palette.green, context.dp(13)), null, null, null)
            compoundDrawablePadding = context.dp(5); visibility = GONE
        }
        init {
            isFocusable = true; isFocusableInTouchMode = true; background = context.focusBackground(); pad(13, 5)
            val line = context.row()
            val labels = context.column().apply { addView(title); addView(progress) }
            line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(10) })
            addView(line, LayoutParams(-1, -1))
        }
    }
}

class SeasonFlow(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val gap = context.dp(7)
        var x = 0; var y = 0; var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + child.measuredWidth > width) { x = 0; y += row + gap; row = 0 }
            x += child.measuredWidth + gap; row = maxOf(row, child.measuredHeight)
        }
        setMeasuredDimension(width, y + row)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = context.dp(7)
        var x = 0; var y = 0; var row = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > width) { x = 0; y += row + gap; row = 0 }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap; row = maxOf(row, child.measuredHeight)
        }
    }
}

/** One small snapshot, blurred off the UI thread, rather than a continuous effect. */
private fun blur(bitmap: Bitmap): Bitmap {
    val width = bitmap.width; val height = bitmap.height
    var source = IntArray(width * height); var target = IntArray(source.size)
    bitmap.getPixels(source, 0, width, 0, 0, width, height)
    repeat(2) {
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (offset in -3..3) {
                val pixel = source[y * width + (x + offset).coerceIn(0, width - 1)]
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
            }
            target[y * width + x] = Color.rgb(red / count, green / count, blue / count)
        }
        val swap = source; source = target; target = swap
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0
            for (offset in -3..3) {
                val pixel = source[(y + offset).coerceIn(0, height - 1) * width + x]
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
            }
            target[y * width + x] = Color.rgb(red / 7, green / 7, blue / 7)
        }
        val temp = source; source = target; target = temp
    }
    bitmap.setPixels(source, 0, width, 0, 0, width, height)
    return bitmap
}
