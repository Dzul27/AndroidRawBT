package com.dzul27.androidrawbt.bluetooth

import java.io.ByteArrayOutputStream

/** Builds the ASCII-only ESC/POS diagnostic receipt (same content as WinRawBT's test receipt). */
object TestReceipt {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    /** [paperWidthMm] 80 uses 48 columns; anything else uses the 58 mm width of 32 columns. */
    fun build(mac: String, paperWidthMm: Int, timestamp: String): ByteArray {
        val out = ByteArrayOutputStream()
        val rule = "-".repeat(if (paperWidthMm == 80) 48 else 32)

        // ESC @ initialize, ESC a 1 center, ESC E 1 bold on
        out.bytes(ESC, '@'.code)
        out.bytes(ESC, 'a'.code, 1)
        out.bytes(ESC, 'E'.code, 1)
        out.line("AndroidRawBT")
        out.line("Bluetooth Print Bridge")
        // ESC E 0 bold off
        out.bytes(ESC, 'E'.code, 0)
        out.line(rule)
        out.line("TEST CONNECTION")
        out.line("STATUS: CONNECTED")
        out.line(rule)
        // ESC a 0 left align
        out.bytes(ESC, 'a'.code, 0)
        out.line("MAC  : ${ascii(mac)}")
        out.line("Time : ${ascii(timestamp)}")
        out.bytes(LF, LF, LF)
        // GS V 66 0: feed and partial cut
        out.bytes(GS, 'V'.code, 66, 0)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.bytes(vararg values: Int) {
        for (v in values) write(v)
    }

    private fun ByteArrayOutputStream.line(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
        write(LF)
    }

    /** Replaces anything outside printable ASCII so the receipt stays pure ASCII. */
    private fun ascii(value: String): String =
        value.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("")
}
