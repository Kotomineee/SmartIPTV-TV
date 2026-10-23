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
    var details: DetailView? = null; private set
    var playerView: TvPlayer? = null; private set
    private var focusedBeforeDetails: View? = null
    private var catalogJob: Future<*>? = null
    private var accountJob: Future<*>? = null
    private var generation = 0
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
        jobs.run(work = { database.writableDatabase; vault.restore() }, done = { credentials ->
            if (credentials != null) authenticate(credentials, true) else loginView?.busy(false)
        }, failed = { loginView?.error(it) })
    }
    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    private fun showLogin() {
        root.removeAllViews(); catalogView = null
        loginView = LoginView(this, vault, ::authenticate).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }
    private fun authenticate(credentials: Credentials, remember: Boolean) {
        val token = ++generation
        loginView?.busy(true)
        api?.cancel()
        val client = XtreamApi(credentials)
        api = client
        jobs.run(work = {
            val account = client.authenticate()
            vault.remember(credentials, remember)
            account
        }, done = { account -> if (token == generation) showCatalog(client, account) }, failed = { if (token == generation) loginView?.error(it) })
    }
    private fun showCatalog(client: XtreamApi, account: Account) {
        root.removeAllViews(); loginView?.password?.text?.clear(); loginView = null
        val screen = CatalogView(this, database, jobs, client.credentials.account, images, ::openContent, ::options, ::refreshCatalog, ::logout)
        catalogView = screen
        root.addView(screen, FrameLayout.LayoutParams(-1, -1)); screen.showAccount(account)
        screen.nav["home"]?.requestFocus()
        refreshCatalog()
        handler.removeCallbacks(accountPoll); if (foreground) handler.postDelayed(accountPoll, 60000)
    }
    private fun refreshCatalog() {
        val client = api ?: return
        val screen = catalogView ?: return
        if (catalogJob != null) return
        val token = generation
        screen.refreshing(true); screen.message("Atualizando seu catálogo…")
        catalogJob = jobs.run(refresh = true, work = {
            for ((kind, label) in listOf("movie" to "Filmes", "series" to "Séries", "live" to "Canais")) {
                val categories = client.categories(kind)
                database.replaceSection(client.credentials.account, kind, categories) { sink -> client.catalog(kind, sink) }
                jobs.ui {
                    if (token == generation) {
                        screen.message("$label atualizados…")
                        if (screen.tab == "home" || screen.tab == kind) screen.reload()
                    }
                }
            }
        }, done = {
            if (token == generation) { catalogJob = null; screen.refreshing(false); screen.message(""); refreshAccount() }
        }, failed = {
            if (token == generation) { catalogJob = null; screen.refreshing(false); screen.message(it, true); screen.reload() }
        })
    }
    private fun refreshAccount() {
        val client = api ?: return
        if (catalogView == null || accountJob != null) return
        val token = generation
        accountJob = jobs.run(work = { client.authenticate() }, done = {
            if (token == generation) { accountJob = null; catalogView?.showAccount(it) }
        }, failed = { if (token == generation) accountJob = null })
    }
    fun openContent(item: Content) {
        val client = api ?: return
        if (item.kind == "episode") {
            catalogView?.message("Carregando episódios…")
            val token = generation
            val series = Content("series", item.seriesId, item.seriesName, item.categories, item.artwork, year = item.year)
            jobs.run(work = { client.seriesInfo(series) }, done = { if (token == generation && playerView == null) { catalogView?.message(""); play(item, false, it.episodes) } }, failed = { if (token == generation) { catalogView?.message(""); play(item, false, emptyList()); toast("A lista dos próximos episódios está indisponível.") } })
            return
        }
        closeDetails()
        val catalog = catalogView ?: return
        focusedBeforeDetails = currentFocus
        val detail = DetailView(this, item, client, database, jobs, images, catalog, ::closeDetails, ::play)
        details = detail
        catalog.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        catalog.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        root.addView(detail, FrameLayout.LayoutParams(-1, -1))
        detail.post { if (details === detail) detail.watch.requestFocus() }
    }
    fun closeDetails() {
        details?.let { detail ->
            detail.dismiss(); root.removeView(detail); details = null
            catalogView?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            catalogView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            focusedBeforeDetails?.takeIf { it.isAttachedToWindow }?.requestFocus()
            focusedBeforeDetails = null
            catalogView?.restorePosters()
            catalogView?.reload()
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
    private fun play(item: Content, restart: Boolean, episodes: List<Content>) {
        val client = api ?: return
        closeDetails()
        playerView?.shutdown(); playerView?.let(root::removeView)
        catalogView?.releasePosters(); catalogView?.visibility = View.GONE
        playerView = TvPlayer(this, client, database, jobs, item, restart, episodes, ::leavePlayer) {
            handler.postDelayed({ if (foreground) refreshAccount() }, 1500)
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)); if (!foreground) it.suspendPlayback() }
    }
    fun leavePlayer() {
        val current = playerView ?: return
        current.shutdown(); root.removeView(current); playerView = null
        catalogView?.visibility = View.VISIBLE
        catalogView?.restorePosters()
        jobs.afterWrites { catalogView?.restoreAfterPlayback() }
        immersive(); refreshAccount()
    }
    private fun logout() {
        ++generation; handler.removeCallbacks(accountPoll)
        playerView?.shutdown(); playerView = null; closeDetails()
        catalogJob?.cancel(true); catalogJob = null; accountJob?.cancel(true); accountJob = null
        api?.cancel(); api = null; vault.clear(); images.switchScreen(); showLogin()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playerView?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("TV uses the hardware Back key")
    override fun onBackPressed() {
        if (details != null) { closeDetails(); return }
        playerView?.let { if (!it.back()) leavePlayer(); return }
        catalogView?.let { screen ->
            if (screen.search.text.isNotBlank()) { screen.search.text.clear(); return }
            if (screen.tab != "home") { screen.navigate("home"); screen.nav["home"]?.requestFocus(); return }
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
        if (level >= TRIM_MEMORY_RUNNING_LOW) { images.switchScreen(); if (foreground) details?.restoreArtwork() ?: catalogView?.restorePosters() }
    }
    override fun onDestroy() {
        playerView?.shutdown(); playerView = null; details?.dismiss(); details = null
        generation++; handler.removeCallbacksAndMessages(null); api?.cancel(); images.close()
        jobs.close { database.close() }; super.onDestroy()
    }
}
