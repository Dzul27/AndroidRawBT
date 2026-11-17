package com.dzul27.androidrawbt.server

import com.dzul27.androidrawbt.settings.Settings
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Loopback HTTP + WebSocket print server (Ktor CIO) bound to 127.0.0.1:[port] only (NFR-2).
 * [settings] is read on every request so origin rules apply live.
 */
class PrintServer(val port: Int, private val settings: () -> Settings) {
    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Binds the port. Blocking: call from a background thread.
     * @throws java.net.BindException if the port is already in use.
     */
    @Synchronized
    fun start() {
        if (engine != null) return
        // Probe first so a port conflict surfaces here as a plain BindException regardless of how
        // the engine reports bind failures from its worker coroutines.
        ServerSocket(port, 1, LOOPBACK).close()
        val boundPort = port
        val currentSettings = settings
        val server = embeddedServer(CIO, port = boundPort, host = LOOPBACK_HOST) {
            printModule(boundPort, currentSettings)
        }
        try {
            server.start(wait = false)
        } catch (e: Throwable) {
            runCatching { server.stop(0, 0) }
            throw e
        }
        engine = server
    }

    /** Graceful stop; safe to call when not started. Blocking: call from a background thread. */
    @Synchronized
    fun stop() {
        val server = engine ?: return
        engine = null
        server.stop(gracePeriodMillis = 300, timeoutMillis = 1_500)
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
