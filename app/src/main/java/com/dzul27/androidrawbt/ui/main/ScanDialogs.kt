package com.dzul27.androidrawbt.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.bluetooth.PrinterDevice
import com.dzul27.androidrawbt.ui.MainViewModel
import com.dzul27.androidrawbt.ui.ScanDialog
import com.dzul27.androidrawbt.ui.openBluetoothSettings

/** Renders whichever Scan & Pair dialog [dialog] describes (FR-BT-04). */
@Composable
fun ScanDialogHost(dialog: ScanDialog?, vm: MainViewModel) {
    val context = LocalContext.current
    when (dialog) {
        null -> Unit
        is ScanDialog.Scanning -> AlertDialog(
            onDismissRequest = vm::dismissScan,
            title = { Text(stringResource(R.string.scan_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dialog.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.scan_running), style = MaterialTheme.typography.bodySmall)
                    } else if (dialog.found.isEmpty()) {
                        Text(stringResource(R.string.scan_none_found))
                    } else {
                        Text(stringResource(R.string.scan_pick_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(dialog.found) { device ->
                            ScanRow(device, onClick = { vm.onScanDeviceSelected(device) })
                            HorizontalDivider()
                        }
                    }
                    TextButton(onClick = { openBluetoothSettings(context) }) {
                        Text(stringResource(R.string.bluetooth_settings))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::startScan, enabled = !dialog.running) {
                    Text(stringResource(R.string.scan_again))
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissScan) { Text(stringResource(R.string.close)) } },
        )

        is ScanDialog.ConfirmPair -> AlertDialog(
            onDismissRequest = vm::declinePair,
            title = { Text(stringResource(R.string.pair_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.pair_confirm,
                        dialog.device.name ?: stringResource(R.string.unnamed),
                        dialog.device.mac,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmPair(dialog.device) }) { Text(stringResource(R.string.pair_action)) }
            },
            dismissButton = { TextButton(onClick = vm::declinePair) { Text(stringResource(R.string.cancel)) } },
        )

        is ScanDialog.Pairing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pair_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(
                            R.string.pairing_progress,
                            dialog.device.name ?: stringResource(R.string.unnamed),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { vm.cancelPairing(dialog.device) }) { Text(stringResource(R.string.cancel)) }
            },
        )

        is ScanDialog.PairFailed -> AlertDialog(
            onDismissRequest = vm::dismissPairFailure,
            title = { Text(stringResource(R.string.pair_failed_title)) },
            text = { Text(stringResource(R.string.pair_failed_body, dialog.reason)) },
            confirmButton = {
                TextButton(onClick = {
                    openBluetoothSettings(context)
                    vm.dismissPairFailure()
                }) { Text(stringResource(R.string.bluetooth_settings)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissPairFailure) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun ScanRow(device: PrinterDevice, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            device.name ?: stringResource(R.string.unnamed),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            if (device.bonded) device.mac else stringResource(R.string.scan_not_paired, device.mac),
            style = MaterialTheme.typography.bodySmall,
            color = if (device.bonded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}
