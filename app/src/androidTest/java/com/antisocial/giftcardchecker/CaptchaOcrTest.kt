package com.antisocial.giftcardchecker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antisocial.giftcardchecker.ocr.OnnxCaptchaOcr
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Instrumented test for the Hugging Face captcha OCR model.
 * 
 * Tests the techietrader/captcha_ocr model from:
 * https://huggingface.co/techietrader/captcha_ocr
 * 
 * Uses captcha images from the TestCaptchas folder.
 * 
 * Note: For running on a device/emulator, test images should be copied to the device
 * or placed in androidTest/assets/ folder. This test tries to find images in multiple locations.
 */
@RunWith(AndroidJUnit4::class)
class CaptchaOcrTest {
    
    private lateinit var context: Context
    private lateinit var ocr: OnnxCaptchaOcr
    private val testCaptchaFiles = listOf("aldi_captcha1.png", "aldi_captcha2.png")
    
    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        ocr = OnnxCaptchaOcr(context)
        
        // Initialize the OCR model
        val initialized = ocr.initialize()
        if (!initialized) {
            throw AssertionError(
                "Failed to initialize ONNX model. " +
                "Please ensure the model file (captcha_ocr.onnx) is in app/src/main/assets/"
            )
        }
    }
    
    @After
    fun tearDown() {
        ocr.close()
    }
    
    /**
     * Helper method to load a test captcha image from various possible locations.
     * Tries:
     * 1. androidTest/assets/ folder
     * 2. Absolute path (for local development)
     * 3. Test files copied to device cache
     */
    private fun loadTestCaptcha(filename: String): Bitmap? {
        // Try 1: Load from androidTest/assets
        try {
            val inputStream: InputStream? = try {
                context.assets.open(filename)
            } catch (e: Exception) {
                null
            }
            if (inputStream != null) {
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap != null) {
                    android.util.Log.d("CaptchaOcrTest", "Loaded $filename from assets")
                    return bitmap
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("CaptchaOcrTest", "Could not load $filename from assets: ${e.message}")
        }
        
        // Try 2: Absolute path (for local development/testing)
        val absolutePath = File("/home/kevin/AndroidStudioProjects/AntiSocialCard-Checker/TestCaptchas", filename)
        if (absolutePath.exists()) {
            val bitmap = BitmapFactory.decodeFile(absolutePath.absolutePath)
            if (bitmap != null) {
                android.util.Log.d("CaptchaOcrTest", "Loaded $filename from absolute path")
                return bitmap
            }
        }
        
        // Try 3: Device cache directory (if files were copied there)
        val cacheFile = File(context.cacheDir, filename)
        if (cacheFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bitmap != null) {
                android.util.Log.d("CaptchaOcrTest", "Loaded $filename from cache")
                return bitmap
            }
        }
        
        android.util.Log.w("CaptchaOcrTest", "Could not find $filename in any location")
        return null
    }
    
    @Test
    fun testAldiCaptcha1() {
        val bitmap = loadTestCaptcha("aldi_captcha1.png")
        assert(bitmap != null) {
            "Test captcha file not found: aldi_captcha1.png. " +
            "Please copy test images to androidTest/assets/ or ensure TestCaptchas folder is accessible."
        }
        
        val recognizedText = ocr.recognize(bitmap!!)
        
        assert(recognizedText != null) {
            "Failed to recognize text from aldi_captcha1.png"
        }
        
        assert(recognizedText!!.isNotEmpty()) {
            "Recognized text is empty for aldi_captcha1.png"
        }
        
        println("aldi_captcha1.png recognized as: '$recognizedText'")
        
        // Log the result (you can verify manually)
        android.util.Log.d("CaptchaOcrTest", "aldi_captcha1.png -> '$recognizedText'")
    }
    
    @Test
    fun testAldiCaptcha2() {
        val bitmap = loadTestCaptcha("aldi_captcha2.png")
        assert(bitmap != null) {
            "Test captcha file not found: aldi_captcha2.png. " +
            "Please copy test images to androidTest/assets/ or ensure TestCaptchas folder is accessible."
        }
        
        val recognizedText = ocr.recognize(bitmap!!)
        
        assert(recognizedText != null) {
            "Failed to recognize text from aldi_captcha2.png"
        }
        
        assert(recognizedText!!.isNotEmpty()) {
            "Recognized text is empty for aldi_captcha2.png"
        }
        
        println("aldi_captcha2.png recognized as: '$recognizedText'")
        
        // Log the result (you can verify manually)
        android.util.Log.d("CaptchaOcrTest", "aldi_captcha2.png -> '$recognizedText'")
    }
    
    @Test
    fun testAllCaptchas() {
        println("\n=== Testing all captchas ===")
        var successCount = 0
        var totalCount = 0
        
        testCaptchaFiles.forEach { filename ->
            totalCount++
            try {
                val bitmap = loadTestCaptcha(filename)
                if (bitmap == null) {
                    println("$filename: (file not found)")
                    android.util.Log.w("CaptchaOcrTest", "$filename -> file not found")
                    return@forEach
                }
                
                val recognizedText = ocr.recognize(bitmap)
                if (recognizedText != null && recognizedText.isNotEmpty()) {
                    println("$filename: '$recognizedText'")
                    android.util.Log.d("CaptchaOcrTest", "$filename -> '$recognizedText'")
                    successCount++
                } else {
                    println("$filename: (failed to recognize)")
                    android.util.Log.w("CaptchaOcrTest", "$filename -> recognition failed")
                }
            } catch (e: Exception) {
                println("$filename: ERROR - ${e.message}")
                android.util.Log.e("CaptchaOcrTest", "Error processing $filename", e)
            }
        }
        
        println("Successfully recognized: $successCount/$totalCount captchas")
        android.util.Log.d("CaptchaOcrTest", "Test completed: $successCount/$totalCount successful")
        
        assert(successCount > 0) {
            "No captchas were successfully recognized. Check model setup and image files."
        }
    }
    
    @Test
    fun testModelInitialization() {
        // Test that model loads correctly
        val testOcr = OnnxCaptchaOcr(context)
        val initialized = testOcr.initialize()
        
        assert(initialized) {
            "Model initialization failed. Check that captcha_ocr.onnx is in app/src/main/assets/"
        }
        
        testOcr.close()
    }
}

