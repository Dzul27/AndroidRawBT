package com.dzul27.androidrawbt.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Result of decoding an incoming print payload into raw printer bytes. */
data class DecodeResult(val bytes: ByteArray, val path: String) {
    override fun equals(other: Any?): Boolean =
        other is DecodeResult && path == other.path && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + path.hashCode()
}

/** Thrown when the payload is empty or cannot be decoded into printer bytes. */
class PayloadDecodeException(message: String) : Exception(message)

/**
 * Single-line hex + ASCII preview of the first [max] bytes, e.g. `1B 40 54 45 | .@TE`;
 * longer payloads append the last 8 bytes.
 */
fun hexAsciiPreview(bytes: ByteArray, max: Int = 32): String {
    val n = minOf(bytes.size, max)
    val sb = StringBuilder(n * 4 + 40)
    for (i in 0 until n) {
        if (i > 0) sb.append(' ')
        sb.append(hex2(bytes[i]))
    }
    sb.append(" | ")
    for (i in 0 until n) sb.append(printable(bytes[i]))
    if (bytes.size > n) {
        sb.append(" ... tail: ")
        val tailStart = maxOf(n, bytes.size - 8)
        for (i in tailStart until bytes.size) {
            if (i > tailStart) sb.append(' ')
            sb.append(hex2(bytes[i]))
        }
    }
    return sb.toString()
}

private fun hex2(b: Byte): String = "%02X".format(b.toInt() and 0xFF)

private fun printable(b: Byte): Char {
    val v = b.toInt() and 0xFF
    return if (v in 0x20..0x7E) v.toChar() else '.'
}

/** Port of WinRawBT's PayloadDecoder. Shared by HTTP, WebSocket and deep-link entry points. */
object PayloadDecoder {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val FS = 0x1C
    private const val DLE = 0x10
    private const val MAX_DEPTH = 4

    private val LATIN1 = Charsets.ISO_8859_1

    /** JSON / form / multipart field names that may carry the payload, in priority order (normalised). */
    private val DATA_KEYS = listOf(
        "data", "payload", "rawdata", "raw", "bytes", "buffer", "base64", "b64", "escpos", "printdata",
        "content", "commands", "command", "cmd", "body", "text", "print", "job", "message", "value",
        "input", "document", "receipt", "output",
    )

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val ENVELOPE_RX = Regex("""^(?:intent|rawbt):""", IC)
    private val DATA_URI_RX = Regex("""^data:[^\s,]*,""", IC)
    private val ESCAPED_COMMAND_RX = Regex("""\\(?:x1[bd]|u001[bd]|03[35])""", IC)
    private val PART_NAME_RX = Regex("""\bname="?([^";\r\n]+)""", IC)
    private val PART_FILE_RX = Regex("""\bfilename="?([^";\r\n]*)""", IC)
    private val PART_TYPE_RX = Regex("""^content-type:\s*([^\r\n]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val WHITESPACE_RX = Regex("[ \t\r\n]+")

    private class Res(
        val bytes: ByteArray,
        val path: String,
        val transformed: Boolean,
        val error: String? = null,
    )

    private class Part(val name: String, val fileName: String?, val type: String?, val content: ByteArray)

    private class ContentType(val mediaType: String, val charset: String?, val boundary: String?)

    /**
     * @param contentType value of the Content-Type header (may be null).
     * @throws PayloadDecodeException on empty/invalid payloads.
     */
    fun decode(body: ByteArray, contentType: String?): DecodeResult {
        if (body.isEmpty()) throw PayloadDecodeException("Empty payload")
        val r = decodeBody(body, contentType, 0)
        if (r.error != null) throw PayloadDecodeException(r.error)
        if (r.bytes.isEmpty()) throw PayloadDecodeException("Empty payload (${r.path})")
        return DecodeResult(r.bytes, r.path)
    }

    // ---------------------------------------------------------------------
    // Body level
    // ---------------------------------------------------------------------
    private fun decodeBody(body: ByteArray, contentType: String?, depth: Int): Res {
        val ct = parseContentType(contentType)

        if (depth > MAX_DEPTH || (isBinaryMediaType(ct.mediaType) && !looksLikeEnvelope(body))) {
            return Res(body, "raw (binary content type, untouched)", false)
        }

        if (ct.mediaType.startsWith("multipart/") && ct.boundary != null) {
            val multipart = tryMultipart(body, ct.boundary, depth)
            if (multipart != null) return multipart
        }

        var bytes = body
        val text: String
        var bytesAreText = true
        var utf8Text = false
        val charset = ct.charset

        if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) {
            text = String(body, 2, body.size - 2, Charsets.UTF_16LE)
            bytesAreText = false
        } else if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) {
            text = String(body, 2, body.size - 2, Charsets.UTF_16BE)
            bytesAreText = false
        } else {
            if (body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()) {
                bytes = body.copyOfRange(3, body.size)
            }

            if (charset != null && (charset.startsWith("utf-16") || charset == "unicode")) {
                text = String(bytes, if (charset.endsWith("be")) Charsets.UTF_16BE else Charsets.UTF_16LE)
                bytesAreText = false
            } else if (charset == null || charset == "utf-8" || charset == "utf8") {
                text = strictUtf8(bytes)
                    ?: return Res(bytes, "raw (body is not valid UTF-8, untouched)", bytes.size != body.size)
                utf8Text = true
            } else {
                text = String(bytes, LATIN1)
            }
        }

        if (hasControlChars(text)) {
            if (!bytesAreText) {
                return Res(stringToBytes(text), "UTF-16 text holding control bytes", true)
            }
            if (utf8Text && isLatin1BinaryString(text)) {
                return Res(text.toByteArray(LATIN1), "raw (text frame carried a binary string; chars restored to single bytes)", true)
            }
            return Res(bytes, "raw (body holds ESC/POS control bytes, untouched)", bytes.size != body.size)
        }

        val form = ct.mediaType == "application/x-www-form-urlencoded"
        return decodeText(text, if (bytesAreText) bytes else null, depth, form, explicitBase64 = false, strict = false)
    }

    private fun parseContentType(contentType: String?): ContentType {
        if (contentType.isNullOrBlank()) return ContentType("", null, null)
        val parts = contentType.split(';')
        val mediaType = parts[0].trim().lowercase()
        var charset: String? = null
        var boundary: String? = null
        for (i in 1 until parts.size) {
            val eq = parts[i].indexOf('=')
            if (eq <= 0) continue
            val name = parts[i].substring(0, eq).trim().lowercase()
            val value = parts[i].substring(eq + 1).trim().trim('"')
            if (name == "charset") charset = value.lowercase() else if (name == "boundary") boundary = value
        }
        return ContentType(mediaType, charset, boundary)
    }

    private fun isBinaryMediaType(m: String): Boolean =
        m == "application/octet-stream" || m == "application/x-binary" || m == "application/binary" ||
            m == "application/x-escpos" || m == "application/escpos" || m == "application/vnd.escpos"

    private fun looksLikeEnvelope(body: ByteArray): Boolean {
        val head = String(body, 0, minOf(body.size, 256), LATIN1).trimStart()
        return ENVELOPE_RX.containsMatchIn(head) || DATA_URI_RX.containsMatchIn(head)
    }

    /** True when the text holds control characters other than TAB/CR/LF (i.e. ESC/POS binary, not text). */
    private fun hasControlChars(text: String): Boolean =
        text.any { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }

    /** A JS `String.fromCharCode` "binary string" sent as UTF-8: has ESC/GS and chars >= 0x80, all <= U+00FF. */
    private fun isLatin1BinaryString(text: String): Boolean {
        var high = false
        var command = false
        for (c in text) {
            val v = c.code
            if (v > 0xFF) return false
            if (v >= 0x80) high = true else if (v == ESC || v == GS) command = true
        }
        return high && command
    }

    private fun stringToBytes(text: String): ByteArray =
        if (isLatin1BinaryString(text)) text.toByteArray(LATIN1) else text.toByteArray(Charsets.UTF_8)

    private fun strictUtf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // ---------------------------------------------------------------------
    // Text level
    // ---------------------------------------------------------------------
    private fun decodeText(
        text: String,
        fallback: ByteArray?,
        depth: Int,
        formHint: Boolean,
        explicitBase64: Boolean,
        strict: Boolean,
    ): Res {
        val t = text.trim()
        if (t.isEmpty()) return Res(ByteArray(0), "empty (whitespace only)", false)
        if (depth > MAX_DEPTH) return plain(text, fallback, null)

        tryEnvelope(t, depth)?.let { return it }

        val looksLikeJson = t[0] == '{' || t[0] == '[' || t[0] == '"'
        if (looksLikeJson) {
            val json = MiniJson.parse(t)
            if (json != null) return extractJson(json.value, depth, "json", false)
        }

        if (!strict && (formHint || startsWithDataKey(t))) {
            tryForm(t, depth)?.let { return it }
        }

        tryHex(t)?.let { return Res(it, "hex text", true) }

        val b64 = tryBase64(t, explicitBase64, strict)
        if (b64 != null) return finishBase64(b64.first, "base64 (${b64.second})", depth)

        if (ESCAPED_COMMAND_RX.containsMatchIn(t)) {
            return Res(unescapeText(text), "escaped text (\\x1b-style sequences converted)", true)
        }

        return plain(
            text, fallback,
            if (t[0] == '{' || t[0] == '[') "text as-is (starts like JSON but is not valid JSON)" else null,
        )
    }

    private fun plain(text: String, fallback: ByteArray?, note: String?): Res {
        if (fallback != null) return Res(fallback, note ?: "text as-is (no wrapper detected)", false)
        val restored = isLatin1BinaryString(text)
        return Res(
            if (restored) text.toByteArray(LATIN1) else text.toByteArray(Charsets.UTF_8),
            (note ?: "text as-is (no wrapper detected)") + (if (restored) ", binary string restored to single bytes" else ""),
            false,
        )
    }

    private fun fail(message: String) = Res(ByteArray(0), message, false, message)

    private fun prefix(trail: String, r: Res) = Res(r.bytes, "$trail -> ${r.path}", true, r.error)

    /** RawBT / Android intent URLs and data URIs. */
    private fun tryEnvelope(t: String, depth: Int): Res? {
        val m = ENVELOPE_RX.find(t)
        if (m != null) {
            val label = m.value.trimEnd(':').lowercase()
            var rest = t.substring(m.value.length)
            val tail = rest.indexOf("#Intent;", ignoreCase = true)
            if (tail >= 0) rest = rest.substring(0, tail)
            if (rest.startsWith("//")) rest = rest.substring(2)

            if (rest.startsWith("base64,", ignoreCase = true)) {
                val b64 = tryBase64(rest.substring(7), true, false)
                    ?: return fail("$label:base64 envelope does not contain valid Base64")
                return finishBase64(b64.first, "$label:base64 envelope (${b64.second})", depth)
            }
            return Res(percentDecode(rest, false), "$label: envelope with percent-encoded text", true)
        }

        if (DATA_URI_RX.containsMatchIn(t)) {
            val comma = t.indexOf(',')
            val header = t.substring(5, comma)
            val data = t.substring(comma + 1)
            if (header.contains(";base64", ignoreCase = true)) {
                val b64 = tryBase64(data, true, false) ?: return fail("data-URI does not contain valid Base64")
                return finishBase64(b64.first, "data-URI base64 (${b64.second})", depth)
            }
            return Res(percentDecode(data, false), "data-URI with percent-encoded text", true)
        }
        return null
    }

    // ---------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------
    private fun extractJson(node: Any?, depth: Int, trail: String, explicitBase64: Boolean): Res {
        if (depth > MAX_DEPTH) return fail("JSON wrapper is nested too deeply")

        when (node) {
            is String -> return prefix(trail, decodeText(node, null, depth + 1, false, explicitBase64, false))

            is List<*> -> {
                val numbers = tryByteArray(node)
                return if (numbers != null) Res(numbers, "$trail byte array", true)
                else fail("$trail is an array that is not a list of byte values (0-255 or -128..127)")
            }

            is Map<*, *> -> {
                var lastFailure: Res? = null
                for (key in DATA_KEYS) {
                    for ((k, v) in node) {
                        if (v == null || normalizeKey(k as String) != key) continue
                        val r = extractJson(v, depth + 1, "$trail \"$k\"", key == "base64" || key == "b64")
                        if (r.error == null) return r
                        lastFailure = r
                    }
                }
                if (lastFailure != null) return lastFailure
                if (node.size == 1) {
                    val only = node.entries.first()
                    return extractJson(only.value, depth + 1, "$trail \"${only.key}\"", false)
                }
                return fail(
                    "JSON object has no recognised payload field (keys: ${node.keys.take(12).joinToString(", ")}). " +
                        "Expected one of: data, payload, bytes, base64, escpos, content, commands, text ...",
                )
            }

            else -> return fail("$trail holds a number/boolean/null instead of print data")
        }
    }

    private fun tryByteArray(list: List<*>): ByteArray? {
        val bytes = ByteArray(list.size)
        for (i in list.indices) {
            val d = list[i] as? Double ?: return null
            if (d != Math.floor(d) || d < -128 || d > 255) return null
            bytes[i] = (if (d < 0) d + 256 else d).toInt().toByte() // signed Java/Kotlin bytes -> unsigned
        }
        return bytes
    }

    // ---------------------------------------------------------------------
    // Form / multipart
    // ---------------------------------------------------------------------
    private fun startsWithDataKey(t: String): Boolean {
        val eq = t.indexOf('=')
        if (eq <= 0 || eq > 24) return false
        for (i in 0 until eq) {
            val c = t[i]
            val alnum = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
            if (!alnum && c != '_' && c != '-') return false
        }
        return normalizeKey(t.substring(0, eq)) in DATA_KEYS
    }

    private fun tryForm(t: String, depth: Int): Res? {
        val fields = HashMap<String, String>()
        for (pair in t.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = normalizeKey(String(percentDecode(pair.substring(0, eq), true), LATIN1))
            if (!fields.containsKey(key)) fields[key] = pair.substring(eq + 1)
        }

        for (key in DATA_KEYS) {
            val raw = fields[key] ?: continue
            // Keep '+' literal first: an unescaped Base64 '+' must not become a space.
            val v1 = percentDecode(raw, false)
            var r = decodeText(bytesToText(v1), v1, depth + 1, false, key == "base64" || key == "b64", false)
            if (!r.transformed && r.error == null && raw.indexOf('+') >= 0) {
                val v2 = percentDecode(raw, true)
                r = decodeText(bytesToText(v2), v2, depth + 1, false, false, false)
            }
            return prefix("form field \"$key\"", r)
        }
        return null
    }

    private fun tryMultipart(body: ByteArray, boundary: String, depth: Int): Res? {
        val delimiter = "--$boundary".toByteArray(LATIN1)
        val partEnd = "\r\n--$boundary".toByteArray(LATIN1)
        val headerEnd = byteArrayOf(13, 10, 13, 10)

        val parts = ArrayList<Part>()
        var pos = indexOf(body, delimiter, 0)
        while (pos >= 0) {
            pos += delimiter.size
            if (pos + 1 < body.size && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break
            if (pos + 1 < body.size && body[pos] == 13.toByte() && body[pos + 1] == 10.toByte()) pos += 2

            val headersEnd = indexOf(body, headerEnd, pos)
            if (headersEnd < 0) break
            val headers = String(body, pos, headersEnd - pos, LATIN1)
            val contentStart = headersEnd + 4
            val next = indexOf(body, partEnd, contentStart)
            if (next < 0) break

            parts.add(
                Part(
                    PART_NAME_RX.find(headers)?.groupValues?.get(1) ?: "",
                    PART_FILE_RX.find(headers)?.groupValues?.get(1),
                    PART_TYPE_RX.find(headers)?.groupValues?.get(1)?.trim(),
                    body.copyOfRange(contentStart, next),
                ),
            )
            pos = next + 2
        }

        if (parts.isEmpty()) return null

        var chosen = -1
        for (key in DATA_KEYS) {
            chosen = parts.indexOfFirst { normalizeKey(it.name) == key }
            if (chosen >= 0) break
        }
        if (chosen < 0) chosen = parts.indexOfFirst { it.fileName != null }
        if (chosen < 0 && parts.size == 1) chosen = 0
        if (chosen < 0) {
            return fail(
                "multipart body has no recognised data field or file (fields: " +
                    parts.take(12).joinToString(", ") { it.name } + ")",
            )
        }

        val part = parts[chosen]
        return prefix("multipart part \"${part.name}\"", decodeBody(part.content, part.type, depth + 1))
    }

    // ---------------------------------------------------------------------
    // Hex
    // ---------------------------------------------------------------------
    /** "1b 40 41" / "1B4041" / "0x1B,0x40" / "\x1b\x40". Accepted only when the first byte is ESC or GS. */
    private fun tryHex(t: String): ByteArray? {
        val digits = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (isHexDigit(c)) {
                if (c == '0' && i + 1 < t.length && (t[i + 1] == 'x' || t[i + 1] == 'X')) {
                    i += 2 // 0x prefix
                    continue
                }
                digits.append(c)
            } else if (c == '\\' && i + 1 < t.length && t[i + 1] == 'x') {
                i++ // \x prefix; the 'x' is skipped by the increment below
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',' || c == ':' || c == ';') {
                // separator
            } else {
                return null
            }
            i++
        }

        if (digits.length < 6 || digits.length % 2 != 0) return null
        val result = ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val first = result[0].toInt() and 0xFF
        return if (first == ESC || first == GS) result else null
    }

    // ---------------------------------------------------------------------
    // Base64
    // ---------------------------------------------------------------------
    /**
     * Tolerant Base64: standard or URL-safe alphabet, wrapped lines, missing padding, percent-encoded `+ / =`.
     * Unless [explicitBase64], plain-looking strings are only accepted when the decoded bytes look like ESC/POS
     * or ASCII text (or the string is long and clean). Returns decoded bytes and a variant description.
     */
    private fun tryBase64(text: String, explicitBase64: Boolean, strict: Boolean): Pair<ByteArray, String>? {
        var s = text.trim()
        if (s.length < (if (explicitBase64) 2 else 4)) return null

        var percent = false
        if (s.indexOf('%') >= 0) {
            s = String(percentDecode(s, false), LATIN1)
            percent = true
        }

        val tokens = s.split(WHITESPACE_RX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        var standardChars = false
        var urlChars = false
        for (token in tokens) {
            for (c in token) {
                if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '=') continue
                if (c == '+' || c == '/') standardChars = true
                else if (c == '-' || c == '_') urlChars = true
                else return null
            }
        }
        if (standardChars && urlChars) return null

        val wrapped = tokens.size > 1
        if (wrapped && !explicitBase64) {
            // Wrapped Base64 (MIME/PEM): equal-length lines (multiple of 4, >= 16), shorter last line.
            val lineLength = tokens[0].length
            if (lineLength < 16 || lineLength % 4 != 0) return null
            for (i in 0 until tokens.size - 1) if (tokens[i].length != lineLength) return null
            if (tokens.last().length > lineLength) return null
        }

        val joined = tokens.joinToString("")
        val core = joined.trimEnd('=')
        if (core.isEmpty() || core.indexOf('=') >= 0 || joined.length - core.length > 2) return null

        val remainder = core.length % 4
        if (remainder == 1) return null

        val normalized = if (urlChars) core.replace('-', '+').replace('_', '/') else core
        val padded = normalized + "=".repeat((4 - remainder) % 4)
        val paddingFixed = padded.length != joined.length

        val decoded = try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (decoded.isEmpty()) return null

        if (!explicitBase64) {
            val accept = if (strict) {
                startsWithCommand(decoded)
            } else {
                val clean = !wrapped && !urlChars && !paddingFixed && !percent
                startsWithCommand(decoded) || wrapped ||
                    (clean && (joined.length >= 24 || (joined.length >= 8 && isPlainAscii(decoded))))
            }
            if (!accept) return null
        }

        val notes = ArrayList<String>()
        if (wrapped) notes.add("wrapped lines")
        if (urlChars) notes.add("URL-safe")
        if (paddingFixed) notes.add("padding restored")
        if (percent) notes.add("percent-decoded")
        return decoded to (if (notes.isEmpty()) "standard" else notes.joinToString(", "))
    }

    /** Base64 decoded; if the result is itself an encoded payload (double Base64, Base64 JSON...) unwrap once more. */
    private fun finishBase64(bytes: ByteArray, label: String, depth: Int): Res {
        if (depth < MAX_DEPTH && bytes.size >= 4 && isPlainAscii(bytes)) {
            val nested = decodeText(String(bytes, LATIN1), null, depth + 1, false, explicitBase64 = false, strict = true)
            if (nested.transformed && nested.error == null && nested.bytes.isNotEmpty()) {
                return Res(nested.bytes, "$label -> ${nested.path}", true)
            }
        }
        return Res(bytes, label, true)
    }

    private fun startsWithCommand(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        val v = b[0].toInt() and 0xFF
        return v == ESC || v == GS || v == FS || v == DLE
    }

    private fun isPlainAscii(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (!((v in 0x20..0x7E) || v == 9 || v == 10 || v == 13)) return false
        }
        return true
    }

    // ---------------------------------------------------------------------
    // Escapes / helpers
    // ---------------------------------------------------------------------
    /** Convert literal `\x1b \u001b \033 \e \n \r \t \\` sequences to bytes. */
    private fun unescapeText(s: String): ByteArray {
        val ms = ByteArrayOutputStream(s.length)
        val run = StringBuilder()

        fun flushRun() {
            if (run.isNotEmpty()) {
                ms.write(run.toString().toByteArray(Charsets.UTF_8))
                run.setLength(0)
            }
        }

        fun emit(value: Int) {
            flushRun()
            ms.write(value)
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                run.append(c)
                i++
                continue
            }

            val n = s[i + 1]
            if ((n == 'x' || n == 'X') && isHex(s, i + 2, 2)) {
                emit(s.substring(i + 2, i + 4).toInt(16))
                i += 4
            } else if (n == 'u' && isHex(s, i + 2, 4)) {
                val code = s.substring(i + 2, i + 6).toInt(16)
                if (code <= 0xFF) emit(code) else run.append(code.toChar())
                i += 6
            } else if (n in '0'..'7') {
                var j = i + 1
                var value = 0
                var count = 0
                while (j < s.length && count < 3 && s[j] in '0'..'7') {
                    value = value * 8 + (s[j] - '0')
                    j++
                    count++
                }
                emit(value and 0xFF)
                i = j
            } else {
                val simple = when (n) {
                    'n' -> 0x0A
                    'r' -> 0x0D
                    't' -> 0x09
                    'a' -> 0x07
                    'b' -> 0x08
                    'f' -> 0x0C
                    'v' -> 0x0B
                    'e' -> ESC
                    else -> -1
                }
                if (simple >= 0) {
                    emit(simple)
                    i += 2
                } else if (n == '\\') {
                    run.append('\\')
                    i += 2
                } else {
                    run.append(c)
                    i++
                }
            }
        }

        flushRun()
        return ms.toByteArray()
    }

    private fun isHexDigit(c: Char) = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')

    private fun isHex(s: String, start: Int, count: Int): Boolean {
        if (start + count > s.length) return false
        for (i in start until start + count) if (!isHexDigit(s[i])) return false
        return true
    }

    /** Byte-exact percent decoding (%XX -> byte). `+` becomes a space only when asked (form encoding). */
    private fun percentDecode(s: String, plusIsSpace: Boolean): ByteArray {
        val ms = ByteArrayOutputStream(s.length)
        val run = StringBuilder()

        fun flushRun() {
            if (run.isNotEmpty()) {
                ms.write(run.toString().toByteArray(Charsets.UTF_8))
                run.setLength(0)
            }
        }

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && isHex(s, i + 1, 2)) {
                flushRun()
                ms.write(s.substring(i + 1, i + 3).toInt(16))
                i += 2
            } else if (c == '+' && plusIsSpace) {
                flushRun()
                ms.write(0x20)
            } else {
                run.append(c)
            }
            i++
        }

        flushRun()
        return ms.toByteArray()
    }

    private fun bytesToText(bytes: ByteArray): String = strictUtf8(bytes) ?: String(bytes, LATIN1)

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace("_", "").replace("-", "").replace(" ", "")

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        var i = start
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}

/** Minimal JSON reader (no Android / org.json dependency). Numbers are Double; objects keep insertion order. */
private object MiniJson {
    private const val MAX_NESTING = 64

    class Value(val value: Any?)

    private class Failure : Exception()

    /** Returns null when [text] is not a single valid JSON value. */
    fun parse(text: String): Value? = try {
        val p = Parser(text)
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) null else Value(v)
    } catch (e: Failure) {
        null
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\r' || s[pos] == '\n')) pos++
        }

        fun value(nesting: Int): Any? {
            if (nesting > MAX_NESTING) throw Failure()
            skipWs()
            if (pos >= s.length) throw Failure()
            return when (s[pos]) {
                '{' -> obj(nesting)
                '[' -> arr(nesting)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> num()
            }
        }

        fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, pos)) throw Failure()
            pos += word.length
            return result
        }

        fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw Failure()
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw Failure()
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw Failure()
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw Failure()
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw Failure())
                                pos += 4
                            }
                            else -> throw Failure()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(nesting: Int): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(value(nesting + 1))
                skipWs()
                if (pos >= s.length) throw Failure()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw Failure()
                }
            }
        }

        fun obj(nesting: Int): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw Failure()
                val key = str()
                skipWs()
                if (pos >= s.length || s[pos++] != ':') throw Failure()
                map[key] = value(nesting + 1)
                skipWs()
                if (pos >= s.length) throw Failure()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw Failure()
                }
            }
        }
    }
}
