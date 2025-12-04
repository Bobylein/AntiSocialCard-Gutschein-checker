package com.antisocial.giftcardchecker.di

import android.content.Context
import com.antisocial.giftcardchecker.utils.JsAssetLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for application-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides a singleton instance of JsAssetLoader for loading JavaScript from assets.
     */
    @Provides
    @Singleton
    fun provideJsAssetLoader(@ApplicationContext context: Context): JsAssetLoader {
        return JsAssetLoader(context)
    }
}
