package com.dzul27.androidrawbt.printing

/**
 * Sends raw bytes to a printer. Implementations (e.g. BluetoothPrinterTransport) open the
 * connection per job, write, flush, drain and close. On failure throw an exception whose
 * message is the user-visible reason (without any "Bluetooth connection failed:" prefix).
 */
interface PrinterTransport {
    suspend fun send(mac: String, bytes: ByteArray)
}
