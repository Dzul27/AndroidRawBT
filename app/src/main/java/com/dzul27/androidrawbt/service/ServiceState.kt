package com.dzul27.androidrawbt.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Actual state of the print server as seen by the UI. */
sealed interface ServiceStatus {
    data object Stopped : ServiceStatus

    data class Running(val port: Int) : ServiceStatus

    /** Start failed; [message] is user-visible, [portInUse] is true for a bind conflict. */
    data class Failed(val message: String, val portInUse: Boolean) : ServiceStatus
}

/** Process-wide service status, written only by [ForegroundPrintService]. */
object ServiceState {
    private val _status = MutableStateFlow<ServiceStatus>(ServiceStatus.Stopped)
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Derived from [status]: true only while the server is bound and accepting jobs. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    internal fun update(status: ServiceStatus) {
        _status.value = status
        _isRunning.value = status is ServiceStatus.Running
    }
}
