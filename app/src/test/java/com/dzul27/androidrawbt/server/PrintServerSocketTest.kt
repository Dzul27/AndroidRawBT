package com.dzul27.androidrawbt.server

import com.dzul27.androidrawbt.printing.PrintQueue
import com.dzul27.androidrawbt.printing.PrinterTransport
import com.dzul27.androidrawbt.settings.Settings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Starts the real [PrintServer] (Ktor CIO) on an ephemeral port and talks to it over plain sockets, covering what
 * `testApplication` cannot: the loopback-only bind (NFR-2), the 403 origin gate and the WebSocket upgrade.
 */
class PrintServerSocketTest {
    private val sent = CopyOnWriteArrayList<ByteArray>()
    private var settings = Settings()
    private var server: PrintServer? = null
    private var port = 0

    @Before
    fun setUp() {
        sent.clear()
        PrintQueue.setMacProvider { "00:11:22:33:44:55" }
        PrintQueue.transport = object : PrinterTransport {
            override suspend fun send(mac: String, bytes: ByteArray) {
                sent += bytes
            }
        }
        port = freePort()
        server = PrintServer(port) { settings }.also { it.start() }
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun postOverRealSocketPrintsAndReturns200() {
        val payload = byteArrayOf(0x1B, 0x40, 'H'.code.toByte(), 'i'.code.toByte(), 0x0A)
        val b64 = Base64.getEncoder().encodeToString(payload)
        val response = http("POST", "/", "text/plain", b64.toByteArray())
        assertEquals(200, response.status)
        assertEquals("""{"status":"OK","bytesSent":5}""", response.body)
        assertEquals(1, sent.size)
        assertTrue(sent[0].contentEquals(payload))
        assertEquals("*", response.headers["access-control-allow-origin"])
    }

    @Test
    fun disallowedOriginIs403AndNothingPrinted() {
        settings = Settings(allowAnyOrigin = false, allowedOrigins = listOf("https://pos.example.com"))
        val bad = http("POST", "/", "text/plain", "SGk=".toByteArray(), origin = "https://evil.example")
        assertEquals(403, bad.status)
        assertTrue(sent.isEmpty())

        val good = http("POST", "/", "text/plain", "SGk=".toByteArray(), origin = "https://pos.example.com")
        assertEquals(200, good.status)
        assertEquals("https://pos.example.com", good.headers["access-control-allow-origin"])
        assertEquals(1, sent.size)
    }

    @Test
    fun preflightAdvertisesPrivateNetworkAccess() {
        val response = http("OPTIONS", "/", null, null, origin = "https://pos.example.com")
        assertEquals(200, response.status)
        assertEquals("true", response.headers["access-control-allow-private-network"])
    }

    @Test
    fun webSocketTextFramePrintsAndReplies() {
        val payload = "hello"
        Socket().use { s ->
            s.soTimeout = 5_000
            s.connect(InetSocketAddress(LOOPBACK, port), 2_000)
            val out = s.getOutputStream()
            out.write(
                (
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()
            val input = s.getInputStream()
            val head = readHead(input)
            assertTrue(head, head.startsWith("HTTP/1.1 101"))

            // Client frames must be masked (RFC 6455).
            val mask = byteArrayOf(1, 2, 3, 4)
            val b64 = Base64.getEncoder().encodeToString(payload.toByteArray()).toByteArray()
            out.write(0x81)
            out.write(0x80 or b64.size)
            out.write(mask)
            out.write(ByteArray(b64.size) { (b64[it].toInt() xor mask[it % 4].toInt()).toByte() })
            out.flush()

            val b0 = input.read()
            assertEquals(0x81, b0)
            val len = input.read()
            assertTrue(len in 1..125)
            val reply = String(input.readNBytes(len))
            assertEquals("""{"status":"OK","bytesSent":5}""", reply)
            assertEquals(1, sent.size)
            assertEquals(payload, String(sent[0]))
        }
    }

    @Test
    fun webSocketUpgradeFromDisallowedOriginIs403() {
        settings = Settings(allowAnyOrigin = false)
        Socket().use { s ->
            s.soTimeout = 5_000
            s.connect(InetSocketAddress(LOOPBACK, port), 2_000)
            s.getOutputStream().write(
                (
                    "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.example\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n"
                    ).toByteArray(),
            )
            s.getOutputStream().flush()
            val head = readHead(s.getInputStream())
            assertTrue(head, head.startsWith("HTTP/1.1 403"))
        }
    }

    @Test
    fun serverIsBoundToLoopbackOnly() {
        // Sanity: reachable through 127.0.0.1.
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 2_000) }

        val nonLoopback = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
        assumeTrue("no non-loopback IPv4 interface on this machine", nonLoopback.isNotEmpty())
        for (addr in nonLoopback) {
            try {
                Socket().use { it.connect(InetSocketAddress(addr, port), 1_500) }
                fail("server must not be reachable via ${addr.hostAddress}:$port")
            } catch (expected: java.io.IOException) {
                // ConnectException (refused) or SocketTimeoutException: not reachable, as required.
            }
        }
    }

    @Test
    fun startingOnABusyPortThrowsBindException() {
        try {
            PrintServer(port) { settings }.start()
            fail("expected BindException")
        } catch (expected: BindException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun http(
        method: String,
        path: String,
        contentType: String?,
        body: ByteArray?,
        origin: String? = null,
    ): Response = Socket().use { s ->
        s.soTimeout = 5_000
        s.connect(InetSocketAddress(LOOPBACK, port), 2_000)
        val req = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
        origin?.let { req.append("Origin: $it\r\n") }
        if (method == "OPTIONS") {
            req.append("Access-Control-Request-Method: POST\r\nAccess-Control-Request-Private-Network: true\r\n")
        }
        contentType?.let { req.append("Content-Type: $it\r\n") }
        req.append("Content-Length: ${body?.size ?: 0}\r\n\r\n")
        val out = s.getOutputStream()
        out.write(req.toString().toByteArray())
        body?.let { out.write(it) }
        out.flush()
        val raw = ByteArrayOutputStream().also { s.getInputStream().copyTo(it) }.toByteArray()
        val text = String(raw, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        val head = text.substring(0, split).split("\r\n")
        val status = head[0].split(" ")[1].toInt()
        val headers = head.drop(1).associate { line ->
            val i = line.indexOf(':')
            line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }
        val rawBody = text.substring(split + 4)
        Response(status, headers, if (headers["transfer-encoding"]?.contains("chunked") == true) dechunk(rawBody) else rawBody)
    }

    private fun dechunk(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val eol = s.indexOf("\r\n", i)
            if (eol < 0) break
            val size = s.substring(i, eol).trim().toInt(16)
            if (size == 0) break
            sb.append(s, eol + 2, eol + 2 + size)
            i = eol + 2 + size + 2
        }
        return sb.toString()
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun freePort(): Int = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
