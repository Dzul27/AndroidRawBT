package com.dzul27.androidrawbt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Starts the print service after device boot when the user enabled "Start on device boot" (FR-UX-04a). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val settings = SettingsRepository(appContext).current()
                if (settings.startOnBoot && !settings.printerMac.isNullOrBlank()) {
                    ActivityLog.info("Device booted: starting service.")
                    ForegroundPrintService.start(appContext)
                }
            } catch (e: Exception) {
                // Background-start restrictions (Android 12+) or a missing permission: manual start still works.
                ActivityLog.error("Could not start service after boot: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
