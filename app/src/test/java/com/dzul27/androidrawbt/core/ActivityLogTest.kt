package com.dzul27.androidrawbt.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLogTest {
    @Test
    fun capsAtMaxLinesAndClears() {
        ActivityLog.clear()
        repeat(ActivityLog.MAX_LINES + 20) { ActivityLog.info("line $it") }
        val lines = ActivityLog.lines.value
        assertEquals(ActivityLog.MAX_LINES, lines.size)
        assertEquals("line 20", lines.first().text)
        assertTrue(Regex("\\d\\d:\\d\\d:\\d\\d").matches(lines.first().time))
        ActivityLog.clear()
        assertTrue(ActivityLog.lines.value.isEmpty())
    }
}
