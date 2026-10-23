package com.smartiptv.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

/** Only active-screen thumbnails live in RAM; disk thumbnails have a fixed budget. */
class ArtworkLoader(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(48))
    private val http = XtreamApi.client().newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val directory = File(context.cacheDir, "posters").apply { mkdirs() }
    private val epoch = AtomicInteger()
    private val targets = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val failed = linkedSetOf<String>()
    private class Ticket(val epoch: Int, val key: String) {
        val cancelled = AtomicBoolean()
        @Volatile var call: Call? = null
        @Volatile var task: Runnable? = null
        fun cancel() { cancelled.set(true); call?.cancel() }
    }
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    val memoryBytes: Int get() = memory.size()
    val generation: Int get() = epoch.get()
    val pendingCount: Int get() = executor.queue.size

    fun load(url: String, view: ImageView) {
        clear(view)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val requestEpoch = epoch.get()
        val key = digest(url)
        if (key in failed) return
        val token = Ticket(requestEpoch, key)
        view.tag = token
        targets += view
        memory.get(key)?.let { view.setImageBitmap(it); return }
        val target = WeakReference(view)
        val task = Runnable {
            if (epoch.get() != requestEpoch || token.cancelled.get()) return@Runnable
            val bitmap = try { cachedBitmap(key) ?: download(url, key, token) } catch (_: Exception) { null }
            handler.post {
                val destination = target.get()
                if (bitmap != null && !token.cancelled.get() && epoch.get() == requestEpoch && destination?.tag === token && destination.isAttachedToWindow) {
                    memory.put(key, bitmap)
                    destination.setImageBitmap(bitmap)
                } else {
                    if (!token.cancelled.get() && epoch.get() == requestEpoch && destination?.tag === token) {
                        destination.tag = null
                        failed += key
                        if (failed.size > 1000) failed.remove(failed.first())
                    }
                }
            }
        }
        token.task = task
        try { executor.execute(task) } catch (_: RejectedExecutionException) { token.cancel(); if (view.tag === token) view.tag = null }
    }

    private fun cachedBitmap(key: String): Bitmap? {
        val file = File(directory, "$key.jpg")
        if (!file.exists()) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    private fun download(url: String, key: String, token: Ticket): Bitmap? {
        if (epoch.get() != token.epoch || token.cancelled.get()) return null
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build())
        token.call = call
        calls += call
        if (token.cancelled.get() || epoch.get() != token.epoch) call.cancel()
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful || epoch.get() != token.epoch || token.cancelled.get()) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > 5 * 1024 * 1024) return@use null
                val raw = File.createTempFile("poster-", ".download", directory)
                try {
                body.byteStream().use { input -> raw.outputStream().buffered().use { output ->
                    val bytes = ByteArray(16384); var total = 0
                    while (true) {
                        if (token.cancelled.get() || Thread.currentThread().isInterrupted) return@use
                        val count = input.read(bytes); if (count < 0) break
                        total += count; if (total > 5 * 1024 * 1024) throw ProviderFailure("Capa muito grande.")
                        output.write(bytes, 0, count)
                    }
                } }
                if (token.cancelled.get() || epoch.get() != token.epoch) return@use null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(raw.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                var sample = 1
                while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 720) sample *= 2
                val decoded = BitmapFactory.decodeFile(raw.path, BitmapFactory.Options().apply {
                    inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
                }) ?: return@use null
                val ratio = minOf(1f, 240f / decoded.width, 360f / decoded.height)
                val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true) else decoded
                if (scaled !== decoded) decoded.recycle()
                if (epoch.get() != token.epoch || token.cancelled.get()) { scaled.recycle(); return@use null }
                val file = File(directory, "$key.jpg")
                val pending = File.createTempFile("thumb-", ".tmp", directory)
                try {
                    pending.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    pending.renameTo(file)
                } finally { pending.delete() }
                trimDisk()
                scaled
                } finally { raw.delete() }
            }
        } finally { calls -= call }
    }

    private fun trimDisk() {
        val files = directory.listFiles { file -> file.extension == "jpg" }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size <= 64L * 1024 * 1024) break
            val length = file.length()
            if (file.delete()) size -= length
        }
    }

    fun clear(view: ImageView) {
        (view.tag as? Ticket)?.let { ticket -> ticket.cancel(); ticket.task?.let(executor::remove) }
        view.tag = null; view.setImageDrawable(null); targets.remove(view)
    }
    fun switchScreen() {
        epoch.incrementAndGet()
        synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }
        targets.toList().forEach { clear(it) }
        executor.queue.clear()
        targets.clear()
        failed.clear()
        memory.evictAll()
    }
    fun close() { switchScreen(); executor.shutdownNow(); http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}
