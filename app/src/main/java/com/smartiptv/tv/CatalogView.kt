package com.smartiptv.tv

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Future

class CatalogView(
    context: Context, private val db: LibraryDb, private val jobs: Jobs, private val accountKey: String,
    val images: ArtworkLoader, private val open: (Content) -> Unit, private val options: (Content) -> Unit,
    private val refresh: () -> Unit, private val logout: () -> Unit,
    private val beforeNavigate: () -> Unit = {},
    val mediaKind: String, private val back: () -> Unit,
) : LinearLayout(context) {
    companion object { const val START_TAB = "favorites" }
    val mediaTitle = when (mediaKind) { "live" -> "TV ao vivo"; "series" -> "Séries"; else -> "Filmes" }
    private val allTitle = when (mediaKind) { "live" -> "Todos os canais"; "series" -> "Todas as séries"; else -> "Todos os filmes" }
    private val navigation = listOf("favorites" to "Favoritos") + if (mediaKind == "live") emptyList() else listOf("continue" to "Continuar assistindo")
    val nav = linkedMapOf<String, Button>()
    var tab = START_TAB; private set
    private var category = ""
    private var alphabetical = false
    private var queryEpoch = 0
    private var restoreGrid: android.os.Parcelable? = null
    private var restoreCategory: android.os.Parcelable? = null
    private var focusAfterLoad = false
    private var pendingGridFocus: Int? = null
    private var focusCheckPosted = false
    private var lastGridPosition = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchTask: Runnable? = null
    private val queries = mutableListOf<Future<*>>()
    private var querySerial = 0
    private var catalogPending = false
    private val updateCatalog = Runnable {
        if (isShown && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) { catalogPending = false; reload() }
    }
    private val heading = context.text(mediaTitle, 25f, bold = true)
    private val subtitle = context.text("", 11f, Palette.muted)
    private val status = context.text("", 11f, Palette.green)
    private val main = context.column()
    private val body = FrameLayout(context)
    val search = context.input("Buscar no catálogo").apply { hint = "Buscar no catálogo…"; textSize = 12f; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
    val count = context.text("", 11f, Palette.muted)
    val order = context.button("Mais recentes") { alphabetical = !alphabetical; updateOrder(); loadGrid(); fillCategories() }
    private val gridPage = context.column()
    private val categories = RecyclerView(context)
    val grid = RecyclerView(context)
    private val empty = context.text("Seu catálogo aparecerá aqui", 17f, Palette.muted).apply { gravity = Gravity.CENTER; pad(20) }
    private val results = FrameLayout(context)
    private var adapter: CatalogAdapter
    private val settings = context.column()
    private val accountExpiration = context.text("Vencimento: —", 10f, Palette.muted)
    private val accountConnections = context.text("Conexões ativas: —", 10f, Palette.muted)
    val refreshButton = context.button("Atualizar conteúdos", symbol = "refresh", action = refresh)
    val nextButton = context.button("", action = { db.autoNext = !db.autoNext; updateAutoNext() })
    val logoutButton = context.button("Sair da conta", symbol = "back", action = logout)

    init {
        orientation = HORIZONTAL; setBackgroundColor(Palette.background)
        val sidebar = context.column().apply { setBackgroundColor(Palette.sidebar); pad(10, 10) }
        addView(sidebar, LayoutParams(context.dp(184), -1))
        sidebar.addView(context.text("SmartIPTV", 20f, bold = true).apply { setCompoundDrawablesWithIntrinsicBounds(Glyph("tv", Palette.green, context.dp(25)), null, null, null); compoundDrawablePadding = context.dp(7) })
        sidebar.gap(10)
        val backButton = context.button("Voltar", symbol = "back", action = back).apply { id = View.generateViewId(); gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        sidebar.addView(backButton, LayoutParams(-1, context.dp(32))); nav["back"] = backButton
        sidebar.gap(8)
        val navArea = context.column()
        navArea.addView(context.eyebrow("BIBLIOTECA")); navArea.gap(6)
        sidebar.addView(navArea)
        navigation.forEach { (key, title) ->
            val button = context.button(title, symbol = when (key) { "continue" -> "clock"; "favorites" -> "heart"; else -> key }) { navigate(key) }
            button.textSize = 12f; button.gravity = Gravity.START or Gravity.CENTER_VERTICAL; button.pad(10, 6)
            button.id = View.generateViewId()
            navArea.addView(button, LayoutParams(-1, context.dp(34)).apply { bottomMargin = context.dp(3) })
            nav[key] = button
        }
        sidebar.gap(6); sidebar.rule(); sidebar.gap(6); sidebar.addView(context.eyebrow("CATEGORIAS")); sidebar.gap(6)
        val all = context.button(allTitle) { images.switchScreen(); category = ""; navigate(mediaKind) }.apply { id = View.generateViewId(); textSize = 12f; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        sidebar.addView(all, LayoutParams(-1, context.dp(34))); nav[mediaKind] = all
        categories.layoutManager = LinearLayoutManager(context)
        categories.isFocusable = false; categories.itemAnimator = null; categories.setHasFixedSize(true)
        sidebar.addView(categories, LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(4); bottomMargin = context.dp(7) })
        val settingsButton = context.button("Configurações", symbol = "settings") { navigate("settings") }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 12f; id = View.generateViewId() }
        sidebar.rule(); sidebar.gap(7); sidebar.addView(settingsButton, LayoutParams(-1, context.dp(32))); nav["settings"] = settingsButton
        main.pad(21, 20); addView(main, LayoutParams(0, -1, 1f))
        val top = context.row()
        top.addView(heading, LayoutParams(0, -2, 1f))
        search.id = View.generateViewId()
        top.addView(search, LayoutParams(context.dp(225), context.dp(38)))
        main.addView(top); main.gap(10); main.addView(subtitle); main.gap(9)
        main.addView(status); main.gap(6); main.addView(body, LayoutParams(-1, 0, 1f))
        status.visibility = GONE
        body.addView(gridPage, FrameLayout.LayoutParams(-1, -1))
        val tools = context.row()
        tools.addView(count, LayoutParams(0, -2, 1f)); tools.addView(order, LayoutParams(-2, context.dp(36)))
        gridPage.addView(tools); gridPage.gap(10); gridPage.addView(results, LayoutParams(-1, 0, 1f))
        results.addView(grid, FrameLayout.LayoutParams(-1, -1)); results.addView(empty, FrameLayout.LayoutParams(-1, -1))
        grid.layoutManager = GridLayoutManager(context, 4)
        grid.itemAnimator = null; grid.setHasFixedSize(true); grid.clipToPadding = false; grid.setItemViewCacheSize(2)
        grid.setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(8))
        adapter = CatalogAdapter(images, open, options, ::loadPage)
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { adapter.reloadPosters() } })
        grid.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val manager = grid.layoutManager as GridLayoutManager
            val columns = ((right - left) / context.dp(128)).coerceIn(2, 8)
            if (manager.spanCount != columns) manager.spanCount = columns
            adapter.fitVisibleCards(); adapter.reloadPosters()
        }
        grid.viewTreeObserver.addOnGlobalLayoutListener {
            completeGridFocus()
            // A section reload may replace every child after its query callback.
            // Keep the focus request until a visible card actually accepts it.
            if (pendingGridFocus == null && focusAfterLoad && grid.isShown && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
                focusAfterLoad = !focusContent()
            }
        }
        body.addView(settings, FrameLayout.LayoutParams(-1, -1)); buildSettings()
        search.addTextChangedListener {
            searchTask?.let(handler::removeCallbacks)
            searchTask = Runnable { if (tab != "settings") render() }.also { handler.postDelayed(it, 300) }
        }
        search.setOnEditorActionListener { _, _, _ ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
            render(); grid.post { focusContent() }; true
        }
        nav.values.forEach { button ->
            button.nextFocusRightId = search.id
        }
        search.nextFocusLeftId = nav.getValue(START_TAB).id
        navigate(START_TAB)
    }

    fun showAccount(account: Account) {
        val date = account.expiration?.let { SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(it * 1000)) } ?: "Não informado"
        accountExpiration.text = "Vencimento: $date"
        accountConnections.text = "Conexões ativas: ${account.active} de ${account.maximum}"
    }
    fun message(text: String, error: Boolean = false) { status.text = text; status.setTextColor(if (error) Palette.error else Palette.green); status.visibility = if (text.isBlank()) GONE else VISIBLE }
    fun refreshing(value: Boolean) { refreshButton.isEnabled = !value; refreshButton.text = if (value) "Atualizando…" else "Atualizar conteúdos" }
    fun navigate(next: String) {
        if (next !in listOf(START_TAB, "continue", mediaKind, "settings") || (mediaKind == "live" && next == "continue")) return
        focusAfterLoad = false; pendingGridFocus = null; lastGridPosition = 0
        beforeNavigate()
        if (next != tab) {
            restoreGrid = null; restoreCategory = null
            queryEpoch++; images.switchScreen(); adapter.reset(); grid.recycledViewPool.clear()
            if (next != mediaKind) category = ""
        }
        tab = next
        nav.forEach { (key, button) ->
            val selected = key == tab && (key != mediaKind || category.isEmpty())
            button.background = context.focusBackground(selected = selected)
            button.setTextColor(if (selected) Palette.green else Palette.text)
        }
        heading.text = if (tab == "settings") "Configurações" else mediaTitle
        subtitle.text = when (tab) { "favorites" -> "Favoritos"; "continue" -> "Continuar assistindo"; "settings" -> "Personalize sua experiência no SmartIPTV."; else -> if (category.isEmpty()) allTitle else categoryName }
        search.hint = when (mediaKind) { "movie" -> "Buscar filmes…"; "series" -> "Buscar séries…"; else -> "Buscar canais…" }
        search.visibility = if (tab == "settings") GONE else VISIBLE
        search.nextFocusLeftId = nav.getValue(tab).id
        updateOrder(); render()
    }
    private var categoryName = ""
    private fun updateOrder() { order.text = if (alphabetical) "Nome A–Z" else if (tab == "continue") "Assistidos recentemente" else "Mais recentes" }
    private fun render() {
        settings.visibility = if (tab == "settings") VISIBLE else GONE
        gridPage.visibility = if (tab == "settings") GONE else VISIBLE
        if (tab == "settings") { cancelQueries(); updateAutoNext() } else loadGrid()
        fillCategories()
    }
    fun reload() {
        catalogPending = false
        if (tab == "favorites" && grid.hasFocus() && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) focusAfterLoad = true
        if (gridPage.visibility == VISIBLE && (grid.adapter?.itemCount ?: 0) > 0) {
            restoreGrid = grid.layoutManager?.onSaveInstanceState()
            restoreCategory = categories.layoutManager?.onSaveInstanceState()
        }
        render()
    }
    fun restoreAfterPlayback(focus: Boolean = true) {
        focusAfterLoad = focus && !nav.values.any { it.hasFocus() } && !search.hasFocus() && !order.hasFocus() && !categories.hasFocus()
        if (tab in listOf("continue", "favorites")) reload() else { refreshLibrary(); if (focusAfterLoad) focusAfterLoad = !focusContent() }
    }
    fun catalogUpdated(defer: Boolean = false) {
        catalogPending = true; handler.removeCallbacks(updateCatalog)
        if (!defer) handler.postDelayed(updateCatalog, 200)
    }
    fun refreshLibrary(restoreFocus: Boolean = false) {
        if (restoreFocus) focusAfterLoad = true
        if (catalogPending) { catalogPending = false; reload(); return }
        if (tab in listOf("continue", "favorites")) { reload(); return }
        val current = queryEpoch
        val adapters = listOf(adapter)
        val items = adapters.flatMap { it.cachedItems() }.distinctBy { it.uid }
        if (items.isEmpty()) return
        query(work = { db.cards(accountKey, Page(items, items.size)).cards.associateBy { it.item.uid } }, done = { values -> if (current == queryEpoch) adapters.forEach { it.updateIndicators(values) } })
    }
    private fun cancelQueries() { pendingGridFocus = null; lastGridPosition = 0; querySerial++; queries.forEach { it.cancel(false) }; queries.clear() }
    private fun <T> query(work: () -> T, done: (T) -> Unit, failed: (String) -> Unit = {}) {
        queries.removeAll { it.isDone }
        val serial = querySerial
        queries += jobs.run(work = work, done = { if (serial == querySerial) done(it) }, failed = { if (serial == querySerial) failed(it) })
    }
    override fun onDetachedFromWindow() {
        cancelQueries(); handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
    fun releasePosters() { images.switchScreen() }
    fun restorePosters() { adapter.reloadPosters() }
    fun focusContent(): Boolean {
        if (descendantFocusability == FOCUS_BLOCK_DESCENDANTS) return false
        if (tab == "settings") return nextButton.requestFocus()
        val card = if (grid.isShown) (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { it.isFocusable } else null
        return card?.requestFocus() ?: if (!focusAfterLoad || count.text.toString().startsWith("0 ")) search.requestFocus() else false
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            focusAfterLoad = false
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> FOCUS_DOWN
                else -> null
            }
            if (direction != null && moveFocus(direction)) return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun focusSidebar() { pendingGridFocus = null; nav[tab]?.requestFocus() }
    private fun focusToolbar(view: View) { pendingGridFocus = null; if (view.isShown) view.requestFocus() }
    private fun gridSections() = listOf(FocusSection(0, adapter.itemCount))
    private fun focusGrid(position: Int) {
        if (position !in 0 until (grid.adapter?.itemCount ?: 0)) return
        pendingGridFocus = position; lastGridPosition = position
        if (!completeGridFocus()) {
            grid.stopScroll()
            (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(position, 0)
            grid.post { completeGridFocus() }
        }
    }
    private fun completeGridFocus(): Boolean {
        val position = pendingGridFocus ?: return false
        if (!grid.isShown || grid.isLayoutRequested || grid.isComputingLayout || descendantFocusability == FOCUS_BLOCK_DESCENDANTS) return false
        val row = grid.findViewHolderForAdapterPosition(position)?.itemView ?: return false
        if (!row.isFocusable || !row.requestFocus()) return false
        // RecyclerView may restore its old focus later in this layout pass.
        // Confirm on the next frame before releasing the destination, and
        // recover it if attaching/rebinding a page temporarily focused Back.
        if (!focusCheckPosted) {
            focusCheckPosted = true
            grid.postOnAnimation {
                focusCheckPosted = false
                if (pendingGridFocus == position && row.hasFocus() && !grid.isLayoutRequested && !grid.isComputingLayout && grid.scrollState == RecyclerView.SCROLL_STATE_IDLE && !grid.hasPendingAdapterUpdates()) pendingGridFocus = null
                else if (pendingGridFocus != null) completeGridFocus()
            }
        }
        return true
    }
    private fun returnToGrid() {
        if (grid.isShown && (grid.adapter?.itemCount ?: 0) > 0) {
            val sections = gridSections()
            val position = lastGridPosition.takeIf { value -> sections.any { value >= it.first && value - it.first < it.count } } ?: sections.firstOrNull()?.first
            if (position != null) focusGrid(position)
        }
    }
    private fun moveFocus(direction: Int): Boolean {
        val focused = findFocus() ?: return false
        if (grid.hasFocus() || pendingGridFocus != null) {
            val holder = grid.findContainingViewHolder(focused)
            val position = pendingGridFocus ?: holder?.absoluteAdapterPosition ?: run { focusContent(); return true }
            if (position < 0) return true
            lastGridPosition = position
            val columns = (grid.layoutManager as GridLayoutManager).spanCount
            val target = GridFocus.target(position, columns, gridSections(), direction)
            if (target != null) {
                // Keep one explicit destination while the next row/page is
                // attached, so metadata updates cannot redirect focus outside
                // the catalog during an offscreen vertical move.
                focusGrid(target)
            }
            else when (direction) {
                FOCUS_LEFT -> { pendingGridFocus = null; focusSidebar() }
                FOCUS_RIGHT -> focusToolbar(if (order.isShown) order else search)
                FOCUS_UP -> focusToolbar(search)
                FOCUS_DOWN -> Unit
            }
            return true
        }
        if (search.hasFocus()) {
            // Once OK opens the keyboard, editing remains owned by the native input.
            if (search.showSoftInputOnFocus) return false
            when (direction) { FOCUS_LEFT -> focusSidebar(); FOCUS_DOWN -> if (order.isShown) focusToolbar(order) else focusContent() }
            return true
        }
        if (order.hasFocus()) {
            when (direction) { FOCUS_UP -> focusToolbar(search); FOCUS_LEFT -> if (grid.isShown) returnToGrid() else focusSidebar(); FOCUS_DOWN -> returnToGrid() }
            return true
        }
        val sidebar = nav.values.toList()
        val navIndex = sidebar.indexOfFirst { it.hasFocus() }
        if (navIndex >= 0) {
            pendingGridFocus = null
            when (direction) {
                FOCUS_RIGHT -> focusContent()
                FOCUS_UP -> if (focused === nav["settings"] && categories.childCount > 0) categories.getChildAt(categories.childCount - 1).requestFocus() else sidebar.getOrNull(navIndex - 1)?.requestFocus()
                FOCUS_DOWN -> if (focused === nav[mediaKind] && categories.childCount > 0) categories.getChildAt(0).requestFocus() else sidebar.getOrNull(navIndex + 1)?.requestFocus()
            }
            return true
        }
        if (categories.hasFocus()) {
            val position = categories.findContainingViewHolder(focused)?.absoluteAdapterPosition ?: return true
            when (direction) {
                FOCUS_LEFT -> Unit
                FOCUS_RIGHT -> returnToGrid()
                FOCUS_UP -> if (position == 0) nav[mediaKind]?.requestFocus() else return false
                FOCUS_DOWN -> if (position + 1 < (categories.adapter?.itemCount ?: 0)) return false else nav["settings"]?.requestFocus()
            }
            return true
        }
        if (settings.hasFocus()) {
            val buttons = listOf(nextButton, refreshButton, logoutButton)
            val index = buttons.indexOfFirst { it.hasFocus() }
            when (direction) { FOCUS_LEFT -> focusSidebar(); FOCUS_UP -> buttons.getOrNull(index - 1)?.requestFocus(); FOCUS_DOWN -> buttons.getOrNull(index + 1)?.requestFocus() }
            return true
        }
        return false
    }
    private fun cards(page: Page): CardPage = db.cards(accountKey, page)
    private fun loadPage(offset: Int, limit: Int, done: (CardPage) -> Unit) {
        val current = queryEpoch
        val kind = tab
        val selected = category; val term = search.text.toString(); val alpha = alphabetical
        query(work = { cards(db.page(accountKey, kind, selected, term, alpha, offset, limit, favoriteKind = mediaKind)) }, done = { if (current == queryEpoch) done(it) }, failed = { if (current == queryEpoch) message(it, true) })
    }
    private fun loadGrid() {
        if (grid.adapter !== adapter) grid.adapter = adapter
        cancelQueries()
        queryEpoch++
        val current = queryEpoch
        adapter.reset(); count.text = "Carregando…"; empty.text = "Carregando conteúdos…"; empty.visibility = VISIBLE; grid.visibility = INVISIBLE
        loadPage(0, CatalogAdapter.PAGE_SIZE) { page ->
            if (queryEpoch != current) return@loadPage
            adapter.reset(page, mediaKind == "live"); count.text = "${page.total} conteúdos"
            restoreGrid?.let { grid.layoutManager?.onRestoreInstanceState(it) }; restoreGrid = null
            empty.text = if (search.text.isNotBlank()) "Nenhum resultado encontrado" else if (tab == "continue") "Nenhum conteúdo em andamento" else if (tab == "favorites") "Seus favoritos ficam aqui" else "Nenhum conteúdo disponível"
            empty.visibility = if (page.total == 0) VISIBLE else GONE; grid.visibility = if (page.total == 0) INVISIBLE else VISIBLE
            if (focusAfterLoad) grid.post { if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) focusAfterLoad = !focusContent() }
        }
    }
    private fun fillCategories() {
        val current = queryEpoch
        query(work = { db.categories(accountKey, mediaKind) }, done = { groups ->
            if (current != queryEpoch) return@query
            val focused = categories.hasFocus()
            val state = restoreCategory ?: categories.layoutManager?.onSaveInstanceState()
            categories.adapter = CategoryAdapter(groups, { if (tab == mediaKind) category else "" }) { selected ->
                val term = search.text.toString()
                images.switchScreen(); category = selected; categoryName = groups.firstOrNull { it.id == selected }?.name.orEmpty()
                navigate(mediaKind)
                if (search.text.toString() != term) search.setText(term)
            }
            state?.let { categories.layoutManager?.onRestoreInstanceState(it) }; restoreCategory = null
            if (focused) categories.post { categories.getChildAt(0)?.requestFocus() }
        })
    }
    private fun buildSettings() {
        val card = context.column().apply { background = shape(Palette.panel, context.dp(12).toFloat()); pad(20); gravity = Gravity.TOP }
        card.addView(context.text("Reprodução", 19f, bold = true)); card.gap(11)
        card.addView(context.text("Escolha se a série deve continuar automaticamente ao terminar um episódio.", 12f, Palette.muted)); card.gap(14)
        card.addView(nextButton, LayoutParams(-1, context.dp(43))); card.gap(18); card.rule(); card.gap(18)
        card.addView(context.text("Conta e catálogo", 19f, bold = true)); card.gap(13)
        card.addView(accountExpiration); card.gap(8); card.addView(accountConnections); card.gap(8)
        card.addView(context.text("SmartIPTV · v${BuildConfig.VERSION_NAME}", 10f, Palette.muted)); card.gap(14)
        card.addView(refreshButton, LayoutParams(-1, context.dp(43))); card.gap(12)
        card.addView(logoutButton, LayoutParams(-1, context.dp(43)))
        val scroll = ScrollView(context); scroll.addView(card); settings.addView(scroll, LayoutParams(-1, -1))
    }
    private fun updateAutoNext() { nextButton.text = "Reproduzir próximo episódio: ${if (db.autoNext) "Ligado" else "Desligado"}" }

    private inner class CategoryAdapter(val groups: List<Category>, val selected: () -> String, val choose: (String) -> Unit) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        inner class Holder(val button: Button) : RecyclerView.ViewHolder(button)
        override fun getItemCount() = groups.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(context.button("") {}.apply { textSize = 11f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 2; layoutParams = RecyclerView.LayoutParams(-1, context.dp(43)).apply { bottomMargin = context.dp(5) } })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val group = groups[position]; holder.button.text = group.name; holder.button.contentDescription = group.name
            holder.button.background = context.focusBackground(selected = group.id == selected()); holder.button.setOnClickListener { choose(group.id) }
        }
    }
}
