package com.antisocial.giftcardchecker.captcha

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CAPTCHA solver using ONNX Runtime for inference.
 *
 * Model specification:
 * - Input: 215x80 grayscale image, normalized to [0,1]
 * - Output: Character sequence (CAPTCHA text)
 */
@Singleton
class CaptchaSolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CaptchaSolver"
        private const val MODEL_PATH = "models/epoch_23.onnx"
        private const val INPUT_WIDTH = 215
        private const val INPUT_HEIGHT = 80

        // Character set for decoding model output
        // Expanded to include lowercase letters (62 chars total)
        // Order assumption: Digits (10) + Lowercase (26) + Uppercase (26)
        private const val CHARSET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        
        // Character set for Tokenizer model (0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ)
        private const val TOKENIZER_CHARSET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        // CTC blank token index (usually 0 or last index)
        // Based on python implementation: BLANK_IDX = len(CHARS) = 62
        private const val CTC_BLANK_INDEX = 62
        
        // Tokenizer special tokens
        private const val TOKEN_EOS = 0
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false
    private var inputName: String = "input"
    private var outputShape: LongArray? = null

    /**
     * Initialize ONNX Runtime session.
     * Call this once before using solve().
     */
    @Synchronized
    fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
            Log.d(TAG, "Initializing ONNX Runtime...")

            ortEnvironment = OrtEnvironment.getEnvironment()

            // Load model from assets
            val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
            Log.d(TAG, "Model loaded: ${modelBytes.size} bytes")

            ortSession = ortEnvironment?.createSession(modelBytes)

            // Log model info
            ortSession?.let { session ->
                val inputs = session.inputNames
                val outputs = session.outputNames
                Log.d(TAG, "Model inputs: $inputs")
                Log.d(TAG, "Model outputs: $outputs")

                // Get actual input name
                if (inputs.isNotEmpty()) {
                    inputName = inputs.first()
                    Log.d(TAG, "Using input name: $inputName")
                }

                // Log input/output info
                session.inputInfo.forEach { (name, info) ->
                    Log.d(TAG, "Input '$name': ${info.info}")
                }
                session.outputInfo.forEach { (name, info) ->
                    Log.d(TAG, "Output '$name': ${info.info}")
                }
            }

            isInitialized = true
            Log.d(TAG, "ONNX Runtime initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX Runtime", e)
            false
        }
    }

    /**
     * Solve CAPTCHA from bitmap image.
     *
     * @param bitmap The CAPTCHA image
     * @return The solved CAPTCHA text, or null if solving failed
     */
    fun solve(bitmap: Bitmap): String? {
        if (!isInitialized) {
            Log.w(TAG, "CaptchaSolver not initialized, attempting initialization...")
            if (!initialize()) {
                Log.e(TAG, "Failed to initialize CaptchaSolver")
                return null
            }
        }

        val session = ortSession ?: run {
            Log.e(TAG, "ONNX session is null")
            return null
        }

        val env = ortEnvironment ?: run {
            Log.e(TAG, "ONNX environment is null")
            return null
        }

        return try {
            Log.d(TAG, "Solving CAPTCHA, input size: ${bitmap.width}x${bitmap.height}")

            // Preprocess image
            val inputArray = preprocess(bitmap)
            Log.d(TAG, "Preprocessed input size: ${inputArray.size}")

            // Create input tensor
            // Shape: [1, 1, 80, 215] (batch, channels, height, width)
            val inputShape = longArrayOf(1, 1, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputArray), inputShape)

            // Run inference
            val inputs = mapOf(inputName to inputTensor)
            val results = session.run(inputs)

            // Get output
            val output = results[0].value
            Log.d(TAG, "Output type: ${output?.javaClass?.simpleName}")

            val text = when (output) {
                is Array<*> -> {
                    // Handle 3D output: [batch, sequence, classes]
                    @Suppress("UNCHECKED_CAST")
                    val outputArray = output as? Array<Array<FloatArray>>
                    if (outputArray != null) {
                        outputShape = longArrayOf(
                            outputArray.size.toLong(),
                            outputArray[0].size.toLong(),
                            outputArray[0][0].size.toLong()
                        )
                        // Check if this is the [1, 26, 95] tokenizer model
                        if (outputArray.size == 1 && outputArray[0].size == 26 && outputArray[0][0].size == 95) {
                            decodeTokenizerOutput(outputArray[0])
                        }
                        // Check for Time-Major format [Time, Batch, Classes] e.g. [53, 1, 63]
                        else if (outputArray.size > 1 && outputArray[0].size == 1) {
                            decodeTimeMajorOutput(outputArray)
                        }
                        else {
                            // Assume standard [Batch, Time, Classes] but we have Batch=1 so outputArray[0] is [Time, Classes]
                            decodeCtcOutput(outputArray[0])
                        }
                    } else {
                        // Try 2D output: [batch, sequence]
                        @Suppress("UNCHECKED_CAST")
                        val output2D = output as? Array<LongArray>
                        if (output2D != null) {
                            decodeDirectOutput(output2D[0])
                        } else {
                            Log.e(TAG, "Unknown output format in Array<*>")
                            null
                        }
                    }
                }
                is FloatArray -> {
                    // Flat output, needs reshaping
                    decodeFlat(output)
                }
                else -> {
                    Log.e(TAG, "Unexpected output type: ${output?.javaClass}")
                    null
                }
            }

            // Cleanup
            inputTensor.close()
            results.close()

            Log.d(TAG, "CAPTCHA solved: $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error solving CAPTCHA", e)
            null
        }
    }

    /**
     * Preprocess image: resize to 215x80, convert to grayscale, normalize to [0,1].
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        // Resize to target dimensions
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)

        // Convert to grayscale
        val grayscale = toGrayscale(resized)

        // Extract pixel values and normalize to [0, 1]
        val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        grayscale.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        val floatArray = FloatArray(INPUT_WIDTH * INPUT_HEIGHT)
        for (i in pixels.indices) {
            // Extract grayscale value (R, G, B are equal in grayscale)
            val gray = pixels[i] and 0xFF
            // Normalize to [-1, 1] as per model requirements: (pixel / 255 - 0.5) / 0.5
            floatArray[i] = (gray / 255.0f - 0.5f) / 0.5f
        }

        // Cleanup temporary bitmaps
        if (resized != bitmap) resized.recycle()
        if (grayscale != resized) grayscale.recycle()

        return floatArray
    }

    /**
     * Convert bitmap to grayscale.
     */
    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val grayscale = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        val paint = Paint()

        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return grayscale
    }

    /**
     * Decode Tokenizer output: [sequence_length, num_classes] -> text
     * Logic: Argmax -> Stop at EOS (0) -> Map 1..62 to Charset
     */
    private fun decodeTokenizerOutput(output: Array<FloatArray>): String {
        val sequenceLength = output.size
        val result = StringBuilder()

        for (t in 0 until sequenceLength) {
            // Find argmax for this timestep
            var maxIndex = 0
            var maxValue = output[t][0]
            val numClasses = output[t].size
            
            for (c in 1 until numClasses) {
                if (output[t][c] > maxValue) {
                    maxValue = output[t][c]
                    maxIndex = c
                }
            }
            
            // Stop at EOS token
            if (maxIndex == TOKEN_EOS) {
                break
            }
            
            // Map index to character
            // Mapping: 1..62 -> TOKENIZER_CHARSET[0..61]
            val charIndex = maxIndex - 1
            if (charIndex >= 0 && charIndex < TOKENIZER_CHARSET.length) {
                result.append(TOKENIZER_CHARSET[charIndex])
            } else {
                Log.w(TAG, "Index $maxIndex out of bounds for charset")
            }
        }

        return result.toString()
    }

    /**
     * Decode Time-Major output: [Time, Batch=1, Classes] -> text
     * Flattens to [Time, Classes] and calls CTC decoder
     */
    private fun decodeTimeMajorOutput(output: Array<Array<FloatArray>>): String {
        val sequenceLength = output.size
        // We know batch size is 1, so we take the first element of the second dimension
        val flattened = Array(sequenceLength) { t ->
            output[t][0]
        }
        return decodeCtcOutput(flattened)
    }

    /**
     * Decode CTC output: [sequence_length, num_classes] -> text
     */
    private fun decodeCtcOutput(output: Array<FloatArray>): String {
        val sequenceLength = output.size
        val numClasses = output[0].size

        val result = StringBuilder()
        var previousIndex = -1

        for (t in 0 until sequenceLength) {
            // Find argmax for this timestep
            var maxIndex = 0
            var maxValue = output[t][0]
            for (c in 1 until numClasses) {
                if (output[t][c] > maxValue) {
                    maxValue = output[t][c]
                    maxIndex = c
                }
            }

            // CTC decoding: skip blank tokens and repeated characters
            if (maxIndex != CTC_BLANK_INDEX && maxIndex != previousIndex) {
                // If blank is at end (62), then 0-61 maps directly to CHARSET
                val charIndex = maxIndex
                if (charIndex >= 0 && charIndex < CHARSET.length) {
                    val char = CHARSET[charIndex]
                    result.append(char)
                }
            }
            previousIndex = maxIndex
        }

        return result.toString().take(6)
    }

    /**
     * Decode direct output (character indices).
     */
    private fun decodeDirectOutput(output: LongArray): String {
        val result = StringBuilder()
        for (idx in output) {
            val charIndex = idx.toInt()
            if (charIndex >= 0 && charIndex < CHARSET.length) {
                result.append(CHARSET[charIndex])
            }
        }
        return result.toString()
    }

    /**
     * Decode flat output by trying to infer the shape.
     */
    private fun decodeFlat(output: FloatArray): String? {
        // Try common shapes
        val possibleClasses = listOf(CHARSET.length + 1, 37, 63, 11) // +1 for blank

        for (numClasses in possibleClasses) {
            if (output.size % numClasses == 0) {
                val sequenceLength = output.size / numClasses
                Log.d(TAG, "Trying shape: sequence=$sequenceLength, classes=$numClasses")

                // Reshape to 2D
                val reshaped = Array(sequenceLength) { t ->
                    FloatArray(numClasses) { c ->
                        output[t * numClasses + c]
                    }
                }

                val result = decodeCtcOutput(reshaped)
                if (result.isNotEmpty()) {
                    return result
                }
            }
        }

        Log.e(TAG, "Could not decode flat output of size ${output.size}")
        return null
    }

    /**
     * Check if the solver is ready to use.
     */
    fun isReady(): Boolean = isInitialized && ortSession != null

    /**
     * Release ONNX resources.
     */
    fun release() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ONNX resources", e)
        }
        ortSession = null
        ortEnvironment = null
        isInitialized = false
    }
}
