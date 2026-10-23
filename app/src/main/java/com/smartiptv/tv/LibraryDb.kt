package com.smartiptv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    private val preferences = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
    @Volatile private var nextEpisode = preferences.getBoolean("auto_next", false)
    private val importLock = Any()
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contents(account TEXT NOT NULL,uid TEXT NOT NULL,kind TEXT NOT NULL,name TEXT NOT NULL,norm TEXT NOT NULL,added INTEGER NOT NULL,item TEXT NOT NULL,PRIMARY KEY(account,uid))")
        db.execSQL("CREATE INDEX content_order ON contents(account,kind,added DESC)")
        db.execSQL("CREATE INDEX content_name ON contents(account,kind,norm)")
        db.execSQL("CREATE TABLE item_category(account TEXT NOT NULL,uid TEXT NOT NULL,category TEXT NOT NULL,PRIMARY KEY(account,uid,category))")
        db.execSQL("CREATE INDEX category_lookup ON item_category(account,category,uid)")
        db.execSQL("CREATE TABLE categories(account TEXT NOT NULL,kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(account,kind,id))")
        db.execSQL("CREATE TABLE library(account TEXT NOT NULL,uid TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,position INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT 0,finished INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,favorite_updated INTEGER NOT NULL DEFAULT 0,norm TEXT NOT NULL,item TEXT NOT NULL,PRIMARY KEY(account,uid))")
        db.execSQL("CREATE TABLE loaded(account TEXT NOT NULL,kind TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(account,kind))")
        db.execSQL("CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT)")
        performanceSchema(db)
        watchedSchema(db)
    }
    private fun performanceSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE library ADD COLUMN series_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX library_series ON library(account,series_id,finished,updated DESC)")
        db.execSQL("DROP INDEX content_order")
        db.execSQL("DROP INDEX content_name")
        db.execSQL("CREATE INDEX content_order ON contents(account,kind,added DESC,uid ASC)")
        db.execSQL("CREATE INDEX content_name ON contents(account,kind,norm,uid)")
        db.execSQL("CREATE INDEX content_recent ON contents(account,added DESC,uid ASC)")
        db.execSQL("CREATE TABLE incoming_contents AS SELECT * FROM contents WHERE 0")
        db.execSQL("CREATE UNIQUE INDEX incoming_uid ON incoming_contents(account,uid)")
        db.execSQL("CREATE TABLE incoming_links AS SELECT * FROM item_category WHERE 0")
        db.execSQL("CREATE UNIQUE INDEX incoming_link ON incoming_links(account,uid,category)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            performanceSchema(db)
            db.rawQuery("SELECT account,uid,item FROM library", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val item = Content.fromJson(JSONObject(cursor.getString(2)))
                    if (item.seriesId.isNotEmpty()) db.execSQL("UPDATE library SET series_id=? WHERE account=? AND uid=?", arrayOf(item.seriesId, cursor.getString(0), cursor.getString(1)))
                }
            }
        }
        if (oldVersion < 3) watchedSchema(db)
    }
    private fun watchedSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE library ADD COLUMN watched INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE library SET watched=1 WHERE finished=1 AND uid LIKE 'episode:%'")
        db.execSQL("CREATE INDEX library_watched ON library(account,series_id,watched)")
    }
    /** Called by the startup worker, never while handling a remote click. */
    fun prepare() {
        val db = writableDatabase
        if (!preferences.contains("auto_next")) {
            val value = db.rawQuery("SELECT value FROM settings WHERE name='auto_next'", null).use { it.moveToFirst() && it.getString(0) == "true" }
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

    fun page(account: String, kind: String, category: String = "", search: String = "", alphabetical: Boolean = false, offset: Int = 0, limit: Int = 60, favoriteKind: String = ""): Page {
        val fromLibrary = kind in listOf("favorites", "continue")
        val conditions = mutableListOf("c.account=?")
        val args = mutableListOf(account)
        if (fromLibrary) conditions += if (kind == "favorites") "c.favorite=1" else "c.position>=1000 AND c.finished=0"
        else if (kind != "all") { conditions += "c.kind=?"; args += kind }
        if (kind == "favorites" && favoriteKind.isNotEmpty()) {
            conditions += when (favoriteKind) {
                "movie" -> "c.uid LIKE 'movie:%'"
                "series" -> "(c.uid LIKE 'series:%' OR c.uid LIKE 'episode:%')"
                "live" -> "c.uid LIKE 'live:%'"
                else -> "0"
            }
        }
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
            // Completion history is independent of the current position during a later replay.
            if (item.kind == "episode" && end) put("watched", 1)
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
    fun watchedEpisodes(account: String, seriesId: String): Set<String> {
        if (seriesId.isBlank()) return emptySet()
        return readableDatabase.rawQuery("SELECT uid FROM library WHERE account=? AND series_id=? AND watched=1 AND uid LIKE 'episode:%'", arrayOf(account, seriesId)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }
    fun episodeStates(account: String, seriesId: String): Map<String, EpisodeState> {
        if (seriesId.isBlank()) return emptyMap()
        return readableDatabase.rawQuery("SELECT uid,watched,position,duration,finished FROM library WHERE account=? AND series_id=? AND uid LIKE 'episode:%' AND (watched=1 OR (finished=0 AND position>=1000))", arrayOf(account, seriesId)).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val position = if (cursor.getInt(4) == 0) cursor.getLong(2).coerceAtLeast(0) else 0L
                    put(cursor.getString(0), EpisodeState(cursor.getInt(1) == 1, position, cursor.getLong(3).coerceAtLeast(0)))
                }
            }
        }
    }
    var autoNext: Boolean
        get() = nextEpisode
        set(value) { nextEpisode = value; preferences.edit().putBoolean("auto_next", value).apply() }
}
