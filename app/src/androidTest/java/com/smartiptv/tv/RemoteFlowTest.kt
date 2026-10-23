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
        val context = instrumentation.targetContext
        context.deleteDatabase("library.db")
        context.getSharedPreferences("session", 0).edit().clear().commit()
        provider = TestProvider(instrumentation.context)
        device = UiDevice.getInstance(instrumentation)
        androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(200).setWaitForSelectorTimeout(3000)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("Login ready") { it.loginView?.submit?.isEnabled == true }
    }
    @After fun cleanup() { device.executeShellCommand("wm size reset"); scenario.close(); provider.close() }
    private fun ui(action: (MainActivity) -> Unit) { scenario.onActivity(action) }
    private fun waitFor(description: String, timeout: Long = 20000, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            val result = AtomicReference(false)
            scenario.onActivity { result.set(condition(it)) }
            if (result.get()) return
            SystemClock.sleep(80)
        }
        throw AssertionError(description)
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir("screenshots")!!
        directory.mkdirs()
        device.waitForIdle(200)
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    private fun login(remember: Boolean = true) {
        ui { activity ->
            val form = activity.loginView!!
            form.server.setText(provider.url); form.username.setText("demo"); form.password.setText("demopass")
            form.remember.isChecked = remember; form.submit.requestFocus()
        }
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

    @Test fun completeRemoteFlowAndRealMedia() {
        ui { activity ->
            val form = activity.loginView!!
            assertEquals("", form.server.hint?.toString() ?: "")
            assertTrue(form.server.text.isEmpty()); assertTrue(form.username.text.isEmpty()); assertTrue(form.password.text.isEmpty())
            form.server.requestFocus()
        }
        capture("01-login-1080p")
        device.pressDPadDown()
        ui { assertTrue("D-pad did not move to username", it.loginView!!.username.hasFocus()) }
        login()
        waitFor("Visible artwork did not load") { it.images.memoryBytes > 0 }
        capture("02-inicio")

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
            navigate("movie")
            ui { activity ->
                val screen = Rect(0, 0, activity.window.decorView.width, activity.window.decorView.height)
                val order = Rect(); activity.catalogView!!.order.getGlobalVisibleRect(order)
                assertTrue("Sort control clipped at $size: $order", screen.contains(order))
                assertTrue(activity.catalogView!!.grid.childCount > 0)
                assertTrue(activity.catalogView!!.grid.width > 0)
            }
            capture("09-layout-$size")
        }
        device.executeShellCommand("wm size reset")
    }
}
