package com.smartiptv.tv

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteFlowTest {
    private lateinit var provider: TestProvider
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var device: UiDevice
    @Before fun setup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        val context = instrumentation.targetContext
        context.deleteDatabase("library.db")
        context.getSharedPreferences("session", 0).edit().clear().commit()
        context.getSharedPreferences("playback", 0).edit().clear().commit()
        provider = TestProvider(instrumentation.context)
        device = UiDevice.getInstance(instrumentation)
        androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(200).setWaitForSelectorTimeout(3000)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Login ready") { it.loginView?.submit?.isEnabled == true && it.hasWindowFocus() }
    }
    @After fun cleanup() { device.executeShellCommand("wm size reset"); scenario.close(); provider.close() }
    private fun ui(action: (MainActivity) -> Unit) { scenario.onActivity(action) }
    private fun focusedGridPosition(activity: MainActivity): Int? = activity.currentFocus?.let { activity.catalogView?.grid?.findContainingViewHolder(it)?.absoluteAdapterPosition }
    private fun selectGridPosition(position: Int) {
        ui { it.catalogView!!.order.requestFocus(); it.catalogView!!.grid.scrollToPosition(position) }
        waitFor("Card $position did not bind") { (it.catalogView!!.grid.findViewHolderForAdapterPosition(position) as? CatalogAdapter.Holder)?.card != null && it.catalogView!!.grid.isShown }
        ui { assertTrue(it.catalogView!!.grid.findViewHolderForAdapterPosition(position)!!.itemView.requestFocus()) }
        waitFor("Card $position did not receive focus") { focusedGridPosition(it) == position }
    }
    private fun waitFor(description: String, timeout: Long = 20000, condition: (MainActivity) -> Boolean) {
        android.util.Log.i("SmartIPTVTest", "Waiting: $description")
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            val result = AtomicReference(false)
            scenario.onActivity { result.set(condition(it)) }
            if (result.get()) { android.util.Log.i("SmartIPTVTest", "Passed: $description"); return }
            SystemClock.sleep(80)
        }
        ui { android.util.Log.e("SmartIPTVTest", "Window=${it.hasWindowFocus()} focus=${it.currentFocus?.contentDescription} login=${it.loginView?.feedback?.text}") }
        capture("failure-" + description.replace(Regex("[^a-zA-Z0-9]"), "-"))
        throw AssertionError(description)
    }
    private fun capture(name: String) {
        android.util.Log.i("SmartIPTVTest", "Capture: $name")
        device.waitForIdle(200)
        device.executeShellCommand("mkdir -p /sdcard/Download/SmartIPTV-TV-testshots")
        val path = "/sdcard/Download/SmartIPTV-TV-testshots/$name.png"
        // Android 9 UiAutomation crops captures after wm size changes; capture the display itself.
        device.executeShellCommand("screencap -p $path")
        assertTrue("Screenshot missing: $name", (device.executeShellCommand("stat -c %s $path").trim().toLongOrNull() ?: 0) > 1000)
    }
    private fun login(remember: Boolean = true) {
        ui { activity ->
            val form = activity.loginView!!
            form.server.setText(provider.url); form.username.setText("demo"); form.password.setText("demopass")
            form.remember.isChecked = remember; assertTrue(form.submit.requestFocus())
        }
        waitFor("Login button ready") { it.hasWindowFocus() && it.loginView!!.submit.hasFocus() }
        device.pressDPadCenter()
        waitFor("Catalog was not opened") { it.catalogView != null }
        waitFor("Catalog did not finish") { it.catalogView!!.refreshButton.isEnabled && it.database.page(provider.account, "movie").total == 120 }
    }
    private fun navigate(tab: String) {
        ui { it.catalogView!!.nav.getValue(tab).requestFocus() }
        device.pressDPadCenter()
        waitFor("Navigation failed: $tab") { it.catalogView!!.tab == tab }
        if (tab != "home" && tab != "settings") waitFor("Grid not ready") { it.catalogView!!.grid.childCount > 0 }
    }
    private fun focusDescription(name: String) {
        ui { activity ->
            fun find(view: View): View? {
                if (view.contentDescription?.toString() == name) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            assertTrue("Control missing: $name", find(activity.window.decorView)?.requestFocus() == true)
        }
    }

    private fun waitForIme(shown: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            val input = device.executeShellCommand("dumpsys input_method")
            if (input.contains("mInputShown=true") == shown) return
            SystemClock.sleep(100)
        }
        capture("failure-keyboard-${if (shown) "missing" else "stuck"}")
        throw AssertionError("Native keyboard visibility did not become $shown")
    }

    @Test fun remoteOkOpensNativeKeyboardForLoginAndMovieSeriesSearch() {
        val ime = device.executeShellCommand("settings get secure default_input_method").trim().substringBefore('/')
        assertTrue("No system keyboard configured", ime.contains('.'))
        fun typeInto(name: String, value: String, field: (MainActivity) -> android.widget.EditText) {
            focusDescription(name)
            ui { assertFalse("D-pad focus opened the keyboard automatically", field(it).showSoftInputOnFocus) }
            device.pressDPadCenter()
            waitForIme(true)
            assertTrue("System keyboard UI is absent for $name", device.wait(androidx.test.uiautomator.Until.hasObject(By.pkg(ime)), 5000))
            capture("11-keyboard-" + name.replace(Regex("[^a-zA-Z]"), "-"))
            device.executeShellCommand("input text $value")
            waitFor("Native input not delivered to $name") { field(it).text.toString() == value }
            device.pressBack()
            waitForIme(false)
            waitFor("Back dismissed the screen instead of the keyboard") { field(it).isAttachedToWindow && it.hasWindowFocus() }
        }
        typeInto("Servidor", provider.url) { it.loginView!!.server }
        device.pressDPadDown()
        waitFor("Keyboard dismissal broke username navigation") { it.loginView!!.username.hasFocus() }
        typeInto("Usuário", "demo") { it.loginView!!.username }
        device.pressDPadDown()
        typeInto("Senha", "demopass") { it.loginView!!.password }
        device.pressDPadDown(); device.pressDPadDown()
        waitFor("Cannot reach login after native keyboard input") { it.loginView!!.submit.hasFocus() }
        device.pressDPadCenter()
        waitFor("Native keyboard login failed") { it.catalogView?.refreshButton?.isEnabled == true && it.database.page(provider.account, "movie").total == 120 }
        navigate("movie")
        typeInto("Buscar no catálogo", "horizonte") { it.catalogView!!.search }
        waitFor("Movie search did not filter") { it.catalogView!!.count.text.toString() == "1 conteúdo" || it.catalogView!!.count.text.toString() == "1 conteúdos" }
        ui { it.catalogView!!.search.text.clear() }
        navigate("series")
        typeInto("Buscar no catálogo", "caminhos") { it.catalogView!!.search }
        waitFor("Series search did not filter") { it.catalogView!!.count.text.toString().startsWith("4 ") }
        // A second OK must reopen a dismissed keyboard in the same field.
        device.pressDPadCenter(); waitForIme(true); device.pressBack(); waitForIme(false)
        ui { assertEquals("series", it.catalogView!!.tab); assertEquals("caminhos", it.catalogView!!.search.text.toString()) }
    }

    @Test fun completeRemoteFlowAndRealMedia() {
        ui { activity ->
            val form = activity.loginView!!
            assertEquals("", form.server.hint?.toString() ?: "")
            assertTrue(form.server.text.isEmpty()); assertTrue(form.username.text.isEmpty()); assertTrue(form.password.text.isEmpty())
            form.server.requestFocus()
        }
        capture("01-login-1080p")
        device.pressDPadDown()
        waitFor("D-pad did not move to username") { it.loginView!!.username.hasFocus() }
        login()
        navigate("home")
        waitFor("Visible artwork did not load") { it.images.memoryBytes > 0 }
        capture("02-novidades")

        // Enter the selected content area with Right and open it with OK.
        navigate("movie")
        ui { it.catalogView!!.nav.getValue("movie").requestFocus() }
        device.pressDPadRight()
        ui { assertTrue(it.catalogView!!.grid.hasFocus()) }
        device.pressDPadCenter()
        waitFor("Movie detail did not open") { it.details != null }
        capture("03-filme-detalhes")
        ui { it.details!!.watch.requestFocus() }
        device.pressDPadRight(); device.pressDPadCenter()
        waitFor("Favorite was not saved") { it.database.page(provider.account, "favorites").total == 1 }
        device.pressBack()
        waitFor("Back did not dismiss detail") { it.details == null }
        navigate("favorites"); capture("04-favoritos")

        // Switching screens releases previous artwork and blocks late callbacks.
        val generation = AtomicReference(0)
        ui { generation.set(it.images.generation) }
        navigate("series")
        ui { assertTrue(it.images.generation > generation.get()); assertTrue(it.images.memoryBytes <= 6 * 1024 * 1024) }
        ui { it.catalogView!!.nav.getValue("series").requestFocus() }; device.pressDPadRight(); device.pressDPadCenter()
        waitFor("Series episodes missing") { it.details?.watch?.isEnabled == true }
        ui { activity ->
            val detail = activity.details!!
            assertEquals(2, detail.seasons.childCount)
            assertEquals("T1", (detail.seasons.getChildAt(0) as Button).text.toString())
            detail.seasons.getChildAt(1).requestFocus()
        }
        device.pressDPadCenter()
        waitFor("Season 2 not selected") { it.details!!.episodeList.adapter!!.itemCount == 3 }
        capture("05-serie-temporadas")
        ui { it.details!!.watch.requestFocus() }; device.pressDPadCenter()
        waitFor("Episode did not play", 30000) { it.playerView?.engine?.let { p -> p.playbackState == Player.STATE_READY && p.currentPosition > 1200 } == true }
        ui { assertEquals(2, it.playerView!!.item.season) }
        waitFor("Controls did not hide", 7000) { it.playerView?.controlsVisible == false }
        device.pressDPadCenter()
        ui { activity -> assertTrue(activity.playerView!!.controlsVisible); assertTrue("First OK paused unexpectedly", activity.playerView!!.engine!!.playWhenReady) }
        device.pressDPadCenter()
        waitFor("Remote pause failed") { it.playerView?.engine?.playWhenReady == false }
        capture("06-player")
        val position = AtomicReference(0L)
        ui { position.set(it.playerView!!.engine!!.currentPosition); it.playerView!!.timeline.requestFocus() }
        device.pressDPadRight()
        ui { assertTrue("Remote seek failed", it.playerView!!.engine!!.currentPosition >= position.get() + 9000) }
        ui { it.playerView!!.volume.requestFocus() }; device.pressDPadCenter(); device.pressDPadLeft(); device.pressDPadCenter()
        ui { assertEquals(.65f, it.playerView!!.engine!!.volume, .02f) }
        // From the slider, Right must reach Audio instead of trapping focus.
        device.pressDPadRight()
        ui { assertEquals("Faixa de áudio", it.currentFocus!!.contentDescription.toString()) }
        device.pressDPadCenter()
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(By.text("Faixa de áudio")), 3000))
        device.pressBack()
        focusDescription("Legendas"); device.pressDPadCenter()
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(By.text("Desativadas")), 3000))
        device.pressBack()

        // Backgrounding releases the decoder; foregrounding restores position.
        ui { position.set(it.playerView!!.engine!!.currentPosition) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui { assertNull(it.playerView!!.engine) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitFor("Suspended playback did not resume") { it.playerView?.engine?.let { p -> p.playbackState == Player.STATE_READY && p.currentPosition >= position.get() - 500 } == true }
        ui { it.leavePlayer() }
        waitFor("Continue progress missing") { it.database.page(provider.account, "continue").total == 1 }
        waitFor("Episode exit did not restore season 2") { it.details?.episodeList?.getChildAt(0)?.contentDescription?.toString()?.contains("Episódio 1:") == true && it.details!!.seriesSnapshot()?.episodes?.firstOrNull { e -> e.uid == it.currentFocus?.tag }?.season == 2 }
        device.pressBack(); waitFor("Returned episode picker did not close") { it.details == null }
        navigate("continue"); capture("07-continuar")

        // Account-scoped favorites, progress and encrypted remembered login survive restart.
        scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Saved session did not restore") { it.catalogView != null }
        ui { assertEquals(1, it.database.page(provider.account, "favorites").total); assertEquals(1, it.database.page(provider.account, "continue").total) }
        navigate("settings"); ui { it.catalogView!!.nextButton.requestFocus() }; device.pressDPadCenter()
        ui { assertTrue(it.database.autoNext) }; capture("08-configuracoes")

        // End the final episode of season 1; autoplay must cross into season 2.
        navigate("series"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Series not reopened") { it.details?.watch?.isEnabled == true }
        ui { it.details!!.seasons.getChildAt(0).performClick(); it.details!!.episodeList.scrollToPosition(2) }
        waitFor("Episode row unavailable") { it.details!!.episodeList.findViewHolderForAdapterPosition(2) != null }
        ui { it.details!!.episodeList.findViewHolderForAdapterPosition(2)!!.itemView.requestFocus() }; device.pressDPadCenter()
        waitFor("Season 1 episode 3 not playing") { it.playerView?.item?.let { c -> c.season == 1 && c.episode == 3 } == true && it.playerView?.engine?.playbackState == Player.STATE_READY }
        ui { it.playerView!!.engine!!.seekTo(it.playerView!!.engine!!.duration - 400); it.playerView!!.engine!!.play() }
        waitFor("Autoplay did not cross seasons", 12000) { it.playerView?.item?.let { c -> c.season == 2 && c.episode == 1 } == true }
        ui { it.leavePlayer() }
        waitFor("Autoplay exit did not select the current episode") { it.details != null && it.currentFocus?.tag == "episode:201" }
        device.pressBack(); waitFor("Autoplay episode picker did not close") { it.details == null }
        navigate("live"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Live detail missing") { it.details != null }
        ui { it.details!!.watch.requestFocus() }; device.pressDPadCenter()
        waitFor("Live playback did not start") { it.playerView?.engine?.playbackState == Player.STATE_READY }
        ui { assertEquals("live", it.playerView!!.item.kind); assertFalse(it.playerView!!.timeline.isEnabled); it.leavePlayer() }

        navigate("settings"); ui { it.catalogView!!.logoutButton.requestFocus() }; device.pressDPadCenter()
        waitFor("Logout failed") { it.loginView != null }
        ui { activity ->
            assertTrue(activity.loginView!!.password.text.isEmpty())
            assertNull(SessionVault(activity).restore())
            assertTrue(activity.database.autoNext)
        }
    }

    @Test fun responsiveLayoutKeepsControlsInsideTheScreen() {
        login(false)
        for (size in listOf("1280x720", "1920x1080")) {
            device.executeShellCommand("wm size $size")
            SystemClock.sleep(1200)
            val dimensions = size.split('x').map(String::toInt)
            waitFor("Display bounds did not update: $size") { it.window.decorView.width == dimensions[0] && it.window.decorView.height == dimensions[1] }
            navigate("movie")
            ui { activity ->
                val screen = Rect(0, 0, activity.window.decorView.width, activity.window.decorView.height)
                val order = Rect(); activity.catalogView!!.order.getGlobalVisibleRect(order)
                assertTrue("Sort control clipped at $size: $order", screen.contains(order))
                assertEquals(activity.catalogView!!.order.width, order.width())
                assertEquals(activity.catalogView!!.order.height, order.height())
                assertTrue(activity.catalogView!!.grid.childCount > 0)
                assertTrue(activity.catalogView!!.grid.width > 0)
            }
            capture("09-layout-$size")
            ui { it.database.record(provider.account, Content("episode", "101", "Episódio 1", seriesId = "4", seriesName = "Novos caminhos 4", season = 1, episode = 1), 5000, 20000) }
            navigate("series"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
            waitFor("Series layout not ready at $size") { it.details?.watch?.isEnabled == true && it.details!!.episodeList.childCount > 0 }
            ui { activity ->
                val detail = activity.details!!
                assertTrue("Episode list too short at $size", detail.episodeList.height >= activity.dp(43))
                listOf(detail.watch, detail.favorite, detail.seasons, detail.episodeList).forEach { view ->
                    val visible = Rect(); assertTrue(view.getGlobalVisibleRect(visible))
                    assertEquals("Control clipped horizontally at $size", view.width, visible.width())
                    assertEquals("Control clipped vertically at $size", view.height, visible.height())
                }
            }
            capture("10-serie-$size")
            device.pressBack(); waitFor("Series detail did not close") { it.details == null }
        }
        device.executeShellCommand("wm size reset")
    }

    @Test fun largeCatalogRefreshAndRapidRemoteInteractionsRemainResponsive() {
        provider.largeCatalog = true
        val started = SystemClock.uptimeMillis()
        ui { activity ->
            activity.loginView!!.apply {
                server.setText(provider.url); username.setText("demo"); password.setText("demopass")
                remember.isChecked = true; submit.requestFocus()
            }
        }
        device.pressDPadCenter()
        waitFor("Large catalog login did not open") { it.catalogView != null }
        navigate("settings")
        var worstPulse = 0L
        repeat(16) {
            ui { it.catalogView!!.nextButton.requestFocus() }
            device.pressDPadCenter()
            val latch = java.util.concurrent.CountDownLatch(1)
            val pulse = SystemClock.uptimeMillis()
            android.os.Handler(android.os.Looper.getMainLooper()).post { latch.countDown() }
            assertTrue("UI blocked while toggling autoplay during catalog import", latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
            worstPulse = maxOf(worstPulse, SystemClock.uptimeMillis() - pulse)
            ui { assertEquals("Unexpected toggle state", (it.catalogView!!.nextButton.text.toString().endsWith("Ligado")), it.database.autoNext) }
        }
        waitFor("Large catalog did not finish all sections", 60000) {
            it.catalogView!!.refreshButton.isEnabled && it.database.page(provider.account, "movie", limit = 1).total == 20000 && it.database.page(provider.account, "series", limit = 1).total == 6000 && it.database.page(provider.account, "live", limit = 1).total == 1000
        }
        val cold = SystemClock.uptimeMillis() - started
        repeat(8) { for (tab in listOf("movie", "series", "live", "settings")) {
            navigate(tab)
            ui { assertTrue(it.images.memoryBytes <= 6 * 1024 * 1024); assertTrue(it.images.pendingCount <= 48) }
        } }
        navigate("movie")
        repeat(12) {
            ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
            waitFor("Rapid detail did not open") { it.details != null }
            device.pressBack()
            waitFor("Rapid detail did not close") { it.details == null }
        }
        ui { it.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); assertEquals(0, it.images.memoryBytes) }
        capture("12-large-catalog-after-stress")
        val requests = provider.catalogRequests.get()
        scenario.close()
        val warmStart = SystemClock.uptimeMillis()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Cached catalog did not restore") { it.catalogView?.refreshButton?.isEnabled == true && it.database.page(provider.account, "movie", limit = 1).total == 20000 }
        assertEquals("Fresh catalog was downloaded again on restart", requests, provider.catalogRequests.get())
        android.util.Log.i("SmartIPTVTest", "PERFORMANCE: 27000 items, cold=${cold}ms, warm=${SystemClock.uptimeMillis() - warmStart}ms, worst UI heartbeat=${worstPulse}ms, downloads=$requests")
    }

    @Test fun completedEpisodeBadgePersistsAcrossSeasonsReplayAndRestart() {
        login()
        fun openSeries() {
            val showing = AtomicReference(false); ui { showing.set(it.details != null) }
            if (!showing.get()) { navigate("series"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter() }
            waitFor("Episode list not ready") { it.details?.watch?.isEnabled == true && it.details!!.episodeList.childCount > 0 }
        }
        fun firstRow(activity: MainActivity) = activity.details!!.episodeList.findViewHolderForAdapterPosition(0)?.itemView as? DetailView.EpisodeRow
        fun expectWatched(value: Boolean) {
            waitFor("Wrong watched episode badge or clipped layout: $value") {
                val badge = firstRow(it)?.badge
                if (!value) badge?.visibility == View.GONE
                else if (badge?.visibility != View.VISIBLE) false
                else {
                    val visible = Rect()
                    badge.getGlobalVisibleRect(visible) && badge.width == visible.width() && badge.height == visible.height()
                }
            }
            ui { activity ->
                val row = firstRow(activity)!!
                assertEquals(value, row.contentDescription.toString().contains(", Assistido"))
                if (value) {
                    val visible = Rect(); assertTrue(row.badge.getGlobalVisibleRect(visible))
                    assertEquals(row.badge.width, visible.width()); assertEquals(row.badge.height, visible.height())
                }
            }
        }
        openSeries(); expectWatched(false)
        ui { it.details!!.watch.requestFocus() }; device.pressDPadCenter()
        waitFor("Watched episode did not play") { it.playerView?.engine?.playbackState == Player.STATE_READY }
        ui { it.playerView!!.engine!!.seekTo(it.playerView!!.engine!!.duration - 400); it.playerView!!.engine!!.play() }
        waitFor("Playback did not record episode completion", 12000) { "episode:101" in it.database.watchedEpisodes(provider.account, "4") }
        ui { it.leavePlayer() }
        openSeries(); expectWatched(true)
        capture("13-episode-watched-1080p")
        ui { it.details!!.seasons.getChildAt(1).requestFocus() }; device.pressDPadCenter()
        expectWatched(false)
        ui { it.details!!.seasons.getChildAt(0).requestFocus() }; device.pressDPadCenter()
        expectWatched(true)

        // Replaying a completed episode keeps its badge and can still save a new resume position.
        ui { firstRow(it)!!.requestFocus() }; device.pressDPadCenter()
        waitFor("Completed episode could not be replayed") { it.playerView?.engine?.let { p -> p.playbackState == Player.STATE_READY && p.currentPosition > 1500 } == true }
        ui { it.leavePlayer() }
        openSeries(); expectWatched(true)
        device.pressBack(); waitFor("Episode modal did not close") { it.details == null }
        scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Session did not restore for watched history") { it.catalogView != null }
        openSeries(); expectWatched(true)

        device.executeShellCommand("wm size 1280x720")
        waitFor("720p episode layout not ready") { it.window.decorView.width == 1280 && firstRow(it)?.isShown == true }
        expectWatched(true); capture("14-episode-watched-720p")
        ui { firstRow(it)!!.requestFocus() }
        device.pressDPadDown()
        waitFor("Watched badge broke D-pad episode navigation") { it.details!!.episodeList.findViewHolderForAdapterPosition(1)?.itemView?.hasFocus() == true }
        device.executeShellCommand("wm size reset")
    }

    @Test fun movieExitReturnsToDetailsAndShowsResumeTimeInTheMovieCatalog() {
        login()
        navigate("movie"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Movie metadata did not load") { it.details?.moviePlotSnapshot() != null }
        ui { it.details!!.favorite.requestFocus() }; device.pressDPadCenter()
        waitFor("Movie favorite did not save") { it.database.page(provider.account, "favorites").total == 1 }
        ui { it.details!!.watch.requestFocus() }; device.pressDPadCenter()
        waitFor("Movie did not play") { it.playerView?.item?.uid == "movie:120" && it.playerView?.engine?.playbackState == Player.STATE_READY }
        ui { it.playerView!!.engine!!.pause(); it.playerView!!.engine!!.seekTo(7300) }
        waitFor("Movie seek did not settle") { it.playerView?.engine?.currentPosition == 7300L }
        val requests = provider.movieInfoRequests.get()
        focusDescription("Voltar ao filme"); device.pressDPadCenter()
        fun expectReturn() {
            waitFor("Movie exit did not restore details and saved time") {
                it.playerView == null && it.details?.item?.uid == "movie:120" && it.details?.movieResume?.text?.toString() == "Parou em 00:07" && it.details?.watch?.hasFocus() == true
            }
            ui { assertEquals("Continuar assistindo", it.details!!.watch.text.toString()) }
        }
        expectReturn()
        assertEquals("Returning to the movie downloaded its metadata again", requests, provider.movieInfoRequests.get())
        capture("17-movie-resume-return-1080p")
        device.pressBack(); waitFor("Movie detail did not close to its catalog") { it.details == null }
        waitFor("Movie catalog card did not display saved time") {
            (it.catalogView!!.grid.findViewHolderForAdapterPosition(0) as? CatalogAdapter.Holder)?.tile?.description?.text?.toString() == "Parou em 00:07"
        }
        capture("18-movie-catalog-resume")
        scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Movie resume session did not restore") { it.catalogView != null }
        navigate("favorites"); waitFor("Movie favorite sections not loaded") { it.catalogView!!.count.text.toString() == "1 conteúdos" }
        ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Favorite movie lost saved time after restart") { it.details?.movieResume?.text?.toString() == "Parou em 00:07" }
        ui { it.details!!.watch.requestFocus() }; device.pressDPadCenter()
        waitFor("Movie did not resume from its saved position") { it.playerView?.engine?.let { p -> p.playbackState == Player.STATE_READY && p.currentPosition >= 7000 } == true }
        ui { it.playerView!!.engine!!.pause(); it.playerView!!.engine!!.seekTo(7300); it.playerView!!.hideControls() }
        device.pressBack(); expectReturn()
        device.executeShellCommand("wm size 1280x720")
        waitFor("Movie resume controls clipped at 720p") {
            it.window.decorView.width == 1280 && listOfNotNull(it.details?.movieResume, it.details?.watch).let { views -> views.size == 2 && views.all { v -> val r = Rect(); v.getGlobalVisibleRect(r) && r.width() == v.width && r.height() == v.height } }
        }
        capture("19-movie-resume-return-720p")
        device.pressBack(); waitFor("Movie detail did not close to favorites") { it.details == null }
        ui { activity ->
            val screen = activity.catalogView!!
            val pending = CatalogView::class.java.getDeclaredField("focusAfterLoad").apply { isAccessible = true }.getBoolean(screen)
            android.util.Log.i("SmartIPTVTest", "MOVIE_RETURN_FOCUS pending=$pending gridShown=${screen.grid.isShown} gridFocus=${screen.grid.hasFocus()} children=${screen.grid.childCount} current=${activity.currentFocus?.javaClass?.simpleName} count=${screen.count.text}")
        }
        waitFor("Favorite movie navigation lost focus") { it.catalogView!!.grid.hasFocus() }
        capture("23-favorite-movie-focus-after-return-720p")
        device.executeShellCommand("wm size reset")
    }

    @Test fun favoritesAreSeparatedAndRemainSearchableSortableAndRemoteNavigable() {
        login()
        ui { activity ->
            val db = activity.database
            db.page(provider.account, "movie", limit = 3).items.forEach { db.toggleFavorite(provider.account, it) }
            db.page(provider.account, "series", limit = 2).items.forEach { db.toggleFavorite(provider.account, it) }
            db.toggleFavorite(provider.account, db.page(provider.account, "live", limit = 1).items.single())
        }
        navigate("favorites")
        waitFor("Favorite sections did not load") { it.catalogView!!.count.text.toString() == "6 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 9 }
        ui { activity ->
            val grid = activity.catalogView!!.grid
            assertTrue(grid.adapter is androidx.recyclerview.widget.ConcatAdapter)
            val manager = grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager
            listOf(0, 4, 7).forEach { assertEquals(manager.spanCount, manager.spanSizeLookup.getSpanSize(it)) }
            listOf(1, 2, 3, 5, 6, 8).forEach { assertEquals(1, manager.spanSizeLookup.getSpanSize(it)) }
            activity.catalogView!!.focusContent()
        }
        waitFor("Favorites did not focus a movie card") { (it.currentFocus as? CatalogAdapter.Tile)?.title?.text?.toString()?.let { name -> name.startsWith("Filme") || name == "Além do horizonte" } == true }
        capture("20-favorites-movies-and-series-1080p")
        device.pressDPadDown()
        waitFor("D-pad did not cross the favorite section heading") { (it.currentFocus as? CatalogAdapter.Tile)?.title?.text?.toString()?.startsWith("Novos caminhos") == true }
        capture("21-favorites-series-focus")
        device.pressDPadLeft()
        waitFor("Left from series favorites did not reach sidebar") { it.catalogView!!.nav.getValue("favorites").hasFocus() }
        ui { it.catalogView!!.search.setText("caminhos") }
        waitFor("Favorite search did not filter across sections") { it.catalogView!!.count.text.toString() == "2 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 3 }
        ui { it.catalogView!!.order.requestFocus() }; device.pressDPadCenter()
        waitFor("Favorite section ordering failed") { it.catalogView!!.order.text.toString() == "Nome A–Z" && it.catalogView!!.count.text.toString() == "2 conteúdos" && (it.catalogView!!.grid.findViewHolderForAdapterPosition(1) as? CatalogAdapter.Holder)?.card?.item?.name == "Novos caminhos 3" }
        ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Filtered series favorite did not open") { it.details?.watch?.isEnabled == true }
        ui { it.details!!.favorite.requestFocus() }; device.pressDPadCenter()
        waitFor("Favorite removal failed") { it.database.page(provider.account, "favorites").total == 5 }
        device.pressBack()
        waitFor("Empty favorite section was not removed on reload") { it.details == null && it.catalogView!!.count.text.toString() == "1 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 2 }
        ui { it.catalogView!!.search.setText("zzznada") }
        waitFor("Empty favorites search left stale section headers") { it.catalogView!!.count.text.toString() == "0 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 0 }
        ui { it.catalogView!!.search.text.clear() }
        waitFor("Clearing favorite search lost saved contents") { it.catalogView!!.count.text.toString() == "5 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 8 }
        device.executeShellCommand("wm size 1280x720")
        waitFor("720p favorite grid not ready") { it.window.decorView.width == 1280 && it.catalogView!!.grid.childCount > 0 }
        ui { it.catalogView!!.focusContent() }; device.pressDPadDown()
        waitFor("720p favorites control navigation failed") { it.catalogView!!.grid.hasFocus() }
        capture("22-favorites-sections-720p")
        ui { assertTrue(it.images.memoryBytes <= 6 * 1024 * 1024); assertTrue(it.images.pendingCount <= 48) }
        device.executeShellCommand("wm size reset")
    }

    @Test fun favoritesOpenFirstAndNovidadesPreservesTheFormerHomeContent() {
        login()
        ui { activity ->
            val screen = activity.catalogView!!
            assertEquals("favorites", screen.tab)
            assertEquals(listOf("favorites", "live", "movie", "series", "continue", "home", "settings"), screen.nav.keys.toList())
            assertEquals("Favoritos", screen.nav.getValue("favorites").text.toString())
            assertEquals("Novidades", screen.nav.getValue("home").text.toString())
            assertTrue(screen.nav.getValue("favorites").hasFocus())
            assertTrue(screen.nav.values.none { it.text.toString() == "Início" })
            activity.database.toggleFavorite(provider.account, activity.database.page(provider.account, "movie", limit = 1).items.single())
            activity.database.toggleFavorite(provider.account, activity.database.page(provider.account, "series", limit = 1).items.single())
            screen.reload()
        }
        waitFor("Default Favorites did not display stored contents") { it.catalogView!!.count.text.toString() == "2 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 4 }
        capture("24-favorites-default-start")
        navigate("home")
        waitFor("Novidades artwork did not load") { it.images.memoryBytes > 0 }
        ui { it.catalogView!!.focusContent() }
        waitFor("Novidades did not retain its content shelves") { (it.currentFocus as? CatalogAdapter.Tile)?.title?.text?.toString() == "Além do horizonte" }
        capture("25-novidades-menu-order")
        device.pressBack()
        waitFor("Back did not return to Favorites") { it.catalogView?.tab == "favorites" && it.catalogView!!.nav.getValue("favorites").hasFocus() }
        navigate("home")
        scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Remembered session did not start on Favorites") { it.catalogView?.tab == "favorites" && it.catalogView!!.count.text.toString() == "2 conteúdos" }
        ui { assertTrue(it.catalogView!!.nav.getValue("favorites").hasFocus()); assertEquals(2, it.database.page(provider.account, "favorites").total) }
    }

    @Test fun directionalCatalogNavigationDoesNotWrapAcrossRowsOrFavoriteSections() {
        login()
        ui { activity ->
            val db = activity.database
            db.toggleFavorite(provider.account, db.page(provider.account, "movie", limit = 1).items.single())
            db.page(provider.account, "series", limit = 4).items.forEach { db.toggleFavorite(provider.account, it) }
            activity.catalogView!!.reload()
        }
        fun expectPosition(position: Int) = waitFor("Directional focus did not reach card $position") { focusedGridPosition(it) == position }
        for (size in listOf("1920x1080", "1280x720")) {
            device.executeShellCommand("wm size $size")
            val width = size.substringBefore('x').toInt()
            waitFor("Directional display did not resize: $size") { it.window.decorView.width == width }
            if (size == "1280x720") navigate("favorites")
            waitFor("Video fixture Favorites did not load") { it.catalogView!!.count.text.toString() == "5 conteúdos" && it.catalogView!!.grid.adapter?.itemCount == 7 }
            selectGridPosition(1)
            device.pressDPadRight()
            waitFor("Right from the lone movie wrapped into series instead of ordering") { it.catalogView!!.order.hasFocus() }
            repeat(2) { device.pressDPadRight() }
            ui { assertTrue("Right at the toolbar edge moved down", it.catalogView!!.order.hasFocus()) }
            capture("26-directional-right-$size")
            device.pressDPadUp(); waitFor("Up from ordering did not reach search") { it.catalogView!!.search.hasFocus() }
            device.pressDPadDown(); waitFor("Down from search did not reach ordering") { it.catalogView!!.order.hasFocus() }
            device.pressDPadLeft(); expectPosition(1)
            device.pressDPadUp(); waitFor("Up from the first movie did not reach search") { it.catalogView!!.search.hasFocus() }
            device.pressDPadLeft(); waitFor("Left from search did not reach the current sidebar tab") { it.catalogView!!.nav.getValue("favorites").hasFocus() }
            device.pressDPadRight(); expectPosition(1)
            device.pressDPadDown(); expectPosition(3)
            device.pressDPadUp(); expectPosition(1)
            device.pressDPadLeft(); waitFor("Left from the movie did not reach Favorites sidebar") { it.catalogView!!.nav.getValue("favorites").hasFocus() }
            device.pressDPadDown(); waitFor("Sidebar Down did not follow menu order") { it.catalogView!!.nav.getValue("live").hasFocus() }
            device.pressDPadUp(); waitFor("Sidebar Up did not return to Favorites") { it.catalogView!!.nav.getValue("favorites").hasFocus() }

            navigate("movie")
            waitFor("Directional movie columns did not settle") {
                val grid = it.catalogView!!.grid
                !grid.isLayoutRequested && (grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount == (grid.width / it.dp(128)).coerceIn(2, 8)
            }
            val columns = AtomicReference(0)
            ui { columns.set((it.catalogView!!.grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount) }
            selectGridPosition(columns.get() - 1)
            device.pressDPadRight(); waitFor("Right at a complete row wrapped to the next row") { it.catalogView!!.order.hasFocus() }
            device.pressDPadLeft(); expectPosition(columns.get() - 1)
            device.pressDPadDown(); expectPosition(columns.get() * 2 - 1)
            device.pressDPadLeft(); expectPosition(columns.get() * 2 - 2)
            var position = columns.get() * 2 - 2
            // Reach another metadata page through real remote input, rather
            // than a programmatic jump that keeps an unrelated child focused.
            while (position < 60) {
                position = minOf(position + columns.get(), 119)
                device.pressDPadDown(); expectPosition(position)
            }
            waitFor("Paged directional card metadata did not load") { (it.catalogView!!.grid.findViewHolderForAdapterPosition(position) as? CatalogAdapter.Holder)?.card != null }
            device.pressDPadUp(); expectPosition(position - columns.get())
            device.pressDPadDown(); expectPosition(position)
            while ((position / columns.get() + 1) * columns.get() < 120) {
                position = minOf((position / columns.get() + 1) * columns.get() + position % columns.get(), 119)
                device.pressDPadDown(); expectPosition(position)
            }
            while (position < 119) { position++; device.pressDPadRight(); expectPosition(position) }
            device.pressDPadDown(); expectPosition(119)
            device.pressDPadRight(); waitFor("Right at the last catalog item did not reach ordering") { it.catalogView!!.order.hasFocus() }
            device.pressDPadLeft(); expectPosition(119)
            capture("27-directional-paged-grid-$size")
        }
        device.executeShellCommand("wm size reset")
        navigate("home")
        waitFor("Directional Novidades shelf did not load") { it.images.memoryBytes > 0 }
        focusDescription("Ver todos os filmes"); device.pressDPadUp()
        waitFor("Novidades link Up did not reach search") { it.catalogView!!.search.hasFocus() }
        device.pressDPadDown()
        waitFor("Search Down did not reach the Novidades link below it") { it.currentFocus?.contentDescription?.toString() == "Ver todos os filmes" }
        device.pressDPadDown()
        waitFor("Novidades link Down did not reach its movie shelf") { it.currentFocus is CatalogAdapter.Tile }
        val gallery = AtomicReference<androidx.recyclerview.widget.RecyclerView>()
        ui { gallery.set(it.currentFocus!!.parent as androidx.recyclerview.widget.RecyclerView); gallery.get().scrollToPosition(17) }
        waitFor("Last Novidades shelf card did not bind") { (gallery.get().findViewHolderForAdapterPosition(17) as? CatalogAdapter.Holder)?.card != null }
        ui { gallery.get().findViewHolderForAdapterPosition(17)!!.itemView.requestFocus() }
        device.pressDPadRight()
        waitFor("Novidades shelf edge wrapped down into series") { it.currentFocus?.contentDescription?.toString() == "Ver todos os filmes" }
    }

    @Test fun episodeExitRestoresSameSeasonAndEpisodeWithPersistentResumeTime() {
        login()
        navigate("series"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Resume series picker did not load") { it.details?.watch?.isEnabled == true }
        ui { it.details!!.seasons.getChildAt(1).requestFocus() }; device.pressDPadCenter()
        waitFor("Second season rows not ready") { it.details!!.episodeList.findViewHolderForAdapterPosition(1) != null }
        ui { it.details!!.episodeList.findViewHolderForAdapterPosition(1)!!.itemView.requestFocus() }; device.pressDPadCenter()
        waitFor("Season 2 episode 2 did not play") { it.playerView?.item?.uid == "episode:202" && it.playerView?.engine?.playbackState == Player.STATE_READY }
        ui { it.playerView!!.engine!!.pause(); it.playerView!!.engine!!.seekTo(7300) }
        waitFor("Partial episode seek did not settle") { it.playerView?.engine?.currentPosition == 7300L }
        val requests = provider.seriesInfoRequests.get()
        focusDescription("Voltar aos episódios"); device.pressDPadCenter()
        fun returnedRow(activity: MainActivity): DetailView.EpisodeRow? = activity.details?.episodeList?.findViewHolderForAdapterPosition(1)?.itemView as? DetailView.EpisodeRow
        fun expectReturn() {
            waitFor("Player exit did not return to series 4 season 2 episode 2") {
                it.playerView == null && it.details?.item?.id == "4" && returnedRow(it)?.tag == "episode:202" && returnedRow(it)?.hasFocus() == true && returnedRow(it)?.progress?.text?.toString() == "Parou em 00:07"
            }
            ui {
                val row = returnedRow(it)!!
                assertFalse(it.catalogView!!.grid.hasFocus())
                assertEquals(View.GONE, row.badge.visibility)
                assertTrue(row.contentDescription.toString().contains("Parou em 00:07"))
                val visible = Rect(); assertTrue(row.progress.getGlobalVisibleRect(visible))
                assertEquals(row.progress.width, visible.width()); assertEquals(row.progress.height, visible.height())
            }
        }
        expectReturn()
        assertEquals("Returning from player downloaded the episode list again", requests, provider.seriesInfoRequests.get())
        capture("15-episode-resume-and-return-1080p")
        device.pressDPadCenter()
        waitFor("OK on returned episode did not resume at saved position") { it.playerView?.item?.uid == "episode:202" && it.playerView?.engine?.let { p -> p.playbackState == Player.STATE_READY && p.currentPosition >= 7000 } == true }
        ui { it.playerView!!.engine!!.pause(); it.playerView!!.engine!!.seekTo(7300); it.playerView!!.hideControls() }
        device.pressBack(); expectReturn()

        // Closing the picker then restarting must preserve per-row progress.
        device.pressBack(); waitFor("Picker did not return to series catalog") { it.details == null }
        scenario.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Resume time session did not restore") { it.catalogView != null }
        navigate("continue"); ui { it.catalogView!!.focusContent() }; device.pressDPadCenter()
        waitFor("Continue watching did not resume episode 202") { it.playerView?.item?.uid == "episode:202" && it.playerView?.engine?.playbackState == Player.STATE_READY }
        ui { it.playerView!!.engine!!.pause(); it.playerView!!.engine!!.seekTo(7300) }
        focusDescription("Voltar aos episódios"); device.pressDPadCenter(); expectReturn()
        device.executeShellCommand("wm size 1280x720")
        waitFor("720p resume picker not ready") { it.window.decorView.width == 1280 && returnedRow(it)?.progress?.let { p -> val rect = Rect(); p.getGlobalVisibleRect(rect) && p.width == rect.width() && p.height == rect.height() } == true }
        capture("16-episode-resume-and-return-720p")
        ui { returnedRow(it)!!.requestFocus() }; device.pressDPadDown()
        waitFor("Resume status broke episode remote navigation") { it.currentFocus?.tag == "episode:203" }
        device.executeShellCommand("wm size reset")
    }
}
