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
    
    private var marketType: MarketType = MarketType.REWE
    private var detectedBarcode: String? = null
    private val isProcessing = AtomicBoolean(false)

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
        Log.d(TAG, "Barcode scanner initialized with all formats")
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnManualEntry.setOnClickListener {
            navigateToPinEntry(null)
        }

        binding.btnUseBarcode.setOnClickListener {
            detectedBarcode?.let { barcode ->
                navigateToPinEntry(barcode)
            }
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

            // Image analysis use case for barcode scanning
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode ->
                        runOnUiThread {
                            onBarcodeDetected(barcode)
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
        if (detectedBarcode == barcode) return // Already showing this barcode

        detectedBarcode = barcode
        binding.tvDetectedBarcode.text = barcode
        binding.cardDetected.visibility = View.VISIBLE

        // Vibrate to give feedback
        // Note: Vibration requires VIBRATE permission
    }

    private fun navigateToPinEntry(barcode: String?) {
        val intent = Intent(this, PinEntryActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_CARD_NUMBER, barcode ?: "")
            putExtra(GiftCard.EXTRA_MARKET_TYPE, marketType)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    /**
     * Image analyzer for barcode detection using ML Kit.
     */
    private inner class BarcodeAnalyzer(
        private val onBarcodeDetected: (String) -> Unit
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
                                    onBarcodeDetected(it)
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
                        isProcessing.set(false)
                        imageProxy.close()
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
    }

    companion object {
        private const val TAG = "ScannerActivity"
    }
}

