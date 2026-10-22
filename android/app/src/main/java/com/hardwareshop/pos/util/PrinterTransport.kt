package com.hardwareshop.pos.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface PrinterConnection : Closeable {
    fun connect()
    val output: OutputStream
}

/** Once writing starts, delivery is uncertain on failure: never automatically resend. */
internal class PrintDeliveryException(message: String, cause: Throwable) : IOException(message, cause)

internal class PrinterTransport(
    private val connectTimeoutMs: Long = 8_000,
    private val writeTimeoutMs: Long = 90_000,
    private val chunkSize: Int = 512,
    private val chunkDelayMs: Long = 40,
    private val drainMs: Long = 1_500
) {
    private val mutex = Mutex()
    companion object {
        private val io = Executors.newCachedThreadPool { Thread(it, "printer-io").apply { isDaemon = true } }
        private val timer = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "printer-timeout").apply { isDaemon = true }
        }
        const val MAX_PAYLOAD_BYTES = 512 * 1024
    }

    suspend fun send(bytes: ByteArray, factories: List<() -> PrinterConnection>, beforeWrite: suspend () -> Unit = {}) = mutex.withLock {
        require(bytes.isNotEmpty()) { "Nothing to print." }
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Receipt is too large. Split it into smaller bills." }
        var last: Exception = IOException("No printer connection available")
        for (create in factories) {
            var connection: PrinterConnection? = null
            var writing = false
            var connected = false
            try {
                connection = create()
                val active = connection
                bounded(active, connectTimeoutMs) { active.connect() }
                connected = true
                beforeWrite()
                // Conservative boundary: OutputStream.write may accept bytes before throwing.
                writing = true
                bounded(active, writeTimeoutMs) {
                    val output = active.output
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = minOf(chunkSize, bytes.size - offset)
                        output.write(bytes, offset, count)
                        output.flush()
                        offset += count
                        if (offset < bytes.size && chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                    }
                    if (drainMs > 0) Thread.sleep(drainMs)
                }
                return@withLock
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (writing) throw PrintDeliveryException(
                    "Print interrupted. Some or all of the receipt may have printed. Check the paper before reprinting.", e
                )
                if (connected || e is SecurityException) throw e
                last = e
            } finally {
                runCatching { connection?.close() }
            }
            delay(200)
        }
        throw IOException("Cannot connect to the printer. Check power, pairing and range, then try again.", last)
    }

    /** The actual socket is owned before scheduling and closed on timeout/cancellation. */
    private suspend fun bounded(connection: PrinterConnection, timeoutMs: Long, operation: () -> Unit) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val task = java.util.concurrent.FutureTask {
                try {
                    operation()
                    if (completed.compareAndSet(false, true)) continuation.resume(Unit)
                } catch (e: Exception) {
                    if (completed.compareAndSet(false, true)) continuation.resumeWithException(e)
                }
            }
            val deadline = timer.schedule({
                if (completed.compareAndSet(false, true)) {
                    runCatching { connection.close() }
                    task.cancel(true)
                    continuation.resumeWithException(TimeoutException("Printer operation timed out"))
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
            continuation.invokeOnCancellation {
                completed.set(true)
                deadline.cancel(false)
                runCatching { connection.close() }
                task.cancel(true)
            }
            io.execute {
                try { task.run() } finally { deadline.cancel(false) }
            }
        }
    }
}
