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
    private var lastImageWidth: Int = 0
    private var lastImageHeight: Int = 0
    private val isProcessing = AtomicBoolean(false)
    private var autoNavigateEnabled = false // Disabled - require user confirmation
    private var currentZoomRatio: Float = 1.0f

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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Preview use case with focus configuration
            val preview = Preview.Builder()
                .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

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
            // Use YUV format for better quality and set target resolution
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.previewView.display?.rotation ?: Surface.ROTATION_0)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(android.util.Size(1920, 1080)) // Prefer high resolution for better distance scanning
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, CombinedAnalyzer { barcode, pin, barcodeBox, pinBox, imageWidth, imageHeight ->
                        runOnUiThread {
                            lastImageWidth = imageWidth
                            lastImageHeight = imageHeight
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
        // Extract only the last 20 digits (gift card numbers are typically 19-20 digits)
        val processedBarcode = extractLast20Digits(barcode)
        
        if (detectedBarcode == processedBarcode) return // Already showing this barcode

        detectedBarcode = processedBarcode
        detectedBarcodeBox = boundingBox
        Log.d(TAG, "Barcode detected with bounding box: $boundingBox")
        updateUI()
        updateHighlights()
        
        Log.d(TAG, "Original barcode: $barcode -> Processed: $processedBarcode")
    }
    
    private fun onPinDetected(pin: String, boundingBox: Rect?) {
        if (detectedPin == pin) return // Already showing this PIN
        
        detectedPin = pin
        detectedPinBox = boundingBox
        Log.d(TAG, "PIN detected with bounding box: $boundingBox")
        updateUI()
        updateHighlights()
        
        Log.d(TAG, "PIN detected: $pin")
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
            binding.btnUseBarcode.text = "Karte überprüfen"
        } else if (detectedBarcode != null) {
            binding.btnUseBarcode.text = "Karte überprüfen"
        }
    }

    /**
     * Update highlight overlays to show detected barcode and PIN regions.
     * Uses PreviewView's matrix transformation to properly convert image coordinates to screen coordinates.
     */
    private fun updateHighlights() {
        Log.d(TAG, "updateHighlights called - barcodeBox: $detectedBarcodeBox, pinBox: $detectedPinBox")
        
        // Hide highlights if no detections
        if (detectedBarcodeBox == null && detectedPinBox == null) {
            binding.barcodeHighlight.visibility = View.GONE
            binding.pinHighlight.visibility = View.GONE
            Log.d(TAG, "No bounding boxes, hiding highlights")
            return
        }

        // Get preview view dimensions
        val previewView = binding.previewView
        val previewWidth = previewView.width
        val previewHeight = previewView.height

        Log.d(TAG, "Preview dimensions: $previewWidth x $previewHeight, Image dimensions: $lastImageWidth x $lastImageHeight")

        if (previewWidth == 0 || previewHeight == 0 || lastImageWidth == 0 || lastImageHeight == 0) {
            // Preview not ready yet, try again after layout
            Log.d(TAG, "Dimensions not ready, scheduling retry")
            previewView.post { updateHighlights() }
            return
        }

        // Calculate image aspect ratio vs preview aspect ratio
        val imageAspect = lastImageWidth.toFloat() / lastImageHeight.toFloat()
        val previewAspect = previewWidth.toFloat() / previewHeight.toFloat()

        // Determine scale factor based on how PreviewView fits the image
        // PreviewView typically uses FIT mode, which maintains aspect ratio
        val scaleFactor = if (imageAspect > previewAspect) {
            // Image is wider - scaled to fit width, letterboxed (bars on top/bottom)
            previewWidth.toFloat() / lastImageWidth.toFloat()
        } else {
            // Image is taller - scaled to fit height, pillarboxed (bars on sides)
            previewHeight.toFloat() / lastImageHeight.toFloat()
        }

        // Calculate offset for centering (PreviewView centers the image)
        val offsetX = if (imageAspect > previewAspect) {
            0f // No horizontal offset when letterboxed
        } else {
            (previewWidth - lastImageWidth * scaleFactor) / 2f
        }

        val offsetY = if (imageAspect > previewAspect) {
            (previewHeight - lastImageHeight * scaleFactor) / 2f
        } else {
            0f // No vertical offset when pillarboxed
        }

        // Helper function to convert image coordinates to screen coordinates
        // Coordinates are relative to previewView, which is constrained to parent
        fun imageToScreen(imageX: Int, imageY: Int): Pair<Int, Int> {
            val screenX = (imageX * scaleFactor + offsetX).toInt()
            val screenY = (imageY * scaleFactor + offsetY).toInt()
            return Pair(screenX, screenY)
        }

        // Update barcode highlight
        detectedBarcodeBox?.let { box ->
            val (left, top) = imageToScreen(box.left, box.top)
            val (right, bottom) = imageToScreen(box.right, box.bottom)
            
            val width = (right - left).coerceAtLeast(10) // Minimum 10px
            val height = (bottom - top).coerceAtLeast(10) // Minimum 10px

            val layoutParams = binding.barcodeHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = left.coerceAtLeast(0)
            layoutParams.topMargin = top.coerceAtLeast(0)
            layoutParams.width = width
            layoutParams.height = height
            binding.barcodeHighlight.layoutParams = layoutParams
            binding.barcodeHighlight.visibility = View.VISIBLE
            binding.barcodeHighlight.bringToFront() // Ensure it's on top

            Log.d(TAG, "Barcode highlight SET: left=$left, top=$top, width=$width, height=$height, visibility=${binding.barcodeHighlight.visibility}")
        } ?: run {
            binding.barcodeHighlight.visibility = View.GONE
            Log.d(TAG, "Barcode highlight HIDDEN - no bounding box")
        }

        // Update PIN highlight
        detectedPinBox?.let { box ->
            val (left, top) = imageToScreen(box.left, box.top)
            val (right, bottom) = imageToScreen(box.right, box.bottom)
            
            val width = (right - left).coerceAtLeast(10) // Minimum 10px
            val height = (bottom - top).coerceAtLeast(10) // Minimum 10px

            val layoutParams = binding.pinHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = left.coerceAtLeast(0)
            layoutParams.topMargin = top.coerceAtLeast(0)
            layoutParams.width = width
            layoutParams.height = height
            binding.pinHighlight.layoutParams = layoutParams
            binding.pinHighlight.visibility = View.VISIBLE
            binding.pinHighlight.bringToFront() // Ensure it's on top

            Log.d(TAG, "PIN highlight SET: left=$left, top=$top, width=$width, height=$height, visibility=${binding.pinHighlight.visibility}")
        } ?: run {
            binding.pinHighlight.visibility = View.GONE
            Log.d(TAG, "PIN highlight HIDDEN - no bounding box")
        }
    }
    
    // Auto-navigation removed - user must confirm manually via button
    
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
     * Improved for better distance scanning and PIN detection in upper-right corner.
     */
    private inner class CombinedAnalyzer(
        private val onResults: (barcode: String?, pin: String?, barcodeBox: Rect?, pinBox: Rect?, imageWidth: Int, imageHeight: Int) -> Unit
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
                var barcodeBoundingBox: Rect? = null
                var pinResult: String? = null
                var barcodeCompleted = false
                var pinCompleted = false

                var pinBoundingBox: Rect? = null

                fun checkCompletion() {
                    if (barcodeCompleted && pinCompleted) {
                        isProcessing.set(false)
                        val imageWidth = imageProxy.width
                        val imageHeight = imageProxy.height
                        imageProxy.close()
                        onResults(barcodeResult, pinResult, barcodeBoundingBox, pinBoundingBox, imageWidth, imageHeight)
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
                                    barcodeResult = extractLast20Digits(it)
                                    barcodeBoundingBox = boundingBox
                                    
                                    // Process PIN detection with region-of-interest if barcode found
                                    if (barcodeBoundingBox != null) {
                                        processPinWithRegionOfInterest(
                                            mediaImage,
                                            rotationDegrees,
                                            barcodeBoundingBox!!,
                                            imageProxy.width,
                                            imageProxy.height
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
                        Log.d(TAG, "PIN detected (full image): $potentialPin")
                        // Try to find the PIN's bounding box in the text blocks
                        val pinBox = findPinBoundingBox(visionText, potentialPin)
                        onComplete(potentialPin, pinBox)
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
         * Process PIN detection with region-of-interest (upper-right corner of barcode).
         */
        private fun processPinWithRegionOfInterest(
            mediaImage: Image,
            rotationDegrees: Int,
            barcodeBox: Rect,
            imageWidth: Int,
            imageHeight: Int,
            onComplete: (String?, Rect?) -> Unit
        ) {
            try {
                // Calculate upper-right corner region of barcode
                // PIN is typically in the upper-right corner, so we'll create a region
                // that's about 40% of the barcode width and 50% of the barcode height
                val regionWidth = (barcodeBox.width() * 0.4f).toInt().coerceAtLeast(100)
                val regionHeight = (barcodeBox.height() * 0.5f).toInt().coerceAtLeast(50)
                
                // Upper-right corner: start from right edge of barcode, go left
                val pinRegionLeft = barcodeBox.right - regionWidth
                val pinRegionTop = barcodeBox.top
                val pinRegionRight = barcodeBox.right
                val pinRegionBottom = barcodeBox.top + regionHeight
                
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
                
                Log.d(TAG, "PIN region: left=$finalLeft, top=$finalTop, right=$finalRight, bottom=$finalBottom (size: ${pinRegion.width()}x${pinRegion.height()})")
                
                // Convert Image to Bitmap, crop, then create InputImage
                val bitmap = imageToBitmap(mediaImage)
                if (bitmap != null) {
                    try {
                        // Crop bitmap to PIN region
                        val croppedBitmap = Bitmap.createBitmap(
                            bitmap,
                            finalLeft,
                            finalTop,
                            pinRegion.width(),
                            pinRegion.height()
                        )
                        
                        // Apply rotation if needed
                        val rotatedBitmap = if (rotationDegrees != 0) {
                            rotateBitmap(croppedBitmap, rotationDegrees)
                        } else {
                            croppedBitmap
                        }
                        
                        // Create InputImage from cropped bitmap
                        val croppedImage = InputImage.fromBitmap(rotatedBitmap, 0)
                        
                        // Process cropped region for PIN
                        textRecognizer.process(croppedImage)
                            .addOnSuccessListener { visionText ->
                                Log.d(TAG, "Text recognition on PIN region completed, blocks: ${visionText.textBlocks.size}")
                                val recognizedText = visionText.text
                                val potentialPin = extractPotentialPin(recognizedText)
                                
                                // Clean up bitmaps
                                if (!croppedBitmap.isRecycled) {
                                    croppedBitmap.recycle()
                                }
                                if (rotatedBitmap != croppedBitmap && !rotatedBitmap.isRecycled) {
                                    rotatedBitmap.recycle()
                                }
                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                                
                                if (potentialPin != null) {
                                    Log.d(TAG, "PIN detected (region-of-interest): $potentialPin")
                                    onComplete(potentialPin, pinRegion)
                                } else {
                                    Log.d(TAG, "No PIN found in region, trying full image as fallback")
                                    // Fallback to full image if region didn't work
                                    val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                    processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Text recognition on PIN region failed", e)
                                // Clean up bitmaps
                                if (!croppedBitmap.isRecycled) {
                                    croppedBitmap.recycle()
                                }
                                if (rotatedBitmap != croppedBitmap && !rotatedBitmap.isRecycled) {
                                    rotatedBitmap.recycle()
                                }
                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                                // Fallback to full image
                                val fullImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                processPinFullImageWithBox(fullImage) { pin, _ -> onComplete(pin, null) }
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

