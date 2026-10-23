package com.smartiptv.tv

import android.util.JsonReader
import android.util.JsonToken
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class XtreamApi(val credentials: Credentials, private val http: OkHttpClient = client()) {
    private val requestTag = Any()
    private data class Scope(val owner: Any, val details: Boolean)
    companion object {
        const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"
        fun client() = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    }

    private fun url(action: String? = null, extra: Pair<String, String>? = null): HttpUrl {
        val url = credentials.server.toHttpUrl().newBuilder().addPathSegment("player_api.php")
            .addQueryParameter("username", credentials.username).addQueryParameter("password", credentials.password)
        if (action != null) url.addQueryParameter("action", action)
        extra?.let { url.addQueryParameter(it.first, it.second) }
        return url.build()
    }

    private fun response(action: String? = null, extra: Pair<String, String>? = null): Response {
        try {
            val request = Request.Builder().url(url(action, extra)).tag(Scope(requestTag, action in listOf("get_vod_info", "get_series_info"))).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                throw ProviderFailure(if (code in listOf(401, 403)) "Acesso recusado. Confira os dados e a situação da conta." else "O servidor não respondeu corretamente. Tente novamente.")
            }
            return response
        } catch (_: IOException) {
            throw ProviderFailure("Não foi possível conectar. Confira a internet e o endereço do servidor.")
        }
    }

    private fun objectResponse(action: String? = null, extra: Pair<String, String>? = null): JSONObject {
        return response(action, extra).use { response ->
            val body = response.body ?: throw ProviderFailure("O servidor enviou uma resposta vazia.")
            val raw = body.byteStream().use { it.readBytesLimited(6 * 1024 * 1024) }
            try { JSONObject(String(raw, Charsets.UTF_8)) }
            catch (_: Exception) { throw ProviderFailure("O servidor não retornou uma resposta Xtream válida.") }
        }
    }

    fun authenticate(): Account {
        val user = objectResponse().optJSONObject("user_info") ?: throw ProviderFailure("Não foi possível confirmar a autenticação Xtream.")
        if (user.text("auth", "0") != "1") throw ProviderFailure("Usuário ou senha não aceitos pelo servidor.")
        val status = user.text("status")
        if (status.isNotBlank() && !status.equals("active", true)) throw ProviderFailure("Sua conta está inativa ou expirada. Consulte seu provedor.")
        return Account(user.text("exp_date").toLongOrNull()?.takeIf { it > 0 }, user.text("active_cons", "—"), user.text("max_connections", "—"), status)
    }

    fun categories(kind: String): List<Category> {
        val result = mutableListOf<Category>()
        array("get_${if (kind == "movie") "vod" else kind}_categories") { row ->
            result += Category(row.text("category_id"), row.text("category_name", "Sem categoria"))
        }
        return result
    }

    fun catalog(kind: String, consumer: (Content) -> Unit) {
        val action = when (kind) { "movie" -> "get_vod_streams"; "series" -> "get_series"; else -> "get_live_streams" }
        array(action) { row -> Content.fromApi(kind, row)?.let(consumer) }
    }

    private fun array(action: String, consumer: (JSONObject) -> Unit) {
        try {
            response(action).use { response ->
                val reader = JsonReader((response.body ?: throw ProviderFailure("Resposta vazia do provedor.")).charStream())
                reader.use {
                    if (it.peek() != JsonToken.BEGIN_ARRAY) throw ProviderFailure("O servidor enviou um catálogo em formato inesperado.")
                    it.beginArray()
                    var count = 0
                    while (it.hasNext()) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (++count > 300000) throw ProviderFailure("O catálogo excede o limite suportado neste aparelho.")
                        (readValue(it) as? JSONObject)?.let(consumer)
                    }
                    it.endArray()
                }
            }
        } catch (error: ProviderFailure) { throw error }
        catch (error: InterruptedException) { throw error }
        catch (_: Exception) { throw ProviderFailure("Não foi possível ler o catálogo. Confira a conexão e tente atualizar.") }
    }

    private fun readValue(reader: JsonReader, depth: Int = 0): Any {
        if (depth > 15) throw ProviderFailure("O catálogo recebido não é válido.")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) result.put(reader.nextName(), readValue(reader, depth + 1))
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) result.put(readValue(reader, depth + 1))
                reader.endArray()
                result
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> reader.nextString().take(18000)
        }
    }

    fun movieInfo(item: Content): String = objectResponse("get_vod_info", "vod_id" to item.id).optJSONObject("info")?.let {
        it.text("plot", it.text("description", item.plot))
    } ?: item.plot

    fun seriesInfo(item: Content): SeriesInfo = parseEpisodes(item, objectResponse("get_series_info", "series_id" to item.id))

    fun playbackUrl(item: Content): String {
        if (item.kind !in listOf("live", "movie", "episode")) throw ProviderFailure("Selecione um episódio para reproduzir a série.")
        val extension = if (item.kind == "live") "ts" else item.extension.takeIf { it.matches("[a-zA-Z0-9]{1,8}".toRegex()) } ?: "mp4"
        return credentials.server.toHttpUrl().newBuilder().addPathSegment(if (item.kind == "episode") "series" else item.kind)
            .addPathSegment(credentials.username).addPathSegment(credentials.password).addPathSegment("${item.id}.$extension").build().toString()
    }

    fun cancel() {
        (http.dispatcher.queuedCalls() + http.dispatcher.runningCalls()).filter { (it.request().tag() as? Scope)?.owner === requestTag }.forEach(Call::cancel)
    }
    fun cancelDetails() { (http.dispatcher.queuedCalls() + http.dispatcher.runningCalls()).filter { (it.request().tag() as? Scope)?.let { tag -> tag.owner === requestTag && tag.details } == true }.forEach(Call::cancel) }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.size() + read > limit) throw ProviderFailure("A resposta recebida excede o limite suportado.")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
