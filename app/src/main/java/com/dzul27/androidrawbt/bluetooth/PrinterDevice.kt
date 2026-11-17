package com.dzul27.androidrawbt.bluetooth

/** A Bluetooth Classic device. [name] is null for unnamed devices (UI shows "(unnamed)"). */
data class PrinterDevice(val name: String?, val mac: String, val bonded: Boolean)
