package com.smartiptv.tv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

private val combiningMarks = Regex("\\p{M}+")
fun normalized(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(combiningMarks, "")
fun digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) { bytes.forEach { byte -> val n = byte.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) } }
}
fun stamp(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)
}

class ProviderFailure(message: String) : Exception(message)

class Credentials(val server: String, val username: String, val password: String) {
    val account: String by lazy { digest(server.trimEnd('/').lowercase(Locale.ROOT) + "\u0000" + username) }
    override fun toString() = "Credentials(<protected>)"
    companion object {
        fun validated(server: String, username: String, password: String): Credentials {
            val address = server.trim().trimEnd('/')
            val url = address.toHttpUrlOrNull() ?: throw ProviderFailure("Informe um endereço de servidor válido, com http:// ou https://.")
            if (url.scheme !in listOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                throw ProviderFailure("Informe somente o endereço do servidor, incluindo a porta se houver.")
            }
            if (username.trim().isEmpty()) throw ProviderFailure("Informe seu usuário.")
            if (password.isEmpty()) throw ProviderFailure("Informe sua senha.")
            return Credentials(address, username.trim(), password)
        }
    }
}

data class Content(
    val kind: String, val id: String, val name: String,
    val categories: List<String> = emptyList(), val artwork: String = "", val added: Long = 0,
    val year: String = "", val rating: String = "", val plot: String = "", val extension: String = "mp4",
    val seriesId: String = "", val seriesName: String = "", val season: Int = 0, val episode: Int = 0, val duration: Long = 0,
) {
    val uid: String get() = "$kind:$id"
    val displayName: String get() = if (kind == "episode" && seriesName.isNotEmpty()) "$seriesName · T%02d E%02d".format(season, episode) else name
    fun json(): JSONObject = JSONObject().put("kind", kind).put("id", id).put("name", name).apply {
        if (categories.isNotEmpty()) put("categories", JSONArray(categories))
        if (artwork.isNotEmpty()) put("artwork", artwork)
        if (added != 0L) put("added", added)
        if (year.isNotEmpty()) put("year", year)
        if (rating.isNotEmpty()) put("rating", rating)
        if (plot.isNotEmpty()) put("plot", plot)
        if (extension != "mp4") put("extension", extension)
        if (seriesId.isNotEmpty()) put("seriesId", seriesId)
        if (seriesName.isNotEmpty()) put("seriesName", seriesName)
        if (season != 0) put("season", season)
        if (episode != 0) put("episode", episode)
        if (duration != 0L) put("duration", duration)
    }
    companion object {
        fun fromJson(j: JSONObject): Content = Content(j.getString("kind"), j.getString("id"), j.getString("name"),
            j.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            j.text("artwork"), j.optLong("added"), j.text("year"), j.text("rating"), j.text("plot"), j.text("extension", "mp4"),
            j.text("seriesId"), j.text("seriesName"), j.optInt("season"), j.optInt("episode"), j.optLong("duration"))

        fun fromApi(kind: String, j: JSONObject): Content? {
            val id = j.text(if (kind == "series") "series_id" else "stream_id")
            if (id.isBlank()) return null
            val groups = j.optJSONArray("category_ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOf(j.text("category_id")).filter { it.isNotEmpty() }
            return Content(kind, id, j.text("name", j.text("title", "Sem título")), groups,
                j.text(if (kind == "series") "cover" else "stream_icon"), j.optLong(if (kind == "series") "last_modified" else "added"),
                j.text("year"), j.text("rating"), j.text("plot"), j.text("container_extension", "mp4"))
        }
    }
}

fun JSONObject.text(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)
data class Category(val id: String, val name: String)
data class Account(val expiration: Long?, val active: String, val maximum: String, val status: String)
data class Progress(val item: Content, val position: Long, val duration: Long, val finished: Boolean, val updated: Long)
data class SeriesInfo(val episodes: List<Content>, val plot: String)
data class Page(val items: List<Content>, val total: Int)

fun parseEpisodes(series: Content, payload: JSONObject): SeriesInfo {
    val groups = when (val raw = payload.opt("episodes")) {
        is JSONObject -> raw
        is JSONArray -> JSONObject().put("1", raw)
        else -> JSONObject()
    }
    val result = linkedMapOf<String, Content>()
    for (key in groups.keys()) {
        val entries = groups.optJSONArray(key) ?: continue
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val id = entry.text("id")
            if (id.isBlank() || result.containsKey(id)) continue
            val info = entry.optJSONObject("info") ?: JSONObject()
            val season = entry.optInt("season", info.optInt("season", key.toIntOrNull() ?: 1))
            val number = entry.optInt("episode_num", info.optInt("episode_num", i + 1))
            result[id] = Content("episode", id, entry.text("title", "Episódio $number"), series.categories, series.artwork,
                entry.optLong("added"), series.year, series.rating, info.text("plot"), entry.text("container_extension", "mp4"),
                series.id, series.name, season, number, info.optLong("duration_secs") * 1000)
        }
    }
    return SeriesInfo(result.values.sortedWith(compareBy({ it.season }, { it.episode }, { normalized(it.name) })), payload.optJSONObject("info")?.text("plot") ?: series.plot)
}
