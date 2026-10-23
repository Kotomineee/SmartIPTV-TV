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
import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit

class XtreamApi(val credentials: Credentials, private val http: OkHttpClient = client()) {
    private val requestTag = Any()
    private data class Scope(val owner: Any, val details: Boolean, val catalog: Boolean)
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
            val request = Request.Builder().url(url(action, extra)).tag(Scope(requestTag, action in listOf("get_vod_info", "get_series_info"), action in listOf("get_vod_streams", "get_series", "get_live_streams", "get_vod_categories", "get_series_categories", "get_live_categories"))).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
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
            val source = body.byteStream()
            val bounded = object : java.io.InputStream() {
                var bytes = 0L
                private fun counted(size: Int): Int {
                    if (size > 0) bytes += size
                    if (bytes > 6L * 1024 * 1024) throw ProviderFailure("A resposta recebida excede o limite suportado.")
                    return size
                }
                override fun read(): Int { val value = source.read(); if (value >= 0) counted(1); return value }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = counted(source.read(buffer, offset, length))
                override fun close() = source.close()
            }
            try { JsonReader(java.io.InputStreamReader(bounded, Charsets.UTF_8)).use { readValue(it) as? JSONObject ?: throw ProviderFailure("O servidor não retornou uma resposta Xtream válida.") } }
            catch (error: ProviderFailure) { throw error }
            catch (error: InterruptedException) { throw error }
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
        array("get_${if (kind == "movie") "vod" else kind}_categories", setOf("category_id", "category_name")) { row ->
            result += Category(row.text("category_id"), row.text("category_name", "Sem categoria"))
        }
        return result
    }

    private fun catalogAction(kind: String) = when (kind) { "movie" -> "get_vod_streams"; "series" -> "get_series"; else -> "get_live_streams" }
    private val catalogFields = setOf("stream_id", "series_id", "name", "title", "category_id", "category_ids", "stream_icon", "cover", "added", "last_modified", "year", "rating", "plot", "container_extension")
    fun catalog(kind: String, consumer: (Content) -> Unit) = array(catalogAction(kind), catalogFields) { row -> Content.fromApi(kind, row)?.let(consumer) }

    /** Network I/O completes on disk before taking any SQLite writer lock. */
    fun downloadCatalog(kind: String, directory: File): File {
        directory.mkdirs()
        val file = File.createTempFile("catalog-", ".json", directory)
        try {
            response(catalogAction(kind)).use { result ->
                val body = result.body ?: throw ProviderFailure("Resposta vazia do provedor.")
                body.byteStream().use { input -> file.outputStream().buffered(32768).use { output ->
                    val buffer = ByteArray(32768); var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > 128L * 1024 * 1024) throw ProviderFailure("O catálogo recebido excede o espaço reservado neste aparelho.")
                        output.write(buffer, 0, count)
                    }
                } }
            }
            return file
        } catch (error: Exception) { file.delete(); throw error }
    }
    fun importCatalog(kind: String, file: File, consumer: (Content) -> Unit) {
        file.reader().buffered(32768).use { readArray(it, catalogFields) { row -> Content.fromApi(kind, row)?.let(consumer) } }
    }

    private fun array(action: String, fields: Set<String>, consumer: (JSONObject) -> Unit) {
        try {
            response(action).use { response ->
                readArray((response.body ?: throw ProviderFailure("Resposta vazia do provedor.")).charStream(), fields, consumer)
            }
        } catch (error: ProviderFailure) { throw error }
        catch (error: InterruptedException) { throw error }
        catch (_: Exception) { throw ProviderFailure("Não foi possível ler o catálogo. Confira a conexão e tente atualizar.") }
    }
    private fun readArray(source: Reader, fields: Set<String>, consumer: (JSONObject) -> Unit) {
                JsonReader(source).use {
                    if (it.peek() != JsonToken.BEGIN_ARRAY) throw ProviderFailure("O servidor enviou um catálogo em formato inesperado.")
                    it.beginArray()
                    var count = 0
                    while (it.hasNext()) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        if (++count > 300000) throw ProviderFailure("O catálogo excede o limite suportado neste aparelho.")
                        if (it.peek() != JsonToken.BEGIN_OBJECT) { it.skipValue(); continue }
                        val row = JSONObject()
                        it.beginObject()
                        while (it.hasNext()) {
                            val key = it.nextName()
                            if (key in fields) row.put(key, readValue(it)) else it.skipValue()
                        }
                        it.endObject()
                        consumer(row)
                    }
                    it.endArray()
                }
    }

    private fun readValue(reader: JsonReader, depth: Int = 0): Any {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (depth > 15) throw ProviderFailure("O catálogo recebido não é válido.")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (key in detailFields || key.toIntOrNull() != null) result.put(key, readValue(reader, depth + 1)) else reader.skipValue()
                }
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

    private val detailFields = setOf("user_info", "auth", "status", "exp_date", "active_cons", "max_connections", "info", "episodes", "id", "season", "episode_num", "title", "container_extension", "added", "plot", "description", "duration_secs")

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
    fun cancelCatalogs() { (http.dispatcher.queuedCalls() + http.dispatcher.runningCalls()).filter { (it.request().tag() as? Scope)?.let { tag -> tag.owner === requestTag && tag.catalog } == true }.forEach(Call::cancel) }
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
