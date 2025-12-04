package com.antisocial.giftcardchecker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ONNX-based CAPTCHA OCR using the techietrader/captcha_ocr model from Hugging Face.
 * 
 * This class handles loading and running inference with the ONNX model for captcha recognition.
 * 
 * Model: https://huggingface.co/techietrader/captcha_ocr
 */
class OnnxCaptchaOcr(private val context: Context) {
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val modelInputName = "image" // Common input name, may need adjustment
    private val modelOutputName = "text" // Common output name, may need adjustment
    
    companion object {
        private const val TAG = "OnnxCaptchaOcr"
        private const val MODEL_FILENAME = "captcha_ocr.onnx"
        // Model expects specific input size - adjust based on model requirements
        // Common sizes: 128x64, 200x50, 300x100
        private const val INPUT_WIDTH = 200
        private const val INPUT_HEIGHT = 50
        private const val INPUT_CHANNELS = 3
    }
    
    /**
     * Initialize the ONNX runtime environment and load the model.
     * The model file should be placed in assets/captcha_ocr.onnx
     */
    fun initialize(): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            
            // Try to load from assets first
            val modelFile = loadModelFromAssets()
            if (modelFile == null) {
                Log.e(TAG, "Model file not found in assets. Please download the model.")
                return false
            }
            
            val sessionOptions = OrtSession.SessionOptions()
            ortSession = ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
            
            // Log model input/output info
            logModelInfo()
            
            Log.d(TAG, "ONNX model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX model", e)
            false
        }
    }
    
    /**
     * Load model from assets folder
     */
    private fun loadModelFromAssets(): File? {
        return try {
            val inputStream = context.assets.open(MODEL_FILENAME)
            val tempFile = File(context.cacheDir, MODEL_FILENAME)
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets", e)
            null
        }
    }
    
    /**
     * Log model input/output information for debugging
     */
    private fun logModelInfo() {
        try {
            val inputNames = ortSession!!.inputNames
            val outputNames = ortSession!!.outputNames
            
            Log.d(TAG, "Model inputs: ${inputNames.size}")
            inputNames.forEach { name ->
                val info = ortSession!!.inputInfo[name]
                Log.d(TAG, "  Input: $name, Info: $info")
            }
            
            Log.d(TAG, "Model outputs: ${outputNames.size}")
            outputNames.forEach { name ->
                val info = ortSession!!.outputInfo[name]
                Log.d(TAG, "  Output: $name, Info: $info")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log model info", e)
        }
    }
    
    /**
     * Recognize text from a captcha image bitmap.
     * 
     * @param bitmap The captcha image bitmap
     * @return The recognized text, or null if recognition failed
     */
    fun recognize(bitmap: Bitmap): String? {
        if (ortSession == null) {
            Log.e(TAG, "Model not initialized. Call initialize() first.")
            return null
        }
        
        return try {
            // Preprocess image
            val preprocessedImage = preprocessImage(bitmap)
            
            // Create input tensor
            val inputTensor = OnnxTensor.createTensor(
                ortEnv!!,
                preprocessedImage
            )
            
            // Run inference
            val inputs = mapOf(modelInputName to inputTensor)
            val outputs = ortSession!!.run(inputs)
            
            // Extract output
            val outputTensor = outputs[0].value as Array<*>
            val recognizedText = postprocessOutput(outputTensor)
            
            // Clean up
            inputTensor.close()
            outputs.close()
            
            recognizedText
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed", e)
            null
        }
    }
    
    /**
     * Recognize text from an image file path.
     */
    fun recognizeFromFile(imagePath: String): String? {
        return try {
            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image from path: $imagePath")
                return null
            }
            recognize(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image from file", e)
            null
        }
    }
    
    /**
     * Preprocess image: resize, normalize, convert to float array
     */
    private fun preprocessImage(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        // Resize bitmap to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(
            bitmap,
            INPUT_WIDTH,
            INPUT_HEIGHT,
            true
        )
        
        // Convert to float array [1, 3, height, width] format (batch, channels, height, width)
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        resizedBitmap.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
        
        val imageArray = Array(1) { // batch size = 1
            Array(INPUT_CHANNELS) { // channels (RGB)
                Array(INPUT_HEIGHT) { // height
                    FloatArray(INPUT_WIDTH) // width
                }
            }
        }
        
        // Normalize pixel values to [0, 1] range
        for (y in 0 until INPUT_HEIGHT) {
            for (x in 0 until INPUT_WIDTH) {
                val pixel = pixels[y * INPUT_WIDTH + x]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                
                imageArray[0][0][y][x] = r // R channel
                imageArray[0][1][y][x] = g // G channel
                imageArray[0][2][y][x] = b // B channel
            }
        }
        
        return imageArray
    }
    
    /**
     * Postprocess model output to extract text.
     * This is model-specific and may need adjustment based on the actual model output format.
     */
    private fun postprocessOutput(output: Array<*>): String {
        // The output format depends on the model architecture
        // Common formats:
        // 1. Character-level predictions: [batch, sequence_length, num_classes]
        // 2. CTC output: [batch, sequence_length, num_classes]
        // 3. Direct text output (if model includes decoder)
        
        // For now, try to handle common output formats
        return try {
            when {
                // If output is a 2D array (sequence_length x num_classes)
                output[0] is Array<*> -> {
                    val sequence = output[0] as Array<*>
                    val charIndices = sequence.map { arr ->
                        if (arr is Array<*>) {
                            // Find index with highest probability
                            arr.mapIndexed { index, value ->
                                index to (value as? Float ?: 0f)
                            }.maxByOrNull { it.second }?.first ?: -1
                        } else {
                            -1
                        }
                    }
                    // Convert indices to characters (assuming standard character set)
                    charIndices.filter { it >= 0 && it < 36 } // 0-9, A-Z = 36 chars
                        .map { idx ->
                            when {
                                idx < 10 -> '0' + idx
                                else -> 'A' + (idx - 10)
                            }
                        }
                        .joinToString("")
                }
                // If output is a 1D array of character indices
                output is Array<*> && output[0] is Number -> {
                    output.map { (it as? Number)?.toInt() ?: -1 }
                        .filter { it >= 0 && it < 36 }
                        .map { idx ->
                            when {
                                idx < 10 -> '0' + idx
                                else -> 'A' + (idx - 10)
                            }
                        }
                        .joinToString("")
                }
                else -> {
                    Log.w(TAG, "Unexpected output format: ${output::class.simpleName}")
                    output.joinToString("")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to postprocess output", e)
            output.joinToString("")
        }
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX resources", e)
        }
    }
}


