package com.smartiptv.tv

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

class Jobs {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val network = Executors.newFixedThreadPool(2)
    private val catalog = Executors.newSingleThreadExecutor()
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    fun <T> run(refresh: Boolean = false, network: Boolean = false, work: () -> T, done: (T) -> Unit, failed: (String) -> Unit = {}): Future<*> {
        val executor = if (refresh) catalog else if (network) this.network else io
        return executor.submit {
            try {
                val result = work()
                if (!closed && !Thread.currentThread().isInterrupted) main.post { if (!closed) done(result) }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (error: Exception) {
                val message = if (error is ProviderFailure) error.message ?: "Falha ao carregar." else "Não foi possível concluir. Confira a conexão e tente novamente."
                if (!closed && !Thread.currentThread().isInterrupted) main.post { if (!closed) failed(message) }
            }
        }
    }
    fun ui(work: () -> Unit) { if (!closed) main.post { if (!closed) work() } }
    fun write(work: () -> Unit) { if (!closed) writer.execute { try { work() } catch (_: Exception) { /* Keep decoding usable when local storage is temporarily unavailable. */ } } }
    fun afterWrites(done: () -> Unit) { if (!closed) writer.execute { ui(done) } }
    fun close(after: () -> Unit) {
        closed = true
        main.removeCallbacksAndMessages(null)
        catalog.shutdownNow()
        io.shutdown()
        network.shutdownNow()
        writer.shutdown()
        Executors.newSingleThreadExecutor().let { cleanup ->
            cleanup.execute { io.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); network.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); catalog.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); after(); cleanup.shutdown() }
        }
    }
}
