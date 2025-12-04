package com.antisocial.giftcardchecker.di

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for ML Kit OCR and barcode scanning dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {

    /**
     * Provides a singleton instance of BarcodeScanner for barcode detection.
     * Configured to detect EAN_13 (REWE) and CODE_128 (ALDI) formats.
     */
    @Provides
    @Singleton
    fun provideBarcodeScanner(): BarcodeScanner {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,    // REWE cards
                Barcode.FORMAT_CODE_128,   // ALDI cards
                Barcode.FORMAT_EAN_8,      // Some gift cards
                Barcode.FORMAT_CODE_39,    // Some gift cards
                Barcode.FORMAT_QR_CODE     // Potential future support
            )
            .build()
        return BarcodeScanning.getClient(options)
    }

    /**
     * Provides a singleton instance of TextRecognizer for PIN OCR.
     * Uses Latin script recognition optimized for numeric text.
     */
    @Provides
    @Singleton
    fun provideTextRecognizer(): TextRecognizer {
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
