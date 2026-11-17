package com.dzul27.androidrawbt.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.bluetooth.BluetoothDevices
import com.dzul27.androidrawbt.bluetooth.PrinterDevice
import com.dzul27.androidrawbt.bluetooth.TestPrint
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.core.LogLine
import com.dzul27.androidrawbt.printing.PrintResult
import com.dzul27.androidrawbt.service.ForegroundPrintService
import com.dzul27.androidrawbt.service.ServiceState
import com.dzul27.androidrawbt.service.ServiceStatus
import com.dzul27.androidrawbt.settings.Settings
import com.dzul27.androidrawbt.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Actions that need Bluetooth runtime permissions first; the screen requests them, then calls [MainViewModel.perform]. */
enum class GatedAction(val discovery: Boolean) {
    Refresh(false),
    Scan(true),
    Test(false),
    Start(false),
}

/** Scan & Pair dialog states. */
sealed interface ScanDialog {
    data class Scanning(val found: List<PrinterDevice>, val running: Boolean) : ScanDialog
    data class ConfirmPair(val device: PrinterDevice) : ScanDialog
    data class Pairing(val device: PrinterDevice) : ScanDialog
    data class PairFailed(val device: PrinterDevice, val reason: String) : ScanDialog
}

data class MainUiState(
    val settings: Settings,
    val status: ServiceStatus,
    val health: ServiceHealth,
    val devices: List<PrinterDevice>,
    val btSupported: Boolean,
    val btOn: Boolean,
    val connectGranted: Boolean,
    val portText: String,
    val portError: Boolean,
    val testRunning: Boolean,
    val starting: Boolean,
    val scan: ScanDialog?,
    val failureDialog: ServiceStatus.Failed?,
    val log: List<LogLine>,
) {
    val running: Boolean get() = status is ServiceStatus.Running
    val selectedMac: String? get() = settings.printerMac
    val selected: PrinterDevice? get() = devices.firstOrNull { it.mac.equals(selectedMac, ignoreCase = true) }

    /** The bonded list is only trustworthy with permission granted and Bluetooth on. */
    val listReliable: Boolean get() = btSupported && connectGranted && btOn
    val printerReady: Boolean get() = isPrinterReady(selectedMac, devices, listReliable)

    /** A printer is saved but is no longer among the paired devices. */
    val printerMissing: Boolean get() = !selectedMac.isNullOrBlank() && listReliable && selected == null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext: Application = app

    private data class Local(
        val devices: List<PrinterDevice> = emptyList(),
        val btSupported: Boolean = true,
        val btOn: Boolean = true,
        val connectGranted: Boolean = true,
        val portText: String? = null,
        val testRunning: Boolean = false,
        val starting: Boolean = false,
        val scan: ScanDialog? = null,
        val failureDialog: ServiceStatus.Failed? = null,
    )

    private val repo = SettingsRepository(app)
    private val bluetooth = BluetoothDevices(app)
    private val local = MutableStateFlow(Local())

    private var scanJob: Job? = null
    private var pairJob: Job? = null
    private var startingJob: Job? = null
    private var found: List<PrinterDevice> = emptyList()
    private var printerBeforeScan: String? = null
    private var autoStartHandled = false

    val settings: StateFlow<Settings?> = repo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<MainUiState?> = combine(
        settings.filterNotNull(),
        ServiceState.status,
        local,
        ActivityLog.lines,
    ) { s, status, l, log ->
        val portText = l.portText ?: s.port.toString()
        MainUiState(
            settings = s,
            status = status,
            health = deriveHealth(status, s.shouldBeRunning, l.connectGranted),
            devices = l.devices,
            btSupported = l.btSupported,
            btOn = l.btOn,
            connectGranted = l.connectGranted,
            portText = portText,
            portError = parsePort(portText) == null,
            testRunning = l.testRunning,
            starting = l.starting,
            scan = l.scan,
            failureDialog = l.failureDialog,
            log = log,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            ServiceState.status.collect { status ->
                if (status is ServiceStatus.Failed) {
                    local.update { it.copy(failureDialog = status, starting = false) }
                } else if (status is ServiceStatus.Running) {
                    local.update { it.copy(starting = false) }
                }
            }
        }
        viewModelScope.launch {
            val s = repo.current()
            refreshEnvironment(reloadDevices = true)
            maybeAutoStart(s)
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        AppLocale.localized(appContext).getString(id, *args)

    // --- Bluetooth environment -------------------------------------------------------------

    fun hasPermissions(discovery: Boolean): Boolean =
        bluetooth.hasConnectPermission() && (!discovery || bluetooth.hasScanPermission())

    fun requiredPermissions(discovery: Boolean): Array<String> = bluetooth.requiredPermissions(discovery)

    /**
     * Re-reads Bluetooth support/state/permission (cheap; called on every resume). The bonded list is
     * only reloaded when [reloadDevices] is set or Bluetooth just became usable.
     */
    fun refreshEnvironment(reloadDevices: Boolean = false) {
        val supported = bluetooth.isBluetoothSupported()
        val granted = bluetooth.hasConnectPermission()
        val on = supported && bluetooth.isBluetoothOn()
        val wasUsable = local.value.let { it.btSupported && it.connectGranted && it.btOn }
        val usable = supported && granted && on
        local.update { it.copy(btSupported = supported, connectGranted = granted, btOn = on) }
        if (usable && (reloadDevices || !wasUsable)) {
            val list = bluetooth.bonded()
            local.update { it.copy(devices = list) }
        }
    }

    /** Explicit Refresh button (FR-BT-02); works while the service runs. */
    fun refreshDevices() {
        refreshEnvironment(reloadDevices = true)
        val l = local.value
        when {
            !l.btSupported -> ActivityLog.warn(str(R.string.log_bt_unsupported))
            !l.connectGranted -> ActivityLog.warn(str(R.string.log_bt_permission))
            !l.btOn -> ActivityLog.warn(str(R.string.log_bt_off))
        }
    }

    fun perform(action: GatedAction) {
        when (action) {
            GatedAction.Refresh -> refreshDevices()
            GatedAction.Scan -> startScan()
            GatedAction.Test -> testConnection()
            GatedAction.Start -> startService()
        }
    }

    // --- Settings ----------------------------------------------------------------------------

    fun selectPrinter(mac: String) {
        val device = local.value.devices.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
        viewModelScope.launch { repo.setPrinterMac(mac) }
        ActivityLog.info(str(R.string.log_printer_selected, device?.name ?: str(R.string.unnamed), mac))
    }

    fun onPortChanged(text: String) {
        val filtered = text.filter { it in '0'..'9' }.take(5)
        local.update { it.copy(portText = filtered) }
        parsePort(filtered)?.let { port -> viewModelScope.launch { repo.setPort(port) } }
    }

    fun setStartOnBoot(value: Boolean) {
        viewModelScope.launch { repo.setStartOnBoot(value) }
    }

    fun setStartOnAppOpen(value: Boolean) {
        viewModelScope.launch { repo.setStartOnAppOpen(value) }
    }

    fun finishOnboarding() {
        viewModelScope.launch { repo.setOnboardingDone(true) }
    }

    fun clearLog() = ActivityLog.clear()

    fun dismissFailure() {
        local.update { it.copy(failureDialog = null) }
    }

    // --- Service -----------------------------------------------------------------------------

    fun startService() {
        val s = settings.value ?: return
        val l = local.value
        val port = parsePort(l.portText ?: s.port.toString()) ?: return
        if (!isPrinterReady(s.printerMac, l.devices, l.btSupported && l.connectGranted && l.btOn)) {
            ActivityLog.warn(str(R.string.log_no_printer))
            return
        }
        viewModelScope.launch {
            repo.setPort(port)
            local.update { it.copy(starting = true, failureDialog = null) }
            try {
                ForegroundPrintService.start(appContext)
            } catch (e: Exception) {
                local.update { it.copy(starting = false) }
                ActivityLog.error(str(R.string.log_start_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            startingJob?.cancel()
            startingJob = launch {
                delay(START_TIMEOUT_MS)
                local.update { it.copy(starting = false) }
            }
        }
    }

    fun stopService() {
        viewModelScope.launch {
            // Persist the user's intent first so the health indicator never flashes "stopped by system".
            repo.setShouldBeRunning(false)
            ForegroundPrintService.stop(appContext)
        }
    }

    private fun maybeAutoStart(s: Settings) {
        if (autoStartHandled) return
        autoStartHandled = true
        if (shouldAutoStart(s, ServiceState.status.value, bluetooth.hasConnectPermission())) {
            ActivityLog.info(str(R.string.log_auto_start))
            startService()
        }
    }

    // --- Test Connection ---------------------------------------------------------------------

    fun testConnection() {
        val s = settings.value ?: return
        val l = local.value
        if (l.testRunning) return
        if (!isPrinterReady(s.printerMac, l.devices, l.btSupported && l.connectGranted && l.btOn)) {
            ActivityLog.warn(str(R.string.log_no_printer))
            return
        }
        if (!bluetooth.isBluetoothOn()) {
            refreshEnvironment()
            ActivityLog.error(str(R.string.log_bt_off))
            return
        }
        local.update { it.copy(testRunning = true) }
        viewModelScope.launch {
            try {
                // TestPrint logs the start and the outcome itself (FR-TEST-03).
                val result = TestPrint.run(appContext)
                if (result is PrintResult.Failure) refreshEnvironment()
            } finally {
                local.update { it.copy(testRunning = false) }
            }
        }
    }

    // --- Scan & Pair -------------------------------------------------------------------------

    fun startScan() {
        refreshEnvironment()
        if (!local.value.btOn) {
            ActivityLog.warn(str(R.string.log_bt_off))
            return
        }
        printerBeforeScan = settings.value?.printerMac
        found = emptyList()
        scanJob?.cancel()
        local.update { it.copy(scan = ScanDialog.Scanning(emptyList(), running = true)) }
        scanJob = viewModelScope.launch {
            try {
                bluetooth.scan().collect { device ->
                    if (found.none { it.mac.equals(device.mac, ignoreCase = true) }) {
                        found = found + device
                        local.update { l ->
                            if (l.scan is ScanDialog.Scanning) l.copy(scan = ScanDialog.Scanning(found, running = true)) else l
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActivityLog.error(str(R.string.log_scan_failed, e.message ?: e.javaClass.simpleName))
            }
            local.update { l ->
                if (l.scan is ScanDialog.Scanning) l.copy(scan = ScanDialog.Scanning(found, running = false)) else l
            }
        }
    }

    /** Closes the Scan & Pair dialog; the previously selected printer stays selected. */
    fun dismissScan() {
        scanJob?.cancel()
        pairJob?.cancel()
        local.update { it.copy(scan = null) }
        restorePreviousPrinter()
    }

    fun onScanDeviceSelected(device: PrinterDevice) {
        scanJob?.cancel()
        if (device.bonded) {
            local.update { it.copy(scan = null) }
            selectPrinter(device.mac)
        } else {
            local.update { it.copy(scan = ScanDialog.ConfirmPair(device)) }
        }
    }

    /** User declined to pair: back to the (finished) result list. */
    fun declinePair() {
        local.update { it.copy(scan = ScanDialog.Scanning(found, running = false)) }
    }

    fun confirmPair(device: PrinterDevice) {
        scanJob?.cancel()
        local.update { it.copy(scan = ScanDialog.Pairing(device)) }
        pairJob?.cancel()
        pairJob = viewModelScope.launch {
            // BluetoothDevices.pair logs start/success/failure itself.
            val result = bluetooth.pair(device.mac)
            if (result.isSuccess) {
                refreshEnvironment(reloadDevices = true)
                local.update { l ->
                    val devices = if (l.devices.any { it.mac.equals(device.mac, true) }) l.devices
                    else l.devices + device.copy(bonded = true)
                    l.copy(devices = devices, scan = null)
                }
                repo.setPrinterMac(device.mac)
                ActivityLog.info(str(R.string.log_printer_selected, device.name ?: str(R.string.unnamed), device.mac))
            } else {
                val reason = result.exceptionOrNull()?.message ?: str(R.string.pair_failed_unknown)
                restorePreviousPrinter()
                local.update { it.copy(scan = ScanDialog.PairFailed(device, reason)) }
            }
        }
    }

    fun cancelPairing(device: PrinterDevice) {
        pairJob?.cancel()
        ActivityLog.warn(str(R.string.log_pair_cancelled))
        restorePreviousPrinter()
        local.update { it.copy(scan = ScanDialog.PairFailed(device, str(R.string.pair_cancelled))) }
    }

    fun dismissPairFailure() {
        local.update { it.copy(scan = null) }
    }

    private fun restorePreviousPrinter() {
        val previous = printerBeforeScan
        if (settings.value?.printerMac != previous) {
            viewModelScope.launch { repo.setPrinterMac(previous) }
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
    }
}
