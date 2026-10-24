package com.smartiptv.tv

import android.content.Context
import android.graphics.Color
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
import androidx.recyclerview.widget.ConcatAdapter
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
) : LinearLayout(context) {
    companion object { const val START_TAB = "favorites" }
    private val navigation = listOf("favorites" to "Favoritos", "live" to "TV ao vivo", "movie" to "Filmes", "series" to "Séries", "continue" to "Continuar assistindo", "home" to "Novidades")
    val nav = linkedMapOf<String, Button>()
    var tab = START_TAB; private set
    private var category = ""
    private var alphabetical = false
    private var queryEpoch = 0
    private var restoreGrid: android.os.Parcelable? = null
    private var restoreCategory: android.os.Parcelable? = null
    private var restoreHomeY = 0
    private var restoreHomeRows = emptyList<android.os.Parcelable?>()
    private var focusAfterLoad = false
    private var pendingGridFocus: Int? = null
    private var lastGridPosition = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchTask: Runnable? = null
    private val queries = mutableListOf<Future<*>>()
    private var querySerial = 0
    private var catalogPending = false
    private val updateCatalog = Runnable {
        if (isShown && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) { catalogPending = false; reload() }
    }
    private val heading = context.text("Favoritos", 25f, bold = true)
    private val subtitle = context.text("", 11f, Palette.muted)
    private val status = context.text("", 11f, Palette.green)
    private val main = context.column()
    private val body = FrameLayout(context)
    val search = context.input("Buscar no catálogo").apply { hint = "Buscar no catálogo…"; textSize = 12f; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
    val count = context.text("", 11f, Palette.muted)
    val order = context.button("Mais recentes") { alphabetical = !alphabetical; updateOrder(); if (tab == "favorites") loadFavorites() else { loadGrid(); fillCategories() } }
    private val gridPage = context.column()
    private val categoryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val categories = RecyclerView(context)
    val grid = RecyclerView(context)
    private val empty = context.text("Seu catálogo aparecerá aqui", 17f, Palette.muted).apply { gravity = Gravity.CENTER; pad(20) }
    private val results = FrameLayout(context)
    private var adapter: CatalogAdapter
    private data class FavoriteSection(val kind: String, val header: FavoriteHeaderAdapter, val cards: CatalogAdapter)
    private val favoriteSections = mutableListOf<FavoriteSection>()
    private val home = ScrollView(context).apply { isFillViewport = false }
    private val homeBody = context.column()
    private val homeRows = mutableListOf<Pair<RecyclerView, CatalogAdapter>>()
    private val homeLinks = mutableListOf<Button>()
    private val settings = context.column()
    private val accountExpiration = context.text("Vencimento: —", 10f, Palette.muted)
    private val accountConnections = context.text("Conexões ativas: —", 10f, Palette.muted)
    val refreshButton = context.button("Atualizar conteúdos", symbol = "refresh", action = refresh)
    val nextButton = context.button("", action = { db.autoNext = !db.autoNext; updateAutoNext() })
    val logoutButton = context.button("Sair da conta", symbol = "back", action = logout)

    init {
        orientation = HORIZONTAL; setBackgroundColor(Palette.background)
        val sidebar = context.column().apply { setBackgroundColor(Palette.sidebar); pad(10, 19) }
        addView(sidebar, LayoutParams(context.dp(172), -1))
        sidebar.addView(context.text("SmartIPTV", 20f, bold = true).apply { setCompoundDrawablesWithIntrinsicBounds(Glyph("tv", Palette.green, context.dp(25)), null, null, null); compoundDrawablePadding = context.dp(7) })
        sidebar.gap(20)
        val navScroll = ScrollView(context)
        val navArea = context.column()
        navArea.addView(context.eyebrow("SUA BIBLIOTECA")); navArea.gap(10)
        navScroll.addView(navArea, FrameLayout.LayoutParams(-1, -2))
        sidebar.addView(navScroll, LayoutParams(-1, 0, 1f))
        navigation.forEach { (key, title) ->
            val button = context.button(title, symbol = when (key) { "continue" -> "clock"; "favorites" -> "heart"; else -> key }) { navigate(key) }
            button.textSize = 12f; button.gravity = Gravity.START or Gravity.CENTER_VERTICAL; button.pad(10, 6)
            button.id = View.generateViewId()
            navArea.addView(button, LayoutParams(-1, context.dp(39)).apply { bottomMargin = context.dp(3) })
            nav[key] = button
        }
        val settingsButton = context.button("Configurações", symbol = "settings") { navigate("settings") }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 12f; id = View.generateViewId() }
        sidebar.addView(settingsButton, LayoutParams(-1, context.dp(40))); nav["settings"] = settingsButton
        sidebar.gap(11); sidebar.rule(); sidebar.gap(12); sidebar.addView(context.eyebrow("SUA CONTA")); sidebar.gap(9)
        sidebar.addView(accountExpiration); sidebar.gap(6); sidebar.addView(accountConnections); sidebar.gap(8)
        sidebar.addView(context.text("SmartIPTV · v${BuildConfig.VERSION_NAME}", 9f, Palette.muted))
        main.pad(21, 20); addView(main, LayoutParams(0, -1, 1f))
        val top = context.row()
        top.addView(heading, LayoutParams(0, -2, 1f))
        search.id = View.generateViewId()
        top.addView(search, LayoutParams(context.dp(225), context.dp(38)))
        main.addView(top); main.gap(10); main.addView(subtitle); main.gap(9)
        main.addView(status); main.gap(6); main.addView(body, LayoutParams(-1, 0, 1f))
        status.visibility = GONE
        body.addView(home, FrameLayout.LayoutParams(-1, -1)); home.addView(homeBody, FrameLayout.LayoutParams(-1, -2))
        body.addView(gridPage, FrameLayout.LayoutParams(-1, -1))
        val tools = context.row()
        tools.addView(count, LayoutParams(0, -2, 1f)); tools.addView(order, LayoutParams(-2, context.dp(36)))
        gridPage.addView(tools); gridPage.gap(10); gridPage.addView(categoryRow, LayoutParams(-1, 0, 1f))
        categories.layoutManager = LinearLayoutManager(context)
        categories.isFocusable = false; categories.setHasFixedSize(true)
        categoryRow.addView(categories, LayoutParams(context.dp(151), -1).apply { rightMargin = context.dp(12) })
        categoryRow.addView(results, LayoutParams(0, -1, 1f))
        results.addView(grid, FrameLayout.LayoutParams(-1, -1)); results.addView(empty, FrameLayout.LayoutParams(-1, -1))
        grid.layoutManager = GridLayoutManager(context, 4).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    if (tab == "favorites") {
                        var offset = 0
                        for (section in favoriteSections) {
                            if (position == offset) return spanCount
                            offset += 1 + section.cards.itemCount
                        }
                    }
                    return 1
                }
            }
        }
        grid.itemAnimator = null; grid.setHasFixedSize(true); grid.clipToPadding = false; grid.setItemViewCacheSize(2)
        grid.isFocusable = true; grid.isFocusableInTouchMode = true
        grid.setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(8))
        adapter = CatalogAdapter(images, open, options, ::loadPage)
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { adapter.reloadPosters(); favoriteSections.forEach { it.cards.reloadPosters() } } })
        grid.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val manager = grid.layoutManager as GridLayoutManager
            val columns = ((right - left) / context.dp(128)).coerceIn(2, 8)
            if (manager.spanCount != columns) manager.spanCount = columns
            adapter.reloadPosters()
            favoriteSections.forEach { it.cards.reloadPosters() }
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
        home.setOnScrollChangeListener { _, _, _, _, _ -> homeRows.forEach { it.second.reloadPosters() } }
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
        focusAfterLoad = false
        pendingGridFocus = null; lastGridPosition = 0
        beforeNavigate()
        if (next != tab) {
            restoreGrid = null; restoreCategory = null; restoreHomeY = 0; restoreHomeRows = emptyList()
            queryEpoch++; images.switchScreen(); adapter.reset()
            homeRows.forEach { it.first.adapter = null; it.second.reset() }; homeRows.clear(); homeLinks.clear(); homeBody.removeAllViews()
            favoriteSections.forEach { it.cards.reset() }; favoriteSections.clear()
            grid.adapter = adapter
            grid.recycledViewPool.clear(); category = ""
        }
        tab = next
        nav.forEach { (key, button) -> button.background = context.focusBackground(selected = key == tab); button.setTextColor(if (key == tab) Palette.green else Palette.muted) }
        heading.text = navigation.firstOrNull { it.first == tab }?.second ?: "Configurações"
        subtitle.text = when (tab) {
            "home" -> "O próximo play começa aqui."
            "live" -> "Seus canais organizados nas categorias do provedor."
            "movie" -> "Encontre seu próximo filme. Escolha uma categoria ou faça uma busca."
            "series" -> "Explore suas séries e escolha a temporada e o episódio."
            "continue" -> "Retome filmes e episódios do ponto em que parou."
            "favorites" -> "Seus conteúdos favoritos, salvos neste aparelho para esta conta."
            else -> "Personalize sua experiência no SmartIPTV."
        }
        search.visibility = if (tab == "settings") GONE else VISIBLE
        search.nextFocusLeftId = nav.getValue(tab).id
        updateOrder(); render()
    }
    private fun updateOrder() { order.text = if (alphabetical) "Nome A–Z" else if (tab == "continue") "Assistidos recentemente" else "Mais recentes" }
    private fun render() {
        home.visibility = if (tab == "home" && search.text.isBlank()) VISIBLE else GONE
        settings.visibility = if (tab == "settings") VISIBLE else GONE
        gridPage.visibility = if (home.visibility != VISIBLE && tab != "settings") VISIBLE else GONE
        if (tab == "settings") { cancelQueries(); updateAutoNext(); return }
        if (home.visibility == VISIBLE) buildHome()
        else if (tab == "favorites") { categories.visibility = GONE; loadFavorites() }
        else { loadGrid(); fillCategories() }
    }
    fun reload() {
        catalogPending = false
        if (tab == "favorites" && grid.hasFocus() && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) focusAfterLoad = true
        if (gridPage.visibility == VISIBLE && (grid.adapter?.itemCount ?: 0) > 0) {
            restoreGrid = grid.layoutManager?.onSaveInstanceState()
            restoreCategory = categories.layoutManager?.onSaveInstanceState()
        }
        if (home.visibility == VISIBLE) {
            restoreHomeY = home.scrollY
            restoreHomeRows = homeRows.map { it.first.layoutManager?.onSaveInstanceState() }
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
        val adapters = listOf(adapter) + homeRows.map { it.second }
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
    fun restorePosters() { adapter.reloadPosters(); homeRows.forEach { it.second.reloadPosters() }; favoriteSections.forEach { it.cards.reloadPosters() } }
    fun focusContent(): Boolean {
        if (descendantFocusability == FOCUS_BLOCK_DESCENDANTS) return false
        if (tab == "settings") return nextButton.requestFocus()
        if (home.visibility == VISIBLE) return homeRows.firstOrNull()?.first?.getChildAt(0)?.requestFocus() ?: nav[tab]?.requestFocus() ?: false
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
    private fun gridSections(): List<FocusSection> {
        if (tab != "favorites") return listOf(FocusSection(0, adapter.itemCount))
        var offset = 0
        return favoriteSections.map { section -> FocusSection(offset + 1, section.cards.itemCount).also { offset += 1 + section.cards.itemCount } }
    }
    private fun focusGrid(position: Int) {
        if (position !in 0 until (grid.adapter?.itemCount ?: 0)) return
        pendingGridFocus = position; lastGridPosition = position
        if (!completeGridFocus()) {
            grid.stopScroll()
            // Park focus on the list so its old child cannot pull an offscreen
            // target back into the old viewport while the next row is laid out.
            val policy = grid.descendantFocusability
            grid.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            grid.requestFocus()
            grid.descendantFocusability = policy
            (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(position, 0)
            grid.post { completeGridFocus() }
        }
    }
    private fun completeGridFocus(): Boolean {
        val position = pendingGridFocus ?: return false
        if (!grid.isShown || descendantFocusability == FOCUS_BLOCK_DESCENDANTS) return false
        val row = grid.findViewHolderForAdapterPosition(position)?.itemView ?: return false
        if (!row.isFocusable || !row.requestFocus()) return false
        pendingGridFocus = null
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
        if (grid.hasFocus()) {
            val holder = grid.findContainingViewHolder(focused)
            val position = pendingGridFocus ?: holder?.absoluteAdapterPosition ?: run { focusContent(); return true }
            if (position < 0) return true
            lastGridPosition = position
            val columns = (grid.layoutManager as GridLayoutManager).spanCount
            val target = GridFocus.target(position, columns, gridSections(), direction)
            if (target != null) focusGrid(target)
            else when (direction) {
                FOCUS_LEFT -> { pendingGridFocus = null; if (categories.isShown) categories.getChildAt(0)?.requestFocus() ?: focusSidebar() else focusSidebar() }
                FOCUS_RIGHT -> focusToolbar(if (order.isShown) order else search)
                FOCUS_UP -> focusToolbar(search)
                FOCUS_DOWN -> Unit
            }
            return true
        }
        if (search.hasFocus()) {
            // Once OK opens the keyboard, editing remains owned by the native input.
            if (search.showSoftInputOnFocus) return false
            when (direction) { FOCUS_LEFT -> focusSidebar(); FOCUS_DOWN -> if (order.isShown) focusToolbar(order) else if (home.isShown) homeLinks.firstOrNull()?.requestFocus() else focusContent() }
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
                FOCUS_UP -> sidebar.getOrNull(navIndex - 1)?.requestFocus()
                FOCUS_DOWN -> sidebar.getOrNull(navIndex + 1)?.requestFocus()
            }
            return true
        }
        if (categories.hasFocus()) {
            val position = categories.findContainingViewHolder(focused)?.absoluteAdapterPosition ?: return true
            when (direction) {
                FOCUS_LEFT -> focusSidebar()
                FOCUS_RIGHT -> returnToGrid()
                FOCUS_UP -> if (position == 0) focusToolbar(search) else return false
                FOCUS_DOWN -> if (position + 1 < (categories.adapter?.itemCount ?: 0)) return false
            }
            return true
        }
        if (settings.hasFocus()) {
            val buttons = listOf(nextButton, refreshButton, logoutButton)
            val index = buttons.indexOfFirst { it.hasFocus() }
            when (direction) { FOCUS_LEFT -> focusSidebar(); FOCUS_UP -> buttons.getOrNull(index - 1)?.requestFocus(); FOCUS_DOWN -> buttons.getOrNull(index + 1)?.requestFocus() }
            return true
        }
        return moveHomeFocus(focused, direction)
    }
    private fun focusHomeRow(index: Int, origin: View) {
        val gallery = homeRows.getOrNull(index)?.first ?: return
        val source = IntArray(2); origin.getLocationOnScreen(source)
        val center = source[0] + origin.width / 2
        val card = (0 until gallery.childCount).map { gallery.getChildAt(it) }.filter { it.isFocusable }.minByOrNull {
            val point = IntArray(2); it.getLocationOnScreen(point)
            kotlin.math.abs(point[0] + it.width / 2 - center)
        }
        card?.requestFocus() ?: homeLinks.getOrNull(index)?.requestFocus()
    }
    private fun moveHomeFocus(focused: View, direction: Int): Boolean {
        if (!home.isShown) return false
        val link = homeLinks.indexOfFirst { it.hasFocus() }
        if (link >= 0) {
            when (direction) {
                FOCUS_LEFT -> focusSidebar()
                FOCUS_UP -> if (link == 0) focusToolbar(search) else homeLinks[link - 1].requestFocus()
                FOCUS_DOWN -> focusHomeRow(link, focused)
            }
            return true
        }
        val row = homeRows.indexOfFirst { it.first.hasFocus() }
        if (row < 0) return false
        val gallery = homeRows[row].first
        val holder = gallery.findContainingViewHolder(focused) ?: return true
        when (direction) {
            FOCUS_LEFT -> if (holder.absoluteAdapterPosition == 0) focusSidebar() else return false
            FOCUS_RIGHT -> if (holder.absoluteAdapterPosition + 1 == (gallery.adapter?.itemCount ?: 0)) homeLinks[row].requestFocus() else return false
            FOCUS_UP -> if (row == 0) focusToolbar(search) else focusHomeRow(row - 1, focused)
            FOCUS_DOWN -> if (row + 1 < homeRows.size) focusHomeRow(row + 1, focused)
        }
        return true
    }
    private fun cards(page: Page): CardPage = db.cards(accountKey, page)
    private fun loadPage(offset: Int, limit: Int, done: (CardPage) -> Unit) {
        val current = queryEpoch
        val kind = if (tab == "home") "all" else tab
        val selected = category; val term = search.text.toString(); val alpha = alphabetical
        query(work = { cards(if (kind == "all") searchAll(term, alpha, offset, limit) else db.page(accountKey, kind, selected, term, alpha, offset, limit)) }, done = { if (current == queryEpoch) done(it) }, failed = { if (current == queryEpoch) message(it, true) })
    }
    private fun searchAll(term: String, alpha: Boolean, offset: Int, limit: Int): Page = db.searchAll(accountKey, term, alpha, offset, limit)
    private fun loadGrid() {
        if (grid.adapter !== adapter) grid.adapter = adapter
        cancelQueries()
        queryEpoch++
        val current = queryEpoch
        adapter.reset(); count.text = "Carregando…"; empty.text = "Carregando conteúdos…"; empty.visibility = VISIBLE; grid.visibility = INVISIBLE
        loadPage(0, CatalogAdapter.PAGE_SIZE) { page ->
            if (queryEpoch != current) return@loadPage
            adapter.reset(page, tab == "live"); count.text = "${page.total} conteúdos"
            restoreGrid?.let { grid.layoutManager?.onRestoreInstanceState(it) }; restoreGrid = null
            empty.text = if (search.text.isNotBlank()) "Nenhum resultado encontrado" else if (tab == "continue") "Nenhum conteúdo em andamento" else if (tab == "favorites") "Seus favoritos ficam aqui" else "Nenhum conteúdo disponível"
            empty.visibility = if (page.total == 0) VISIBLE else GONE; grid.visibility = if (page.total == 0) INVISIBLE else VISIBLE
            if (focusAfterLoad) grid.post { if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) focusAfterLoad = !focusContent() }
        }
    }
    private fun loadFavorites() {
        cancelQueries(); queryEpoch++
        val current = queryEpoch; val term = search.text.toString(); val alpha = alphabetical
        count.text = "Carregando…"; empty.text = "Carregando favoritos…"; empty.visibility = VISIBLE; grid.visibility = INVISIBLE
        query(work = {
            listOf("movie" to "Filmes", "series" to "Séries", "live" to "TV ao vivo").map { (kind, title) ->
                Triple(kind, title, cards(db.page(accountKey, "favorites", search = term, alphabetical = alpha, limit = CatalogAdapter.PAGE_SIZE, favoriteKind = kind)))
            }
        }, done = { sections ->
            if (current != queryEpoch || tab != "favorites") return@query
            favoriteSections.forEach { it.cards.reset() }; favoriteSections.clear()
            sections.filter { it.third.total > 0 }.forEach { (kind, title, first) ->
                val sectionCards = CatalogAdapter(images, open, options, { offset, limit, done ->
                    query(work = { cards(db.page(accountKey, "favorites", search = term, alphabetical = alpha, offset = offset, limit = limit, favoriteKind = kind)) }, done = { if (current == queryEpoch && tab == "favorites") done(it) })
                })
                sectionCards.reset(first, kind == "live")
                favoriteSections += FavoriteSection(kind, FavoriteHeaderAdapter(title, first.total), sectionCards)
            }
            val config = ConcatAdapter.Config.Builder().setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build()
            grid.adapter = ConcatAdapter(config, *favoriteSections.flatMap { listOf(it.header, it.cards) }.toTypedArray())
            restoreGrid?.let { grid.layoutManager?.onRestoreInstanceState(it) }; restoreGrid = null
            val total = sections.sumOf { it.third.total }
            count.text = "$total conteúdos"
            empty.text = if (term.isNotBlank()) "Nenhum resultado encontrado" else "Seus favoritos ficam aqui"
            empty.visibility = if (total == 0) VISIBLE else GONE; grid.visibility = if (total == 0) INVISIBLE else VISIBLE
            if (focusAfterLoad) grid.post { if (current == queryEpoch && descendantFocusability != FOCUS_BLOCK_DESCENDANTS) focusAfterLoad = !focusContent() }
        }, failed = { if (current == queryEpoch) { empty.text = "Não foi possível carregar os favoritos. Tente novamente."; message(it, true) } })
    }
    private fun fillCategories() {
        val enabled = tab in listOf("live", "movie", "series")
        categories.visibility = if (enabled) VISIBLE else GONE
        if (!enabled) return
        val current = queryEpoch; val kind = tab
        query(work = { listOf(Category("", "Todas as categorias")) + db.categories(accountKey, kind) }, done = { groups ->
            if (current != queryEpoch || kind != tab) return@query
            categories.adapter = CategoryAdapter(groups, { category }) { selected -> category = selected; categories.adapter?.notifyDataSetChanged(); loadGrid() }
            restoreCategory?.let { categories.layoutManager?.onRestoreInstanceState(it) }; restoreCategory = null
        })
    }
    private fun buildHome() {
        cancelQueries()
        queryEpoch++; val current = queryEpoch
        homeRows.forEach { it.first.adapter = null }; homeRows.clear(); homeLinks.clear(); homeBody.removeAllViews()
        val banner = context.column().apply { background = shape(Color.rgb(25, 48, 38), context.dp(11).toFloat()); pad(19, 17) }
        banner.addView(context.eyebrow("SEU CATÁLOGO, DO SEU JEITO")); banner.gap(8)
        banner.addView(context.text("O próximo play começa aqui.", 24f, bold = true)); banner.gap(7)
        banner.addView(context.text("Descubra o que chegou e volte às suas histórias favoritas.", 12f, Palette.muted))
        homeBody.addView(banner, LayoutParams(-1, -2)); homeBody.gap(19)
        for ((rowIndex, entry) in listOf("movie" to "Filmes recém-adicionados", "series" to "Séries atualizadas", "live" to "Canais recém-adicionados").withIndex()) {
            val (kind, title) = entry
            val row = context.row(); row.addView(context.text(title, 17f, bold = true), LayoutParams(0, -2, 1f))
            val link = context.button("Ver todos →") { navigate(kind); focusContent() }.apply {
                contentDescription = when (kind) { "movie" -> "Ver todos os filmes"; "series" -> "Ver todas as séries"; else -> "Ver todos os canais" }
            }
            homeLinks += link
            row.addView(link, LayoutParams(-2, context.dp(35)))
            homeBody.addView(row); homeBody.gap(9)
            val gallery = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false); itemAnimator = null; setItemViewCacheSize(1) }
            val galleryAdapter = CatalogAdapter(images, open, options, { offset, limit, done ->
                query(work = { cards(db.page(accountKey, kind, offset = offset, limit = minOf(limit, 18))) }, done = { if (current == queryEpoch) done(it.copy(total = minOf(it.total, 18))) })
            }, true)
            gallery.adapter = galleryAdapter
            gallery.addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { galleryAdapter.reloadPosters() } })
            homeRows += gallery to galleryAdapter
            homeBody.addView(gallery, LayoutParams(-1, context.dp(if (kind == "live") 153 else 219))); homeBody.gap(19)
            query(work = { cards(db.page(accountKey, kind, limit = 18)) }, done = {
                if (current == queryEpoch) {
                    galleryAdapter.reset(it.copy(total = minOf(it.total, 18)), kind == "live")
                    restoreHomeRows.getOrNull(rowIndex)?.let { state -> gallery.layoutManager?.onRestoreInstanceState(state) }
                    if (rowIndex == 0 && focusAfterLoad) gallery.post { focusAfterLoad = !focusContent() }
                }
            })
        }
        home.post { if (current == queryEpoch) home.scrollTo(0, restoreHomeY) }
    }
    private fun buildSettings() {
        val card = context.column().apply { background = shape(Palette.panel, context.dp(12).toFloat()); pad(20); gravity = Gravity.TOP }
        card.addView(context.text("Reprodução", 19f, bold = true)); card.gap(11)
        card.addView(context.text("Escolha se a série deve continuar automaticamente ao terminar um episódio.", 12f, Palette.muted)); card.gap(14)
        card.addView(nextButton, LayoutParams(-1, context.dp(43))); card.gap(18); card.rule(); card.gap(18)
        card.addView(context.text("Conta e catálogo", 19f, bold = true)); card.gap(13)
        card.addView(refreshButton, LayoutParams(-1, context.dp(43))); card.gap(12)
        card.addView(logoutButton, LayoutParams(-1, context.dp(43)))
        val scroll = ScrollView(context); scroll.addView(card); settings.addView(scroll, LayoutParams(-1, -1))
    }
    private fun updateAutoNext() { nextButton.text = "Reproduzir próximo episódio: ${if (db.autoNext) "Ligado" else "Desligado"}" }

    private class FavoriteHeaderAdapter(private val title: String, private val count: Int) : RecyclerView.Adapter<FavoriteHeaderAdapter.Holder>() {
        init { setHasStableIds(true) }
        class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)
        override fun getItemCount() = 1
        override fun getItemId(position: Int) = 0L
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val row = context.row().apply { pad(4, 8); layoutParams = RecyclerView.LayoutParams(-1, context.dp(44)); isFocusable = false }
            row.addView(View(context).apply { background = shape(Palette.green, context.dp(2).toFloat(), width = 0) }, LayoutParams(context.dp(3), context.dp(20)).apply { rightMargin = context.dp(9) })
            row.addView(context.text(title, 16f, bold = true), LayoutParams(0, -2, 1f))
            row.addView(context.text("$count", 11f, Palette.green).apply { pad(9, 3); background = shape(Palette.selected, context.dp(9).toFloat(), width = 0) })
            row.contentDescription = "$title · $count favoritos"
            return Holder(row)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = Unit
    }

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
