package com.dzul27.androidrawbt.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    INDONESIAN("id"),
}

/**
 * Per-app language without AppCompat. Android 13+ delegates to the system [LocaleManager];
 * Android 8-12 stores the choice in SharedPreferences and wraps the activity context
 * ([localized]) before the activity is created, then recreates the activity on change.
 */
object AppLocale {
    private const val PREFS = "ui_prefs"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.SYSTEM else fromLanguage(locales[0].language)
        } else {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
            AppLanguage.entries.firstOrNull { it.name == saved } ?: AppLanguage.SYSTEM
        }

    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity with the new configuration.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, language.name).apply()
            activity.recreate()
        }
    }

    /** Context whose resources follow the chosen language (a no-op on Android 13+ and for "system"). */
    fun localized(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current(base).tag ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun fromLanguage(language: String): AppLanguage = when (language) {
        "id", "in" -> AppLanguage.INDONESIAN
        "en" -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }
}
