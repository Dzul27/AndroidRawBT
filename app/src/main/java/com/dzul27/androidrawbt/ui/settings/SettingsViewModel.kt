package com.dzul27.androidrawbt.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dzul27.androidrawbt.settings.BtConnectMode
import com.dzul27.androidrawbt.settings.Settings
import com.dzul27.androidrawbt.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)

    /** Null until the first DataStore read completes. */
    val settings: StateFlow<Settings?> = repo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setPaperWidth(mm: Int) {
        viewModelScope.launch { repo.setPaperWidthMm(mm) }
    }

    fun setBtConnectMode(mode: BtConnectMode) {
        viewModelScope.launch { repo.setBtConnectMode(mode) }
    }

    fun setAllowAnyOrigin(value: Boolean) {
        viewModelScope.launch { repo.setAllowAnyOrigin(value) }
    }

    fun setAllowedOrigins(origins: List<String>) {
        viewModelScope.launch { repo.setAllowedOrigins(origins) }
    }
}
