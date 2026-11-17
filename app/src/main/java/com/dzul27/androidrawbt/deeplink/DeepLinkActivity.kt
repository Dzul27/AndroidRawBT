package com.dzul27.androidrawbt.deeplink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.dzul27.androidrawbt.R
import com.dzul27.androidrawbt.core.ActivityLog
import com.dzul27.androidrawbt.core.PayloadDecodeException
import com.dzul27.androidrawbt.core.PayloadDecoder
import com.dzul27.androidrawbt.core.hexAsciiPreview
import com.dzul27.androidrawbt.printing.PrintQueue
import com.dzul27.androidrawbt.printing.PrintResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles rawbt:base64,... links (FR-SVC-06): decode with the shared decoder, print via [PrintQueue]
 * (works with or without the service running), then finish().
 */
class DeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val link = intent?.dataString
        val context = applicationContext
        val payload = link?.let { decode(context, it) } ?: return
        // Application-level scope: printing continues after this activity has finished.
        printScope.launch {
            val message = when (val result = PrintQueue.submit(payload, "deeplink")) {
                is PrintResult.Ok -> null
                is PrintResult.Failure -> context.getString(R.string.deeplink_failed, result.message)
            }
            if (message != null) toast(context, message)
        }
    }

    /** Returns the printer bytes, or null (after logging + toast) when the link is invalid. */
    private fun decode(context: Context, link: String): ByteArray? {
        val raw = link.toByteArray(Charsets.UTF_8)
        ActivityLog.info("Deep link as received: ${PayloadDecoder.formatBodyHead(raw)}")
        val decoded = try {
            PayloadDecoder.decode(raw, "text/plain")
        } catch (e: PayloadDecodeException) {
            ActivityLog.warn("Deep link rejected: ${e.message ?: "invalid payload"}")
            null
        } catch (e: Exception) {
            ActivityLog.warn("Deep link rejected: ${e.message ?: e.javaClass.simpleName}")
            null
        }
        if (decoded == null || decoded.bytes.isEmpty()) {
            if (decoded != null) ActivityLog.warn("Deep link rejected: empty payload.")
            Toast.makeText(context, R.string.deeplink_invalid, Toast.LENGTH_LONG).show()
            return null
        }
        ActivityLog.info(
            "Deep link: ${decoded.bytes.size} B | decode: ${decoded.path} | " + hexAsciiPreview(decoded.bytes, 48),
        )
        ActivityLog.info("Deep link: ${PayloadDecoder.summarizeEscPos(decoded.bytes)}")
        return decoded.bytes
    }

    private companion object {
        val printScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun toast(context: Context, message: String) = withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
