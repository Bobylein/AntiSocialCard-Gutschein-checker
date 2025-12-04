package com.antisocial.giftcardchecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.antisocial.giftcardchecker.databinding.ActivityScannerBinding
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Activity for scanning gift card barcodes using CameraX and ML Kit.
 * Supports multiple barcode formats and passes the scanned barcode to PinEntryActivity.
 */
class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var textRecognizer: TextRecognizer
    
    private var marketType: MarketType = MarketType.REWE
    private var detectedBarcode: String? = null
    private var detectedPin: String? = null
    private val isProcessing = AtomicBoolean(false)
    private var autoNavigateEnabled = true

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.scanner_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get market type from intent
        marketType = intent.getSerializableExtra(GiftCard.EXTRA_MARKET_TYPE) as? MarketType 
            ?: MarketType.REWE

        setupBarcodeScanner()
        setupUI()
        checkCameraPermission()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupBarcodeScanner() {
        // Use default client which supports ALL barcode formats
        // This is more reliable than specifying individual formats
        barcodeScanner = BarcodeScanning.getClient()
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Log.d(TAG, "Barcode scanner and text recognizer initialized")
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnManualEntry.setOnClickListener {
            autoNavigateEnabled = false
            navigateToBalanceCheck()
        }

        binding.btnUseBarcode.setOnClickListener {
            autoNavigateEnabled = false
            navigateToBalanceCheck()
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image analysis use case for barcode and text scanning
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, CombinedAnalyzer { barcode, pin ->
                        runOnUiThread {
                            barcode?.let { onBarcodeDetected(it) }
                            pin?.let { onPinDetected(it) }
                        }
                    })
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, R.string.error_camera, Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodeDetected(barcode: String) {
        // Extract only the last 20 digits (gift card numbers are typically 19-20 digits)
        val processedBarcode = extractLast20Digits(barcode)
        
        if (detectedBarcode == processedBarcode) return // Already showing this barcode

        detectedBarcode = processedBarcode
        updateUI()
        
        Log.d(TAG, "Original barcode: $barcode -> Processed: $processedBarcode")
        
        // Check if we have both barcode and PIN, then auto-navigate
        checkAndNavigate()
    }
    
    private fun onPinDetected(pin: String) {
        if (detectedPin == pin) return // Already showing this PIN
        
        detectedPin = pin
        updateUI()
        
        Log.d(TAG, "PIN detected: $pin")
        
        // Check if we have both barcode and PIN, then auto-navigate
        checkAndNavigate()
    }
    
    private fun updateUI() {
        if (detectedBarcode != null) {
            binding.tvDetectedBarcode.text = "Barcode: $detectedBarcode"
            binding.cardDetected.visibility = View.VISIBLE
        }
        
        if (detectedPin != null) {
            binding.tvDetectedPin.text = "PIN: $detectedPin"
            binding.tvDetectedPin.visibility = View.VISIBLE
        }
        
        // Update button text if we have both
        if (detectedBarcode != null && detectedPin != null) {
            binding.btnUseBarcode.text = "Daten überprüfen"
        } else if (detectedBarcode != null) {
            binding.btnUseBarcode.text = "Daten überprüfen"
        }
    }
    
    private fun checkAndNavigate() {
        if (autoNavigateEnabled && detectedBarcode != null && detectedPin != null) {
            // Auto-navigate to confirmation screen after a short delay
            handler.postDelayed({
                if (autoNavigateEnabled) {
                    navigateToConfirmation()
                }
            }, 1500) // 1.5 second delay
        }
    }
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private fun extractLast20Digits(barcode: String): String {
        // Remove any non-digit characters and take the last 20 digits
        val digitsOnly = barcode.filter { it.isDigit() }
        return if (digitsOnly.length > 20) {
            digitsOnly.takeLast(20)
        } else {
            digitsOnly
        }
    }

    private fun navigateToConfirmation() {
        val cardNumber = detectedBarcode ?: ""
        val pin = detectedPin ?: ""
        
        if (cardNumber.isEmpty()) {
            Toast.makeText(this, "Bitte scannen Sie den Barcode", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, ConfirmationActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_CARD_NUMBER, cardNumber)
            putExtra(GiftCard.EXTRA_PIN, pin)
            putExtra(GiftCard.EXTRA_MARKET_TYPE, marketType)
        }
        startActivity(intent)
        finish()
    }
    
    private fun navigateToBalanceCheck() {
        navigateToConfirmation()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        barcodeScanner.close()
        textRecognizer.close()
    }

    /**
     * Image analyzer for simultaneous barcode and text (PIN) detection using ML Kit.
     */
    private inner class CombinedAnalyzer(
        private val onResults: (barcode: String?, pin: String?) -> Unit
    ) : ImageAnalysis.Analyzer {

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            // Use compareAndSet for thread-safe processing flag
            if (!isProcessing.compareAndSet(false, true)) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                Log.d(TAG, "Analyzing image with rotation: $rotationDegrees")

                val image = InputImage.fromMediaImage(
                    mediaImage,
                    rotationDegrees
                )

                var barcodeResult: String? = null
                var pinResult: String? = null
                var completedTasks = 0
                val totalTasks = 2

                fun checkCompletion() {
                    completedTasks++
                    if (completedTasks == totalTasks) {
                        isProcessing.set(false)
                        imageProxy.close()
                        onResults(barcodeResult, pinResult)
                    }
                }

                // Process barcode
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        Log.d(TAG, "Found ${barcodes.size} barcodes")
                        for (barcode in barcodes) {
                            val format = barcode.format
                            val value = barcode.rawValue
                            Log.d(TAG, "Barcode detected: format=$format, value=$value")
                            
                            value?.let {
                                if (isValidBarcode(it)) {
                                    Log.d(TAG, "Valid barcode: $it")
                                    barcodeResult = extractLast20Digits(it)
                                    return@addOnSuccessListener
                                } else {
                                    Log.d(TAG, "Invalid barcode filtered: $it")
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Barcode scanning failed", e)
                    }
                    .addOnCompleteListener {
                        checkCompletion()
                    }

                // Process text (for PIN detection)
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        Log.d(TAG, "Text recognition completed, blocks: ${visionText.textBlocks.size}")
                        val recognizedText = visionText.text
                        val potentialPin = extractPotentialPin(recognizedText)
                        
                        if (potentialPin != null) {
                            Log.d(TAG, "PIN detected: $potentialPin")
                            pinResult = potentialPin
                        } else {
                            Log.d(TAG, "No PIN found in text: ${recognizedText.take(100)}")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        checkCompletion()
                    }
            } else {
                Log.w(TAG, "Media image is null")
                isProcessing.set(false)
                imageProxy.close()
            }
        }

        private fun isValidBarcode(value: String): Boolean {
            // Accept any non-empty barcode - ML Kit already validates formats
            // Gift cards can have various formats
            val isValid = value.isNotEmpty() && value.length >= 4
            Log.d(TAG, "isValidBarcode($value) = $isValid")
            return isValid
        }
        
        private fun extractPotentialPin(text: String): String? {
            // Look for 4-8 digit sequences that could be PINs
            val pinPattern = Regex("""\b(\d{4,8})\b""")
            val matches = pinPattern.findAll(text)
            
            // Prefer 4-digit sequences (most common PIN length)
            for (match in matches) {
                val value = match.value
                if (value.length == 4) {
                    return value
                }
            }
            
            // If no 4-digit found, return first match
            return matches.firstOrNull()?.value
        }
    }

    companion object {
        private const val TAG = "ScannerActivity"
    }
}

