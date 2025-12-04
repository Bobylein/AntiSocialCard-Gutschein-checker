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
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.antisocial.giftcardchecker.databinding.ActivityScannerBinding
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import com.antisocial.giftcardchecker.utils.getSerializableExtraCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer
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
    private var cameraControl: Camera2CameraControl? = null
    private var camera: Camera? = null
    
    private var marketType: MarketType = MarketType.REWE
    private var detectedBarcode: String? = null
    private var detectedPin: String? = null
    private var pinSearchRegion: Rect? = null // Store expected PIN search region (BLUE)
    private var barcodeSearchRegion: Rect? = null // Store expected barcode search region (RED)
    private var imageAnalysisWidth: Int = 1920 // Default, will be updated from actual image
    private var imageAnalysisHeight: Int = 1080 // Default, will be updated from actual image
    private var imageRotationDegrees: Int = 0 // Will be updated from actual image
    private val isProcessing = AtomicBoolean(false)
    private var autoNavigateEnabled = false // Disabled - require user confirmation
    private var currentZoomRatio: Float = 1.0f
    
    /**
     * Card types for different markets - different card designs have different PIN locations
     */
    enum class ReweCardType {
        TYPE_1,  // PIN in separate field to the left of barcode
        TYPE_2   // Aztec barcode - uses first 13 digits of scanned code
    }
    
    enum class LidlCardType {
        TYPE_1  // PIN in separate field to the upper-right of barcode
        // Other types can be added here as needed
    }
    
    private var detectedReweCardType: ReweCardType? = null
    private var detectedLidlCardType: LidlCardType? = null

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
        marketType = intent.getSerializableExtraCompat<MarketType>(GiftCard.EXTRA_MARKET_TYPE)
            ?: MarketType.REWE

        setupBarcodeScanner()
        setupUI()
        checkCameraPermission()

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize zoom gesture handler
        setupZoomGestureHandler()
    }

    private fun setupBarcodeScanner() {
        // Configure barcode scanner for better distance scanning
        // Using all formats for maximum compatibility
        val options = com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ALL_FORMATS
            )
            .build()
        
        barcodeScanner = BarcodeScanning.getClient(options)
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Log.d(TAG, "Barcode scanner and text recognizer initialized with optimized settings")
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
            // User confirms the detected barcode and PIN
            if (detectedBarcode != null) {
                navigateToConfirmation()
            } else {
                Toast.makeText(this, "Bitte scannen Sie den Barcode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupZoomGestureHandler() {
        // Setup pinch-to-zoom gesture handler
        // The actual camera info will be available after camera is bound
        binding.previewView.setOnTouchListener { view, event ->
            if (event.pointerCount == 2 && camera != null) {
                handlePinchToZoom(event)
                true
            } else {
                false
            }
        }
    }

    private var lastFingerSpacing: Float = 0f

    private fun handlePinchToZoom(event: android.view.MotionEvent) {
        val cameraInfo = camera?.cameraInfo ?: return
        
        when (event.action) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                lastFingerSpacing = getFingerSpacing(event)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val newFingerSpacing = getFingerSpacing(event)
                if (newFingerSpacing > 0 && lastFingerSpacing > 0) {
                    val zoomState = cameraInfo.zoomState.value
                    zoomState?.let { state ->
                        val delta = (newFingerSpacing - lastFingerSpacing) / 100f
                        val newZoom = (currentZoomRatio + delta).coerceIn(
                            state.minZoomRatio,
                            state.maxZoomRatio
                        )
                        setZoomRatio(newZoom)
                        lastFingerSpacing = newFingerSpacing
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, 
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                lastFingerSpacing = 0f
            }
        }
    }

    private fun getFingerSpacing(event: android.view.MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun setZoomRatio(zoomRatio: Float) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
        currentZoomRatio = zoomRatio
        Log.d(TAG, "Zoom set to: ${zoomRatio}x")
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

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Configure Camera2Interop for focus control
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_ON
                )
            val previewWithFocus = previewBuilder
                .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image analysis use case with optimized resolution for better distance scanning
            // Use YUV format for better quality
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, CombinedAnalyzer { barcode, pin, barcodeBox, pinBox ->
                        runOnUiThread {
                            barcode?.let { barcodeValue ->
                                onBarcodeDetected(barcodeValue, barcodeBox)
                            }
                            pin?.let { pinValue ->
                                onPinDetected(pinValue, pinBox)
                            }
                        }
                    })
                }

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    previewWithFocus,
                    imageAnalyzer
                )

                // Get Camera2CameraControl for zoom
                camera?.let {
                    val camera2Control = Camera2CameraControl.from(it.cameraControl)
                    cameraControl = camera2Control
                    
                    // Set initial zoom if supported
                    val zoomState = it.cameraInfo.zoomState.value
                    zoomState?.let { state ->
                        currentZoomRatio = state.zoomRatio
                        if (state.maxZoomRatio > 1.0f) {
                            Log.d(TAG, "Camera supports zoom: ${state.minZoomRatio}x to ${state.maxZoomRatio}x (current: ${state.zoomRatio}x)")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, R.string.error_camera, Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodeDetected(barcode: String, boundingBox: Rect?) {
        // Extract card number based on market type
        val processedBarcode = extractCardNumber(barcode, marketType)
        
        if (detectedBarcode == processedBarcode) return // Already showing this barcode

        detectedBarcode = processedBarcode
        // Note: We show expected search regions, not detected bounding boxes
        updateUI()
        
        Log.d(TAG, "Original barcode: $barcode -> Processed: $processedBarcode")
    }
    
    private fun onPinDetected(pin: String, boundingBox: Rect?) {
        if (detectedPin == pin) return // Already showing this PIN
        
        // Validate PIN before accepting it
        if (!isValidPin(pin)) {
            Log.d(TAG, "Rejected invalid PIN: $pin")
            return
        }
        
        // If we already have a PIN, only replace it if the new one is more likely to be correct
        if (detectedPin != null) {
            if (isLikelyFalsePositive(pin)) {
                Log.d(TAG, "Rejected likely false positive PIN: $pin (keeping existing: $detectedPin)")
                return
            }
            // Only replace if new PIN seems more reliable (e.g., from a better region)
            Log.d(TAG, "Replacing PIN: $detectedPin -> $pin")
        }
        
        detectedPin = pin
        // Note: We show expected search regions, not detected bounding boxes
        updateUI()
        
        Log.d(TAG, "PIN detected: $pin")
    }
    
    /**
     * Check if a PIN is valid (basic format check).
     */
    private fun isValidPin(pin: String): Boolean {
        // Must be exactly 4 digits
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            return false
        }
        return true
    }
    
    /**
     * Check if a PIN is likely a false positive (e.g., phone numbers, common patterns).
     */
    private fun isLikelyFalsePositive(pin: String): Boolean {
        // Common false positives to reject
        val falsePositives = setOf(
            "0800",  // German toll-free phone number prefix
            "0000",  // Common placeholder/default
            "1111",  // Common test/placeholder
            "1234",  // Common test/placeholder
            "0001",  // Common placeholder
        )
        
        if (falsePositives.contains(pin)) {
            return true
        }
        
        // Reject if all digits are the same (e.g., "2222", "9999")
        if (pin.all { it == pin[0] }) {
            return true
        }
        
        // Reject sequential patterns (e.g., "1234", "4321")
        val digits = pin.map { it.toString().toInt() }
        val isSequential = digits.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) == 1 }
        if (isSequential) {
            return true
        }
        
        return false
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

    /**
     * Update highlight overlays to show EXPECTED barcode and PIN search regions.
     * - RED: Expected barcode region
     * - BLUE: Expected PIN region
     */
    private fun updateHighlights() {
        // Get preview view dimensions
        val previewView = binding.previewView
        val previewWidth = previewView.width
        val previewHeight = previewView.height

        if (previewWidth == 0 || previewHeight == 0) {
            // Preview not ready yet, try again after layout
            previewView.post { updateHighlights() }
            return
        }

        // Use actual image analysis dimensions (ML Kit coordinate system)
        val imageWidth = imageAnalysisWidth
        val imageHeight = imageAnalysisHeight
        val rotation = imageRotationDegrees

        // Calculate how the image is displayed in the preview view
        // PreviewView may scale and crop the image to fit
        // Note: PreviewView automatically handles rotation, so we need to account for it
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val previewAspectRatio = previewWidth.toFloat() / previewHeight.toFloat()
        
        val scaleX: Float
        val scaleY: Float
        val offsetX: Int
        val offsetY: Int
        
        // For portrait rotations (90°/270°), ML Kit swaps width/height
        // But PreviewView displays in the same orientation as ML Kit coordinates
        // So we can use the ML Kit dimensions directly
        if (imageAspectRatio > previewAspectRatio) {
            // Image is wider - fit to width, letterbox top/bottom
            scaleX = previewWidth.toFloat() / imageWidth.toFloat()
            scaleY = scaleX // Maintain aspect ratio
            offsetX = 0
            offsetY = ((previewHeight - imageHeight * scaleY) / 2).toInt()
        } else {
            // Image is taller - fit to height, letterbox left/right
            scaleY = previewHeight.toFloat() / imageHeight.toFloat()
            scaleX = scaleY // Maintain aspect ratio
            offsetX = ((previewWidth - imageWidth * scaleX) / 2).toInt()
            offsetY = 0
        }

        // Transform function for a single region
        fun transformRegion(region: Rect): android.graphics.Rect {
            val left = (region.left * scaleX + offsetX).toInt()
            val top = (region.top * scaleY + offsetY).toInt()
            val right = (region.right * scaleX + offsetX).toInt()
            val bottom = (region.bottom * scaleY + offsetY).toInt()
            return android.graphics.Rect(left, top, right, bottom)
        }

        // Update barcode search region highlight (RED) - where we expect the barcode
        barcodeSearchRegion?.let { region ->
            val transformed = transformRegion(region)

            val layoutParams = binding.barcodeHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = transformed.left.coerceAtLeast(0)
            layoutParams.topMargin = transformed.top.coerceAtLeast(0)
            layoutParams.width = transformed.width().coerceAtLeast(1)
            layoutParams.height = transformed.height().coerceAtLeast(1)
            binding.barcodeHighlight.layoutParams = layoutParams
            binding.barcodeHighlight.visibility = View.VISIBLE

            Log.d(TAG, "Barcode search region (RED): left=${transformed.left}, top=${transformed.top}, width=${transformed.width()}, height=${transformed.height()}, rotation=$rotation")
        } ?: run {
            binding.barcodeHighlight.visibility = View.GONE
        }

        // Update PIN search region highlight (BLUE) - where we expect the PIN
        pinSearchRegion?.let { region ->
            val transformed = transformRegion(region)

            val layoutParams = binding.pinHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = transformed.left.coerceAtLeast(0)
            layoutParams.topMargin = transformed.top.coerceAtLeast(0)
            layoutParams.width = transformed.width().coerceAtLeast(1)
            layoutParams.height = transformed.height().coerceAtLeast(1)
            binding.pinHighlight.layoutParams = layoutParams
            binding.pinHighlight.visibility = View.VISIBLE

            // Also log barcode position for comparison
            barcodeSearchRegion?.let { barcodeRegion ->
                val barcodeTransformed = transformRegion(barcodeRegion)
                Log.d(TAG, "=== PIN OVERLAY DEBUG ===")
                Log.d(TAG, "Barcode overlay: top=${barcodeTransformed.top}, bottom=${barcodeTransformed.bottom}")
                Log.d(TAG, "PIN overlay: top=${transformed.top}, bottom=${transformed.bottom}")
                Log.d(TAG, "PIN should be ABOVE barcode: PIN.bottom (${transformed.bottom}) < Barcode.top (${barcodeTransformed.top})")
                Log.d(TAG, "Actual: PIN.bottom=${transformed.bottom}, Barcode.top=${barcodeTransformed.top}")
                Log.d(TAG, "PIN region ML Kit: top=${region.top}, bottom=${region.bottom}")
                Log.d(TAG, "Barcode region ML Kit: top=${barcodeRegion.top}, bottom=${barcodeRegion.bottom}")
                Log.d(TAG, "ML Kit: PIN.bottom (${region.bottom}) < Barcode.top (${barcodeRegion.top}) = ${region.bottom < barcodeRegion.top}")
                Log.d(TAG, "Image dimensions: ${imageWidth}x${imageHeight}, Preview: ${previewWidth}x${previewHeight}, Rotation: $rotation")
                Log.d(TAG, "Scale: X=$scaleX, Y=$scaleY, Offset: X=$offsetX, Y=$offsetY")
                Log.d(TAG, "========================")
            }

            Log.d(TAG, "PIN search region (BLUE): left=${transformed.left}, top=${transformed.top}, width=${transformed.width()}, height=${transformed.height()}, rotation=$rotation")
        } ?: run {
            binding.pinHighlight.visibility = View.GONE
        }
        
        // Hide the debug highlight (no longer needed)
        binding.pinSearchRegionHighlight.visibility = View.GONE
    }
    
    // Auto-navigation removed - user must confirm manually via button
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    /**
     * Extract card number from barcode based on market type and card type.
     * - REWE TYPE_1: last 13 digits
     * - REWE TYPE_2: first 13 digits (Aztec barcode)
     * - LIDL: last 20 digits
     * - ALDI: last 20 digits
     */
    private fun extractCardNumber(barcode: String, marketType: MarketType, cardType: ReweCardType? = null): String {
        // Remove any non-digit characters
        val digitsOnly = barcode.filter { it.isDigit() }
        
        return when (marketType) {
            MarketType.REWE -> {
                when (cardType) {
                    ReweCardType.TYPE_2 -> {
                        // REWE TYPE_2: first 13 digits (Aztec barcode)
                        if (digitsOnly.length >= 13) {
                            digitsOnly.take(13)
                        } else {
                            digitsOnly
                        }
                    }
                    else -> {
                        // REWE TYPE_1: last 13 digits (default)
                        if (digitsOnly.length >= 13) {
                            digitsOnly.takeLast(13)
                        } else {
                            digitsOnly
                        }
                    }
                }
            }
            MarketType.LIDL -> {
                // LIDL: last 20 digits (always take last 20 if available)
                if (digitsOnly.length > 20) {
                    digitsOnly.takeLast(20)
                } else {
                    digitsOnly
                }
            }
            MarketType.ALDI -> {
                // ALDI: last 20 digits (always take last 20 if available)
                if (digitsOnly.length > 20) {
                    digitsOnly.takeLast(20)
                } else {
                    digitsOnly
                }
            }
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
     * Improved for better distance scanning and PIN detection in upper-right corner.
     */
    private inner class CombinedAnalyzer(
        private val onResults: (barcode: String?, pin: String?, barcodeBox: Rect?, pinBox: Rect?) -> Unit
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

                // Get the dimensions that ML Kit uses for coordinates
                // When rotation is 90° or 270°, ML Kit swaps width/height
                // ML Kit coordinates are relative to the rotated coordinate system
                val mlKitWidth = if (rotationDegrees == 90 || rotationDegrees == 270) {
                    imageProxy.height
                } else {
                    imageProxy.width
                }
                val mlKitHeight = if (rotationDegrees == 90 || rotationDegrees == 270) {
                    imageProxy.width
                } else {
                    imageProxy.height
                }
                
                Log.d(TAG, "ImageProxy dimensions: ${imageProxy.width}x${imageProxy.height}")
                Log.d(TAG, "ML Kit coordinate dimensions: ${mlKitWidth}x${mlKitHeight} (rotation: $rotationDegrees)")

                // Store dimensions for highlight positioning
                imageAnalysisWidth = mlKitWidth
                imageAnalysisHeight = mlKitHeight
                imageRotationDegrees = rotationDegrees

                var barcodeResult: String? = null
                var barcodeBoundingBox: Rect? = null
                var pinResult: String? = null
                var barcodeCompleted = false
                var pinCompleted = false

                var pinBoundingBox: Rect? = null

                fun checkCompletion() {
                    if (barcodeCompleted && pinCompleted) {
                        isProcessing.set(false)
                        imageProxy.close()
                        onResults(barcodeResult, pinResult, barcodeBoundingBox, pinBoundingBox)
                    }
                }

                // Process barcode first to get bounding box for PIN region detection
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        Log.d(TAG, "Found ${barcodes.size} barcodes")
                        for (barcode in barcodes) {
                            val format = barcode.format
                            val value = barcode.rawValue
                            val boundingBox = barcode.boundingBox
                            Log.d(TAG, "Barcode detected: format=$format, value=$value")
                            
                            value?.let {
                                if (isValidBarcode(it)) {
                                    Log.d(TAG, "Valid barcode: $it")
                                    
                                    // Detect REWE card type based on barcode format
                                    val reweCardType = if (marketType == MarketType.REWE) {
                                        detectReweCardType(format)
                                    } else {
                                        null
                                    }
                                    detectedReweCardType = reweCardType
                                    
                                    barcodeResult = extractCardNumber(it, marketType, reweCardType)
                                    barcodeBoundingBox = boundingBox
                                    
                                    // Set barcode search region (RED highlight) to show detected barcode position
                                    barcodeSearchRegion = boundingBox
                                    runOnUiThread { updateHighlights() }
                                    
                                    // Process PIN detection with region-of-interest if barcode found
                                    if (barcodeBoundingBox != null) {
                                        processPinWithRegionOfInterest(
                                            mediaImage,
                                            rotationDegrees,
                                            barcodeBoundingBox!!,
                                            mlKitWidth,
                                            mlKitHeight,
                                            marketType
                                        ) { pin, pinBox ->
                                            pinResult = pin
                                            pinBoundingBox = pinBox
                                            pinCompleted = true
                                            checkCompletion()
                                        }
                                    } else {
                                        // Fallback to full image if no bounding box
                                        processPinFullImageWithBox(image) { pin, pinBox ->
                                            pinResult = pin
                                            pinBoundingBox = pinBox
                                            pinCompleted = true
                                            checkCompletion()
                                        }
                                    }
                                    barcodeCompleted = true
                                    checkCompletion()
                                    return@addOnSuccessListener
                                } else {
                                    Log.d(TAG, "Invalid barcode filtered: $it")
                                }
                            }
                        }
                        
                        // No valid barcode found, process PIN on full image
                        barcodeCompleted = true
                        if (barcodeResult == null) {
                            processPinFullImage(image) { pin ->
                                pinResult = pin
                                pinBoundingBox = null
                                pinCompleted = true
                                checkCompletion()
                            }
                        }
                        checkCompletion()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Barcode scanning failed", e)
                        barcodeCompleted = true
                        // Try PIN detection on full image as fallback
                        processPinFullImage(image) { pin ->
                            pinResult = pin
                            pinBoundingBox = null
                            pinCompleted = true
                            checkCompletion()
                        }
                        checkCompletion()
                    }
            } else {
                Log.w(TAG, "Media image is null")
                isProcessing.set(false)
                imageProxy.close()
            }
        }

        /**
         * Process PIN detection on full image (fallback).
         */
        private fun processPinFullImage(
            image: InputImage,
            onComplete: (String?) -> Unit
        ) {
            // For full image, we don't have a specific bounding box
            processPinFullImageWithBox(image) { pin, _ -> onComplete(pin) }
        }

        /**
         * Process PIN detection on full image with bounding box support.
         */
        private fun processPinFullImageWithBox(
            image: InputImage,
            onComplete: (String?, Rect?) -> Unit
        ) {
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    Log.d(TAG, "Text recognition completed, blocks: ${visionText.textBlocks.size}")
                    val recognizedText = visionText.text
                    val potentialPin = extractPotentialPin(recognizedText)
                    
                    if (potentialPin != null) {
                        // Validate PIN before accepting it - reject false positives
                        if (isLikelyFalsePositive(potentialPin)) {
                            Log.d(TAG, "Rejected likely false positive PIN from full image: $potentialPin")
                            onComplete(null, null)
                        } else {
                            Log.d(TAG, "PIN detected (full image): $potentialPin")
                            // Try to find the PIN's bounding box in the text blocks
                            val pinBox = findPinBoundingBox(visionText, potentialPin)
                            onComplete(potentialPin, pinBox)
                        }
                    } else {
                        Log.d(TAG, "No PIN found in text: ${recognizedText.take(100)}")
                        onComplete(null, null)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    onComplete(null, null)
                }
        }

        /**
         * Find the bounding box of the detected PIN in the vision text.
         */
        private fun findPinBoundingBox(visionText: com.google.mlkit.vision.text.Text, pin: String): Rect? {
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        if (element.text.contains(pin)) {
                            return element.boundingBox
                        }
                    }
                }
            }
            return null
        }

        /**
         * Detect REWE card type based on barcode format.
         * TYPE_2 uses Aztec barcode format.
         */
        private fun detectReweCardType(barcodeFormat: Int): ReweCardType {
            // TYPE_2 uses Aztec barcode format
            return if (barcodeFormat == Barcode.FORMAT_AZTEC) {
                ReweCardType.TYPE_2
            } else {
                ReweCardType.TYPE_1
            }
        }
        
        /**
         * Detect Lidl card type based on barcode position and card layout.
         * For now, defaults to TYPE_1. Can be enhanced with visual detection later.
         */
        private fun detectLidlCardType(@Suppress("UNUSED_PARAMETER") barcodeBox: Rect, @Suppress("UNUSED_PARAMETER") imageWidth: Int, @Suppress("UNUSED_PARAMETER") imageHeight: Int): LidlCardType {
            // For now, default to TYPE_1 (PIN in separate field to upper-right)
            // Future: Could analyze card layout, barcode position, or other visual features
            // to automatically detect card type
            return LidlCardType.TYPE_1
        }
        
        /**
         * Process PIN detection with region-of-interest.
         * PIN location depends on market type and card type.
         */
        private fun processPinWithRegionOfInterest(
            mediaImage: Image,
            rotationDegrees: Int,
            barcodeBox: Rect,
            imageWidth: Int,
            imageHeight: Int,
            marketType: MarketType,
            onComplete: (String?, Rect?) -> Unit
        ) {
            try {
                // Calculate region based on market type and card type
                val regionWidth = (barcodeBox.width() * 0.4f).toInt().coerceAtLeast(100)
                val regionHeight = (barcodeBox.height() * 0.5f).toInt().coerceAtLeast(50)
                
                val pinRegionLeft: Int
                val pinRegionTop: Int
                val pinRegionRight: Int
                val pinRegionBottom: Int
                
                when (marketType) {
                    MarketType.REWE -> {
                        // Use already detected REWE card type
                        val cardType = detectedReweCardType ?: ReweCardType.TYPE_1
                        
                        when (cardType) {
                            ReweCardType.TYPE_1 -> {
                                // TYPE_1: PIN is in a separate field to the LEFT of the barcode
                                // 
                                // IMPORTANT: REWE cards are designed to be scanned with the card held
                                // horizontally (landscape). When the phone is in portrait mode (90° rotation),
                                // the "left side of the card" maps to the TOP of the ML Kit coordinate system.
                                //
                                // We use a barcode-relative region that works regardless of rotation:
                                // The PIN field is to the left of the barcode on the physical card.
                                // In ML Kit coordinates (which are rotation-corrected):
                                // - For 90° rotation: "left of barcode" = above the barcode's bounding box
                                // - For 0° rotation: "left of barcode" = to the left of barcode's bounding box
                                //
                                // Strategy: Search the area to the left AND above the barcode to handle
                                // different physical card orientations.
                                
                                // Calculate region based on barcode position - much more reliable
                                val barcodeWidth = barcodeBox.width()
                                val barcodeHeight = barcodeBox.height()
                                
                                // For 90° or 270° rotation (portrait phone, landscape card), 
                                // "left of barcode" appears as "above barcode" in ML Kit coords
                                // For 0° or 180° rotation, "left of barcode" is actually left
                                val isPortraitMode = rotationDegrees == 90 || rotationDegrees == 270
                                
                                if (isPortraitMode) {
                                    // In portrait mode with landscape card: PIN is ABOVE barcode in ML Kit coords
                                    // Search a region above the barcode
                                    val searchHeight = (barcodeHeight * 2).coerceAtMost(barcodeBox.top)
                                    pinRegionLeft = (barcodeBox.left - barcodeWidth / 2).coerceAtLeast(0)
                                    pinRegionTop = (barcodeBox.top - searchHeight).coerceAtLeast(0)
                                    pinRegionRight = (barcodeBox.right + barcodeWidth / 2).coerceAtMost(imageWidth)
                                    pinRegionBottom = barcodeBox.top
                                    
                                    Log.d(TAG, "REWE TYPE_1 (portrait mode): PIN search ABOVE barcode in ML Kit coords")
                                } else {
                                    // In landscape mode: PIN is to the LEFT of barcode in ML Kit coords
                                    val searchWidth = (barcodeWidth * 2).coerceAtMost(barcodeBox.left)
                                    pinRegionLeft = (barcodeBox.left - searchWidth).coerceAtLeast(0)
                                    pinRegionTop = (barcodeBox.top - barcodeHeight / 2).coerceAtLeast(0)
                                    pinRegionRight = barcodeBox.left
                                    pinRegionBottom = (barcodeBox.bottom + barcodeHeight / 2).coerceAtMost(imageHeight)
                                    
                                    Log.d(TAG, "REWE TYPE_1 (landscape mode): PIN search LEFT of barcode in ML Kit coords")
                                }
                                
                                Log.d(TAG, "Rotation: $rotationDegrees, isPortraitMode: $isPortraitMode")
                                Log.d(TAG, "Barcode box: left=${barcodeBox.left}, top=${barcodeBox.top}, right=${barcodeBox.right}, bottom=${barcodeBox.bottom}")
                                Log.d(TAG, "Search region: left=$pinRegionLeft, top=$pinRegionTop, right=$pinRegionRight, bottom=$pinRegionBottom")
                            }
                            ReweCardType.TYPE_2 -> {
                                // TYPE_2: Aztec barcode - PIN location is in separate field to the LEFT
                                // Apply same rotation-aware logic, but with a gap of 2x pin area height
                                val barcodeWidth = barcodeBox.width()
                                val barcodeHeight = barcodeBox.height()
                                val isPortraitMode = rotationDegrees == 90 || rotationDegrees == 270
                                
                                // Gap between barcode and PIN area = 2 times the PIN area height
                                val gapSize = (regionHeight * 2f).toInt()
                                
                                if (isPortraitMode) {
                                    // PIN is ABOVE barcode in ML Kit coords (portrait phone, landscape card)
                                    // Add gap of 1.5x pin area height between barcode and PIN search region
                                    val searchHeight = (barcodeHeight * 2).coerceAtMost((barcodeBox.top - gapSize).coerceAtLeast(0))
                                    pinRegionLeft = (barcodeBox.left - barcodeWidth / 2).coerceAtLeast(0)
                                    pinRegionBottom = (barcodeBox.top - gapSize).coerceAtLeast(0)
                                    pinRegionTop = (pinRegionBottom - searchHeight).coerceAtLeast(0)
                                    pinRegionRight = (barcodeBox.right + barcodeWidth / 2).coerceAtMost(imageWidth)
                                    
                                    Log.d(TAG, "REWE TYPE_2 (portrait mode): Aztec barcode - PIN search ABOVE barcode with gap=$gapSize")
                                } else {
                                    // PIN is to the LEFT of barcode in ML Kit coords
                                    // Add gap of 2x pin area width between barcode and PIN search region
                                    val gapWidth = (regionWidth * 2f).toInt()
                                    val searchWidth = (barcodeWidth * 2).coerceAtMost((barcodeBox.left - gapWidth).coerceAtLeast(0))
                                    pinRegionRight = (barcodeBox.left - gapWidth).coerceAtLeast(0)
                                    pinRegionLeft = (pinRegionRight - searchWidth).coerceAtLeast(0)
                                    pinRegionTop = (barcodeBox.top - barcodeHeight / 2).coerceAtLeast(0)
                                    pinRegionBottom = (barcodeBox.bottom + barcodeHeight / 2).coerceAtMost(imageHeight)
                                    
                                    Log.d(TAG, "REWE TYPE_2 (landscape mode): Aztec barcode - PIN search LEFT of barcode with gap=$gapWidth")
                                }
                            }
                        }
                    }
                    MarketType.LIDL -> {
                        // Detect Lidl card type
                        val cardType = detectLidlCardType(barcodeBox, imageWidth, imageHeight)
                        detectedLidlCardType = cardType
                        
                        when (cardType) {
                            LidlCardType.TYPE_1 -> {
                                // TYPE_1: PIN is in a separate field to the UPPER-RIGHT of the barcode
                                // The PIN field is typically a distinct rectangular area above and to the right of barcode
                                // Use a larger region to cover the separate PIN field area
                                val extendedWidth = (regionWidth * 2).toInt().coerceAtMost(imageWidth / 2)
                                val extendedHeight = (regionHeight * 2).toInt().coerceAtMost(imageHeight / 2)
                                
                                // Position region starting from barcode's upper-right corner, extending upward and rightward
                                pinRegionLeft = barcodeBox.right - (extendedWidth / 2)
                                pinRegionTop = (barcodeBox.top - extendedHeight).coerceAtLeast(0)
                                pinRegionRight = barcodeBox.right + (extendedWidth / 2).coerceAtMost(imageWidth - barcodeBox.right)
                                pinRegionBottom = barcodeBox.top + (extendedHeight / 2)
                                Log.d(TAG, "Lidl TYPE_1: PIN in separate field to the UPPER-RIGHT of barcode")
                                Log.d(TAG, "Extended search region: width=$extendedWidth, height=$extendedHeight")
                            }
                        }
                    }
                    MarketType.ALDI -> {
                        // ALDI: PIN is in the upper-right corner area of the card
                        // Position search area towards the corner with less overlap, similar to Lidl
                        val extendedWidth = (regionWidth * 2).coerceAtMost(imageWidth / 2)
                        val extendedHeight = (regionHeight * 2).coerceAtMost(imageHeight / 2)
                        
                        // Position region towards the upper-right corner, extending further right
                        // and upward with minimal overlap on the barcode
                        pinRegionLeft = barcodeBox.right  // Start from right edge of barcode (no overlap)
                        pinRegionTop = (barcodeBox.top - extendedHeight).coerceAtLeast(0)
                        pinRegionRight = (barcodeBox.right + extendedWidth).coerceAtMost(imageWidth)
                        pinRegionBottom = barcodeBox.top  // End at barcode top (no vertical overlap)
                        
                        Log.d(TAG, "ALDI: PIN search in upper-right corner (no overlap with barcode)")
                        Log.d(TAG, "Extended search region: width=$extendedWidth, height=$extendedHeight")
                    }
                    else -> {
                        // For other cards: PIN is typically in the upper-right corner
                        // Upper-right corner: start from right edge of barcode, go left
                        pinRegionLeft = barcodeBox.right - regionWidth
                        pinRegionTop = barcodeBox.top
                        pinRegionRight = barcodeBox.right
                        pinRegionBottom = barcodeBox.top + regionHeight
                        Log.d(TAG, "Other card (${marketType}): Looking for PIN in upper-right corner")
                    }
                }
                
                // Ensure region is within image bounds
                val finalLeft = pinRegionLeft.coerceAtLeast(0)
                val finalTop = pinRegionTop.coerceAtLeast(0)
                val finalRight = pinRegionRight.coerceAtMost(imageWidth)
                val finalBottom = pinRegionBottom.coerceAtMost(imageHeight)
                
                val pinRegion = Rect(
                    finalLeft,
                    finalTop,
                    finalRight,
                    finalBottom
                )
                
                // Store PIN search region for display (in ML Kit coordinate system)
                pinSearchRegion = pinRegion
                
                // Update highlights on UI thread to show PIN search region
                runOnUiThread { updateHighlights() }
                
                Log.d(TAG, "=== PIN REGION DEBUG ===")
                Log.d(TAG, "Market type: $marketType")
                when (marketType) {
                    MarketType.REWE -> detectedReweCardType?.let { Log.d(TAG, "REWE card type: $it") }
                    MarketType.LIDL -> detectedLidlCardType?.let { Log.d(TAG, "Lidl card type: $it") }
                    else -> {}
                }
                Log.d(TAG, "Barcode box: left=${barcodeBox.left}, top=${barcodeBox.top}, right=${barcodeBox.right}, bottom=${barcodeBox.bottom}")
                Log.d(TAG, "Barcode size: ${barcodeBox.width()}x${barcodeBox.height()}")
                Log.d(TAG, "Image size: ${imageWidth}x${imageHeight}")
                Log.d(TAG, "Calculated region width: $regionWidth, height: $regionHeight")
                Log.d(TAG, "PIN region: left=$finalLeft, top=$finalTop, right=$finalRight, bottom=$finalBottom (size: ${pinRegion.width()}x${pinRegion.height()})")
                Log.d(TAG, "Rotation degrees: $rotationDegrees")
                Log.d(TAG, "========================")
                
                // Convert Image to Bitmap - keep in original sensor orientation
                // The text on the card is always upright, so we crop from the original bitmap
                // and transform ML Kit coordinates to match the bitmap's sensor orientation
                val bitmap = imageToBitmap(mediaImage)
                if (bitmap != null) {
                    try {
                        // Transform ML Kit coordinates to original bitmap coordinates
                        // ML Kit coordinates are relative to display orientation, but bitmap is in sensor orientation
                        val (cropCoords, cropSize) = transformCoordinatesForRotation(
                            finalLeft, finalTop, pinRegion.width(), pinRegion.height(),
                            bitmap.width, bitmap.height,
                            rotationDegrees
                        )
                        
                        val cropLeft = cropCoords.first.coerceIn(0, bitmap.width - 1)
                        val cropTop = cropCoords.second.coerceIn(0, bitmap.height - 1)
                        val cropWidth = cropSize.first.coerceIn(1, bitmap.width - cropLeft)
                        val cropHeight = cropSize.second.coerceIn(1, bitmap.height - cropTop)
                        
                        Log.d(TAG, "Crop region (original bitmap): left=$cropLeft, top=$cropTop, width=$cropWidth, height=$cropHeight")
                        Log.d(TAG, "Original bitmap dimensions: ${bitmap.width}x${bitmap.height}")
                        Log.d(TAG, "ML Kit coordinates: left=$finalLeft, top=$finalTop, width=${pinRegion.width()}, height=${pinRegion.height()}, rotation=$rotationDegrees")
                        
                        // Crop from ORIGINAL bitmap (sensor orientation)
                        // The text on the card is upright, but the cropped region might be rotated
                        // due to sensor orientation, so we'll try different rotations in OCR
                        val croppedBitmap = Bitmap.createBitmap(
                            bitmap,
                            cropLeft,
                            cropTop,
                            cropWidth,
                            cropHeight
                        )
                        
                        // Try OCR with multiple orientations
                        // The text is upright on the card, but the cropped region orientation
                        // depends on sensor orientation, so we try different rotations to find the best one
                        processCroppedBitmapWithMultipleOrientations(croppedBitmap) { pin, pinBox ->
                            // Clean up bitmaps
                            if (!croppedBitmap.isRecycled) {
                                croppedBitmap.recycle()
                            }
                            if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                            
                            if (pin != null) {
                                // Validate PIN before returning it - reject false positives
                                if (isLikelyFalsePositive(pin)) {
                                    Log.d(TAG, "Rejected likely false positive PIN from region: $pin")
                                    // For Lidl cards, don't fallback if we get a false positive
                                    if (marketType == MarketType.LIDL) {
                                        Log.d(TAG, "Lidl card: False positive detected, not searching full image")
                                        onComplete(null, null)
                                    } else {
                                        // For other cards, try full image fallback
                                        val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                        processPinFullImageWithBox(fullImage) { fallbackPin, _ -> onComplete(fallbackPin, null) }
                                    }
                                } else {
                                    Log.d(TAG, "PIN detected (region-of-interest): $pin")
                                    onComplete(pin, pinRegion)
                                }
                            } else {
                                // No PIN found, continue with fallback logic below
                                handlePinNotFoundInRegion(mediaImage, rotationDegrees, marketType, barcodeBox, imageWidth, imageHeight, bitmap, pinRegion, onComplete)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cropping bitmap", e)
                        // Clean up original bitmap
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                        // Fallback to full image
                        val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                        processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
                    }
                } else {
                    Log.w(TAG, "Failed to convert image to bitmap, using full image")
                    val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                    processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing PIN region", e)
                // Fallback to full image
                val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
            }
        }
        
        /**
         * Process cropped bitmap with multiple orientations to improve OCR reliability.
         * Tries multiple rotations to find the best text recognition result.
         * Prioritizes orientations that work well for portrait mode (90° counter-clockwise = 270° clockwise).
         */
        private fun processCroppedBitmapWithMultipleOrientations(
            croppedBitmap: Bitmap,
            onComplete: (String?, Rect?) -> Unit
        ) {
            // Try orientations in order of likelihood:
            // 1. 270° (90° counter-clockwise) - works well when phone is rotated
            // 2. 0° (original orientation)
            // 3. 90° (90° clockwise)
            // 4. 180° (upside down)
            val orientations = listOf(270, 0, 90, 180)
            var attemptsCompleted = 0
            var bestPin: String? = null
            var bestPinBox: Rect? = null
            
            fun tryNextOrientation() {
                if (attemptsCompleted >= orientations.size) {
                    // All orientations tried, return best result
                    onComplete(bestPin, bestPinBox)
                    return
                }
                
                val rotation = orientations[attemptsCompleted]
                attemptsCompleted++
                
                // Rotate bitmap if needed
                val orientedBitmap = if (rotation != 0) {
                    rotateBitmap(croppedBitmap, rotation)
                } else {
                    croppedBitmap
                }
                
                // Create InputImage with rotation 0 (bitmap is already rotated)
                val inputImage = InputImage.fromBitmap(orientedBitmap, 0)
                
                Log.d(TAG, "Trying OCR with rotation: ${rotation}°")
                
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val recognizedText = visionText.text
                        Log.d(TAG, "OCR result at ${rotation}°: '$recognizedText'")
                        
                        val potentialPin = extractPotentialPin(recognizedText)
                        
                        // Clean up rotated bitmap if we created one
                        if (rotation != 0 && orientedBitmap != croppedBitmap && !orientedBitmap.isRecycled) {
                            orientedBitmap.recycle()
                        }
                        
                        if (potentialPin != null && !isLikelyFalsePositive(potentialPin)) {
                            // Found a valid PIN, use this result
                            Log.d(TAG, "Found valid PIN at ${rotation}°: $potentialPin")
                            bestPin = potentialPin
                            bestPinBox = null // We don't have precise bounding box for rotated versions
                            // Don't try other orientations, we found a good result
                            onComplete(bestPin, bestPinBox)
                        } else {
                            // Try next orientation
                            tryNextOrientation()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.d(TAG, "OCR failed at ${rotation}°: ${e.message}")
                        // Clean up rotated bitmap if we created one
                        if (rotation != 0 && orientedBitmap != croppedBitmap && !orientedBitmap.isRecycled) {
                            orientedBitmap.recycle()
                        }
                        // Try next orientation
                        tryNextOrientation()
                    }
            }
            
            // Start trying orientations
            tryNextOrientation()
        }
        
        /**
         * Handle case when PIN is not found in the initial region.
         * Uses rotation-aware fallback strategy.
         */
        private fun handlePinNotFoundInRegion(
            mediaImage: Image,
            rotationDegrees: Int,
            marketType: MarketType,
            barcodeBox: Rect,
            imageWidth: Int,
            imageHeight: Int,
            bitmap: Bitmap,
            pinRegion: Rect,
            onComplete: (String?, Rect?) -> Unit
        ) {
            Log.d(TAG, "No PIN found in initial region, trying fallback")
            
            if (marketType == MarketType.REWE) {
                val cardType = detectedReweCardType ?: ReweCardType.TYPE_1
                val isPortraitMode = rotationDegrees == 90 || rotationDegrees == 270
                
                // Try a wider search region around the barcode
                Log.d(TAG, "REWE: Trying wider search region (rotation=$rotationDegrees, isPortrait=$isPortraitMode)")
                
                val barcodeWidth = barcodeBox.width()
                val barcodeHeight = barcodeBox.height()
                
                // Create a larger search region that includes both above AND to the left of barcode
                // This handles the case where the card might be rotated differently than expected
                val widerRegion: Rect
                
                if (isPortraitMode) {
                    // Portrait mode: search both above barcode AND a bit to the sides
                    val searchHeight = (barcodeHeight * 3).coerceAtMost(barcodeBox.top)
                    widerRegion = Rect(
                        (barcodeBox.left - barcodeWidth).coerceAtLeast(0),
                        (barcodeBox.top - searchHeight).coerceAtLeast(0),
                        (barcodeBox.right + barcodeWidth).coerceAtMost(imageWidth),
                        barcodeBox.centerY() // Include part of barcode area
                    )
                } else {
                    // Landscape mode: search to the left of barcode and a bit above/below
                    val searchWidth = (barcodeWidth * 3).coerceAtMost(barcodeBox.left)
                    widerRegion = Rect(
                        (barcodeBox.left - searchWidth).coerceAtLeast(0),
                        (barcodeBox.top - barcodeHeight).coerceAtLeast(0),
                        barcodeBox.centerX(), // Include part of barcode area
                        (barcodeBox.bottom + barcodeHeight).coerceAtMost(imageHeight)
                    )
                }
                
                Log.d(TAG, "Wider search region: left=${widerRegion.left}, top=${widerRegion.top}, right=${widerRegion.right}, bottom=${widerRegion.bottom}")
                
                if (widerRegion.width() > 0 && widerRegion.height() > 0) {
                    try {
                        val (widerCropCoords, widerCropSize) = transformCoordinatesForRotation(
                            widerRegion.left, widerRegion.top, 
                            widerRegion.width(), widerRegion.height(),
                            bitmap.width, bitmap.height,
                            rotationDegrees
                        )
                        
                        val widerCropLeft = widerCropCoords.first.coerceIn(0, bitmap.width - 1)
                        val widerCropTop = widerCropCoords.second.coerceIn(0, bitmap.height - 1)
                        val widerCropWidth = widerCropSize.first.coerceIn(1, bitmap.width - widerCropLeft)
                        val widerCropHeight = widerCropSize.second.coerceIn(1, bitmap.height - widerCropTop)
                        
                        Log.d(TAG, "Wider crop: left=$widerCropLeft, top=$widerCropTop, width=$widerCropWidth, height=$widerCropHeight")
                        
                        val widerCroppedBitmap = Bitmap.createBitmap(
                            bitmap,
                            widerCropLeft,
                            widerCropTop,
                            widerCropWidth,
                            widerCropHeight
                        )
                        
                        processCroppedBitmapWithMultipleOrientations(widerCroppedBitmap) { widerPin, _ ->
                            if (!widerCroppedBitmap.isRecycled) {
                                widerCroppedBitmap.recycle()
                            }
                            
                            if (widerPin != null && !isLikelyFalsePositive(widerPin)) {
                                Log.d(TAG, "PIN found in wider region: $widerPin")
                                onComplete(widerPin, widerRegion)
                            } else {
                                Log.d(TAG, "REWE: No valid PIN found in wider region either")
                                onComplete(null, null)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing wider region", e)
                        onComplete(null, null)
                    }
                } else {
                    Log.d(TAG, "REWE: Wider region has zero dimensions, giving up")
                    onComplete(null, null)
                }
            } else {
                Log.d(TAG, "Trying full image as fallback for non-REWE card")
                val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
            }
        }

        /**
         * Convert Image to Bitmap.
         */
        private fun imageToBitmap(image: Image): Bitmap? {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                100,
                out
            )
            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        /**
         * Transform coordinates based on image rotation.
         * 
         * ML Kit provides coordinates in a "display-corrected" coordinate system where
         * (0,0) is the top-left corner of how the image would be displayed upright on screen.
         * 
         * The native bitmap from the camera sensor is NOT rotated - it's always in sensor orientation.
         * For most phones, the sensor captures images in landscape orientation.
         * 
         * This function transforms ML Kit coordinates back to native bitmap coordinates.
         * 
         * @param left, top, width, height - coordinates in ML Kit's rotated space
         * @param bitmapWidth, bitmapHeight - dimensions of the NATIVE (unrotated) bitmap
         * @param rotationDegrees - the rotation that ML Kit applied to get display coordinates
         * 
         * Note: ML Kit's imageWidth/imageHeight are AFTER rotation correction (swapped for 90°/270°)
         * but bitmapWidth/bitmapHeight are the NATIVE sensor dimensions (before rotation).
         */
        private fun transformCoordinatesForRotation(
            left: Int, top: Int, width: Int, height: Int,
            bitmapWidth: Int, bitmapHeight: Int,
            rotationDegrees: Int
        ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
            // For rotations, we need to consider that:
            // - Native bitmap: bitmapWidth x bitmapHeight (sensor dimensions, typically landscape)
            // - ML Kit coords: after rotation, so dimensions are potentially swapped
            
            // Calculate ML Kit's effective dimensions (what ML Kit thinks the image size is)
            val mlKitWidth: Int
            val mlKitHeight: Int
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                // ML Kit swaps dimensions for portrait rotation
                mlKitWidth = bitmapHeight
                mlKitHeight = bitmapWidth
            } else {
                mlKitWidth = bitmapWidth
                mlKitHeight = bitmapHeight
            }
            
            // Scale factors if ML Kit dimensions differ from bitmap
            // (This handles potential resolution differences)
            val scaleX = bitmapWidth.toFloat() / if (rotationDegrees == 90 || rotationDegrees == 270) mlKitHeight else mlKitWidth
            val scaleY = bitmapHeight.toFloat() / if (rotationDegrees == 90 || rotationDegrees == 270) mlKitWidth else mlKitHeight
            
            Log.d(TAG, "transformCoordinates: rotation=$rotationDegrees, bitmap=${bitmapWidth}x${bitmapHeight}, mlKit=${mlKitWidth}x${mlKitHeight}")
            Log.d(TAG, "transformCoordinates: input left=$left, top=$top, width=$width, height=$height")
            
            return when (rotationDegrees) {
                90 -> {
                    // 90° clockwise: ML Kit's origin is at bitmap's top-right corner (rotated)
                    // ML Kit x -> Bitmap y (from top)
                    // ML Kit y -> Bitmap x (from right edge, inverted)
                    // 
                    // To reverse 90° clockwise rotation:
                    // bitmap_x = mlKit_y (but from the bottom of ML Kit space)
                    // bitmap_y = mlKit_x
                    val newLeft = top  // ML Kit's Y becomes bitmap's X
                    val newTop = bitmapHeight - left - width  // ML Kit's X becomes bitmap's Y (inverted)
                    val newWidth = height  // Swap width/height
                    val newHeight = width
                    Log.d(TAG, "transformCoordinates 90°: output left=$newLeft, top=$newTop, width=$newWidth, height=$newHeight")
                    Pair(Pair(newLeft, newTop), Pair(newWidth, newHeight))
                }
                180 -> {
                    // 180° rotation: both axes inverted
                    val newLeft = bitmapWidth - left - width
                    val newTop = bitmapHeight - top - height
                    Log.d(TAG, "transformCoordinates 180°: output left=$newLeft, top=$newTop")
                    Pair(Pair(newLeft, newTop), Pair(width, height))
                }
                270 -> {
                    // 270° clockwise (= 90° counter-clockwise)
                    // ML Kit's origin is at bitmap's bottom-left corner (rotated)
                    // ML Kit x -> Bitmap y (from bottom, inverted)  
                    // ML Kit y -> Bitmap x (from left)
                    val newLeft = bitmapWidth - top - height  // ML Kit's Y becomes bitmap's X (inverted)
                    val newTop = left  // ML Kit's X becomes bitmap's Y
                    val newWidth = height  // Swap width/height
                    val newHeight = width
                    Log.d(TAG, "transformCoordinates 270°: output left=$newLeft, top=$newTop, width=$newWidth, height=$newHeight")
                    Pair(Pair(newLeft, newTop), Pair(newWidth, newHeight))
                }
                else -> {
                    // 0° or no rotation: coordinates stay the same
                    Log.d(TAG, "transformCoordinates 0°: no change")
                    Pair(Pair(left, top), Pair(width, height))
                }
            }
        }
        
        /**
         * Rotate bitmap by specified degrees.
         */
        private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
            val matrix = Matrix()
            matrix.postRotate(degrees.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        private fun isValidBarcode(value: String): Boolean {
            // Accept any non-empty barcode - ML Kit already validates formats
            // Gift cards can have various formats
            val isValid = value.isNotEmpty() && value.length >= 4
            Log.d(TAG, "isValidBarcode($value) = $isValid")
            return isValid
        }
        
        private fun extractPotentialPin(text: String): String? {
            // Look for exactly 4-digit sequences (all gift card PINs are 4 digits)
            val pinPattern = Regex("""\b(\d{4})\b""")
            val matches = pinPattern.findAll(text)
            
            // Return the first 4-digit sequence found
            return matches.firstOrNull()?.value
        }
    }

    companion object {
        private const val TAG = "ScannerActivity"
    }
}

