package com.dzul27.androidrawbt.ui

import com.dzul27.androidrawbt.bluetooth.PrinterDevice
import com.dzul27.androidrawbt.service.ServiceStatus
import com.dzul27.androidrawbt.settings.Settings
import java.net.URI
import java.net.URISyntaxException

// Pure UI logic (no Android dependencies) so it can be unit-tested on the JVM.

const val MIN_PORT = 1024
const val MAX_PORT = 65535

/** Returns the port when [text] is a plain number in [MIN_PORT]..[MAX_PORT], else null. */
fun parsePort(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || t.length > 5 || !t.all { it in '0'..'9' }) return null
    val value = t.toInt()
    return if (value in MIN_PORT..MAX_PORT) value else null
}

/**
 * Turns what a user types ("pos.example.com", "https://pos.example.com/app") into a browser Origin:
 * `scheme://host[:port]` in lower case, default ports dropped, `"null"` for the file:// origin.
 * A missing scheme defaults to https. Returns null when the input is not a usable http(s) address.
 * Mirrors WinRawBT's OriginHelper.Normalize.
 */
fun normalizeOrigin(input: String?): String? {
    var text = input?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.equals("null", ignoreCase = true)) return "null"
    if (!text.contains("://")) text = "https://$text"

    val uri = try {
        URI(text)
    } catch (e: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host
    if (host.isNullOrEmpty() || uri.userInfo != null) return null
    val port = uri.port
    if (port != -1 && port !in 1..65535) return null
    val defaultPort = if (scheme == "http") 80 else 443
    val authority = if (port == -1 || port == defaultPort) host else "$host:$port"
    return "$scheme://$authority".lowercase()
}

sealed interface OriginAdd {
    data class Added(val origins: List<String>) : OriginAdd
    data object Invalid : OriginAdd
    data object Duplicate : OriginAdd
}

/** Validates [input] and appends its normalized form to [current]. */
fun addOrigin(current: List<String>, input: String): OriginAdd {
    val origin = normalizeOrigin(input) ?: return OriginAdd.Invalid
    if (current.any { it.equals(origin, ignoreCase = true) }) return OriginAdd.Duplicate
    return OriginAdd.Added(current + origin)
}

/** What the health indicator on the main screen shows (FR-UX-06). */
sealed interface ServiceHealth {
    data class Running(val port: Int) : ServiceHealth
    data object Stopped : ServiceHealth

    /** The persisted "should be running" flag is set but the service is not alive. */
    data object StoppedBySystem : ServiceHealth

    /** Service runs but the Bluetooth permission was revoked meanwhile. */
    data object PermissionMissing : ServiceHealth
    data class Failed(val message: String, val portInUse: Boolean) : ServiceHealth
}

fun deriveHealth(status: ServiceStatus, shouldBeRunning: Boolean, connectGranted: Boolean): ServiceHealth =
    when (status) {
        is ServiceStatus.Running ->
            if (connectGranted) ServiceHealth.Running(status.port) else ServiceHealth.PermissionMissing
        is ServiceStatus.Failed -> ServiceHealth.Failed(status.message, status.portInUse)
        ServiceStatus.Stopped ->
            if (shouldBeRunning) ServiceHealth.StoppedBySystem else ServiceHealth.Stopped
    }

/**
 * True when [mac] may be used for Test Connection / Start Service. When the bonded list is
 * [listReliable] (permission granted and Bluetooth on) the printer must be in it, so unpaired
 * devices are never used; otherwise only a saved MAC is required.
 */
fun isPrinterReady(mac: String?, devices: List<PrinterDevice>, listReliable: Boolean): Boolean {
    if (mac.isNullOrBlank()) return false
    return !listReliable || devices.any { it.mac.equals(mac, ignoreCase = true) }
}

/** FR-UX-04b: start the service when the app opens if enabled, a printer is saved and it is not running. */
fun shouldAutoStart(settings: Settings, status: ServiceStatus, connectGranted: Boolean): Boolean =
    settings.startOnAppOpen &&
        !settings.printerMac.isNullOrBlank() &&
        connectGranted &&
        status is ServiceStatus.Stopped
