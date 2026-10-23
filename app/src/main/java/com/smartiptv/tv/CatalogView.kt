package com.smartiptv.tv

import android.content.Context
import android.graphics.Color
import android.view.Gravity
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
) : LinearLayout(context) {
    private val navigation = listOf("home" to "Início", "live" to "TV ao vivo", "movie" to "Filmes", "series" to "Séries", "continue" to "Continuar assistindo", "favorites" to "Favoritos")
    val nav = linkedMapOf<String, Button>()
    var tab = "home"; private set
    private var category = ""
    private var alphabetical = false
    private var queryEpoch = 0
    private var restoreGrid: android.os.Parcelable? = null
    private var restoreCategory: android.os.Parcelable? = null
    private var restoreHomeY = 0
    private var restoreHomeRows = emptyList<android.os.Parcelable?>()
    private var focusAfterLoad = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchTask: Runnable? = null
    private val queries = mutableListOf<Future<*>>()
    private var querySerial = 0
    private val updateCatalog = Runnable { reload() }
    private val heading = context.text("Início", 25f, bold = true)
    private val subtitle = context.text("", 11f, Palette.muted)
    private val status = context.text("", 11f, Palette.green)
    private val main = context.column()
    private val body = FrameLayout(context)
    val search = context.input("Buscar no catálogo").apply { hint = "Buscar no catálogo…"; textSize = 12f; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
    val count = context.text("", 11f, Palette.muted)
    val order = context.button("Mais recentes") { alphabetical = !alphabetical; updateOrder(); loadGrid(); fillCategories() }
    private val gridPage = context.column()
    private val categoryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val categories = RecyclerView(context)
    val grid = RecyclerView(context)
    private val empty = context.text("Seu catálogo aparecerá aqui", 17f, Palette.muted).apply { gravity = Gravity.CENTER; pad(20) }
    private val results = FrameLayout(context)
    private var adapter: CatalogAdapter
    private val home = ScrollView(context).apply { isFillViewport = false }
    private val homeBody = context.column()
    private val homeRows = mutableListOf<Pair<RecyclerView, CatalogAdapter>>()
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
            button.textSize = 12f; button.gravity = Gravity.START or Gravity.CENTER_VERTICAL; button.pad(10, 6)…9988 tokens truncated…ECT value FROM settings WHERE name='auto_next'", null).use { it.moveToFirst() && it.getString(0) == "true" }
            nextEpisode = value
            preferences.edit().putBoolean("auto_next", value).apply()
        }
    }

    fun hasCatalog(account: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM loaded WHERE account=? LIMIT 1", arrayOf(account)).use { it.moveToFirst() }
    fun isFresh(account: String, kind: String, now: Long = System.currentTimeMillis()): Boolean = readableDatabase.rawQuery("SELECT updated FROM loaded WHERE account=? AND kind=?", arrayOf(account, kind)).use {
        it.moveToFirst() && now - it.getLong(0) in 0 until 6 * 60 * 60 * 1000L
    }

    fun replaceSection(account: String, kind: String, categories: List<Category>, produce: ((Content) -> Unit) -> Unit) = synchronized(importLock) {
        val db = writableDatabase
        // Build the new section in short batches. The active catalog stays readable until publication.
        db.execSQL("DELETE FROM incoming_contents")
        db.execSQL("DELETE FROM incoming_links")
        var transaction = false
        try {
            val itemStatement = db.compileStatement("INSERT OR IGNORE INTO incoming_contents VALUES (?,?,?,?,?,?,?)")
            val groupStatement = db.compileStatement("INSERT OR IGNORE INTO incoming_links VALUES (?,?,?)")
            itemStatement.use { statement ->
                groupStatement.use { categoryStatement ->
                    var batch = 0
                    produce { item ->
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (!transaction) { db.beginTransactionNonExclusive(); transaction = true }
                        statement.clearBindings()
                        listOf(account, item.uid, kind, item.name, normalized(item.name)).forEachIndexed { index, value -> statement.bindString(index + 1, value) }
                        statement.bindLong(6, item.added); statement.bindString(7, item.json().toString())
                        val inserted = statement.executeInsert()
                        if (inserted != -1L) item.categories.distinct().forEach { category ->
                            categoryStatement.bindString(1, account); categoryStatement.bindString(2, item.uid); categoryStatement.bindString(3, category)
                            categoryStatement.executeInsert()
                        }
                        if (++batch == 250) {
                            db.setTransactionSuccessful(); db.endTransaction(); transaction = false; batch = 0
                        }
                    }
                }
            }
            if (transaction) { db.setTransactionSuccessful(); db.endTransaction(); transaction = false }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            db.beginTransactionNonExclusive(); transaction = true
            db.execSQL("DELETE FROM item_category WHERE account=? AND uid IN (SELECT uid FROM contents WHERE account=? AND kind=?)", arrayOf(account, account, kind))
            db.delete("contents", "account=? AND kind=?", arrayOf(account, kind))
            db.delete("categories", "account=? AND kind=?", arrayOf(account, kind))
            categories.forEach { group ->
                db.insertWithOnConflict("categories", null, ContentValues().apply { put("account", account); put("kind", kind); put("id", group.id); put("name", group.name) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL("INSERT INTO contents SELECT * FROM incoming_contents")
            db.execSQL("INSERT INTO item_category SELECT * FROM incoming_links")
            db.execSQL("INSERT OR REPLACE INTO loaded VALUES (?,?,?)", arrayOf<Any>(account, kind, System.currentTimeMillis()))
            db.setTransactionSuccessful()
        } finally {
            if (transaction) db.endTransaction()
            db.execSQL("DELETE FROM incoming_contents")
            db.execSQL("DELETE FROM incoming_links")
        }
    }

    fun categories(account: String, kind: String): List<Category> = readableDatabase.rawQuery("SELECT id,name FROM categories WHERE account=? AND kind=? ORDER BY rowid", arrayOf(account, kind)).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Category(cursor.getString(0), cursor.getString(1))) }
    }

    fun page(account: String, kind: String, category: String = "", search: String = "", alphabetical: Boolean = false, offset: Int = 0, limit: Int = 60): Page {
        val fromLibrary = kind in listOf("favorites", "continue")
        val conditions = mutableListOf("c.account=?")
        val args = mutableListOf(account)
        if (fromLibrary) conditions += if (kind == "favorites") "c.favorite=1" else "c.position>=1000 AND c.finished=0"
        else if (kind != "all") { conditions += "c.kind=?"; args += kind }
        if (category.isNotEmpty() && !fromLibrary) {
            conditions += "EXISTS(SELECT 1 FROM item_category g WHERE g.account=c.account AND g.uid=c.uid AND g.category=?)"
            args += category
        }
        if (search.isNotBlank()) {
            conditions += "c.norm LIKE ? ESCAPE '\\'"
            args += "%" + normalized(search.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val table = if (fromLibrary) "library" else "contents"
        val where = conditions.joinToString(" AND ")
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM $table c WHERE $where", args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        val order = if (alphabetical) "c.norm ASC,c.uid ASC" else if (kind == "favorites") "c.favorite_updated DESC,c.uid ASC" else if (fromLibrary) "c.updated DESC,c.uid ASC" else "c.added DESC,c.uid ASC"
        val items = readableDatabase.rawQuery("SELECT c.item FROM $table c WHERE $where ORDER BY $order LIMIT ? OFFSET ?", (args + limit.coerceIn(1, 100) + offset.coerceAtLeast(0)).map { it.toString() }.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Content.fromJson(JSONObject(cursor.getString(0)))) }
        }
        return Page(items, total)
    }

    fun searchAll(account: String, search: String, alphabetical: Boolean, offset: Int, limit: Int): Page = page(account, "all", search = search, alphabetical = alphabetical, offset = offset, limit = limit)

    fun cards(account: String, page: Page): CardPage {
        if (page.items.isEmpty()) return CardPage(emptyList(), page.total)
        val rows = mutableMapOf<String, Pair<Boolean, Progress?>>()
        val items = page.items.associateBy { it.uid }
        val placeholders = items.keys.joinToString(",") { "?" }
        readableDatabase.rawQuery("SELECT uid,favorite,position,duration,finished,updated FROM library WHERE account=? AND uid IN ($placeholders)", (listOf(account) + items.keys).toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val item = items.getValue(c.getString(0))
                rows[item.uid] = (c.getInt(1) == 1) to if (c.getLong(2) >= 1000 && c.getInt(4) == 0) Progress(item, c.getLong(2), c.getLong(3), false, c.getLong(5)) else null
            }
        }
        return CardPage(page.items.map { item -> CardData(item, rows[item.uid]?.first == true, if (item.kind == "series") checkpoint(account, item) else rows[item.uid]?.second) }, page.total)
    }

    fun toggleFavorite(account: String, item: Content): Boolean {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
        val active = !isFavorite(account, item)
        ensureLibrary(account, item)
        writableDatabase.update("library", ContentValues().apply { put("favorite", if (active) 1 else 0); put("favorite_updated", System.currentTimeMillis()) }, "account=? AND uid=?", arrayOf(account, item.uid))
        db.setTransactionSuccessful()
        return active
        } finally { db.endTransaction() }
    }
    fun isFavorite(account: String, item: Content): Boolean = readableDatabase.rawQuery("SELECT favorite FROM library WHERE account=? AND uid=?", arrayOf(account, item.uid)).use { it.moveToFirst() && it.getInt(0) == 1 }

    private fun ensureLibrary(account: String, item: Content) {
        writableDatabase.insertWithOnConflict("library", null, ContentValues().apply {
            put("account", account); put("uid", item.uid); put("item", item.json().toString()); put("norm", normalized(item.name)); put("series_id", item.seriesId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun record(account: String, item: Content, position: Long, duration: Long, finished: Boolean = false) {
        if (item.kind !in listOf("movie", "episode") || (position < 1000 && !finished)) return
        val end = finished || (duration > 0 && position >= duration * .98 && duration - position <= 60000)
        ensureLibrary(account, item)
        writableDatabase.update("library", ContentValues().apply {
            put("item", item.json().toString()); put("norm", normalized(item.name)); put("series_id", item.seriesId); put("position", position.coerceAtLeast(0))
            put("duration", duration.coerceAtLeast(0)); put("finished", if (end) 1 else 0); put("updated", System.currentTimeMillis())
        }, "account=? AND uid=?", arrayOf(account, item.uid))
    }
    fun checkpoint(account: String, item: Content): Progress? {
        if (item.kind == "series") {
            return readableDatabase.rawQuery("SELECT item,position,duration,finished,updated FROM library WHERE account=? AND series_id=? AND finished=0 AND position>=1000 ORDER BY updated DESC LIMIT 1", arrayOf(account, item.id)).use { cursor ->
                while (cursor.moveToNext()) {
                    val entry = Content.fromJson(JSONObject(cursor.getString(0)))
                    if (entry.kind == "episode" && entry.seriesId == item.id) return@use Progress(entry, cursor.getLong(1), cursor.getLong(2), false, cursor.getLong(4))
                }
                null
            }
        }
        return readableDatabase.rawQuery("SELECT position,duration,finished,updated FROM library WHERE account=? AND uid=? AND finished=0 AND position>=1000", arrayOf(account, item.uid)).use { c ->
            if (c.moveToFirst()) Progress(item, c.getLong(0), c.getLong(1), false, c.getLong(3)) else null
        }
    }
    fun removeProgress(account: String, item: Content) { writableDatabase.execSQL("UPDATE library SET position=0,duration=0,finished=0 WHERE account=? AND uid=?", arrayOf(account, item.uid)) }
    var autoNext: Boolean
        get() = nextEpisode
        set(value) { nextEpisode = value; preferences.edit().putBoolean("auto_next", value).apply() }
}
