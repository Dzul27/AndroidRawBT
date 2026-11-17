package com.dzul27.androidrawbt.server

import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.settings.Settings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException

private const val SERVICE_NAME = "AndroidRawBT"
private const val PRIVATE_NETWORK_HEADER = "Access-Control-Allow-Private-Network"
private const val PREFLIGHT_MAX_AGE_SECONDS = "86400"
private const val ALLOWED_METHODS = "POST, GET, OPTIONS"
private val JSON = ContentType.Application.Json

/**
 * Routing for the print API (PRD 6.2), installable without sockets so tests can use `testApplication`.
 * [settings] is read on every request so origin rules apply live.
 *
 * Order of work for every request: origin check (403 before anything is decoded/printed, WebSocket upgrades
 * included) -> CORS headers -> `OPTIONS` preflight -> routes.
 */
fun Application.printModule(port: Int, settings: () -> Settings) {
    install(WebSockets)

    intercept(ApplicationCallPipeline.Plugins) {
        val s = settings()
        val origin = call.request.headers[HttpHeaders.Origin]
        when (val decision = OriginPolicy.evaluate(origin, s.allowAnyOrigin, s.allowedOrigins)) {
            OriginDecision.Rejected -> {
                ActivityLog.warn(
                    "Rejected request from origin \"$origin\" (not in the allowed websites). " +
                        "Add it under Settings > Allowed origins, or enable \"Allow any origin\".",
                )
                call.respondText(errorJson("Origin not allowed"), JSON, HttpStatusCode.Forbidden)
                finish()
            }

            is OriginDecision.Allowed -> {
                val method = call.request.local.method
                if (method !in HANDLED_METHODS) {
                    call.respondText(errorJson("Method not allowed"), JSON, HttpStatusCode.MethodNotAllowed)
                    finish()
                    return@intercept
                }
                addCorsHeaders(call, decision)
                if (method == HttpMethod.Options) {
                    call.response.headers.append(HttpHeaders.AccessControlMaxAge, PREFLIGHT_MAX_AGE_SECONDS)
                    call.response.headers.append(PRIVATE_NETWORK_HEADER, "true")
                    call.respondText("", status = HttpStatusCode.OK)
                    finish()
                }
            }
        }
    }

    routing {
        get("/") {
            call.respondText(
                """{"status":"OK","service":"$SERVICE_NAME","port":$port,"running":true}""",
                JSON,
            )
        }

        post("/") {
            val body = call.receive<ByteArray>()
            val contentType = call.request.headers[HttpHeaders.ContentType]
            val outcome = PrintJobs.process(
                origin = "http",
                client = "HTTP POST / ${describeClient(call)}",
                contentTypeLabel = contentType ?: "(none)",
                decoderContentType = contentType,
                body = body,
            )
            call.respondText(outcome.json, JSON, HttpStatusCode.fromValue(outcome.status))
        }

        webSocket("/") {
            val client = describeClient(call)
            ActivityLog.info("WebSocket client connected. $client")
            try {
                for (frame in incoming) {
                    val isText = frame is Frame.Text
                    if (!isText && frame !is Frame.Binary) continue
                    val raw = frame.readBytes()
                    if (raw.isEmpty()) continue
                    val outcome = PrintJobs.process(
                        origin = "ws",
                        client = "WS ${if (isText) "text" else "binary"} frame $client",
                        contentTypeLabel = if (isText) "n/a (WS text frame)" else "n/a (WS binary frame)",
                        decoderContentType = if (isText) "text/plain" else "application/octet-stream",
                        body = raw,
                    )
                    send(Frame.Text(outcome.json))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActivityLog.warn("WebSocket error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                ActivityLog.info("WebSocket client disconnected.")
            }
        }
    }
}

private val HANDLED_METHODS = setOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Options)

private fun addCorsHeaders(call: ApplicationCall, decision: OriginDecision.Allowed) {
    val headers = call.response.headers
    decision.allowOrigin?.let { headers.append(HttpHeaders.AccessControlAllowOrigin, it) }
    if (decision.varyOrigin) headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
    headers.append(HttpHeaders.AccessControlAllowMethods, ALLOWED_METHODS)
    // Echo what the preflight asks for; a wildcard otherwise.
    headers.append(
        HttpHeaders.AccessControlAllowHeaders,
        call.request.headers[HttpHeaders.AccessControlRequestHeaders]?.takeIf { it.isNotBlank() } ?: "*",
    )
}

/** "(User-Agent: ..., Origin: ...)" to tell which POS app is sending; empty when neither is present. */
private fun describeClient(call: ApplicationCall): String {
    val agent = call.request.headers[HttpHeaders.UserAgent]?.let { if (it.length > 60) it.take(60) + "..." else it }
    val origin = call.request.headers[HttpHeaders.Origin]
    if (agent == null && origin == null) return ""
    return "(User-Agent: ${agent ?: "-"}, Origin: ${origin ?: "-"})"
}
