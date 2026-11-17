package com.dzul27.androidrawbt.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.server.PrintServer
import com.dzul27.androidrawbt.settings.Settings
import com.dzul27.androidrawbt.settings.SettingsRepository
import java.net.BindException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service (type connectedDevice) that owns the [PrintServer].
 *
 * It alone persists `shouldBeRunning`: true after a successful start, false on a user stop
 * (Stop action / [stop]); an OS kill leaves the flag untouched so the UI can show "Stopped by system".
 */
class ForegroundPrintService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes start/stop work so quick stop-then-start requests cannot interleave. */
    private val lifecycle = Mutex()
    private lateinit var repo: SettingsRepository
    private var settingsJob: Job? = null

    @Volatile
    private var latestSettings = Settings()

    @Volatile
    private var server: PrintServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(applicationContext)
        Notifications.ensureChannel(this)
        // Keeps origin rules live: PrintServer reads this snapshot on every request.
        settingsJob = scope.launch { repo.flow.collect { latestSettings = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { userStop(startId) }
            return START_NOT_STICKY
        }
        // A null intent is the system restarting a killed START_STICKY service: same path as a user start.
        try {
            enterForeground(getString(R.string.svc_notif_starting))
        } catch (e: SecurityException) {
            failStart(getString(R.string.svc_err_permission), portInUse = false, startId, e)
            return START_NOT_STICKY
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background (Android 12+).
            failStart(getString(R.string.svc_err_start_failed, e.message ?: e.javaClass.simpleName), false, startId, e)
            return START_NOT_STICKY
        }
        scope.launch { startServer(startId) }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        val orphan = server
        server = null
        if (orphan != null) {
            // Torn down without a user stop (system / process pressure): leave shouldBeRunning untouched.
            if (ServiceState.status.value is ServiceStatus.Running) ServiceState.update(ServiceStatus.Stopped)
            ActivityLog.warn("Service destroyed by the system.")
            cleanupScope.launch { runCatching { orphan.stop() } }
        }
        super.onDestroy()
    }

    private fun enterForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            Notifications.NOTIFICATION_ID,
            Notifications.build(this, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private suspend fun startServer(startId: Int) = lifecycle.withLock {
        val running = server
        if (running != null) {
            runCatching { enterForeground(getString(R.string.svc_notif_running, running.port)) }
            return@withLock
        }
        if (ServiceState.status.value is ServiceStatus.Failed) ServiceState.update(ServiceStatus.Stopped)

        val settings = repo.current()
        latestSettings = settings
        val port = settings.port
        if (port !in 1..65535) {
            failStart(getString(R.string.svc_err_invalid_port, port), portInUse = false, startId, null)
            return@withLock
        }

        val candidate = PrintServer(port) { latestSettings }
        try {
            candidate.start()
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                candidate.stop()
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            failStart(getString(R.string.svc_err_permission), portInUse = false, startId, e)
            return@withLock
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is BindException }) {
                failStart(getString(R.string.svc_err_port_in_use, port), portInUse = true, startId, e)
            } else {
                val reason = e.message ?: e.javaClass.simpleName
                failStart(getString(R.string.svc_err_start_failed, reason), portInUse = false, startId, e)
            }
            return@withLock
        }

        server = candidate
        withContext(NonCancellable) { repo.setShouldBeRunning(true) }
        ServiceState.update(ServiceStatus.Running(port))
        runCatching { enterForeground(getString(R.string.svc_notif_running, port)) }
        ActivityLog.info("Service started 127.0.0.1:$port")
    }

    /** Bind/start failure: report, never stay in the foreground. shouldBeRunning is left as it was. */
    private fun failStart(message: String, portInUse: Boolean, startId: Int, cause: Throwable?) {
        ActivityLog.error(message + (cause?.message?.let { " ($it)" } ?: ""))
        ServiceState.update(ServiceStatus.Failed(message, portInUse))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private suspend fun userStop(startId: Int) {
        lifecycle.withLock {
            val running = server
            server = null
            withContext(NonCancellable) {
                running?.let { runCatching { it.stop() } }
                repo.setShouldBeRunning(false)
            }
            ServiceState.update(ServiceStatus.Stopped)
            ActivityLog.info("Service stopped.")
            // Inside the lock so a queued restart re-promotes the service after the notification is removed.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf(startId)
    }

    companion object {
        const val ACTION_STOP = "com.dzul27.androidrawbt.action.STOP"

        /** Outlives the service instance so a late server shutdown is not cancelled with it. */
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** True while the server is bound and accepting jobs (derived from [ServiceState.status]). */
        val isRunning: StateFlow<Boolean> get() = ServiceState.isRunning

        /** Call only from a user action or the boot receiver (Android 12+ background-start limits). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ForegroundPrintService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ForegroundPrintService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
