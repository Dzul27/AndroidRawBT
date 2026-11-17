package com.dzul27.androidrawbt.ui.main

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.bluetooth.PrinterDevice
import com.dzul27.androidrawbt.core.Level
import com.dzul27.androidrawbt.core.LogLine
import com.dzul27.androidrawbt.ui.GatedAction
import com.dzul27.androidrawbt.ui.MAX_PORT
import com.dzul27.androidrawbt.ui.MIN_PORT
import com.dzul27.androidrawbt.ui.MainUiState
import com.dzul27.androidrawbt.ui.MainViewModel
import com.dzul27.androidrawbt.ui.ServiceHealth
import com.dzul27.androidrawbt.ui.openAppSettings
import com.dzul27.androidrawbt.ui.openBluetoothSettings
import com.dzul27.androidrawbt.ui.theme.StatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenBatteryGuidance: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<GatedAction?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pending
        pending = null
        vm.refreshEnvironment()
        // Denied: the permission banner stays visible with the explanation and app-settings shortcut.
        if (action != null && vm.hasPermissions(action.discovery)) vm.perform(action)
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshEnvironment() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshEnvironment() }

    fun runGated(action: GatedAction) {
        if (vm.hasPermissions(action.discovery)) {
            vm.perform(action)
        } else {
            pending = action
            permissionLauncher.launch(vm.requiredPermissions(action.discovery))
        }
    }

    fun enableBluetooth() {
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: ActivityNotFoundException) {
            openBluetoothSettings(context)
        }
    }

    val ui = state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        if (ui == null) return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val controls: @Composable () -> Unit = {
                    Controls(
                        ui = ui,
                        vm = vm,
                        onAction = ::runGated,
                        onEnableBluetooth = ::enableBluetooth,
                    )
                }
                if (maxWidth >= 600.dp) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { controls() }
                        LogPanel(
                            log = ui.log,
                            onClear = vm::clearLog,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(16.dp),
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { controls() }
                        LogPanel(
                            log = ui.log,
                            onClear = vm::clearLog,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            HealthBar(
                health = ui.health,
                restartEnabled = !ui.starting,
                onRestart = { runGated(GatedAction.Start) },
                onBatteryGuidance = onOpenBatteryGuidance,
                onOpenAppSettings = { openAppSettings(context) },
            )
        }
    }

    if (ui != null) {
        ui.failureDialog?.let { failure ->
            AlertDialog(
                onDismissRequest = vm::dismissFailure,
                title = {
                    Text(
                        stringResource(
                            if (failure.portInUse) R.string.failure_port_title else R.string.failure_generic_title,
                        ),
                    )
                },
                text = {
                    Text(
                        if (failure.portInUse) {
                            stringResource(R.string.failure_port_body, ui.settings.port)
                        } else {
                            stringResource(R.string.failure_generic_body, failure.message)
                        },
                    )
                },
                confirmButton = { TextButton(onClick = vm::dismissFailure) { Text(stringResource(R.string.ok)) } },
            )
        }
        ScanDialogHost(ui.scan, vm)
    }
}

@Composable
private fun Controls(
    ui: MainUiState,
    vm: MainViewModel,
    onAction: (GatedAction) -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val context = LocalContext.current

    when {
        !ui.btSupported -> Banner(
            text = stringResource(R.string.bt_unsupported),
            isError = true,
        )
        !ui.connectGranted -> Banner(
            text = stringResource(R.string.permission_explain),
            isError = true,
        ) {
            TextButton(onClick = { onAction(GatedAction.Refresh) }) { Text(stringResource(R.string.permission_grant)) }
            TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.open_app_settings)) }
        }
        !ui.btOn -> Banner(
            text = stringResource(R.string.bt_off_banner),
            isError = false,
        ) {
            TextButton(onClick = onEnableBluetooth) { Text(stringResource(R.string.bt_enable)) }
            TextButton(onClick = { openBluetoothSettings(context) }) { Text(stringResource(R.string.bluetooth_settings)) }
        }
    }

    Text(stringResource(R.string.printer), style = MaterialTheme.typography.titleMedium)
    PrinterDropdown(ui = ui, onSelect = vm::selectPrinter)
    if (ui.printerMissing) {
        Text(
            stringResource(R.string.printer_not_paired_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onAction(GatedAction.Refresh) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.refresh))
        }
        OutlinedButton(
            onClick = { onAction(GatedAction.Scan) },
            enabled = !ui.running,
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.scan_and_pair)) }
    }
    if (ui.running) {
        Text(
            stringResource(R.string.scan_disabled_running),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    OutlinedTextField(
        value = ui.portText,
        onValueChange = vm::onPortChanged,
        label = { Text(stringResource(R.string.port)) },
        singleLine = true,
        enabled = !ui.running,
        isError = ui.portError,
        supportingText = {
            Text(
                if (ui.portError) stringResource(R.string.port_invalid, MIN_PORT, MAX_PORT)
                else stringResource(R.string.port_hint),
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onAction(GatedAction.Test) },
            enabled = !ui.testRunning && ui.printerReady,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(if (ui.testRunning) R.string.testing else R.string.test_connection))
        }
        if (ui.running) {
            Button(onClick = vm::stopService, modifier = Modifier.weight(1f)) {
                Text("\u25A0 " + stringResource(R.string.stop_service))
            }
        } else {
            Button(
                onClick = { onAction(GatedAction.Start) },
                enabled = ui.printerReady && !ui.portError && !ui.starting,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.start_service))
            }
        }
    }

    SwitchRow(stringResource(R.string.start_on_boot), ui.settings.startOnBoot, vm::setStartOnBoot)
    SwitchRow(stringResource(R.string.start_on_app_open), ui.settings.startOnAppOpen, vm::setStartOnAppOpen)
}

@Composable
private fun Banner(text: String, isError: Boolean, actions: @Composable () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        }
    }
}

@Composable
private fun PrinterDropdown(ui: MainUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val unnamed = stringResource(R.string.unnamed)
    val selected = ui.selected
    val mac = ui.selectedMac
    val label = when {
        selected != null -> deviceLabel(selected, unnamed)
        !mac.isNullOrBlank() -> stringResource(R.string.printer_saved_label, mac)
        else -> ""
    }
    Box {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.select_printer)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay so a tap anywhere on the field opens the menu.
        Box(
            Modifier
                .matchParentSize()
                .clickable(role = Role.DropdownList) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (ui.devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.no_paired_devices)) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            }
            for (device in ui.devices) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(device.name ?: unnamed)
                            Text(device.mac, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(device.mac)
                    },
                )
            }
        }
    }
}

private fun deviceLabel(device: PrinterDevice, unnamed: String) = "${device.name ?: unnamed} (${device.mac})"

@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LogPanel(log: List<LogLine>, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(log) {
        if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex)
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.activity_log), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClear, enabled = log.isNotEmpty()) { Text(stringResource(R.string.clear_log)) }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (log.isEmpty()) {
                Text(
                    stringResource(R.string.log_empty),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(8.dp)) {
                    items(log) { line ->
                        Text(
                            "${line.time} ${line.text}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = when (line.level) {
                                Level.ERROR -> MaterialTheme.colorScheme.error
                                Level.WARN -> StatusColors.Warning
                                Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthBar(
    health: ServiceHealth,
    restartEnabled: Boolean,
    onRestart: () -> Unit,
    onBatteryGuidance: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val color: Color
    val text: String
    when (health) {
        is ServiceHealth.Running -> {
            color = StatusColors.Running
            text = stringResource(R.string.health_running, health.port)
        }
        ServiceHealth.Stopped -> {
            color = StatusColors.Stopped
            text = stringResource(R.string.health_stopped)
        }
        ServiceHealth.StoppedBySystem -> {
            color = StatusColors.Warning
            text = stringResource(R.string.health_stopped_by_system)
        }
        ServiceHealth.PermissionMissing -> {
            color = StatusColors.Warning
            text = stringResource(R.string.health_permission_missing)
        }
        is ServiceHealth.Failed -> {
            color = MaterialTheme.colorScheme.error
            text = stringResource(R.string.health_failed, health.message)
        }
    }
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (health == ServiceHealth.PermissionMissing) it.clickable(onClick = onOpenAppSettings) else it },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (health == ServiceHealth.StoppedBySystem) {
                    Button(onClick = onRestart, enabled = restartEnabled) { Text(stringResource(R.string.restart)) }
                }
            }
            if (health == ServiceHealth.StoppedBySystem) {
                TextButton(onClick = onBatteryGuidance) { Text(stringResource(R.string.battery_guidance_link)) }
            }
        }
    }
}
