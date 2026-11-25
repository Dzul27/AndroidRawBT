package com.dzul27.androidrawbt.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the RFCOMM socket to the printer is created; alternatives help printers that ignore the standard one. */
enum class BtConnectMode { STANDARD, INSECURE, CHANNEL_1 }

data class Settings(
    val printerMac: String? = null,
    val port: Int = 8080,
    val paperWidthMm: Int = 58,
    val btConnectMode: BtConnectMode = BtConnectMode.STANDARD,
    val allowAnyOrigin: Boolean = true,
    val allowedOrigins: List<String> = emptyList(),
    val startOnBoot: Boolean = false,
    val startOnAppOpen: Boolean = false,
    val shouldBeRunning: Boolean = false,
    val onboardingDone: Boolean = false,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val flow: Flow<Settings> = store.data.map { p ->
        Settings(
            printerMac = p[PRINTER_MAC],
            port = p[PORT] ?: 8080,
            paperWidthMm = p[PAPER_WIDTH_MM] ?: 58,
            btConnectMode = BtConnectMode.entries.firstOrNull { it.name == p[BT_CONNECT_MODE] } ?: BtConnectMode.STANDARD,
            allowAnyOrigin = p[ALLOW_ANY_ORIGIN] ?: true,
            allowedOrigins = p[ALLOWED_ORIGINS]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            startOnBoot = p[START_ON_BOOT] ?: false,
            startOnAppOpen = p[START_ON_APP_OPEN] ?: false,
            shouldBeRunning = p[SHOULD_BE_RUNNING] ?: false,
            onboardingDone = p[ONBOARDING_DONE] ?: false,
        )
    }

    suspend fun current(): Settings = flow.first()

    /** Pass null to clear the saved printer. */
    suspend fun setPrinterMac(mac: String?) {
        store.edit { if (mac == null) it.remove(PRINTER_MAC) else it[PRINTER_MAC] = mac }
    }

    suspend fun setPort(port: Int) {
        store.edit { it[PORT] = port }
    }

    suspend fun setPaperWidthMm(mm: Int) {
        store.edit { it[PAPER_WIDTH_MM] = mm }
    }

    suspend fun setBtConnectMode(mode: BtConnectMode) {
        store.edit { it[BT_CONNECT_MODE] = mode.name }
    }

    suspend fun setAllowAnyOrigin(value: Boolean) {
        store.edit { it[ALLOW_ANY_ORIGIN] = value }
    }

    suspend fun setAllowedOrigins(origins: List<String>) {
        store.edit { it[ALLOWED_ORIGINS] = origins.joinToString("\n") }
    }

    suspend fun setStartOnBoot(value: Boolean) {
        store.edit { it[START_ON_BOOT] = value }
    }

    suspend fun setStartOnAppOpen(value: Boolean) {
        store.edit { it[START_ON_APP_OPEN] = value }
    }

    suspend fun setShouldBeRunning(value: Boolean) {
        store.edit { it[SHOULD_BE_RUNNING] = value }
    }

    suspend fun setOnboardingDone(value: Boolean) {
        store.edit { it[ONBOARDING_DONE] = value }
    }

    private companion object {
        val PRINTER_MAC = stringPreferencesKey("printer_mac")
        val PORT = intPreferencesKey("port")
        val PAPER_WIDTH_MM = intPreferencesKey("paper_width_mm")
        val BT_CONNECT_MODE = stringPreferencesKey("bt_connect_mode")
        val ALLOW_ANY_ORIGIN = booleanPreferencesKey("allow_any_origin")
        val ALLOWED_ORIGINS = stringPreferencesKey("allowed_origins")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val START_ON_APP_OPEN = booleanPreferencesKey("start_on_app_open")
        val SHOULD_BE_RUNNING = booleanPreferencesKey("should_be_running")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }
}
