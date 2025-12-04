package com.antisocial.giftcardchecker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for the Gift Card Checker app.
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection.
 */
@HiltAndroidApp
class GiftCardCheckerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Application initialization
    }
}
