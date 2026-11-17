package com.dzul27.androidrawbt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.annotation.StringRes
import androidx.compose.ui.unit.dp
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.ui.isIgnoringBatteryOptimizations
import com.dzul27.androidrawbt.ui.openUrl
import com.dzul27.androidrawbt.ui.requestIgnoreBatteryOptimizations

private data class OemGuide(@param:StringRes val label: Int, val url: String)

private val oemGuides = listOf(
    OemGuide(R.string.oem_xiaomi, "https://dontkillmyapp.com/xiaomi"),
    OemGuide(R.string.oem_oppo, "https://dontkillmyapp.com/oppo"),
    OemGuide(R.string.oem_realme, "https://dontkillmyapp.com/realme"),
    OemGuide(R.string.oem_vivo, "https://dontkillmyapp.com/vivo"),
    OemGuide(R.string.oem_samsung, "https://dontkillmyapp.com/samsung"),
    OemGuide(R.string.oem_huawei, "https://dontkillmyapp.com/huawei"),
)

/** Battery-optimization exemption button plus per-OEM guidance links (FR-UX-05, Settings > battery). */
@Composable
fun BatteryGuidance(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    // Returning from the system dialog / settings re-checks the state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = isIgnoringBatteryOptimizations(context) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.battery_explain), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (exempt) R.string.battery_exempt_yes else R.string.battery_exempt_no),
            style = MaterialTheme.typography.labelLarge,
            color = if (exempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!exempt) {
            Button(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                Text(stringResource(R.string.battery_request))
            }
        }
        Text(stringResource(R.string.battery_oem_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.battery_oem_explain), style = MaterialTheme.typography.bodySmall)
        for (guide in oemGuides) {
            TextButton(onClick = { openUrl(context, guide.url) }) { Text(stringResource(guide.label)) }
        }
    }
}
