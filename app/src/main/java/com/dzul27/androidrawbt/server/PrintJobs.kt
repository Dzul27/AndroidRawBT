package com.dzul27.androidrawbt.server

import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.core.PayloadDecodeException
import com.dzul27.androidrawbt.core.PayloadDecoder
import com.dzul27.androidrawbt.core.hexAsciiPreview
import com.dzul27.androidrawbt.printing.PrintQueue
import com.dzul27.androidrawbt.printing.PrintResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

/** HTTP status plus the JSON body that is sent to the client (HTTP body or WS text frame). */
internal data class JobOutcome(val status: Int, val json: String)

internal const val MSG_EMPTY_OR_INVALID = "Empty payload or invalid Base64"
private const val PREVIEW_BYTES = 48

internal fun okJson(bytesSent: Int) = """{"status":"OK","bytesSent":$bytesSent}"""

internal fun errorJson(message: String) = """{"status":"ERROR","message":"${jsonEscape(message)}"}"""

internal fun jsonEscape(value: String): String {
    val sb = StringBuilder(value.length + 8)
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

/** Decode one print job (HTTP body or WebSocket frame), log it (never the whole payload) and queue it. */
internal object PrintJobs {
    private val counter = AtomicInteger()

    /**
     * @param origin where the job came from, used for the log line and as the [PrintQueue] source ("http", "ws").
     * @param contentTypeLabel Content-Type text shown in the log.
     * @param decoderContentType Content-Type handed to [PayloadDecoder].
     */
    suspend fun process(
        origin: String,
        client: String,
        contentTypeLabel: String,
        decoderContentType: String?,
        body: ByteArray,
    ): JobOutcome {
        val tag = "Job #${counter.incrementAndGet()}"
        try {
            val decoded = try {
                PayloadDecoder.decode(body, decoderContentType)
            } catch (e: PayloadDecodeException) {
                ActivityLog.info("$tag $client | Content-Type: $contentTypeLabel | body ${body.size} B")
                ActivityLog.info("$tag body as received: ${PayloadDecoder.formatBodyHead(body)}")
                ActivityLog.warn("$tag rejected: ${e.message ?: MSG_EMPTY_OR_INVALID}")
                return JobOutcome(400, errorJson(MSG_EMPTY_OR_INVALID))
            }
            val payload = decoded.bytes
            ActivityLog.info(
                "$tag $client | Content-Type: $contentTypeLabel | body ${body.size} B -> ${payload.size} B | " +
                    "decode: ${decoded.path}",
            )
            if (!payload.contentEquals(body)) {
                ActivityLog.info("$tag body as received: ${PayloadDecoder.formatBodyHead(body)}")
            }
            if (payload.isEmpty()) {
                ActivityLog.warn("$tag rejected: empty payload.")
                return JobOutcome(400, errorJson(MSG_EMPTY_OR_INVALID))
            }
            ActivityLog.info(
                "$tag first ${minOf(payload.size, PREVIEW_BYTES)} of ${payload.size} B (hex | ascii): " +
                    hexAsciiPreview(payload, PREVIEW_BYTES),
            )
            ActivityLog.info("$tag ${PayloadDecoder.summarizeEscPos(payload)}")
            return when (val result = PrintQueue.submit(payload, origin)) {
                is PrintResult.Ok -> JobOutcome(200, okJson(result.bytesSent))
                is PrintResult.Failure ->
                    JobOutcome(500, errorJson("Bluetooth connection failed: ${result.message}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            ActivityLog.error("$tag failed: $msg")
            return JobOutcome(500, errorJson("Internal error: $msg"))
        }
    }
}
