package com.dzul27.androidrawbt.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.ui.MainViewModel
import com.dzul27.androidrawbt.ui.components.BatteryGuidance
import com.dzul27.androidrawbt.ui.openAppSettings

private const val STEP_COUNT = 4

private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** First-launch walkthrough (FR-UX-05): Bluetooth, notifications, battery exemption, Chrome LNA note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(vm: MainViewModel, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(0) }
    // Bumped to re-read permission state after a system dialog or returning from app settings.
    var tick by remember { mutableStateOf(0) }
    var bluetoothAsked by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        bluetoothAsked = true
        vm.refreshEnvironment()
        tick++
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsAsked = true
        tick++
    }

    BackHandler(enabled = step > 0) { step-- }

    // `tick` is read so the values below are recomputed after every permission change.
    val bluetoothGranted = tick >= 0 && vm.hasPermissions(discovery = true)
    val notificationsOk = tick >= 0 && notificationsGranted(context)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onb_title)) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.onb_step_of, step + 1, STEP_COUNT),
                style = MaterialTheme.typography.labelLarge,
            )
            LinearProgressIndicator(progress = { (step + 1) / STEP_COUNT.toFloat() }, modifier = Modifier.fillMaxWidth())

            when (step) {
                0 -> {
                    StepTitle(R.string.onb_bt_title)
                    Text(stringResource(R.string.onb_bt_body))
                    StatusLine(bluetoothGranted, R.string.onb_granted, R.string.onb_not_granted)
                    if (!bluetoothGranted) {
                        Button(onClick = { bluetoothLauncher.launch(vm.requiredPermissions(discovery = true)) }) {
                            Text(stringResource(R.string.permission_grant))
                        }
                        if (bluetoothAsked) {
                            Text(stringResource(R.string.permission_explain), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { openAppSettings(context) }) {
                                Text(stringResource(R.string.open_app_settings))
                            }
                        }
                    }
                }

                1 -> {
                    StepTitle(R.string.onb_notif_title)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(stringResource(R.string.onb_notif_body))
                        StatusLine(notificationsOk, R.string.onb_granted, R.string.onb_not_granted)
                        if (!notificationsOk) {
                            Text(
                                stringResource(R.string.onb_notif_warning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text(stringResource(R.string.permission_grant))
                            }
                            if (notificationsAsked) {
                                OutlinedButton(onClick = { openAppSettings(context) }) {
                                    Text(stringResource(R.string.open_app_settings))
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.onb_notif_not_needed))
                    }
                }

                2 -> {
                    StepTitle(R.string.onb_battery_title)
                    BatteryGuidance()
                }

                else -> {
                    StepTitle(R.string.onb_lna_title)
                    Text(stringResource(R.string.onb_lna_body))
                    Text(stringResource(R.string.onb_lna_fix), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) { Text(stringResource(R.string.onb_back)) }
                } else {
                    Spacer(Modifier)
                }
                if (step < STEP_COUNT - 1) {
                    Button(onClick = { step++ }) { Text(stringResource(R.string.onb_next)) }
                } else {
                    Button(onClick = onFinish) { Text(stringResource(R.string.onb_finish)) }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun StatusLine(ok: Boolean, @StringRes okText: Int, @StringRes notOkText: Int) {
    Text(
        stringResource(if (ok) okText else notOkText),
        style = MaterialTheme.typography.labelLarge,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}
