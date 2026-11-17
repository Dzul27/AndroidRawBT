package com.dzul27.androidrawbt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.printing.PrinterTransport
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Connect-on-demand RFCOMM/SPP transport: one socket per job, never held open.
 * Failures throw [IOException] whose message is the user-visible reason.
 */
@SuppressLint("MissingPermission")
class BluetoothPrinterTransport(context: Context) : PrinterTransport {
    private val context = context.applicationContext
    private val devices = BluetoothDevices(this.context)

    override suspend fun send(mac: String, bytes: ByteArray) {
        val device = resolveDevice(mac)
        withContext(Dispatchers.IO) {
            try {
                cancelDiscovery()
                val socket = connectWithRetry(device)
                try {
                    try {
                        writePaced(socket.outputStream, bytes)
                    } catch (e: IOException) {
                        throw IOException(context.getString(R.string.bt_err_write, e.message ?: "IO"), e)
                    }
                    // Let the printer's hardware buffer drain before the link is torn down;
                    // closing early drops queued bytes and leaves the printer mid-sequence.
                    delay(drainMillis(bytes.size))
                } finally {
                    closeQuietly(socket)
                }
            } catch (e: SecurityException) {
                throw IOException(context.getString(R.string.bt_err_permission), e)
            }
        }
    }

    /** Fails fast (no connect attempt) when Bluetooth is off, permission is missing or the MAC is not bonded. */
    private fun resolveDevice(mac: String): BluetoothDevice {
        if (!devices.isBluetoothSupported()) throw IOException(context.getString(R.string.bt_err_not_supported))
        if (!devices.isBluetoothOn()) throw IOException(context.getString(R.string.bt_err_off))
        if (!devices.hasConnectPermission()) throw IOException(context.getString(R.string.bt_err_permission))
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) throw IOException(context.getString(R.string.bt_err_invalid_mac))
        try {
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
            val device = adapter.getRemoteDevice(mac)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw IOException(context.getString(R.string.bt_err_not_paired))
            }
            return device
        } catch (e: SecurityException) {
            throw IOException(context.getString(R.string.bt_err_permission), e)
        }
    }

    private fun cancelDiscovery() {
        try {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // Without scan permission no discovery of ours can be running.
        }
    }

    /** Connects on a fresh socket; one retry after a short delay (printer may be waking up). */
    private suspend fun connectWithRetry(device: BluetoothDevice): BluetoothSocket {
        for (attempt in 1..MAX_ATTEMPTS) {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                return socket
            } catch (e: IOException) {
                socket?.let { closeQuietly(it) }
                if (attempt == MAX_ATTEMPTS) {
                    throw IOException(context.getString(R.string.bt_err_unreachable), e)
                }
                ActivityLog.warn(context.getString(R.string.bt_log_retry))
                delay(RETRY_DELAY_MS)
            }
        }
        throw IOException(context.getString(R.string.bt_err_unreachable))
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Already closed or link dropped; nothing to recover.
        }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_000L
    }
}

internal const val WRITE_CHUNK_BYTES = 128
internal const val WRITE_PAUSE_MS = 15L
internal const val DRAIN_MIN_MS = 3_800L
internal const val DRAIN_MAX_MS = 10_000L
private const val DRAIN_BASE_MS = 1_500L
private const val DRAIN_MS_PER_20_BYTES = 90L

/**
 * Writes [bytes] unmodified and in order in [WRITE_CHUNK_BYTES] pieces, flushing and pausing
 * [WRITE_PAUSE_MS] between pieces. Cheap SPP printers have a small receive buffer and often do
 * not honour RFCOMM flow control, so one large write overruns it and prints as garbage.
 */
internal suspend fun writePaced(out: OutputStream, bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val len = minOf(WRITE_CHUNK_BYTES, bytes.size - offset)
        out.write(bytes, offset, len)
        out.flush()
        offset += len
        if (offset < bytes.size) delay(WRITE_PAUSE_MS)
    }
}

/**
 * Time to wait after the last write before closing the socket. Closing before the printer has
 * finished burning paper drops its buffer and leaves it mid-sequence, so the wait follows the
 * mechanical print time (~90 ms per 20 bytes of text on a 58 mm printer), bounded to 3.8-10 s.
 */
internal fun drainMillis(size: Int): Long =
    (size / 20L * DRAIN_MS_PER_20_BYTES + DRAIN_BASE_MS).coerceIn(DRAIN_MIN_MS, DRAIN_MAX_MS)
