package com.dzul27.androidrawbt.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PayloadDecoderTest {
    private val escpos = byteArrayOf(0x1B, 0x40, 'H'.code.toByte(), 'i'.code.toByte(), 0x0A, 0x1D, 0x56, 0x42, 0x00)
    private val b64 = Base64.getEncoder().encodeToString(escpos)

    private fun decode(body: String, ct: String? = "text/plain") = PayloadDecoder.decode(body.toByteArray(), ct)

    private fun assertFails(body: ByteArray, ct: String?) {
        try {
            PayloadDecoder.decode(body, ct)
            fail("expected PayloadDecodeException")
        } catch (e: PayloadDecodeException) {
            // expected
        }
    }

    @Test
    fun emptyBodyThrows() {
        assertFails(ByteArray(0), "text/plain")
        assertFails("   \r\n".toByteArray(), "text/plain")
    }

    @Test
    fun octetStreamPassesThroughUntouched() {
        val bin = byteArrayOf(0x1B, 0x40, 0x80.toByte(), 0xFF.toByte(), 0x00, 0x1D, 0x56, 0x00)
        val r = PayloadDecoder.decode(bin, "application/octet-stream")
        assertArrayEquals(bin, r.bytes)
        assertTrue(r.path.startsWith("raw"))
    }

    @Test
    fun octetStreamThatLooksLikeBase64IsNotDecoded() {
        val r = decode(b64, "application/octet-stream")
        assertArrayEquals(b64.toByteArray(), r.bytes)
    }

    @Test
    fun binaryWithControlBytesPassesThroughUnderTextPlain() {
        val r = PayloadDecoder.decode(escpos, "text/plain")
        assertArrayEquals(escpos, r.bytes)
        val noCt = PayloadDecoder.decode(escpos, null)
        assertArrayEquals(escpos, noCt.bytes)
    }

    @Test
    fun invalidUtf8BinaryPassesThrough() {
        val bin = byteArrayOf(0x41, 0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte(), 0x00)
        val r = PayloadDecoder.decode(bin, "text/plain")
        assertArrayEquals(bin, r.bytes)
    }

    @Test
    fun plainTextIsSentAsIs() {
        val r = decode("Hello world\n")
        assertArrayEquals("Hello world\n".toByteArray(), r.bytes)
        assertTrue(r.path.contains("as-is"))
    }

    @Test
    fun shortPlainWordIsNotMisreadAsBase64() {
        assertArrayEquals("Test".toByteArray(), decode("Test").bytes)
        assertArrayEquals("Receipt".toByteArray(), decode("Receipt").bytes)
    }

    @Test
    fun utf8BomIsDropped() {
        val body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hello".toByteArray()
        assertArrayEquals("Hello".toByteArray(), PayloadDecoder.decode(body, "text/plain").bytes)
    }

    @Test
    fun utf16WithBomIsDecoded() {
        val body = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hi there".toByteArray(Charsets.UTF_16LE)
        assertArrayEquals("Hi there".toByteArray(), PayloadDecoder.decode(body, "text/plain").bytes)
    }

    @Test
    fun standardBase64() {
        val r = decode(b64)
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("base64"))
    }

    @Test
    fun base64WithSurroundingWhitespace() {
        assertArrayEquals(escpos, decode("  $b64\r\n").bytes)
    }

    @Test
    fun urlSafeBase64() {
        val bin = byteArrayOf(0x1B, 0x40, 0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x3E, 0x3F, 0x41)
        val urlSafe = Base64.getUrlEncoder().encodeToString(bin)
        assertTrue(urlSafe.contains('-') || urlSafe.contains('_'))
        val r = decode(urlSafe)
        assertArrayEquals(bin, r.bytes)
        assertTrue(r.path.contains("URL-safe"))
    }

    @Test
    fun unpaddedBase64() {
        val data = byteArrayOf(0x1B, 0x40, 0x41, 0x42)
        val unpadded = Base64.getEncoder().withoutPadding().encodeToString(data)
        assertTrue(unpadded.length % 4 != 0)
        val r = decode(unpadded)
        assertArrayEquals(data, r.bytes)
        assertTrue(r.path.contains("padding restored"))
    }

    @Test
    fun wrappedBase64() {
        val data = ByteArray(120) { (it + 1).toByte() }
        data[0] = 0x1B
        val wrapped = Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(data)
        assertTrue(wrapped.contains("\r\n"))
        val r = decode(wrapped)
        assertArrayEquals(data, r.bytes)
        assertTrue(r.path.contains("wrapped lines"))
    }

    @Test
    fun doubleEncodedBase64() {
        val outer = Base64.getEncoder().encodeToString(b64.toByteArray())
        assertArrayEquals(escpos, decode(outer).bytes)
    }

    @Test
    fun invalidBase64IsTreatedAsText() {
        val r = decode("not base64 at all!!")
        assertArrayEquals("not base64 at all!!".toByteArray(), r.bytes)
    }

    @Test
    fun hexText() {
        val expected = byteArrayOf(0x1B, 0x40, 0x41, 0x42)
        assertArrayEquals(expected, decode("1b 40 41 42").bytes)
        assertArrayEquals(expected, decode("1B404142").bytes)
        assertArrayEquals(expected, decode("0x1B,0x40,0x41,0x42").bytes)
        assertArrayEquals(expected, decode("\\x1b\\x40\\x41\\x42").bytes)
        assertEquals("hex text", decode("1b 40 41 42").path)
    }

    @Test
    fun hexNotStartingWithCommandIsNotHex() {
        assertArrayEquals("414243".toByteArray(), decode("414243").bytes)
    }

    @Test
    fun dataUriBase64() {
        val r = decode("data:application/octet-stream;base64,$b64")
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("data-URI base64"))
    }

    @Test
    fun dataUriPercentEncoded() {
        val r = decode("data:text/plain,Hello%20World%0A")
        assertArrayEquals("Hello World\n".toByteArray(), r.bytes)
    }

    @Test
    fun dataUriWithInvalidBase64Fails() {
        assertFails("data:text/plain;base64,@@@@".toByteArray(), "text/plain")
    }

    @Test
    fun dataUriUnderOctetStreamIsStillDecoded() {
        val r = decode("data:;base64,$b64", "application/octet-stream")
        assertArrayEquals(escpos, r.bytes)
    }

    @Test
    fun rawbtBase64Scheme() {
        val r = decode("rawbt:base64,$b64")
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("rawbt:base64 envelope"))
    }

    @Test
    fun rawbtSchemeWithSlashesAndUppercase() {
        assertArrayEquals(escpos, decode("RAWBT://base64,$b64").bytes)
    }

    @Test
    fun intentBase64WithIntentTail() {
        val r = decode("intent:base64,$b64#Intent;scheme=rawbt;package=ru.a402d.rawbtprinter;end;")
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("intent:base64 envelope"))
    }

    @Test
    fun rawbtPlainTextEnvelope() {
        val r = decode("rawbt:Hello%20There")
        assertArrayEquals("Hello There".toByteArray(), r.bytes)
    }

    @Test
    fun envelopeWithInvalidBase64Fails() {
        assertFails("rawbt:base64,!!!!".toByteArray(), "text/plain")
    }

    @Test
    fun jsonDataString() {
        val r = decode("{\"data\":\"$b64\"}", "application/json")
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("json \"data\""))
    }

    @Test
    fun jsonBase64KeyIsExplicitSoShortValuesDecode() {
        val r = decode("{\"base64\":\"SGk=\"}", "application/json")
        assertArrayEquals("Hi".toByteArray(), r.bytes)
    }

    @Test
    fun jsonByteArrayUnsignedAndSigned() {
        assertArrayEquals(escpos, decode("{\"data\":[27,64,72,105,10,29,86,66,0]}", "application/json").bytes)
        val signed = decode("[27,64,-1,-128,127]", "application/json")
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0xFF.toByte(), 0x80.toByte(), 0x7F), signed.bytes)
    }

    @Test
    fun jsonBufferStyleNestedObject() {
        val r = decode("{\"data\":{\"bytes\":[27,64,65]}}", "application/json")
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0x41), r.bytes)
    }

    @Test
    fun jsonKeyNamesAreNormalised() {
        val r = decode("{\"Raw_Data\":\"$b64\"}", "application/json")
        assertArrayEquals(escpos, r.bytes)
    }

    @Test
    fun jsonSingleUnknownKeyIsUsed() {
        val r = decode("{\"weird\":\"$b64\"}", "application/json")
        assertArrayEquals(escpos, r.bytes)
    }

    @Test
    fun jsonUnknownKeysFail() {
        assertFails("{\"a\":\"x\",\"b\":\"y\"}".toByteArray(), "application/json")
    }

    @Test
    fun jsonBadArrayFails() {
        assertFails("{\"data\":[1,2,300]}".toByteArray(), "application/json")
        assertFails("{\"data\":[1,\"a\"]}".toByteArray(), "application/json")
    }

    @Test
    fun jsonStringEscapesAndUnicode() {
        val r = decode("{\"text\":\"Caf\\u00e9\\nOK\"}", "application/json")
        assertArrayEquals("Caf\u00e9\nOK".toByteArray(), r.bytes)
    }

    @Test
    fun jsonWithBase64NestedInsideDataUri() {
        val r = decode("{\"data\":\"data:;base64,$b64\"}", "application/json")
        assertArrayEquals(escpos, r.bytes)
    }

    @Test
    fun invalidJsonIsPlainText() {
        val r = decode("{not json}", "application/json")
        assertArrayEquals("{not json}".toByteArray(), r.bytes)
        assertTrue(r.path.contains("not valid JSON"))
    }

    @Test
    fun formUrlEncodedWithPercentEncodedBase64() {
        val encoded = java.net.URLEncoder.encode(b64, "UTF-8")
        val r = decode("data=$encoded", "application/x-www-form-urlencoded")
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("form field \"data\""))
    }

    @Test
    fun formWithLiteralPlusInBase64() {
        val bin = byteArrayOf(0x1B, 0x40, 0x3E, 0x41, 0x42, 0x43)
        val std = Base64.getEncoder().encodeToString(bin)
        assertTrue(std.contains('+'))
        assertArrayEquals(bin, decode("data=$std", "application/x-www-form-urlencoded").bytes)
    }

    @Test
    fun formWithPlainTextUsesPlusAsSpace() {
        val r = decode("text=Hello+World%21", "application/x-www-form-urlencoded")
        assertArrayEquals("Hello World!".toByteArray(), r.bytes)
    }

    @Test
    fun formWithoutKnownKeyIsPlainText() {
        val r = decode("foo=bar", "application/x-www-form-urlencoded")
        assertArrayEquals("foo=bar".toByteArray(), r.bytes)
    }

    @Test
    fun formKeyDetectedEvenWithoutContentType() {
        assertArrayEquals(escpos, decode("base64=$b64", null).bytes)
    }

    private fun multipart(boundary: String, vararg parts: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((header, content) in parts) {
            out.write("--$boundary\r\n$header\r\n\r\n".toByteArray())
            out.write(content)
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun multipartDataFieldBase64() {
        val body = multipart("XyZ", "Content-Disposition: form-data; name=\"data\"" to b64.toByteArray())
        val r = PayloadDecoder.decode(body, "multipart/form-data; boundary=XyZ")
        assertArrayEquals(escpos, r.bytes)
        assertTrue(r.path.startsWith("multipart part \"data\""))
    }

    @Test
    fun multipartBinaryFilePartIsUntouched() {
        val bin = byteArrayOf(0x1B, 0x40, 0x80.toByte(), 0xFF.toByte(), 0x00, 0x0D, 0x0A, 0x1D, 0x56, 0x00)
        val body = multipart(
            "b0und",
            "Content-Disposition: form-data; name=\"file\"; filename=\"r.bin\"\r\nContent-Type: application/octet-stream" to bin,
        )
        val r = PayloadDecoder.decode(body, "multipart/form-data; boundary=\"b0und\"")
        assertArrayEquals(bin, r.bytes)
    }

    @Test
    fun multipartPrefersDataKeyOverOtherParts() {
        val body = multipart(
            "B",
            "Content-Disposition: form-data; name=\"other\"" to "zzz".toByteArray(),
            "Content-Disposition: form-data; name=\"payload\"" to b64.toByteArray(),
        )
        assertArrayEquals(escpos, PayloadDecoder.decode(body, "multipart/form-data; boundary=B").bytes)
    }

    @Test
    fun multipartWithoutRecognisedFieldFails() {
        val body = multipart(
            "B",
            "Content-Disposition: form-data; name=\"a\"" to "1".toByteArray(),
            "Content-Disposition: form-data; name=\"b\"" to "2".toByteArray(),
        )
        assertFails(body, "multipart/form-data; boundary=B")
    }

    @Test
    fun escapedTextSequences() {
        val r = decode("\\x1b@Hello\\n\\x1dV\\x00")
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 0x0A, 0x1D, 'V'.code.toByte(), 0x00), r.bytes)
        assertTrue(r.path.startsWith("escaped text"))
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0x41), decode("\\u001b@A").bytes)
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0x41), decode("\\033@A").bytes)
    }

    @Test
    fun latin1BinaryStringSentAsUtf8IsRestored() {
        val s = "\u001b@\u00e9\u0080\u001dV"
        val r = PayloadDecoder.decode(s.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        assertArrayEquals(byteArrayOf(0x1B, 0x40, 0xE9.toByte(), 0x80.toByte(), 0x1D, 0x56), r.bytes)
    }

    @Test
    fun escPosInitBinaryUnchangedByEveryTextCharset() {
        val bin = byteArrayOf(0x1B, 0x40, 'T'.code.toByte(), 0x0A)
        assertArrayEquals(bin, PayloadDecoder.decode(bin, "text/plain; charset=iso-8859-1").bytes)
        assertArrayEquals(bin, PayloadDecoder.decode(bin, "application/json").bytes)
    }

    @Test
    fun hexAsciiPreviewFormatsShortAndLong() {
        assertEquals("1B 40 41 | .@A", hexAsciiPreview(byteArrayOf(0x1B, 0x40, 0x41)))
        val long = ByteArray(40) { it.toByte() }
        val p = hexAsciiPreview(long, 4)
        assertTrue(p.startsWith("00 01 02 03 | ...."))
        assertTrue(p.endsWith("tail: 20 21 22 23 24 25 26 27"))
        assertEquals(" | ", hexAsciiPreview(ByteArray(0)))
    }

    @Test
    fun fsAndGsInsideWrappersAreCommandBytesNotWhitespace() {
        // Kotlin's trim() strips U+001C..U+001F; WinRawBT's (.NET) Trim() does not, so a lone FS/GS must survive.
        assertArrayEquals(byteArrayOf(0x1D), decode("{\"data\":\"\\u001d\"}", "application/json").bytes)
        assertArrayEquals(byteArrayOf(0x1C), decode("payload=%1C", "application/x-www-form-urlencoded").bytes)
    }

    @Test
    fun jsonUnicodeEscapeNeedsFourHexDigits() {
        val body = "{\"data\":\"\\u+01dA\"}"
        val r = decode(body, "application/json")
        assertArrayEquals(body.toByteArray(), r.bytes)
        assertTrue(r.path.contains("not valid JSON"))
    }

    @Test
    fun escPosSummaryReportsCodePageHighBytesCutAndImages() {
        val text = byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x13, 0x43, 0x61, 0x66, 0x82.toByte(), 0x0A, 0x1D, 0x56, 0x42, 0x00)
        assertEquals(
            "ESC/POS scan: starts with ESC @=yes, code page (ESC t n)=19, raster images (GS v 0)=0, " +
                "QR/2D (GS ( k)=0, cut (GS V)=1, drawer (ESC p)=0, line feeds=1, bytes>=0x80 outside images=1",
            PayloadDecoder.summarizeEscPos(text),
        )
        // GS v 0 m xL xH yL yH + 2*2 image bytes: the 0xFF image data must not count as text bytes.
        val raster = byteArrayOf(0x1B, 0x40, 0x1D, 0x76, 0x30, 0x00, 0x02, 0x00, 0x02, 0x00) +
            ByteArray(4) { 0xFF.toByte() } + byteArrayOf(0x0A)
        assertEquals(
            "ESC/POS scan: starts with ESC @=yes, code page (ESC t n)=none set, raster images (GS v 0)=1, " +
                "QR/2D (GS ( k)=0, cut (GS V)=0, drawer (ESC p)=0, line feeds=1, bytes>=0x80 outside images=0",
            PayloadDecoder.summarizeEscPos(raster),
        )
        assertTrue(PayloadDecoder.summarizeEscPos(byteArrayOf(0x41, 0x0A)).startsWith("ESC/POS scan: starts with ESC @=NO"))
    }

    @Test
    fun bodyHeadQuotesPrintableAsciiAndMarksTruncation() {
        val head = PayloadDecoder.formatBodyHead(byteArrayOf('H'.code.toByte(), 'i'.code.toByte(), 0x0A, 0x80.toByte(), 'A'.code.toByte()))
        assertEquals("\"Hi..A\"", head)
        assertEquals("\"abc\"...", PayloadDecoder.formatBodyHead("abcdef".toByteArray(), 3))
    }

    private val realistic = byteArrayOf(
        0x1B, 0x40, 0x1B, 0x74, 0x00, 0x1B, 0x61, 0x00, 'H'.code.toByte(), 'i'.code.toByte(), 0x0A,
        0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x3E, 0x3F, 0x1D, 0x56, 0x42, 0x00,
    )
    private val realisticB64 = Base64.getEncoder().encodeToString(realistic)

    @Test
    fun rawbtEnvelopeAroundDataUriIsDecodedAgain() {
        assertTrue(realisticB64.contains('+') || realisticB64.contains('/'))
        val link = "rawbt:data:application/octet-stream;base64,$realisticB64"
        val r = decode(link)
        assertArrayEquals(realistic, r.bytes)
        assertTrue(r.path, r.path.contains("data-URI base64"))
    }

    @Test
    fun rawbtEnvelopeAroundFullyPercentEncodedDataUri() {
        // %2B / %2F / %3D must come back as + / = in the Base64, never as spaces or leftovers.
        val inner = java.net.URLEncoder.encode("data:application/octet-stream;base64,$realisticB64", "UTF-8")
        assertTrue(inner.contains("%2B") || inner.contains("%2F"))
        assertArrayEquals(realistic, decode("rawbt:$inner").bytes)
        assertArrayEquals(realistic, decode("rawbt://$inner").bytes)
        assertArrayEquals(realistic, decode("intent:$inner#Intent;scheme=rawbt;end;").bytes)
    }

    @Test
    fun rawbtEnvelopeWithLiteralPlusInPartlyEncodedDataUri() {
        val link = "rawbt:data%3Aapplication%2Foctet-stream%3Bbase64%2C$realisticB64"
        assertArrayEquals(realistic, decode(link).bytes)
    }

    @Test
    fun rawbtEnvelopeWithPercentEncodedBase64Prefix() {
        val link = "rawbt:base64%2C" + java.net.URLEncoder.encode(realisticB64, "UTF-8")
        assertArrayEquals(realistic, decode(link).bytes)
    }

    @Test
    fun rawbtBase64EnvelopeStillWorks() {
        assertArrayEquals(realistic, decode("rawbt:base64,$realisticB64").bytes)
    }

    @Test
    fun rawbtEnvelopeWrapsAnyDecodableText() {
        assertArrayEquals(realistic, decode("rawbt:" + java.net.URLEncoder.encode("{\"data\":\"$realisticB64\"}", "UTF-8")).bytes)
        assertArrayEquals(
            realistic.copyOf(8),
            decode("rawbt:" + java.net.URLEncoder.encode("1b 40 1b 74 00 1b 61 00", "UTF-8").replace("+", "%20")).bytes,
        )
        assertArrayEquals("Hello World".toByteArray(), decode("rawbt:Hello%20World").bytes)
    }

    @Test
    fun deeplyNestedEnvelopesTerminate() {
        var link = "rawbt:Hello%20World"
        repeat(12) { link = "rawbt:" + java.net.URLEncoder.encode(link, "UTF-8") }
        val r = decode(link)
        assertTrue(r.bytes.isNotEmpty())
    }
}
