package com.dzul27.androidrawbt.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

// Shortcuts into Android system screens. Each returns false when no activity could handle it.

private fun Context.launch(intent: Intent): Boolean {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun openAppSettings(context: Context): Boolean = context.launch(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
)

fun openBluetoothSettings(context: Context): Boolean =
    context.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

fun openUrl(context: Context, url: String): Boolean =
    context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** Asks the system to exempt the app from battery optimization; falls back to the settings list. */
fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    return context.launch(direct) || context.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
