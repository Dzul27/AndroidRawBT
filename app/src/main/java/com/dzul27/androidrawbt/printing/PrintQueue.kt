package com.dzul27.androidrawbt.printing

import android.content.Context
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PrintResult {
    data class Ok(val bytesSent: Int) : PrintResult

    /** [message] is the user-visible reason (no HTTP status prefix). */
    data class Failure(val message: String) : PrintResult
}

/**
 * Single FIFO queue for every print job (HTTP, WebSocket, deep link, test print).
 * kotlinx Mutex is fair, so waiting callers are served in arrival order.
 */
object PrintQueue {
    private val mutex = Mutex()

    @Volatile
    var transport: PrinterTransport = object : PrinterTransport {
        override suspend fun send(mac: String, bytes: ByteArray) {
            throw IllegalStateException("Printer transport not configured")
        }
    }

    @Volatile
    private var macProvider: suspend () -> String? = { null }

    /** Reads the selected printer MAC from [SettingsRepository] on every job. */
    fun init(context: Context) {
        val repo = SettingsRepository(context.applicationContext)
        macProvider = { repo.current().printerMac }
    }

    /** Overrides how the printer MAC is resolved (tests). */
    fun setMacProvider(provider: suspend () -> String?) {
        macProvider = provider
    }

    suspend fun submit(bytes: ByteArray, source: String): PrintResult {
        if (bytes.isEmpty()) return PrintResult.Failure("Empty payload")
        ActivityLog.info("Job from $source queued (${bytes.size} bytes)")
        return mutex.withLock {
            val mac = macProvider()
            if (mac.isNullOrBlank()) {
                val msg = "No printer selected"
                ActivityLog.error("Job from $source failed: $msg")
                return@withLock PrintResult.Failure(msg)
            }
            try {
                transport.send(mac, bytes)
                ActivityLog.info("Job from $source: ${bytes.size} bytes. Printed OK.")
                PrintResult.Ok(bytes.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                ActivityLog.error("Job from $source failed: $msg")
                PrintResult.Failure(msg)
            }
        }
    }
}
