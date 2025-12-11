package com.antisocial.giftcardchecker.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences wrapper for app settings.
 * Currently manages auto-CAPTCHA solving preference.
 */
@Singleton
class SettingsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "giftcard_checker_settings"
        private const val KEY_AUTO_CAPTCHA_ENABLED = "auto_captcha_enabled"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether automatic CAPTCHA solving is enabled.
     * Default: false (manual CAPTCHA entry)
     */
    var autoCaptchaEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTCHA_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTCHA_ENABLED, value).apply()
}
