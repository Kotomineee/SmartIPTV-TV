package com.smartiptv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections

class TestProvider(context: Context) : AutoCloseable {
    val server = MockWebServer()
    val failedMedia = Collections.synchronizedSet(mutableSetOf<String>())
    val posterRequests = Collections.synchronizedList(mutableListOf<String>())
    val mediaRequests = Collections.synchronizedList(mutableListOf<String>())
    private val media = context.assets.open("resume.mp4").use { it.readBytes() }
    private val artwork: ByteArray = Bitmap.createBitmap(240, 360, Bitmap.Config.RGB_565).let { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(31, 68, 53))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(101, 223, 173) }
        canvas.drawCircle(160f, 120f, 85f, paint)
        paint.color = Color.rgb(20, 35, 29); canvas.drawCircle(90f, 165f, 65f, paint)
        paint.color = Color.WHITE; paint.textSize = 21f; paint.isFakeBoldText = true
        canvas.drawText("NOVOS", 24f, 286f, paint); canvas.drawText("CAMINHOS", 24f, 314f, paint)
        ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    val url: String get() = server.url("/").toString().trimEnd('/')
    val account: String get() = Credentials.validated(url, "demo", "demopass").account
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath ?: "/"
                if (path.startsWith("/poster/")) { posterRequests += path; return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(artwork)) }
                if (path.startsWith("/movie/") || path.startsWith("/series/") || path.startsWith("/live/")) {
                    val name = path.substringAfterLast('/')
                    mediaRequests += name
                    if (name in failedMedia) return MockResponse().setResponseCode(503)
                    val range = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (range >= media.size) return MockResponse().setResponseCode(416)
                    return MockResponse().setResponseCode(if (range > 0 || request.getHeader("Range") != null) 206 else 200)
                        .setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Range", "bytes $range-${media.size - 1}/${media.size}")
                        .setBody(Buffer().write(media, range, media.size - range))
                }
                val user = request.requestUrl?.queryParameter("username")
                val password = request.requestUrl?.queryParameter("password")
                if (user != "demo" || password != "demopass") return json(JSONObject().put("user_info", JSONObject().put("auth", 0)))
                return json(payload(request.requestUrl?.queryParameter("action") ?: ""))
            }
        }
        server.start()
    }
    private fun json(value: Any): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun payload(action: String): Any = when {
        action.isEmpty() -> JSONObject("""{"user_info":{"auth":1,"status":"Active","active_cons":"1","max_connections":"2","exp_date":"1893456000"}}""")
        action.endsWith("_categories") -> JSONArray().put(JSONObject().put("category_id", "1").put("category_name", "Lançamentos")).put(JSONObject().put("category_id", "2").put("category_name", "Aventura"))
        action == "get_vod_streams" -> JSONArray().apply { repeat(120) { index -> put(JSONObject().put("stream_id", index + 1).put("name", if (index == 119) "Além do horizonte" else "Filme ${index + 1}").put("category_id", if (index % 2 == 0) "1" else "2").put("added", 1700000000L + index).put("year", "2025").put("stream_icon", "$url/poster/movie_$index.png").put("container_extension", "mp4")) } }
        action == "get_series" -> JSONArray().apply { repeat(4) { index -> put(JSONObject().put("series_id", index + 1).put("name", "Novos caminhos ${index + 1}").put("category_id", "1").put("year", "2026").put("last_modified", index + 1).put("plot", "Uma história sobre descobertas e novos caminhos.").put("cover", "$url/poster/series_$index.png")) } }
        action == "get_live_streams" -> JSONArray().apply { repeat(3) { index -> put(JSONObject().put("stream_id", index + 1).put("name", "Canal ${index + 1}").put("category_id", "1").put("stream_icon", "$url/poster/live_$index.png")) } }
        action == "get_vod_info" -> JSONObject().put("info", JSONObject().put("plot", "Uma jornada de descobertas, encontros e paisagens inesquecíveis."))
        action == "get_series_info" -> JSONObject().put("seasons", JSONArray()).put("info", JSONObject().put("plot", "Uma história sobre descobertas e novos caminhos.")).put("episodes", JSONObject().apply {
            for (season in 1..2) put("$season", JSONArray().apply { for (episode in 1..3) put(JSONObject().put("id", season * 100 + episode).put("season", season).put("episode_num", episode).put("title", "Episódio $episode: novos caminhos").put("container_extension", "mp4")) })
        })
        else -> JSONArray()
    }
    override fun close() { server.shutdown() }
}
