package com.dzul27.androidrawbt.bluetooth

import android.content.Context
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.printing.PrintQueue
import com.dzul27.androidrawbt.printing.PrintResult
import com.dzul27.androidrawbt.settings.SettingsRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Test Connection: prints the diagnostic receipt on the selected printer through the shared queue. */
object TestPrint {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun run(context: Context): PrintResult {
        val app = context.applicationContext
        val settings = SettingsRepository(app).current()
        val mac = settings.printerMac
        if (mac.isNullOrBlank()) {
            val message = app.getString(R.string.bt_test_no_printer)
            ActivityLog.error(app.getString(R.string.bt_test_failed, message))
            return PrintResult.Failure(message)
        }
        ActivityLog.info(app.getString(R.string.bt_test_start, mac))
        val receipt = TestReceipt.build(mac, settings.paperWidthMm, LocalDateTime.now().format(timestampFormat))
        val result = PrintQueue.submit(receipt, "test")
        when (result) {
            is PrintResult.Ok -> ActivityLog.info(app.getString(R.string.bt_test_ok))
            is PrintResult.Failure -> ActivityLog.error(app.getString(R.string.bt_test_failed, result.message))
        }
        return result
    }
}
