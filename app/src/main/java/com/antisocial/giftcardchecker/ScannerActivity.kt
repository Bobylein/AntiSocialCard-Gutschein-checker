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
    private var detectedBarcodeBox: Rect? = null
    private var detectedPinBox: Rect? = null
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
        marketType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(GiftCard.EXTRA_MARKET_TYPE, MarketType::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(GiftCard.EXTRA_MARKET_TYPE) as? MarketType
        } ?: MarketType.REWE

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
        detectedBarcodeBox = boundingBox
        updateUI()
        updateHighlights()
        
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
        detectedPinBox = boundingBox
        updateUI()
        updateHighlights()
        
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
     * Update highlight overlays to show detected barcode and PIN regions.
     */
    private fun updateHighlights() {
        // Hide highlights if no detections
        if (detectedBarcodeBox == null && detectedPinBox == null) {
            binding.barcodeHighlight.visibility = View.GONE
            binding.pinHighlight.visibility = View.GONE
            return
        }

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

        // Calculate how the image is displayed in the preview view
        // PreviewView may scale and crop the image to fit
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val previewAspectRatio = previewWidth.toFloat() / previewHeight.toFloat()
        
        val scaleX: Float
        val scaleY: Float
        val offsetX: Int
        val offsetY: Int
        
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

        // Update barcode highlight
        detectedBarcodeBox?.let { box ->
            val left = (box.left * scaleX + offsetX).toInt()
            val top = (box.top * scaleY + offsetY).toInt()
            val right = (box.right * scaleX + offsetX).toInt()
            val bottom = (box.bottom * scaleY + offsetY).toInt()

            val layoutParams = binding.barcodeHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = left
            layoutParams.topMargin = top
            layoutParams.width = right - left
            layoutParams.height = bottom - top
            binding.barcodeHighlight.layoutParams = layoutParams
            binding.barcodeHighlight.visibility = View.VISIBLE

            Log.d(TAG, "Barcode highlight: image=${imageWidth}x${imageHeight}, preview=${previewWidth}x${previewHeight}, scale=$scaleX/$scaleY, offset=$offsetX/$offsetY")
            Log.d(TAG, "Barcode box: left=${box.left}, top=${box.top}, right=${box.right}, bottom=${box.bottom}")
            Log.d(TAG, "Barcode highlight: left=$left, top=$top, width=${right - left}, height=${bottom - top}")
        } ?: run {
            binding.barcodeHighlight.visibility = View.GONE
        }

        // Update PIN highlight
        detectedPinBox?.let { box ->
            val left = (box.left * scaleX + offsetX).toInt()
            val top = (box.top * scaleY + offsetY).toInt()
            val right = (box.right * scaleX + offsetX).toInt()
            val bottom = (box.bottom * scaleY + offsetY).toInt()

            val layoutParams = binding.pinHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = left
            layoutParams.topMargin = top
            layoutParams.width = right - left
            layoutParams.height = bottom - top
            binding.pinHighlight.layoutParams = layoutParams
            binding.pinHighlight.visibility = View.VISIBLE

            Log.d(TAG, "PIN highlight: image=${imageWidth}x${imageHeight}, preview=${previewWidth}x${previewHeight}, scale=$scaleX/$scaleY, offset=$offsetX/$offsetY")
            Log.d(TAG, "PIN box: left=${box.left}, top=${box.top}, right=${box.right}, bottom=${box.bottom}")
            Log.d(TAG, "PIN highlight: left=$left, top=$top, width=${right - left}, height=${bottom - top}")
        } ?: run {
            binding.pinHighlight.visibility = View.GONE
        }
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
                        // Store image dimensions and rotation for highlight positioning
                        imageAnalysisWidth = mlKitWidth
                        imageAnalysisHeight = mlKitHeight
                        imageRotationDegrees = rotationDegrees
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
                                // The PIN field is typically a distinct rectangular area, separate from the barcode
                                // Use a much larger region to cover the separate PIN field area - search from left edge of image
                                // Calculate region to cover entire left half of image, up to barcode
                                val maxSearchWidth = barcodeBox.left.coerceAtLeast(200) // Search up to barcode or at least 200px
                                val extendedWidth = maxSearchWidth.coerceAtMost(imageWidth / 2) // Up to half image width
                                
                                // Use full height from top to bottom of barcode area, plus some margin
                                val extendedHeight = (barcodeBox.height() * 2).coerceAtMost(imageHeight)
                                
                                // Position region starting from left edge of image, extending to barcode
                                // IMPORTANT: Region must be STRICTLY to the LEFT of barcode (pinRegionRight = barcodeBox.left)
                                pinRegionLeft = 0  // Start from left edge of image
                                pinRegionTop = (barcodeBox.top - barcodeBox.height() / 2).coerceAtLeast(0)  // Start above barcode
                                pinRegionRight = barcodeBox.left  // Stop at barcode's left edge - do NOT include barcode
                                pinRegionBottom = (barcodeBox.bottom + barcodeBox.height() / 2).coerceAtMost(imageHeight)  // Extend below barcode
                                
                                Log.d(TAG, "REWE TYPE_1: PIN in separate field to the LEFT of barcode")
                                Log.d(TAG, "Search region: LEFT side only (right edge at barcode left: ${barcodeBox.left})")
                                Log.d(TAG, "Extended search region: width=$extendedWidth (from 0 to ${barcodeBox.left}), height=$extendedHeight")
                                Log.d(TAG, "Region coordinates: left=$pinRegionLeft, top=$pinRegionTop, right=$pinRegionRight, bottom=$pinRegionBottom")
                                Log.d(TAG, "Region size: ${pinRegionRight - pinRegionLeft} x ${pinRegionBottom - pinRegionTop}")
                            }
                            ReweCardType.TYPE_2 -> {
                                // TYPE_2: Aztec barcode - PIN location same as TYPE_1 (in separate field to the LEFT)
                                val maxSearchWidth = barcodeBox.left.coerceAtLeast(200)
                                val extendedWidth = maxSearchWidth.coerceAtMost(imageWidth / 2)
                                val extendedHeight = (barcodeBox.height() * 2).coerceAtMost(imageHeight)
                                
                                pinRegionLeft = 0
                                pinRegionTop = (barcodeBox.top - barcodeBox.height() / 2).coerceAtLeast(0)
                                pinRegionRight = barcodeBox.left
                                pinRegionBottom = (barcodeBox.bottom + barcodeBox.height() / 2).coerceAtMost(imageHeight)
                                
                                Log.d(TAG, "REWE TYPE_2: Aztec barcode - PIN in separate field to the LEFT of barcode")
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
                    else -> {
                        // For other cards (ALDI, etc.): PIN is typically in the upper-right corner
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
                
                // Convert Image to Bitmap, rotate to match InputImage orientation, then crop
                val bitmap = imageToBitmap(mediaImage)
                if (bitmap != null) {
                    // Rotate bitmap to match InputImage orientation (ML Kit coordinate system)
                    // This way we can use ML Kit coordinates directly without transformation
                    val rotatedBitmap = if (rotationDegrees != 0) {
                        rotateBitmap(bitmap, rotationDegrees)
                    } else {
                        bitmap
                    }
                    
                    try {
                        
                        // Now use ML Kit coordinates directly (they're relative to the rotated bitmap)
                        val cropLeft = finalLeft.coerceIn(0, rotatedBitmap.width - 1)
                        val cropTop = finalTop.coerceIn(0, rotatedBitmap.height - 1)
                        val cropWidth = pinRegion.width().coerceIn(1, rotatedBitmap.width - cropLeft)
                        val cropHeight = pinRegion.height().coerceIn(1, rotatedBitmap.height - cropTop)
                        
                        Log.d(TAG, "Crop region (using ML Kit coordinates directly): left=$cropLeft, top=$cropTop, width=$cropWidth, height=$cropHeight")
                        Log.d(TAG, "Rotated bitmap dimensions: ${rotatedBitmap.width}x${rotatedBitmap.height}")
                        Log.d(TAG, "ML Kit image dimensions: ${imageWidth}x${imageHeight}")
                        
                        // Crop bitmap to PIN region (using ML Kit coordinates directly)
                        val croppedBitmap = Bitmap.createBitmap(
                            rotatedBitmap,
                            cropLeft,
                            cropTop,
                            cropWidth,
                            cropHeight
                        )
                        
                        // Create InputImage from cropped bitmap with rotation 0
                        // The bitmap is already rotated to match ML Kit's coordinate system
                        val croppedImage = InputImage.fromBitmap(croppedBitmap, 0)
                        
                        // Process cropped region for PIN
                        textRecognizer.process(croppedImage)
                            .addOnSuccessListener { visionText ->
                                Log.d(TAG, "=== OCR RESULTS FOR PIN REGION ===")
                                Log.d(TAG, "Text blocks found: ${visionText.textBlocks.size}")
                                val recognizedText = visionText.text
                                Log.d(TAG, "Full recognized text: '$recognizedText'")
                                
                                // Log all text blocks in detail for debugging
                                visionText.textBlocks.forEachIndexed { blockIdx, block ->
                                    Log.d(TAG, "Block $blockIdx: '${block.text}' (${block.lines.size} lines)")
                                    block.lines.forEachIndexed { lineIdx, line ->
                                        Log.d(TAG, "  Line $lineIdx: '${line.text}' (${line.elements.size} elements)")
                                        line.elements.forEachIndexed { elemIdx, element ->
                                            Log.d(TAG, "    Element $elemIdx: '${element.text}' at ${element.boundingBox}")
                                        }
                                    }
                                }
                                
                                val potentialPin = extractPotentialPin(recognizedText)
                                Log.d(TAG, "Extracted PIN: $potentialPin")
                                Log.d(TAG, "===================================")
                                
                                // Clean up bitmaps
                                if (!croppedBitmap.isRecycled) {
                                    croppedBitmap.recycle()
                                }
                                if (rotatedBitmap != bitmap && !rotatedBitmap.isRecycled) {
                                    rotatedBitmap.recycle()
                                }
                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                                
                                if (potentialPin != null) {
                                    // Validate PIN before returning it - reject false positives
                                    if (isLikelyFalsePositive(potentialPin)) {
                                        Log.d(TAG, "Rejected likely false positive PIN from region: $potentialPin")
                                        // For Lidl cards, don't fallback if we get a false positive
                                        if (marketType == MarketType.LIDL) {
                                            Log.d(TAG, "Lidl card: False positive detected, not searching full image")
                                            onComplete(null, null)
                                        } else {
                                            // For other cards, try full image fallback
                                            val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                            processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
                                        }
                                    } else {
                                        Log.d(TAG, "PIN detected (region-of-interest): $potentialPin")
                                        onComplete(potentialPin, pinRegion)
                                    }
                                } else {
                                    Log.d(TAG, "No PIN found in region")
                                    Log.d(TAG, "Recognized text in region was: '$recognizedText'")
                                    
                                    // For REWE cards, try a wider search area as fallback
                                    // Search the entire left half of the image if initial region didn't work
                                    if (marketType == MarketType.REWE) {
                                        Log.d(TAG, "REWE card: No PIN found in initial left region, trying wider left-side search")
                                        
                                        // Create a much wider region covering entire left half
                                        val widerLeftRegion = Rect(
                                            0,  // Start from left edge
                                            0,  // Start from top
                                            barcodeBox.left,  // Up to barcode
                                            imageHeight  // Full height
                                        )
                                        
                                        // Crop and process wider region
                                        try {
                                            // Use ML Kit coordinates directly (bitmap is already rotated)
                                            val widerCropLeft = widerLeftRegion.left.coerceIn(0, rotatedBitmap.width - 1)
                                            val widerCropTop = widerLeftRegion.top.coerceIn(0, rotatedBitmap.height - 1)
                                            val widerCropWidth = widerLeftRegion.width().coerceIn(1, rotatedBitmap.width - widerCropLeft)
                                            val widerCropHeight = widerLeftRegion.height().coerceIn(1, rotatedBitmap.height - widerCropTop)
                                            
                                            val widerCroppedBitmap = Bitmap.createBitmap(
                                                rotatedBitmap,
                                                widerCropLeft,
                                                widerCropTop,
                                                widerCropWidth,
                                                widerCropHeight
                                            )
                                            
                                            // Create InputImage from cropped bitmap with rotation 0
                                            // The bitmap is already rotated to match ML Kit's coordinate system
                                            val widerCroppedImage = InputImage.fromBitmap(widerCroppedBitmap, 0)
                                            
                                            textRecognizer.process(widerCroppedImage)
                                                .addOnSuccessListener { widerVisionText ->
                                                    val widerText = widerVisionText.text
                                                    Log.d(TAG, "Wider region OCR result: '$widerText'")
                                                    val widerPin = extractPotentialPin(widerText)
                                                    
                                                    // Clean up bitmaps
                                                    if (!widerCroppedBitmap.isRecycled) {
                                                        widerCroppedBitmap.recycle()
                                                    }
                                                    
                                                    if (widerPin != null && !isLikelyFalsePositive(widerPin)) {
                                                        Log.d(TAG, "PIN found in wider region: $widerPin")
                                                        onComplete(widerPin, widerLeftRegion)
                                                    } else {
                                                        Log.d(TAG, "REWE card: No valid PIN found even in wider left region")
                                                        onComplete(null, null)
                                                    }
                                                }
                                                .addOnFailureListener { e ->
                                                    Log.e(TAG, "Wider region OCR failed", e)
                                                    // Clean up bitmaps
                                                    if (!widerCroppedBitmap.isRecycled) {
                                                        widerCroppedBitmap.recycle()
                                                    }
                                                    onComplete(null, null)
                                                }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error processing wider region", e)
                                            onComplete(null, null)
                                        }
                                    } else {
                                        Log.d(TAG, "Trying full image as fallback for non-REWE card")
                                        val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                        processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Text recognition on PIN region failed", e)
                                // Clean up bitmaps
                                if (!croppedBitmap.isRecycled) {
                                    croppedBitmap.recycle()
                                }
                                if (rotatedBitmap != bitmap && !rotatedBitmap.isRecycled) {
                                    rotatedBitmap.recycle()
                                }
                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                                // Fallback to full image (but not for REWE cards to avoid false positives)
                                if (marketType == MarketType.REWE) {
                                    Log.d(TAG, "REWE card: OCR failed in left region, not searching full image to avoid false positives")
                                    onComplete(null, null)
                                } else {
                                    val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                    processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
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
         * ML Kit coordinates are relative to the rotated image, but the bitmap is in native orientation.
         * This function transforms the coordinates to match the bitmap orientation.
         * 
         * For portrait mode (90° or 270°), the image is rotated, so coordinates need to be transformed.
         */
        private fun transformCoordinatesForRotation(
            left: Int, top: Int, width: Int, height: Int,
            bitmapWidth: Int, bitmapHeight: Int,
            rotationDegrees: Int
        ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
            return when (rotationDegrees) {
                90 -> {
                    // 90° clockwise rotation (portrait mode)
                    // ML Kit rotates image 90° clockwise, so coordinates need inverse transformation
                    // In ML Kit rotated space: (left, top) maps to native bitmap's (bitmapHeight - top - height, left)
                    // For 90° clockwise rotation:
                    // - ML Kit's left (x) becomes native's top (y), but inverted: bitmapHeight - top - height
                    // - ML Kit's top (y) becomes native's left (x): left
                    val newLeft = bitmapHeight - top - height
                    val newTop = left
                    val newWidth = height
                    val newHeight = width
                    Pair(Pair(newLeft, newTop), Pair(newWidth, newHeight))
                }
                180 -> {
                    // 180° rotation
                    // (x, y) -> (bitmapWidth - x - w, bitmapHeight - y - h)
                    val newLeft = bitmapWidth - left - width
                    val newTop = bitmapHeight - top - height
                    Pair(Pair(newLeft, newTop), Pair(width, height))
                }
                270 -> {
                    // 270° clockwise rotation (portrait mode, other orientation)
                    // ML Kit rotates image 270° clockwise (or 90° counter-clockwise)
                    // Inverse transformation: (top, bitmapWidth - left - width)
                    val newLeft = top
                    val newTop = bitmapWidth - left - width
                    val newWidth = height
                    val newHeight = width
                    Pair(Pair(newLeft, newTop), Pair(newWidth, newHeight))
                }
                else -> {
                    // 0° or no rotation: coordinates stay the same
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

