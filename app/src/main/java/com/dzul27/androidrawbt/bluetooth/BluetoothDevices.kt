package com.dzul27.androidrawbt.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.core.ActivityLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Bonded-device listing, discovery scan and system-dialog pairing for Bluetooth Classic. */
@SuppressLint("MissingPermission")
class BluetoothDevices(context: Context) {
    private val context = context.applicationContext

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isBluetoothSupported(): Boolean = adapter != null

    fun isBluetoothOn(): Boolean = try {
        adapter?.isEnabled == true
    } catch (ignored: SecurityException) {
        false
    }

    /** BLUETOOTH_CONNECT on Android 12+, legacy BLUETOOTH (install-time) below. */
    fun hasConnectPermission(): Boolean = granted(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
        else Manifest.permission.BLUETOOTH,
    )

    /** BLUETOOTH_SCAN on Android 12+, ACCESS_FINE_LOCATION on Android 8-11. */
    fun hasScanPermission(): Boolean = granted(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /**
     * Runtime permissions to request. Android 12+: CONNECT (+ SCAN for discovery).
     * Android 8-11: only ACCESS_FINE_LOCATION for discovery; the legacy Bluetooth permissions are
     * install-time, so the list is empty when [forDiscovery] is false.
     */
    fun requiredPermissions(forDiscovery: Boolean): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (forDiscovery) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (forDiscovery) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            emptyArray()
        }

    fun bonded(): List<PrinterDevice> {
        val a = adapter ?: return emptyList()
        if (!hasConnectPermission()) {
            ActivityLog.warn(context.getString(R.string.bt_err_permission))
            return emptyList()
        }
        return try {
            val list = a.bondedDevices.orEmpty()
                .map { PrinterDevice(it.name, it.address, true) }
                .sortedBy { (it.name ?: "").lowercase() }
            ActivityLog.info(context.getString(R.string.bt_log_loaded, list.size))
            list
        } catch (ignored: SecurityException) {
            ActivityLog.warn(context.getString(R.string.bt_err_permission))
            emptyList()
        }
    }

    /**
     * Discovery scan. Emits bonded devices first, then each newly found device once (deduped by
     * MAC). Completes after [durationMs] or when the system reports discovery finished; discovery
     * is cancelled on completion/cancellation.
     */
    fun scan(durationMs: Long = 12_000): Flow<PrinterDevice> = callbackFlow {
        val a = adapter
        if (a == null || !a.isEnabled || !hasScanPermission() || !hasConnectPermission()) {
            ActivityLog.error(
                context.getString(
                    if (a == null) R.string.bt_err_not_supported
                    else if (!a.isEnabled) R.string.bt_err_off
                    else R.string.bt_err_permission,
                ),
            )
            close()
            return@callbackFlow
        }

        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun offer(name: String?, mac: String, bonded: Boolean) {
            if (seen.add(mac.uppercase())) trySend(PrinterDevice(name, mac, bonded))
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.device() ?: return
                        try {
                            val name = d.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            offer(name, d.address, d.bondState == BluetoothDevice.BOND_BONDED)
                        } catch (ignored: SecurityException) {
                            // Permission revoked mid-scan; ignore this result.
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        try {
            ActivityLog.info(context.getString(R.string.bt_log_scan_start))
            for (d in a.bondedDevices.orEmpty()) offer(d.name, d.address, true)
            if (!a.startDiscovery()) {
                ActivityLog.error(context.getString(R.string.bt_err_scan_failed))
                close()
            } else {
                launch {
                    delay(durationMs)
                    close()
                }
            }
        } catch (ignored: SecurityException) {
            ActivityLog.error(context.getString(R.string.bt_err_permission))
            close()
        }

        awaitClose {
            context.unregisterReceiver(receiver)
            try {
                a.cancelDiscovery()
            } catch (ignored: SecurityException) {
                // No scan permission means no discovery to cancel.
            }
            ActivityLog.info(context.getString(R.string.bt_log_scan_done, seen.size))
        }
    }

    /**
     * Starts system pairing (createBond) and suspends until the bond state resolves or ~60 s pass.
     * The Android system dialog handles confirmation/PIN; this app never touches a PIN.
     */
    suspend fun pair(mac: String): Result<Unit> {
        val a = adapter ?: return fail(R.string.bt_err_not_supported)
        if (!a.isEnabled) return fail(R.string.bt_err_off)
        if (!hasConnectPermission()) return fail(R.string.bt_err_permission)
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return fail(R.string.bt_err_invalid_mac)

        val outcome = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.device() ?: return
                if (!d.address.equals(mac, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                when {
                    state == BluetoothDevice.BOND_BONDED -> outcome.complete(true)
                    state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING ->
                        outcome.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val device = a.getRemoteDevice(mac)
            if (device.bondState == BluetoothDevice.BOND_BONDED) return Result.success(Unit)
            ActivityLog.info(context.getString(R.string.bt_log_pair_start, mac))
            try {
                a.cancelDiscovery()
            } catch (ignored: SecurityException) {
                // No scan permission means no discovery to cancel.
            }
            if (!device.createBond()) return fail(R.string.bt_err_pair_start)
            return when (withTimeoutOrNull(PAIR_TIMEOUT_MS) { outcome.await() }) {
                true -> {
                    ActivityLog.info(context.getString(R.string.bt_log_pair_ok, mac))
                    Result.success(Unit)
                }
                false -> fail(R.string.bt_err_pair_failed)
                null -> fail(R.string.bt_err_pair_timeout)
            }
        } catch (ignored: SecurityException) {
            return fail(R.string.bt_err_permission)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun fail(resId: Int): Result<Unit> {
        val message = context.getString(resId)
        ActivityLog.error(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val PAIR_TIMEOUT_MS = 60_000L
    }
}
