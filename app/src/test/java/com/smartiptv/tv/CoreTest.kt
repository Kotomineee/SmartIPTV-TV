package com.smartiptv.tv

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CoreTest {
    private lateinit var context: Context
    private lateinit var db: LibraryDb
    private lateinit var server: MockWebServer
    private val movie = Content("movie", "one", "Além do horizonte", listOf("1"), added = 10)
    private val series = Content("series", "series1", "Novos caminhos")
    private val account = "account-a"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("library.db")
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        db = LibraryDb(context)
        server = MockWebServer().apply { start() }
    }
    @After fun cleanup() { db.close(); server.shutdown() }
    private fun api(user: String = "demo", password: String = "demopass") = XtreamApi(Credentials.validated(server.url("/").toString(), user, password))
    private fun response(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }
    private fun store(items: List<Content>, kind: String = "movie") = db.replaceSection(account, kind, listOf(Category("1", "Aventura"), Category("2", "Drama"))) { sink -> items.forEach(sink) }

    @Test fun serverValidationKeepsEditablePortAndBasePath() {
        val credentials = Credentials.validated(" https://example.test:8443/base/ ", " demo ", "secret")
        assertEquals("https://example.test:8443/base", credentials.server)
        assertEquals("demo", credentials.username)
        assertFalse(credentials.toString().contains("secret"))
    }
    @Test fun rejectsMalformedServersAndMissingCredentials() {
        listOf("example.test", "ftp://example.test", "https://u:p@example.test", "http://example.test?password=p", "http://example.test/#fragment").forEach {
            assertThrows(ProviderFailure::class.java) { Credentials.validated(it, "demo", "secret") }
        }
        assertThrows(ProviderFailure::class.java) { Credentials.validated("http://example.test", "", "secret") }
        assertThrows(ProviderFailure::class.java) { Credentials.validated("http://example.test", "demo", "") }
    }
    @Test fun authenticationAndAccountMetadata() {
        response("""{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000","active_cons":"1","max_connections":"2"}}""")
        assertEquals(Account(1893456000L, "1", "2", "Active"), api().authenticate())
    }
    @Test fun rejectedAndExpiredAccountsNeverEchoCredentials() {
        response("""{"user_info":{"auth":0}}""")
        val error = assertThrows(ProviderFailure::class.java) { api(password = "sensitive-value").authenticate() }
        assertFalse(error.message!!.contains("sensitive-value"))
        response("""{"user_info":{"auth":1,"status":"Expired"}}""")
        assertThrows(ProviderFailure::class.java) { api().authenticate() }
    }
    @Test fun httpAndSchemaFailuresAreFriendly() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("secret diagnostic"))
        val error = assertThrows(ProviderFailure::class.java) { api().authenticate() }
        assertFalse(error.message!!.contains("secret diagnostic"))
        response("[]")
        assertThrows(ProviderFailure::class.java) { api().authenticate() }
    }
    @Test fun playbackEscapesEveryPathSegmentAndRejectsUnsafeExtension() {
        val client = api("user/name", "p/ass word")
        val url = client.playbackUrl(movie.copy(id = "../x", extension = "../../bad"))
        assertTrue(url.contains("user%2Fname/p%2Fass%20word/..%2Fx.mp4"))
        assertTrue(client.playbackUrl(Content("live", "10", "Canal")).endsWith("10.ts"))
        assertThrows(ProviderFailure::class.java) { client.playbackUrl(series) }
    }
    @Test fun streamedCatalogDeduplicatesAndCanBePagedWithoutLoadingEverything() {
        val entries = JSONArray()
        repeat(1200) { entries.put(JSONObject().put("stream_id", it + 1).put("name", "Filme $it").put("added", it).put("category_id", "1")) }
        entries.put(entries.getJSONObject(0))
        response(entries.toString())
        store(emptyList())
        db.replaceSection(account, "movie", listOf(Category("1", "Aventura"))) { sink -> api().catalog("movie", sink) }
        val page = db.page(account, "movie", offset = 80, limit = 40)
        assertEquals(1200, page.total); assertEquals(40, page.items.size); assertEquals("Filme 1119", page.items[0].name)
        assertEquals(1200, db.page(account, "movie", category = "1").total)
    }
    @Test fun failedCatalogRefreshRollsBackThePreviousSection() {
        store(listOf(movie))
        response("""{"error":"bad provider"}""")
        assertThrows(ProviderFailure::class.java) { db.replaceSection(account, "movie", emptyList()) { sink -> api().catalog("movie", sink) } }
        assertEquals(movie.uid, db.page(account, "movie").items.single().uid)
        assertEquals(2, db.categories(account, "movie").size)
    }
    @Test fun categoriesAndSearchAreAccountScopedAndAccentInsensitive() {
        store(listOf(movie, movie.copy(id = "two", name = "Outro", categories = listOf("2"))))
        assertEquals(1, db.page(account, "movie", category = "1").total)
        assertEquals(1, db.page(account, "movie", search = "alem").total)
        assertEquals(0, db.page("other-account", "movie").total)
        assertEquals(0, db.page(account, "movie", search = "%' OR 1=1 --").total)
        assertEquals(0, db.page(account, "movie", search = "_").total)
    }
    @Test fun homeSearchIncludesMoviesSeriesAndLive() {
        store(listOf(movie)); store(listOf(series.copy(name = "Além das cidades")), "series")
        assertEquals(2, db.searchAll(account, "alem", false, 0, 40).total)
    }
    @Test fun seasonsComeFromAvailableEpisodesEvenWithEmptyProviderSeasons() {
        val payload = JSONObject("""{"seasons":[],"episodes":{"2":[{"id":"201","season":2,"episode_num":1,"title":"Segundo ano"}],"1":[{"id":"102","episode_num":2},{"id":"101","episode_num":1},{"id":"101","episode_num":1}]}}""")
        val result = parseEpisodes(series, payload).episodes
        assertEquals(listOf("101", "102", "201"), result.map { it.id })
        assertEquals(listOf(1, 2), result.map { it.season }.distinct())
        assertEquals("Novos caminhos · T02 E01", result.last().displayName)
    }
    @Test fun emptyAndListEpisodePayloadsAreSupported() {
        assertTrue(parseEpisodes(series, JSONObject()).episodes.isEmpty())
        assertEquals(1, parseEpisodes(series, JSONObject("""{"episodes":[{"id":1,"episode_num":1}]}""")).episodes.size)
    }
    @Test fun favoritesSurviveRestartAndRemainAccountIsolated() {
        assertTrue(db.toggleFavorite(account, movie)); assertFalse(db.isFavorite("account-b", movie))
        db.close(); db = LibraryDb(context)
        assertTrue(db.isFavorite(account, movie)); assertEquals(movie, db.page(account, "favorites").items.single())
        assertFalse(db.toggleFavorite(account, movie)); assertEquals(0, db.page(account, "favorites").total)
    }
    @Test fun progressResumesAndCompletionRemovesContinueEntry() {
        val episode = Content("episode", "101", "Episódio 1", seriesId = series.id, seriesName = series.name, season = 1, episode = 1)
        db.record(account, episode, 7000, 20000)
        assertEquals(7000L, db.checkpoint(account, series)!!.position)
        assertEquals(episode.uid, db.page(account, "continue").items.single().uid)
        db.close(); db = LibraryDb(context)
        assertEquals(7000L, db.checkpoint(account, episode)!!.position)
        db.record(account, episode, 20000, 20000, true)
        assertNull(db.checkpoint(account, episode)); assertEquals(0, db.page(account, "continue").total)
    }
    @Test fun liveAndUnstartedItemsAreExcludedAndProgressCanBeRemoved() {
        db.record(account, Content("live", "1", "Canal"), 90000, 100000)
        db.record(account, movie, 500, 20000)
        assertEquals(0, db.page(account, "continue").total)
        db.record(account, movie, 5000, 20000); db.removeProgress(account, movie)
        assertNull(db.checkpoint(account, movie))
    }
    @Test fun autoNextPreferenceSurvivesRestart() {
        assertFalse(db.autoNext); db.autoNext = true; db.close(); db = LibraryDb(context); assertTrue(db.autoNext)
    }
    @Test fun sessionIsEncryptedAndCanBeClearedWithoutRemovingHints() {
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val vault = SessionVault(context) { key }
        val credentials = Credentials.validated("http://example.test", "demo", "private-password")
        vault.remember(credentials, true)
        val stored = context.getSharedPreferences("session", Context.MODE_PRIVATE).getString("credentials", "")!!
        assertFalse(stored.contains("private-password")); assertFalse(stored.contains("example.test"))
        assertEquals("private-password", vault.restore()!!.password)
        vault.clear(); assertNull(vault.restore()); assertEquals("demo", vault.lastUser)
    }
    @Test fun corruptSessionNeverFallsBackToPlaintext() {
        val vault = SessionVault(context) { SecretKeySpec(ByteArray(32), "AES") }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("credentials", "plaintext-password").commit()
        assertThrows(ProviderFailure::class.java) { vault.restore() }
        assertNull(vault.restore())
    }
    @Test fun unrememberedLoginNeverPersistsPassword() {
        val vault = SessionVault(context) { SecretKeySpec(ByteArray(32), "AES") }
        vault.remember(Credentials.validated("http://example.test", "demo", "private-password"), false)
        assertNull(vault.restore())
        assertFalse(context.getSharedPreferences("session", Context.MODE_PRIVATE).all.values.toString().contains("private-password"))
    }
    @Test fun gridMetadataIsBoundedAndLatePagesCannotReturnToAnotherTab() {
        val loader = ArtworkLoader(context)
        val adapter = CatalogAdapter(loader, {}, {}, { offset, limit, done ->
            done(CardPage((offset until offset + limit).map { CardData(movie.copy(id = "$it"), false, null) }, 10000))
        })
        adapter.reset(CardPage(listOf(CardData(movie, false, null)), 10000))
        val parent = FrameLayout(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        repeat(30) { adapter.onBindViewHolder(holder, it * 40) }
        assertTrue(adapter.retainedItems <= 200)
        var callback: ((CardPage) -> Unit)? = null
        val delayed = CatalogAdapter(loader, {}, {}, { _, _, done -> callback = done })
        delayed.reset(CardPage(emptyList(), 1000)); delayed.onBindViewHolder(delayed.onCreateViewHolder(parent, 0), 80)
        delayed.reset(CardPage(listOf(CardData(movie, false, null)), 1))
        callback!!(CardPage(listOf(CardData(series, false, null)), 1000))
        assertEquals(1, delayed.count); assertEquals(1, delayed.retainedItems)
        loader.switchScreen(); assertEquals(0, loader.memoryBytes); loader.close()
    }
}
