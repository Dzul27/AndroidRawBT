package com.dzul27.androidrawbt.printing

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintQueueTest {
    @Test
    fun jobsAreSerializedInOrderAndFailuresMapped() = runTest {
        val events = mutableListOf<String>()
        PrintQueue.setMacProvider { "00:11:22:33:44:55" }
        PrintQueue.transport = object : PrinterTransport {
            override suspend fun send(mac: String, bytes: ByteArray) {
                events += "start ${bytes[0]}"
                delay(10)
                if (bytes[0] == 3.toByte()) throw java.io.IOException("boom")
                events += "end ${bytes[0]}"
            }
        }
        val a = async { PrintQueue.submit(byteArrayOf(1), "t") }
        val b = async { PrintQueue.submit(byteArrayOf(2), "t") }
        val c = async { PrintQueue.submit(byteArrayOf(3), "t") }
        assertEquals(PrintResult.Ok(1), a.await())
        assertEquals(PrintResult.Ok(1), b.await())
        assertEquals(PrintResult.Failure("boom"), c.await())
        assertEquals(listOf("start 1", "end 1", "start 2", "end 2", "start 3"), events)
        assertTrue(PrintQueue.submit(ByteArray(0), "t") is PrintResult.Failure)
    }

    @Test
    fun failsWithoutPrinter() = runTest {
        PrintQueue.setMacProvider { null }
        assertEquals(PrintResult.Failure("No printer selected"), PrintQueue.submit(byteArrayOf(1), "t"))
    }
}
