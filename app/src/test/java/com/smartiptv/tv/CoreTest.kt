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
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
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
    @Test fun favoriteSectionsKeepTypesAccountsAndLegacyEpisodeFavoritesSeparate() {
        val episode = Content("episode", "101", "Estreia", seriesId = series.id)
        val live = Content("live", "live1", "Canal")
        listOf(movie, series, episode, live).forEach { db.toggleFavorite(account, it) }
        db.toggleFavorite("other-account", movie.copy(id = "other"))
        db.close(); db = LibraryDb(context)
        assertEquals(listOf(movie.uid), db.page(account, "favorites", favoriteKind = "movie").items.map { it.uid })
        assertEquals(setOf(series.uid, episode.uid), db.page(account, "favorites", favoriteKind = "series").items.map { it.uid }.toSet())
        assertEquals(listOf(live.uid), db.page(account, "favorites", favoriteKind = "live").items.map { it.uid })
        assertEquals(4, db.page(account, "favorites").total)
        assertEquals(0, db.page("missing", "favorites", favoriteKind = "movie").total)
        db.toggleFavorite(account, movie)
        assertEquals(0, db.page(account, "favorites", favoriteKind = "movie").total)
        assertEquals(2, db.page(account, "favorites", favoriteKind = "series").total)
    }
    @Test fun favoriteSectionsPaginateSearchAndSortWithoutMixingContentTypes() {
        repeat(85) { db.toggleFavorite(account, movie.copy(id = "m$it", name = "Filme %03d".format(it))) }
        repeat(45) { db.toggleFavorite(account, series.copy(id = "s$it", name = "Série %03d".format(it))) }
        val first = db.page(account, "favorites", alphabetical = true, limit = 40, favoriteKind = "movie")
        val second = db.page(account, "favorites", alphabetical = true, offset = 40, limit = 40, favoriteKind = "movie")
        assertEquals(85, first.total); assertEquals(40, first.items.size)
        assertEquals("Filme 040", second.items.first().name)
        assertTrue(first.items.none { it in second.items })
        assertEquals(5, db.page(account, "favorites", offset = 80, limit = 40, favoriteKind = "movie").items.size)
        assertEquals(45, db.page(account, "favorites", favoriteKind = "series").total)
        assertEquals(5, db.page(account, "favorites", search = "serie 04", favoriteKind = "series").total)
        assertEquals(0, db.page(account, "favorites", search = "serie", favoriteKind = "movie").total)
    }
    @Test fun movieResumeTimeSurvivesRestartAndCompletionKeepsTheFavorite() {
        db.toggleFavorite(account, movie)
        db.record(account, movie, 754000, 2400000)
        db.close(); db = LibraryDb(context)
        assertEquals(754000L, db.cards(account, Page(listOf(movie), 1)).cards.single().progress!!.position)
        assertEquals(754000L, db.checkpoint(account, movie)!!.position)
        db.record(account, movie, 2400000, 2400000, true)
        assertNull(db.checkpoint(account, movie))
        assertNull(db.cards(account, db.page(account, "favorites", favoriteKind = "movie")).cards.single().progress)
        assertTrue(db.isFavorite(account, movie))
    }
    @Test fun movieTileUpdatesAndClearsItsVisibleResumeTime() {
        val loader = ArtworkLoader(context)
        val adapter = CatalogAdapter(loader, {}, {}, { _, _, _ -> })
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val item = movie.copy(year = "2025")
        holder.bind(CardData(item, true, Progress(item, 3723000, 0, false, 1)))
        assertEquals("Parou em 1:02:03", holder.tile.description.text.toString())
        assertTrue(holder.tile.contentDescription.toString().contains("Parou em 1:02:03"))
        holder.indicators(CardData(item, true, null))
        assertEquals("2025", holder.tile.description.text.toString())
        assertFalse(holder.tile.contentDescription.toString().contains("Parou em"))
        holder.bind(null); assertEquals("", holder.tile.description.text.toString())
        loader.close()
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
    @Test fun loginLayoutSettlesAfterResizing() {
        val form = LoginView(context, SessionVault(context)) { _, _ -> }
        for ((width, height) in listOf(1920 to 1080, 640 to 360, 1920 to 1080)) {
            repeat(3) {
                form.forceLayout()
                form.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                form.layout(0, 0, width, height)
            }
            fun check(view: android.view.View, path: String = "root") {
                if (view.visibility == android.view.View.GONE) return
                assertFalse("Unsettled layout at ${width}x$height: $path ${view.javaClass.simpleName} width=${view.width} gravity=${(view as? android.widget.LinearLayout)?.gravity}", view.isLayoutRequested)
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) check(view.getChildAt(index), "$path/$index")
            }
            check(form)
        }
    }

    @Test fun remoteOkEnablesNativeKeyboardOnlyOnActivation() {
        val field = context.input("Servidor")
        assertFalse(field.showSoftInputOnFocus)
        for (key in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            field.onKeyPreIme(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
            assertFalse(field.showSoftInputOnFocus)
            field.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            assertFalse("Key down must not open the keyboard twice", field.showSoftInputOnFocus)
            field.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            assertTrue("OK must enable the editor before requesting the IME", field.showSoftInputOnFocus)
        }
    }

    @Test fun clickingSearchEnablesEditingAndImeActionReturnsToNavigation() {
        val field = context.input("Buscar no catálogo")
        field.setText("Horizonte")
        assertTrue(field.performClick())
        assertTrue(field.showSoftInputOnFocus)
        assertEquals(field.text.length, field.selectionStart)
        field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        assertFalse(field.showSoftInputOnFocus)
    }

    @Test fun playbackToggleAndReadsStayResponsiveWhileImportIsPaused() {
        store(listOf(movie))
        val staged = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val import = workers.submit {
            db.replaceSection(account, "movie", emptyList()) { sink ->
                repeat(250) { sink(movie.copy(id = "new-$it")) }
                staged.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                throw ProviderFailure("Interrupted catalog")
            }
        }
        try {
            assertTrue(staged.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val interaction = workers.submit<Boolean> {
                repeat(50) { db.autoNext = !db.autoNext }
                assertEquals(movie.uid, db.page(account, "movie").items.single().uid)
                db.toggleFavorite(account, movie)
            }
            assertTrue("Catalog import blocked local interactions", interaction.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { import.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            workers.shutdownNow()
        }
        assertEquals(movie.uid, db.page(account, "movie").items.single().uid)
    }

    @Test fun failedStreamAfterCommittedStagingBatchPreservesTheCatalog() {
        store(listOf(movie))
        assertThrows(ProviderFailure::class.java) {
            db.replaceSection(account, "movie", emptyList()) { sink ->
                repeat(501) { sink(movie.copy(id = "$it")) }
                throw ProviderFailure("Truncated response")
            }
        }
        assertEquals(movie.uid, db.page(account, "movie").items.single().uid)
        assertEquals(2, db.categories(account, "movie").size)
    }

    @Test fun freshCatalogSkipsDownloadsAndFailureDoesNotDiscardOtherSections() {
        val client = api()
        val key = client.credentials.account
        for ((kind, items) in listOf("movie" to listOf(movie), "series" to listOf(series), "live" to emptyList())) db.replaceSection(key, kind, emptyList()) { sink -> items.forEach(sink) }
        val sync = CatalogSync(client, db, context.cacheDir)
        assertTrue(sync.refresh(false).updated.isEmpty())
        assertEquals(0, server.requestCount)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val action = request.requestUrl!!.queryParameter("action")!!
                return when {
                    action.endsWith("categories") -> MockResponse().setBody("[]")
                    action == "get_vod_streams" -> MockResponse().setResponseCode(503)
                    action == "get_series" -> MockResponse().setBody("""[{"series_id":2,"name":"Outra série"}]""")
                    else -> MockResponse().setBody("[]")
                }
            }
        }
        val result = sync.refresh(true)
        assertEquals(listOf("movie"), result.failed)
        assertTrue(result.updated.containsAll(listOf("series", "live")))
        assertEquals(movie.uid, db.page(key, "movie").items.single().uid)
        assertEquals("Outra série", db.page(client.credentials.account, "series").items.single().name)
        assertTrue(java.io.File(context.cacheDir, "catalog-sync").listFiles().isNullOrEmpty())
    }

    @Test fun selectiveStreamIgnoresUnusedNestedPayloadAndDiskImportUsesSameSchema() {
        val payload = """[{"stream_id":9,"name":"Filme","category_ids":["1","2"],"irrelevant":{"nested":[${"0,".repeat(10000)}0]},"container_extension":"mkv"}]"""
        response(payload)
        val client = api()
        val file = client.downloadCatalog("movie", context.cacheDir)
        try {
            val items = mutableListOf<Content>()
            client.importCatalog("movie", file) { items += it }
            assertEquals(listOf("1", "2"), items.single().categories)
            assertEquals("mkv", items.single().extension)
        } finally { file.delete() }
    }

    @Test fun cardMetadataUsesSeriesIndexAndKeepsAccountIsolation() {
        val episode = Content("episode", "501", "Ep", seriesId = series.id, seriesName = series.name)
        db.record(account, episode, 5000, 10000)
        db.toggleFavorite(account, series)
        val page = db.cards(account, Page(listOf(series, movie), 2))
        assertTrue(page.cards[0].favorite)
        assertEquals(episode.uid, page.cards[0].progress!!.item.uid)
        assertNull(page.cards[1].progress)
        assertNull(db.cards("other", Page(listOf(series), 1)).cards.single().progress)
        assertTrue(db.isFresh(account, "movie").not())
        store(listOf(movie)); assertTrue(db.isFresh(account, "movie"))
        assertFalse(db.isFresh(account, "movie", System.currentTimeMillis() + 7 * 60 * 60 * 1000))
    }

    @Test fun versionOneDatabaseMigratesWithoutLosingFavoritesProgressOrAutoplay() {
        db.close(); context.deleteDatabase("library.db")
        val file = context.getDatabasePath("library.db"); file.parentFile!!.mkdirs()
        val old = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        old.use {
            it.execSQL("CREATE TABLE contents(account TEXT,uid TEXT,kind TEXT,name TEXT,norm TEXT,added INTEGER,item TEXT,PRIMARY KEY(account,uid))")
            it.execSQL("CREATE INDEX content_order ON contents(account,kind,added DESC)")
            it.execSQL("CREATE INDEX content_name ON contents(account,kind,norm)")
            it.execSQL("CREATE TABLE item_category(account TEXT,uid TEXT,category TEXT,PRIMARY KEY(account,uid,category))")
            it.execSQL("CREATE TABLE categories(account TEXT,kind TEXT,id TEXT,name TEXT,PRIMARY KEY(account,kind,id))")
            it.execSQL("CREATE TABLE library(account TEXT,uid TEXT,favorite INTEGER DEFAULT 0,position INTEGER DEFAULT 0,duration INTEGER DEFAULT 0,finished INTEGER DEFAULT 0,updated INTEGER DEFAULT 0,favorite_updated INTEGER DEFAULT 0,norm TEXT,item TEXT,PRIMARY KEY(account,uid))")
            it.execSQL("CREATE TABLE loaded(account TEXT,kind TEXT,updated INTEGER,PRIMARY KEY(account,kind))")
            it.execSQL("CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT)")
            it.execSQL("INSERT INTO settings VALUES ('auto_next','true')")
            it.execSQL("INSERT INTO contents VALUES (?,?,?,?,?,?,?)", arrayOf(account, movie.uid, "movie", movie.name, normalized(movie.name), 10, movie.json().toString()))
            val episode = Content("episode", "old-101", "Episódio antigo", seriesId = series.id, seriesName = series.name)
            it.execSQL("INSERT INTO library(account,uid,favorite,position,duration,updated,norm,item) VALUES (?,?,?,?,?,?,?,?)", arrayOf(account, episode.uid, 1, 5000, 10000, 1, normalized(episode.name), episode.json().toString()))
            it.version = 1
        }
        db = LibraryDb(context); db.prepare()
        assertEquals(3, db.readableDatabase.version)
        assertTrue(db.autoNext)
        assertEquals(movie.uid, db.page(account, "movie").items.single().uid)
        assertEquals("episode:old-101", db.page(account, "favorites").items.single().uid)
        assertEquals(5000L, db.checkpoint(account, series)!!.position)
        db.autoNext = false; db.close(); db = LibraryDb(context); db.prepare()
        assertFalse(db.autoNext)
    }

    @Test fun detailResponsesSkipUnusedDataWithoutKeepingRawCopies() {
        response("""{"movie_data":{"unused":[${"0,".repeat(50000)}0]},"info":{"backdrop_path":["unused"],"plot":"Sinopse preservada"}}""")
        assertEquals("Sinopse preservada", api().movieInfo(movie))
        response("""{"episodes":{"1":[{"id":"101","season":1,"episode_num":1,"title":"Estreia","info":{"plot":"História","duration_secs":60,"ignored":[1,2,3]}}]},"info":{"plot":"A série"}}""")
        val info = api().seriesInfo(series)
        assertEquals("A série", info.plot)
        assertEquals(60000L, info.episodes.single().duration)
        assertEquals("História", info.episodes.single().plot)
    }

    @Test fun watchedEpisodeHistorySurvivesReplayRestartAndProgressRemoval() {
        val episode = Content("episode", "101", "Estreia", seriesId = series.id, seriesName = series.name, season = 1, episode = 1)
        db.record(account, episode, 5000, 20000)
        assertTrue(db.watchedEpisodes(account, series.id).isEmpty())
        db.record(account, episode, 20000, 20000, true)
        assertEquals(setOf(episode.uid), db.watchedEpisodes(account, series.id))
        assertEquals(0, db.page(account, "continue").total)
        db.record(account, episode, 5000, 20000)
        assertEquals(5000L, db.checkpoint(account, episode)!!.position)
        assertEquals(setOf(episode.uid), db.watchedEpisodes(account, series.id))
        db.close(); db = LibraryDb(context)
        assertEquals(setOf(episode.uid), db.watchedEpisodes(account, series.id))
        db.removeProgress(account, episode)
        assertEquals(setOf(episode.uid), db.watchedEpisodes(account, series.id))
    }

    @Test fun watchedEpisodesAreIsolatedByAccountSeriesAndContentType() {
        val episode = Content("episode", "101", "Estreia", seriesId = series.id)
        val nextSeason = episode.copy(id = "201", season = 2)
        db.record(account, episode, 20000, 20000, true)
        db.record(account, nextSeason, 20000, 20000, true)
        db.record(account, movie.copy(seriesId = series.id), 20000, 20000, true)
        assertEquals(setOf(episode.uid, nextSeason.uid), db.watchedEpisodes(account, series.id))
        assertTrue(db.watchedEpisodes("other-account", series.id).isEmpty())
        assertTrue(db.watchedEpisodes(account, "other-series").isEmpty())
        assertTrue(db.watchedEpisodes(account, "").isEmpty())
    }

    @Test fun episodeResumeTimesAreBatchedScopedAndSurviveRestart() {
        val first = Content("episode", "101", "Estreia", seriesId = series.id, season = 1, episode = 1)
        val second = first.copy(id = "202", season = 2, episode = 2)
        db.record(account, first, 754000, 2400000)
        db.record(account, second, 3723000, 5400000)
        db.record("other-account", first, 8000, 20000)
        db.record(account, first.copy(id = "other", seriesId = "other-series"), 9000, 20000)
        db.record(account, movie.copy(seriesId = series.id), 9000, 20000)
        db.close(); db = LibraryDb(context)
        val states = db.episodeStates(account, series.id)
        assertEquals(setOf(first.uid, second.uid), states.keys)
        assertEquals("Parou em 12:34", states.getValue(first.uid).resumeText)
        assertEquals("Parou em 1:02:03", states.getValue(second.uid).resumeText)
        assertEquals(3723000L, states.getValue(second.uid).position)
        assertFalse(states.getValue(second.uid).watched)
        assertTrue(db.episodeStates(account, "").isEmpty())
        assertTrue(db.episodeStates("unknown", series.id).isEmpty())
    }

    @Test fun completedEpisodeHasNoStaleResumeTimeButReplayCanHaveBoth() {
        val episode = Content("episode", "101", "Estreia", seriesId = series.id)
        db.record(account, episode, 7000, 20000)
        db.record(account, episode, 20000, 20000, true)
        assertEquals(EpisodeState(true, 0, 20000), db.episodeStates(account, series.id)[episode.uid])
        assertEquals("", db.episodeStates(account, series.id).getValue(episode.uid).resumeText)
        db.record(account, episode, 5000, 20000)
        assertEquals(EpisodeState(true, 5000, 20000), db.episodeStates(account, series.id)[episode.uid])
        db.removeProgress(account, episode)
        assertEquals(EpisodeState(true, 0, 0), db.episodeStates(account, series.id)[episode.uid])
    }

    @Test fun episodeWithoutKnownDurationStillShowsItsSavedTime() {
        val episode = Content("episode", "101", "Estreia", seriesId = series.id)
        db.toggleFavorite(account, episode)
        assertTrue(db.episodeStates(account, series.id).isEmpty())
        db.record(account, episode, 9000, 0)
        assertEquals("Parou em 00:09", db.episodeStates(account, series.id).getValue(episode.uid).resumeText)
        db.removeProgress(account, episode)
        assertTrue(db.episodeStates(account, series.id).isEmpty())
    }

    @Test fun versionTwoMigrationRecognizesPreviouslyCompletedEpisodes() {
        db.close(); context.deleteDatabase("library.db")
        val file = context.getDatabasePath("library.db"); file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE library(account TEXT,uid TEXT,finished INTEGER DEFAULT 0,series_id TEXT NOT NULL DEFAULT '',PRIMARY KEY(account,uid))")
            old.execSQL("INSERT INTO library VALUES (?,?,?,?)", arrayOf<Any>(account, "episode:101", 1, series.id))
            old.execSQL("INSERT INTO library VALUES (?,?,?,?)", arrayOf<Any>(account, "episode:102", 0, series.id))
            old.execSQL("INSERT INTO library VALUES (?,?,?,?)", arrayOf<Any>(account, "movie:103", 1, series.id))
            old.version = 2
        }
        db = LibraryDb(context)
        assertEquals(setOf("episode:101"), db.watchedEpisodes(account, series.id))
        assertEquals(3, db.readableDatabase.version)
    }
}
