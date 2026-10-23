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
) : FrameLayout(context) {
    private val account = api.credentials.account
    private val panel = context.column().apply { pad(22, 18); background = shape(Palette.panel, context.dp(13).toFloat(), Color.rgb(66, 102, 83)); isClickable = true }
    private val poster = context.poster()
    private val plot = context.text(item.plot.ifBlank { "Carregando informações…" }, 12f, Color.rgb(178, 196, 187))
    private val caption = context.text(item.year, 10f, Palette.muted)
    val favorite = context.button("Adicionar aos favoritos", symbol = "heart") { toggleFavorite() }
    val watch = context.button(if (item.kind == "series") "Assistir episódio" else "Assistir agora", true, "play") { selected()?.let { play(it, false, episodes) } }
    private val feedback = context.text("", 11f, Palette.muted)
    private val retry = context.button("Carregar novamente") { load() }
    val seasons = SeasonFlow(context)
    val episodeList = RecyclerView(context)
    private var episodes = emptyList<Content>()
    private var selectedEpisode: Content? = null
    private var checkpoint: Progress? = null
    private var season = 0
    private var future: Future<*>? = null
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
        info.addView(context.text(item.name, if (series) 22f else 24f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }); info.gap(8)
        info.addView(caption); info.gap(10)
        plot.maxLines = if (series) 3 else 5; plot.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(plot); info.gap(11)
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
        jobs.run(work = { db.isFavorite(account, item) to db.checkpoint(account, item) }, done = { (active, progress) ->
            if (dismissed) return@run
            favorite.text = if (active) "Remover dos favoritos" else "Adicionar aos favoritos"
            checkpoint = progress
            if (progress != null) {
                secondary.addView(context.button(if (series) "Continuar episódio" else "Assistir do início") { play(if (series) progress.item else item, !series, episodes) }, LinearLayout.LayoutParams(-2, context.dp(36)).apply { topMargin = context.dp(8) })
                if (!series) watch.text = "Continuar assistindo"
                else if (episodes.isNotEmpty()) selectSeason(progress.item.season, progress.item.uid)
            }
        })
        images.load(item.artwork, poster)
        post { if (!dismissed) images.load(item.artwork, poster) }
        val width = 320
        if (backgroundView.width > 0 && backgroundView.height > 0) {
            val height = (width.toFloat() * backgroundView.height / backgroundView.width).toInt().coerceAtLeast(1)
            val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(snapshot).apply { scale(width.toFloat() / backgroundView.width, height.toFloat() / backgroundView.height); backgroundView.draw(this) }
            jobs.run(work = { blur(snapshot) }, done = { bitmap -> if (!dismissed) { blurred = bitmap; backdrop.setImageBitmap(bitmap) } else bitmap.recycle() })
        }
        load()
        post { watch.requestFocus() }
    }

    private fun selected(): Content? = if (item.kind == "series") selectedEpisode else item
    private fun toggleFavorite() {
        jobs.run(work = { db.toggleFavorite(account, item) }, done = { active -> if (!dismissed) favorite.text = if (active) "Remover dos favoritos" else "Adicionar aos favoritos" }, failed = { if (!dismissed) { feedback.text = "Não foi possível salvar o favorito neste aparelho."; feedback.visibility = VISIBLE } })
    }
    private fun load() {
        future?.cancel(true); retry.visibility = GONE
        if (item.kind == "live") { plot.text = "Transmissão ao vivo do seu provedor."; feedback.visibility = GONE; return }
        feedback.visibility = if (item.kind == "series") VISIBLE else GONE
        feedback.text = "Carregando temporadas e episódios…"
        if (item.kind == "series") future = jobs.run(work = { api.seriesInfo(item) }, done = { result ->
            if (dismissed) return@run
            episodes = result.episodes
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
            if (values.isNotEmpty()) selectSeason(checkpoint?.item?.season?.takeIf { it in values } ?: values.first(), checkpoint?.item?.uid)
            if (findFocus() == null || findFocus() === this) watch.requestFocus()
        }, failed = ::failed)
        else future = jobs.run(work = { api.movieInfo(item) }, done = { if (!dismissed) plot.text = it.ifBlank { "Este título não possui sinopse informada pelo provedor." } }, failed = {
            if (!dismissed) plot.text = item.plot.ifBlank { "Informações adicionais indisponíveis. Você pode tentar reproduzir o conteúdo." }
        })
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
        dismissed = true; future?.cancel(true); api.cancelDetails()
        images.clear(poster); backdrop.setImageDrawable(null); blurred?.recycle(); blurred = null
    }
    fun restoreArtwork() { if (!dismissed) images.load(item.artwork, poster) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) panel.layoutParams = (panel.layoutParams as LayoutParams).apply {
            width = minOf(context.dp(800), w - context.dp(48)); height = if (item.kind == "series") h - context.dp(32) else -2
        }
        val compact = h < context.dp(450)
        closeRow.visibility = if (compact) GONE else VISIBLE
        panel.pad(if (compact) 18 else 22, if (compact) 12 else 18)
        plot.maxLines = if (compact) 2 else if (item.kind == "series") 3 else 5
    }
    private inner class EpisodeAdapter(val list: List<Content>) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
        inner class Holder(val button: Button) : RecyclerView.ViewHolder(button)
        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(context.button("") {}.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 12f; maxLines = 2
            layoutParams = RecyclerView.LayoutParams(-1, context.dp(43)).apply { bottomMargin = context.dp(4) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val episode = list[position]
            holder.button.text = "${episode.episode}     ${episode.name}"
            holder.button.contentDescription = "Episódio ${episode.episode}: ${episode.name}"
            holder.button.setOnFocusChangeListener { _, focused -> if (focused) selectedEpisode = episode }
            holder.button.setOnClickListener { play(episode, false, episodes) }
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
