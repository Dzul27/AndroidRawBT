package com.dzul27.androidrawbt.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestReceiptTest {
    private val mac = "00:11:22:33:44:55"
    private val ts = "2026-11-17 08:01:15"

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun startsWithInitAndEndsWithCut() {
        val b = TestReceipt.build(mac, 58, ts)
        assertEquals(listOf(0x1B, 0x40), b.take(2).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x1D, 0x56, 66, 0), b.takeLast(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun containsHeaderStatusMacAndTimestamp() {
        val s = text(TestReceipt.build(mac, 58, ts))
        assertTrue(s.contains("AndroidRawBT"))
        assertTrue(s.contains("CONNECTED"))
        assertTrue(s.contains(mac))
        assertTrue(s.contains(ts))
    }

    @Test
    fun separatorMatchesPaperWidth() {
        val s58 = text(TestReceipt.build(mac, 58, ts))
        val s80 = text(TestReceipt.build(mac, 80, ts))
        assertTrue(s58.contains("-".repeat(32) + "\n"))
        assertTrue(!s58.contains("-".repeat(33)))
        assertTrue(s80.contains("-".repeat(48) + "\n"))
        assertTrue(!s80.contains("-".repeat(49)))
    }

    @Test
    fun printableTextIsAscii() {
        val b = TestReceipt.build("é:ü", 58, ts)
        // Control bytes are < 0x20; everything else must be 7-bit ASCII.
        assertTrue(b.all { it >= 0 })
        assertTrue(text(b).contains("?:?"))
    }
}
