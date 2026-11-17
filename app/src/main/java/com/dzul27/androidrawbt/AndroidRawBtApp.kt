package com.dzul27.androidrawbt

import android.app.Application
import com.dzul27.androidrawbt.bluetooth.BluetoothPrinterTransport
import com.dzul27.androidrawbt.printing.PrintQueue

class AndroidRawBtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Gives the print queue access to the persisted printer MAC and the Bluetooth transport.
        PrintQueue.init(this)
        PrintQueue.transport = BluetoothPrinterTransport(this)
    }
}
