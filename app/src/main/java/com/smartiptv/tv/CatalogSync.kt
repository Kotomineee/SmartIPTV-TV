package com.smartiptv.tv

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Two bounded downloads overlap; one importer publishes each successful section independently. */
class CatalogSync(private val api: XtreamApi, private val db: LibraryDb, private val cache: File) {
    data class Result(val updated: List<String>, val failed: List<String>)
    private data class Download(val kind: String, val categories: List<Category> = emptyList(), val file: File? = null)
    fun refresh(force: Boolean, priority: String = "movie", sectionReady: (String) -> Unit = {}): Result {
        val kinds = listOf(priority, "movie", "series", "live").distinct().filter { it in listOf("movie", "series", "live") && (force || !db.isFresh(api.credentials.account, it)) }
        if (kinds.isEmpty()) return Result(emptyList(), emptyList())
        val directory = File(cache, "catalog-sync").apply { mkdirs() }
        // A previous interrupted process may have left only disposable network responses here.
        directory.listFiles()?.forEach { it.delete() }
        val workers = Executors.newFixedThreadPool(2)
        val ready = ExecutorCompletionService<Download>(workers)
        val futures = kinds.map { kind -> ready.submit(Callable {
            try {
                val categories = api.categories(kind)
                val file = api.downloadCatalog(kind, directory)
                if (Thread.currentThread().isInterrupted) { file.delete(); throw InterruptedException() }
                Download(kind, categories, file)
            } catch (error: InterruptedException) { throw error }
            catch (_: Exception) { Download(kind) }
        }) }
        val updated = mutableListOf<String>(); val failed = mutableListOf<String>()
        try {
            repeat(kinds.size) {
                val download = ready.take().get()
                if (download.file == null) failed += download.kind
                else try {
                    db.replaceSection(api.credentials.account, download.kind, download.categories) { sink -> api.importCatalog(download.kind, download.file, sink) }
                    updated += download.kind; sectionReady(download.kind)
                } catch (error: InterruptedException) { throw error }
                catch (_: Exception) { failed += download.kind }
                finally { download.file.delete() }
            }
            return Result(updated, failed)
        } finally {
            if (Thread.currentThread().isInterrupted || futures.any { !it.isDone }) api.cancelCatalogs()
            futures.forEach { it.cancel(true) }; workers.shutdownNow()
            // File deletion is on this worker. Each active download also deletes itself on cancellation.
            try { workers.awaitTermination(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            directory.listFiles()?.forEach { it.delete() }
        }
    }
}
