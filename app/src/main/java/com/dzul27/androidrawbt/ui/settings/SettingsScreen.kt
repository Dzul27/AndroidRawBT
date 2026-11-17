package com.dzul27.androidrawbt.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzul27.androidrawbt.BuildConfig
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.ui.AppLanguage
import com.dzul27.androidrawbt.ui.AppLocale
import com.dzul27.androidrawbt.ui.OriginAdd
import com.dzul27.androidrawbt.ui.addOrigin
import com.dzul27.androidrawbt.ui.components.BatteryGuidance
import com.dzul27.androidrawbt.ui.findActivity
import com.dzul27.androidrawbt.ui.openUrl

private const val REPO_URL = "https://github.com/Dzul27/AndroidRawBT"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, scrollToBattery: Boolean, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var batteryY by remember { mutableStateOf(-1) }

    LaunchedEffect(scrollToBattery, batteryY) {
        if (scrollToBattery && batteryY >= 0) scrollState.animateScrollTo(batteryY)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Paper width (FR-TEST-04)
            SectionTitle(R.string.paper_width)
            Text(stringResource(R.string.paper_width_explain), style = MaterialTheme.typography.bodySmall)
            RadioRow(stringResource(R.string.paper_58), s.paperWidthMm == 58) { vm.setPaperWidth(58) }
            RadioRow(stringResource(R.string.paper_80), s.paperWidthMm == 80) { vm.setPaperWidth(80) }
            HorizontalDivider()

            // Origin control (FR-SVC-03)
            SectionTitle(R.string.origins_title)
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = s.allowAnyOrigin,
                        role = Role.Switch,
                        onValueChange = vm::setAllowAnyOrigin,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.allow_any_origin),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = s.allowAnyOrigin, onCheckedChange = null)
            }
            if (s.allowAnyOrigin) {
                Text(stringResource(R.string.allow_any_origin_on), style = MaterialTheme.typography.bodySmall)
            } else {
                OriginsEditor(origins = s.allowedOrigins, onChange = vm::setAllowedOrigins)
            }
            HorizontalDivider()

            // Language
            SectionTitle(R.string.language)
            val language = remember { AppLocale.current(context) }
            val activity = context.findActivity()
            LanguageRow(R.string.language_system, AppLanguage.SYSTEM, language, activity)
            LanguageRow(R.string.language_english, AppLanguage.ENGLISH, language, activity)
            LanguageRow(R.string.language_indonesian, AppLanguage.INDONESIAN, language, activity)
            HorizontalDivider()

            // Battery guidance
            Column(
                Modifier.onGloballyPositioned { batteryY = it.positionInParent().y.toInt() },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle(R.string.battery_title)
                BatteryGuidance()
            }
            HorizontalDivider()

            // About
            SectionTitle(R.string.about)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openUrl(context, REPO_URL) }) { Text(stringResource(R.string.about_source)) }
        }
    }
}

@Composable
private fun LanguageRow(@StringRes label: Int, option: AppLanguage, current: AppLanguage, activity: android.app.Activity?) {
    RadioRow(stringResource(label), option == current) {
        if (option != current && activity != null) AppLocale.set(activity, option)
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun OriginsEditor(origins: List<String>, onChange: (List<String>) -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    fun add() {
        when (val result = addOrigin(origins, input)) {
            is OriginAdd.Added -> {
                onChange(result.origins)
                input = ""
                error = null
            }
            OriginAdd.Invalid -> error = R.string.origin_invalid
            OriginAdd.Duplicate -> error = R.string.origin_duplicate
        }
    }

    Text(stringResource(R.string.origins_explain), style = MaterialTheme.typography.bodySmall)
    if (origins.isEmpty()) {
        Text(
            stringResource(R.string.origins_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    for (origin in origins) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(origin, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { onChange(origins - origin) }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.origin_remove, origin))
            }
        }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                error = null
            },
            label = { Text(stringResource(R.string.origin_input_label)) },
            placeholder = { Text("https://pos.example.com") },
            singleLine = true,
            isError = error != null,
            supportingText = { error?.let { Text(stringResource(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::add, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.origin_add), modifier = Modifier.size(24.dp))
        }
    }
}
