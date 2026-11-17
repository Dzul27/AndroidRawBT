package com.dzul27.androidrawbt.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dzul27.androidrawbt.ui.main.MainScreen
import com.dzul27.androidrawbt.ui.onboarding.OnboardingScreen
import com.dzul27.androidrawbt.ui.settings.SettingsScreen
import com.dzul27.androidrawbt.ui.settings.SettingsViewModel
import com.dzul27.androidrawbt.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppRoot(mainViewModel, settingsViewModel)
            }
        }
    }
}

private enum class Screen { Main, Settings }

/** State-based navigation: onboarding (until finished) -> Main <-> Settings. */
@Composable
private fun AppRoot(mainVm: MainViewModel, settingsVm: SettingsViewModel) {
    val settings by mainVm.settings.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    var scrollToBattery by rememberSaveable { mutableStateOf(false) }

    val current = settings ?: return // first DataStore read still pending

    if (!current.onboardingDone) {
        OnboardingScreen(vm = mainVm, onFinish = mainVm::finishOnboarding)
        return
    }

    when (screen) {
        Screen.Main -> MainScreen(
            vm = mainVm,
            onOpenSettings = {
                scrollToBattery = false
                screen = Screen.Settings
            },
            onOpenBatteryGuidance = {
                scrollToBattery = true
                screen = Screen.Settings
            },
        )

        Screen.Settings -> {
            BackHandler { screen = Screen.Main }
            SettingsScreen(
                vm = settingsVm,
                scrollToBattery = scrollToBattery,
                onBack = { screen = Screen.Main },
            )
        }
    }
}
