package com.antisocial.giftcardchecker.captcha

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility for extracting and downloading CAPTCHA images.
 */
@Singleton
class CaptchaImageExtractor @Inject constructor() {

    companion object {
        private const val TAG = "CaptchaImageExtractor"
        private const val CONNECTION_TIMEOUT = 10000 // 10 seconds
        private const val READ_TIMEOUT = 10000 // 10 seconds
    }

    /**
     * Download CAPTCHA image from URL.
     *
     * @param imageUrl The URL of the CAPTCHA image
     * @return The downloaded bitmap, or null if download failed
     */
    suspend fun downloadCaptchaImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading CAPTCHA image: $imageUrl")

            val url = URL(imageUrl)
            val connection = url.openConnection().apply {
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                setRequestProperty("Accept", "image/*")
                
                // Inject cookies from WebView to maintain session
                val cookies = CookieManager.getInstance().getCookie(imageUrl)
                if (cookies != null) {
                    setRequestProperty("Cookie", cookies)
                }
            }

            connection.connect()
            val inputStream = connection.getInputStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (bitmap != null) {
                Log.d(TAG, "Downloaded CAPTCHA image: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e(TAG, "Failed to decode CAPTCHA image")
            }

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading CAPTCHA image", e)
            null
        }
    }

    /**
     * Decode base64 image data to bitmap.
     *
     * @param base64Data The base64 encoded image (with or without data URI prefix)
     * @return The decoded bitmap, or null if decoding failed
     */
    fun decodeBase64Image(base64Data: String): Bitmap? {
        return try {
            // Remove data URI prefix if present
            val pureBase64 = if (base64Data.contains(",")) {
                base64Data.substring(base64Data.indexOf(",") + 1)
            } else {
                base64Data
            }

            val imageBytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            if (bitmap != null) {
                Log.d(TAG, "Decoded base64 image: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e(TAG, "Failed to decode base64 image")
            }

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 image", e)
            null
        }
    }
}
