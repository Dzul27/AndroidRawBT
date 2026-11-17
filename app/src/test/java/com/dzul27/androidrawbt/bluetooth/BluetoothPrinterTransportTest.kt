package com.dzul27.androidrawbt.bluetooth

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothPrinterTransportTest {
    private class Recording : OutputStream() {
        val sink = ByteArrayOutputStream()
        val writeSizes = mutableListOf<Int>()
        var flushes = 0

        override fun write(b: Int) = throw UnsupportedOperationException()

        override fun write(b: ByteArray, off: Int, len: Int) {
            writeSizes += len
            sink.write(b, off, len)
        }

        override fun flush() {
            flushes++
        }
    }

    @Test
    fun largePayloadIsChunkedFlushedPacedAndUnmodified() = runTest {
        val payload = ByteArray(WRITE_CHUNK_BYTES * 3 + 7) { (it * 31).toByte() }
        val out = Recording()
        writePaced(out, payload)
        assertArrayEquals(payload, out.sink.toByteArray())
        assertEquals(listOf(WRITE_CHUNK_BYTES, WRITE_CHUNK_BYTES, WRITE_CHUNK_BYTES, 7), out.writeSizes)
        assertEquals(4, out.flushes)
        assertEquals(3 * WRITE_PAUSE_MS, currentTime)
    }

    @Test
    fun smallPayloadIsSingleWriteWithoutPause() = runTest {
        val out = Recording()
        writePaced(out, byteArrayOf(1, 2, 3))
        assertEquals(listOf(3), out.writeSizes)
        assertEquals(0L, currentTime)
    }

    @Test
    fun drainIsScaledWithSizeAndBounded() {
        assertEquals(DRAIN_MIN_MS, drainMillis(0))
        assertEquals(DRAIN_MIN_MS, drainMillis(190)) // short test receipt
        assertEquals(1_500L + 900 / 20 * 90, drainMillis(900)) // typical POS receipt
        assertTrue(drainMillis(1_200) > drainMillis(900))
        assertEquals(DRAIN_MAX_MS, drainMillis(10_000_000))
    }
}
