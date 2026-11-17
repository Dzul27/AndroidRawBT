package com.dzul27.androidrawbt.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Level { INFO, WARN, ERROR }

/** [time] is formatted HH:mm:ss. */
data class LogLine(val time: String, val level: Level, val text: String)

/** Memory-only activity log, newest last, capped at [MAX_LINES]. Thread-safe. */
object ActivityLog {
    const val MAX_LINES = 500

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    fun info(msg: String) = add(Level.INFO, msg)
    fun warn(msg: String) = add(Level.WARN, msg)
    fun error(msg: String) = add(Level.ERROR, msg)

    fun clear() {
        _lines.value = emptyList()
    }

    private fun add(level: Level, msg: String) {
        val line = LogLine(LocalTime.now().format(timeFormat), level, msg)
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}
