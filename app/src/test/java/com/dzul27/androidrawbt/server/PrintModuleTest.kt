package com.dzul27.androidrawbt.server

import com.dzul27.androidrawbt.printing.PrintQueue
import com.dzul27.androidrawbt.printing.PrinterTransport
import com.dzul27.androidrawbt.settings.Settings
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrintModuleTest {
    private val sent = mutableListOf<ByteArray>()
    private var failWith: String? = null

    @Before
    fun setUp() {
        sent.clear()
        failWith = null
        PrintQueue.setMacProvider { "00:11:22:33:44:55" }
        PrintQueue.transport = object : PrinterTransport {
            override suspend fun send(mac: String, bytes: ByteArray) {
                failWith?.let { throw IOException(it) }
                sent += bytes
            }
        }
    }

    private fun serve(settings: Settings = Settings(), block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        runBlocking {
            testApplication {
                application { printModule(8080) { settings } }
                block()
            }
        }
    }

    @Test
    fun rawPostIsForwardedByteForByte() = serve {
        val response = client.post("/") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(0x1B, 0x40, 0x41, 0x0A))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"OK","bytesSent":4}""", response.bodyAsText())
        assertEquals(1, sent.size)
        assertTrue(sent[0].contentEquals(byteArrayOf(0x1B, 0x40, 0x41, 0x0A)))
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun emptyPayloadIs400() = serve {
        val response = client.post("/") {
            contentType(ContentType.Text.Plain)
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            """{"status":"ERROR","message":"Empty payload or invalid Base64"}""",
            response.bodyAsText(),
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun transportFailureIs500WithReason() = serve {
        failWith = "Printer unreachable or powered off."
        val response = client.post("/") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(
            """{"status":"ERROR","message":"Bluetooth connection failed: Printer unreachable or powered off."}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun rejectedOriginIs403BeforePrinting() = serve(Settings(allowAnyOrigin = false, allowedOrigins = listOf("https://pos.example.com"))) {
        val response = client.post("/") {
            header(HttpHeaders.Origin, "https://evil.example")
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun allowedOriginIsEchoedAndMissingOriginAccepted() = serve(Settings(allowAnyOrigin = false, allowedOrigins = listOf("https://pos.example.com"))) {
        val withOrigin = client.post("/") {
            header(HttpHeaders.Origin, "https://pos.example.com")
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(1))
        }
        assertEquals(HttpStatusCode.OK, withOrigin.status)
        assertEquals("https://pos.example.com", withOrigin.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals(HttpHeaders.Origin, withOrigin.headers[HttpHeaders.Vary])

        val noOrigin = client.post("/") {
            contentType(ContentType.Application.OctetStream)
            setBody(byteArrayOf(2))
        }
        assertEquals(HttpStatusCode.OK, noOrigin.status)
        assertNull(noOrigin.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun preflightCarriesCorsAndPrivateNetworkHeaders() = serve {
        val response = client.options("/") {
            header(HttpHeaders.Origin, "https://pos.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type, x-custom")
            header("Access-Control-Request-Private-Network", "true")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", response.headers["Access-Control-Allow-Private-Network"])
        assertEquals("content-type, x-custom", response.headers[HttpHeaders.AccessControlAllowHeaders])
        assertEquals("POST, GET, OPTIONS", response.headers[HttpHeaders.AccessControlAllowMethods])
        assertEquals("86400", response.headers[HttpHeaders.AccessControlMaxAge])
        assertTrue(sent.isEmpty())
    }

    @Test
    fun preflightFromRejectedOriginIs403() = serve(Settings(allowAnyOrigin = false)) {
        val response = client.options("/") {
            header(HttpHeaders.Origin, "https://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun getReturnsServiceStatus() = serve {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"OK","service":"AndroidRawBT","port":8080,"running":true}""", response.bodyAsText())
    }
}
