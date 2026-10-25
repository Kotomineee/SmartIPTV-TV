package com.smartiptv.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.Future

class MainActivity : Activity() {
    lateinit var database: LibraryDb; private set
    lateinit var images: ArtworkLoader; private set
    private lateinit var vault: SessionVault
    private lateinit var jobs: Jobs
    private lateinit var root: FrameLayout
    private var api: XtreamApi? = null
    var loginView: LoginView? = null; private set
    var catalogView: CatalogView? = null; private set
    var selectionView: ContentSelectionView? = null; private set
    private var account: Account? = null
    private var selectedKind = "live"
    private var catalogEpoch = 0
    var details: DetailView? = null; private set
    var playerView: TvPlayer? = null; private set
    private var playbackSeries: Content? = null
    private var playbackSeriesInfo: SeriesInfo? = null
    private var playbackMoviePlot: String? = null
    private var playbackCatalogFocus: View? = null
    private var focusedBeforeDetails: View? = null
    private var catalogJob: Future<*>? = null
    private var accountJob: Future<*>? = null
    private var generation = 0
    private var contentRequest = 0
    private var pendingEpisode = false
    private var foreground = false
    private val handler = Handler(Looper.getMainLooper())
    private val accountPoll = object : Runnable {
        override fun run() { if (foreground) { refreshAccount(); handler.postDelayed(this, 60000) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        database = LibraryDb(this); images = ArtworkLoader(this); vault = SessionVault(this); jobs = Jobs()
        root = FrameLayout(this)
        setContentView(root)
        showLogin()
        loginView?.busy(true)
        jobs.run(work = { database.prepare(); vault.restore() }, done = { credentials ->
            if (credentials != null) authenticate(credentials, true) else loginView?.busy(false)
        }, failed = { loginView?.error(it) })
    }
    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    private fun showLogin() {
        root.removeAllViews(); catalogView = null; selectionView = null
        loginView = LoginView(this, vault, ::authenticate).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }
    private fun authenticate(credentials: Credentials, remember: Boolean) {
        val token = ++generation
        loginView?.busy(true)
        api?.cancel()
        val client = XtreamApi(credentials)
        api = client
        jobs.run(network = true, work = {
            val account = client.authenticate()
            vault.remember(credentials, remember)
            account
        }, done = { account -> if (token == generation) showSelection(account) }, failed = { if (token == generation) loginView?.error(it) })
    }
    private fun stopCatalogRefresh() {
        catalogEpoch++; catalogJob?.cancel(true); catalogJob = null; api?.cancelCatalogs()
    }
    private fun showSelection(currentAccount: Account? = account) {
        account = currentAccount
        cancelPendingContent(); stopCatalogRefresh()
        catalogView?.releasePosters(); root.removeAllViews(); catalogView = null
        loginView?.password?.text?.clear(); loginView = null
        selectionView = ContentSelectionView(this, selectedKind, ::showCatalog).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        handler.removeCallbacks(accountPoll); if (foreground) handler.postDelayed(accountPoll, 60000)
    }
    private fun showCatalog(kind: String) {
        val client = api ?: return
        if (kind !in listOf("live", "series", "movie")) return
        selectedKind = kind
        root.removeAllViews(); selectionView = null
        val screen = CatalogView(this, database, jobs, client.credentials.account, images, ::openContent, ::options, { refreshCatalog(true) }, ::logout, ::cancelPendingContent, kind, { showSelection() })
        catalogView = screen
        root.addView(screen, FrameLayout.LayoutParams(-1, -1)); account?.let(screen::showAccount)
        screen.nav[CatalogView.START_TAB]?.requestFocus()
        refreshCatalog()
    }
    private fun refreshCatalog(force: Boolean = false) {
        val client = api ?: return
        val screen = catalogView ?: return
        if (catalogJob != null) return
        val token = generation
        val refreshEpoch = ++catalogEpoch
        val priority = screen.mediaKind
        screen.refreshing(true); screen.message("Atualizando catálogo em segundo plano…")
        catalogJob = jobs.run(refresh = true, work = {
            CatalogSync(client, database, cacheDir).refresh(force, priority, only = priority) { kind ->
                jobs.ui {
                    if (token == generation && refreshEpoch == catalogEpoch && catalogView === screen && kind == screen.mediaKind) screen.catalogUpdated(playerView != null || details != null)
                }
            }
        }, done = { result ->
            if (token == generation && refreshEpoch == catalogEpoch && catalogView === screen) {
                catalogJob = null; screen.refreshing(false)
                screen.message(if (result.failed.isEmpty()) "" else "Algumas seções não puderam ser atualizadas. O catálogo salvo continua disponível.", result.failed.isNotEmpty())
                refreshAccount()
            }
        }, failed = {
            if (token == generation && refreshEpoch == catalogEpoch && catalogView === screen) { catalogJob = null; screen.refreshing(false); screen.message(it, true); screen.reload() }
        })
    }
    private fun refreshAccount() {
        val client = api ?: return
        if ((catalogView == null && selectionView == null) || accountJob != null) return
        val token = generation
        accountJob = jobs.run(network = true, work = { client.authenticate() }, done = {
            if (token == generation) { accountJob = null; account = it; catalogView?.showAccount(it) }
        }, failed = { if (token == generation) accountJob = null })
    }
    fun openContent(item: Content) {
        val client = api ?: return
        cancelPendingContent()
        if (item.kind == "episode") {
            pendingEpisode = true
            catalogView?.message("Carregando episódios…")
            val token = generation
            val request = contentRequest
            val series = Content("series", item.seriesId, item.seriesName, item.categories, item.artwork, year = item.year)
            jobs.run(network = true, work = { client.seriesInfo(series) }, done = { if (token == generation && request == contentRequest && playerView == null) { pendingEpisode = false; catalogView?.message(""); play(item, false, it.episodes, series, it) } }, failed = { if (token == generation && request == contentRequest && playerView == null) { pendingEpisode = false; catalogView?.message(""); play(item, false, emptyList(), series); toast("A lista dos próximos episódios está indisponível.") } })
            return
        }
        showDetails(item)
    }
    private fun showDetails(item: Content, returnEpisode: Content? = null, cachedSeries: SeriesInfo? = null, cachedMoviePlot: String? = null) {
        val client = api ?: return
        closeDetails()
        val catalog = catalogView ?: return
        focusedBeforeDetails = currentFocus
        val detail = DetailView(this, item, client, database, jobs, images, catalog, ::closeDetails, { content, restart, episodes -> play(content, restart, episodes) }, returnEpisode, cachedSeries, cachedMoviePlot)
        details = detail
        catalog.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        catalog.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.addView(detail, FrameLayout.LayoutParams(-1, -1))
        if (returnEpisode == null) detail.post { if (details === detail) detail.watch.requestFocus() }
    }
    private fun cancelPendingContent() {
        contentRequest++
        if (pendingEpisode) { pendingEpisode = false; api?.cancelDetails(); catalogView?.message("") }
    }
    fun closeDetails() {
        details?.let { detail ->
            detail.dismiss(); root.removeView(detail); details = null
            catalogView?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            catalogView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            val restoredFocus = focusedBeforeDetails?.takeIf { it.isAttachedToWindow }?.requestFocus() == true
            if (!restoredFocus) catalogView?.focusContent()
            focusedBeforeDetails = null
            catalogView?.restorePosters()
            catalogView?.refreshLibrary(restoreFocus = !restoredFocus)
        }
    }
    private fun options(item: Content) {
        val client = api ?: return
        jobs.run(work = { database.isFavorite(client.credentials.account, item) }, done = { favorite ->
            if (catalogView == null || playerView != null) return@run
            val actions = mutableListOf(if (favorite) "Remover dos favoritos" else "Adicionar aos favoritos")
            if (catalogView?.tab == "continue") actions += "Remover de continuar assistindo"
            val dialog = AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toTypedArray()) { _, which ->
                jobs.run(work = {
                    if (which == 0) database.toggleFavorite(client.credentials.account, item) else database.removeProgress(client.credentials.account, item)
                }, done = { catalogView?.reload() }, failed = { toast("Não foi possível salvar a alteração neste aparelho.") })
            }.create()
            dialog.show(); dialog.window?.setBackgroundDrawable(shape(Palette.panel, dp(12).toFloat()))
        })
    }
    private fun play(item: Content, restart: Boolean, episodes: List<Content>, series: Content? = null, seriesInfo: SeriesInfo? = null) {
        val client = api ?: return
        playbackSeries = if (item.kind == "episode" && item.seriesId.isNotBlank()) series ?: details?.item?.takeIf { it.kind == "series" && it.id == item.seriesId }
            ?: Content("series", item.seriesId, item.seriesName.ifBlank { item.name }, item.categories, item.artwork, year = item.year, rating = item.rating) else null
        playbackSeriesInfo = if (playbackSeries != null) seriesInfo ?: details?.seriesSnapshot() ?: episodes.takeIf { it.isNotEmpty() }?.let { SeriesInfo(it, playbackSeries!!.plot) } else null
        playbackMoviePlot = if (item.kind == "movie") details?.moviePlotSnapshot() else null
        playbackCatalogFocus = focusedBeforeDetails ?: currentFocus
        closeDetails()
        playerView?.shutdown(); playerView?.let(root::removeView)
        catalogView?.releasePosters(); catalogView?.visibility = View.GONE
        playerView = TvPlayer(this, client, database, jobs, item, restart, episodes, ::leavePlayer) {
            handler.postDelayed({ if (foreground) refreshAccount() }, 1500)
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)); if (!foreground) it.suspendPlayback() }
    }
    fun leavePlayer() {
        val current = playerView ?: return
        val episode = current.item.takeIf { it.kind == "episode" }
        val movie = current.item.takeIf { it.kind == "movie" }
        val moviePlot = playbackMoviePlot
        val series = playbackSeries; val seriesInfo = playbackSeriesInfo
        val catalogFocus = playbackCatalogFocus
        playbackSeries = null; playbackSeriesInfo = null; playbackMoviePlot = null; playbackCatalogFocus = null
        val token = generation; val request = contentRequest; val screen = catalogView
        current.shutdown(); root.removeView(current); playerView = null
        catalogView?.visibility = View.VISIBLE
        catalogView?.restorePosters()
        jobs.afterWrites {
            if (token == generation && request == contentRequest && playerView == null && details == null && catalogView === screen) {
                screen?.restoreAfterPlayback(focus = movie == null && (episode == null || series == null))
                if (episode != null && series != null) {
                    showDetails(series, episode, seriesInfo)
                    focusedBeforeDetails = catalogFocus?.takeIf { it.isAttachedToWindow }
                } else if (movie != null) {
                    showDetails(movie, cachedMoviePlot = moviePlot)
                    focusedBeforeDetails = catalogFocus?.takeIf { it.isAttachedToWindow }
                }
            }
        }
        immersive(); refreshAccount()
    }
    private fun logout() {
        cancelPendingContent()
        ++generation; handler.removeCallbacks(accountPoll)
        playerView?.shutdown(); playerView = null; playbackSeries = null; playbackSeriesInfo = null; playbackMoviePlot = null; playbackCatalogFocus = null; closeDetails()
        stopCatalogRefresh(); accountJob?.cancel(true); accountJob = null; account = null
        api?.cancel(); api = null; vault.clear(); images.switchScreen(); showLogin()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playerView?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("TV uses the hardware Back key")
    override fun onBackPressed() {
        if (pendingEpisode) { cancelPendingContent(); return }
        if (details != null) { closeDetails(); return }
        playerView?.let { if (!it.back()) leavePlayer(); return }
        catalogView?.let { screen ->
            if (screen.search.text.isNotBlank()) { screen.search.text.clear(); return }
            if (screen.tab != CatalogView.START_TAB) { screen.navigate(CatalogView.START_TAB); screen.nav[CatalogView.START_TAB]?.requestFocus(); return }
            showSelection(); return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    override fun onStart() { super.onStart(); foreground = true; playerView?.resumePlayback(); details?.restoreArtwork() ?: catalogView?.restorePosters(); handler.removeCallbacks(accountPoll); handler.postDelayed(accountPoll, 60000) }
    override fun onResume() { super.onResume(); immersive() }
    override fun onStop() { foreground = false; handler.removeCallbacks(accountPoll); playerView?.suspendPlayback(); images.switchScreen(); super.onStop() }
    @Deprecated("Android memory callback")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) images.switchScreen()
    }
    override fun onDestroy() {
        playerView?.shutdown(); playerView = null; playbackSeries = null; playbackSeriesInfo = null; playbackMoviePlot = null; playbackCatalogFocus = null; details?.dismiss(); details = null
        generation++; handler.removeCallbacksAndMessages(null); api?.cancel(); images.close()
        jobs.close { database.close() }; super.onDestroy()
    }
}
