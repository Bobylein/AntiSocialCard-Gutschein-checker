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
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
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
    private var imageAnalysisOutputTransform: OutputTransform? = null // For coordinate transformation
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
        
        // Setup card overlay sizing to fit within bounds
        setupCardOverlaySizing()
        
        // Make card visible so buttons are always shown
        binding.cardDetected.visibility = View.VISIBLE
        binding.btnManualEntry.visibility = View.VISIBLE
        
        // Position PIN instruction based on market type
        positionPinInstruction()
        
        // Setup footer content sizing to ensure all content fits
        setupFooterContentSizing()
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
                Toast.makeText(this, getString(R.string.please_scan_barcode), Toast.LENGTH_SHORT).show()
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
    
    private fun setupCardOverlaySizing() {
        // Wait for layout to be measured, then adjust card frame size to fit within bounds
        binding.cardCutoutOverlay.post {
            val overlay = binding.cardCutoutOverlay
            val cardFrame = binding.cardCutoutFrame
            
            // Get available space (80% of overlay dimensions with 10% margins)
            // The overlay is now constrained above the card, so it will automatically leave space
            val availableWidth = overlay.width * 0.8f
            val availableHeight = overlay.height * 0.7f  // Use 70% to be more conservative and leave room
            
            // Calculate size based on 5:8 aspect ratio (width:height)
            // Try width-first approach
            val widthFromWidth = availableWidth
            val heightFromWidth = widthFromWidth * 8f / 5f
            
            // Try height-first approach
            val heightFromHeight = availableHeight
            val widthFromHeight = heightFromHeight * 5f / 8f
            
            // Use the approach that fits within both constraints
            val finalWidth: Float
            val finalHeight: Float
            
            if (heightFromWidth <= availableHeight) {
                // Width constraint is limiting
                finalWidth = widthFromWidth
                finalHeight = heightFromWidth
            } else {
                // Height constraint is limiting
                finalWidth = widthFromHeight
                finalHeight = heightFromHeight
            }
            
            // Update layout params to set explicit size and center it
            val layoutParams = cardFrame.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            
            // Clear old constraints
            layoutParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            
            // Set new constraints to parent and center with bias
            layoutParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.horizontalBias = 0.5f
            layoutParams.verticalBias = 0.5f
            
            // Set explicit size
            layoutParams.width = finalWidth.toInt()
            layoutParams.height = finalHeight.toInt()
            
            // Clear margins to ensure proper centering
            layoutParams.marginStart = 0
            layoutParams.marginEnd = 0
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = 0
            
            cardFrame.layoutParams = layoutParams
            cardFrame.requestLayout()
            
            // Update PIN instruction position after card frame is sized
            positionPinInstruction()
        }
    }
    
    private fun positionPinInstruction() {
        // Position PIN instruction and arrow horizontally based on market type
        // REWE: PIN is on the LEFT, ALDI/Lidl: PIN is on the RIGHT
        binding.cardCutoutOverlay.post {
            val pinText = binding.tvPinInstruction
            val arrowView = binding.ivArrowDown
            val textParams = pinText.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val arrowParams = arrowView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            
            when (marketType) {
                MarketType.REWE -> {
                    // REWE: PIN is on the left side
                    textParams.horizontalBias = 0.0f
                    arrowParams.horizontalBias = 0.2f
                }
                MarketType.ALDI, MarketType.LIDL -> {
                    // ALDI/Lidl: PIN is on the right side (upper-right)
                    textParams.horizontalBias = 1.0f
                    arrowParams.horizontalBias = 0.6f
                }
            }
            
            pinText.layoutParams = textParams
            arrowView.layoutParams = arrowParams
            pinText.requestLayout()
            arrowView.requestLayout()
        }
    }
    
    private fun setupFooterContentSizing() {
        // Ensure footer content fits by adjusting font sizes if necessary
        binding.cardDetected.post {
            val cardDetected = binding.cardDetected
            val contentLayout = cardDetected.getChildAt(0) as? android.view.ViewGroup ?: return@post
            
            // Measure content height
            contentLayout.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(cardDetected.width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val contentHeight = contentLayout.measuredHeight
            val availableHeight = cardDetected.height - (cardDetected.paddingTop + cardDetected.paddingBottom)
            
            // If content doesn't fit, reduce font sizes proportionally
            if (contentHeight > availableHeight && availableHeight > 0) {
                val scaleFactor = (availableHeight.toFloat() / contentHeight.toFloat()).coerceIn(0.7f, 1.0f)
                adjustTextSizes(contentLayout, scaleFactor)
            }
        }
    }
    
    private fun adjustTextSizes(parent: android.view.ViewGroup, scaleFactor: Float) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            when (child) {
                is android.widget.TextView -> {
                    val currentSize = child.textSize / resources.displayMetrics.scaledDensity
                    child.textSize = currentSize * scaleFactor
                }
                is android.view.ViewGroup -> {
                    adjustTextSizes(child, scaleFactor)
                }
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
     * Check if a PIN is likely a false positive.
     * With tight search regions, we only reject obvious impossible patterns.
     */
    private fun isLikelyFalsePositive(pin: String): Boolean {
        // Reject all same digits (0000, 1111, etc.)
        if (pin.all { it == pin[0] }) {
            return true
        }

        // Reject simple sequential patterns (1234, 4321, 2345, etc.)
        val digits = pin.map { it.digitToInt() }
        val isAscending = digits.zipWithNext().all { (a, b) -> b - a == 1 }
        val isDescending = digits.zipWithNext().all { (a, b) -> a - b == 1 }
        if (isAscending || isDescending) {
            return true
        }

        return false
    }
    
    private fun updateUI() {
        // Card is always visible, but content visibility depends on detection
        binding.cardDetected.visibility = View.VISIBLE
        
        if (detectedBarcode != null) {
            binding.tvDetectedLabel.visibility = View.VISIBLE
            binding.tvDetectedBarcode.text = getString(R.string.barcode_label, detectedBarcode)
            binding.tvDetectedBarcode.visibility = View.VISIBLE
            binding.btnUseBarcode.visibility = View.VISIBLE
        } else {
            binding.tvDetectedLabel.visibility = View.GONE
            binding.tvDetectedBarcode.visibility = View.GONE
            binding.btnUseBarcode.visibility = View.GONE
        }
        
        if (detectedPin != null) {
            binding.tvDetectedPin.text = getString(R.string.pin_label, detectedPin)
            binding.tvDetectedPin.visibility = View.VISIBLE
        } else {
            binding.tvDetectedPin.visibility = View.GONE
        }
        
        // Update button text if we have both
        if (detectedBarcode != null && detectedPin != null) {
            binding.btnUseBarcode.text = getString(R.string.verify_data)
        } else if (detectedBarcode != null) {
            binding.btnUseBarcode.text = getString(R.string.verify_data)
        }
        
        // Manual entry button is always visible
        binding.btnManualEntry.visibility = View.VISIBLE
        
        // Recalculate footer content sizing after visibility changes
        setupFooterContentSizing()
    }

    /**
     * Update highlight overlays to show EXPECTED barcode and PIN search regions.
     * - RED: Expected barcode region
     * - BLUE: Expected PIN region
     * 
     * This method transforms coordinates from ML Kit's coordinate system (display-oriented)
     * to PreviewView's coordinate system, accounting for scaling and aspect ratio differences.
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


        // Direct coordinate transformation: ML Kit coords -> PreviewView coords
        // Both are in display orientation, so we just need to scale and account for aspect ratio
        val mlKitWidth = imageAnalysisWidth.toFloat()
        val mlKitHeight = imageAnalysisHeight.toFloat()
        
        Log.d(TAG, "=== COORDINATE TRANSFORMATION ===")
        Log.d(TAG, "ML Kit image: ${mlKitWidth.toInt()}x${mlKitHeight.toInt()} (rotation: $imageRotationDegrees°)")
        Log.d(TAG, "PreviewView: ${previewWidth}x${previewHeight}")
        Log.d(TAG, "Barcode region: $barcodeSearchRegion")
        Log.d(TAG, "PIN region: $pinSearchRegion")

        // Get PreviewView's ScaleType to determine how to scale
        val scaleType = previewView.scaleType
        val imageAspectRatio = mlKitWidth / mlKitHeight
        val previewAspectRatio = previewWidth.toFloat() / previewHeight.toFloat()

        // Calculate scale and offset based on ScaleType
        val scale: Float
        val offsetX: Float
        val offsetY: Float

        when (scaleType) {
            androidx.camera.view.PreviewView.ScaleType.FILL_CENTER -> {
                // FILL_CENTER: Scale to fill entire preview, cropping if needed
                if (imageAspectRatio > previewAspectRatio) {
                    // Image is wider - scale to fill height, crop left/right
                    scale = previewHeight.toFloat() / mlKitHeight
                    val scaledWidth = mlKitWidth * scale
                    offsetX = (previewWidth - scaledWidth) / 2f
                    offsetY = 0f
                } else {
                    // Image is taller - scale to fill width, crop top/bottom
                    scale = previewWidth.toFloat() / mlKitWidth
                    val scaledHeight = mlKitHeight * scale
                    offsetX = 0f
                    offsetY = (previewHeight - scaledHeight) / 2f
                }
            }
            androidx.camera.view.PreviewView.ScaleType.FIT_CENTER -> {
                // FIT_CENTER: Scale to fit entirely within preview, letterboxing if needed
                if (imageAspectRatio > previewAspectRatio) {
                    // Image is wider - fit to width, letterbox top/bottom
                    scale = previewWidth.toFloat() / mlKitWidth
                    val scaledHeight = mlKitHeight * scale
                    offsetX = 0f
                    offsetY = (previewHeight - scaledHeight) / 2f
                } else {
                    // Image is taller - fit to height, letterbox left/right
                    scale = previewHeight.toFloat() / mlKitHeight
                    val scaledWidth = mlKitWidth * scale
                    offsetX = (previewWidth - scaledWidth) / 2f
                    offsetY = 0f
                }
            }
            else -> {
                // Default to FILL_CENTER behavior
                if (imageAspectRatio > previewAspectRatio) {
                    scale = previewHeight.toFloat() / mlKitHeight
                    val scaledWidth = mlKitWidth * scale
                    offsetX = (previewWidth - scaledWidth) / 2f
                    offsetY = 0f
                } else {
                    scale = previewWidth.toFloat() / mlKitWidth
                    val scaledHeight = mlKitHeight * scale
                    offsetX = 0f
                    offsetY = (previewHeight - scaledHeight) / 2f
                }
            }
        }

        Log.d(TAG, "ScaleType: $scaleType, Scale: $scale, Offset: ($offsetX, $offsetY)")

        // Transform function: ML Kit coords -> PreviewView coords
        fun transformRegion(region: Rect): android.graphics.RectF {
            val left = region.left * scale + offsetX
            val top = region.top * scale + offsetY
            val right = region.right * scale + offsetX
            val bottom = region.bottom * scale + offsetY
            
            val rectF = android.graphics.RectF(left, top, right, bottom)
            Log.d(TAG, "Transform: MLKit[$region] -> Preview[$rectF]")
            
            return rectF
        }

        // Update barcode search region highlight (RED) - where we expect the barcode
        barcodeSearchRegion?.let { region ->
            val transformed = transformRegion(region)

            val layoutParams = binding.barcodeHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = transformed.left.toInt().coerceAtLeast(0)
            layoutParams.topMargin = transformed.top.toInt().coerceAtLeast(0)
            layoutParams.width = transformed.width().toInt().coerceAtLeast(1)
            layoutParams.height = transformed.height().toInt().coerceAtLeast(1)
            binding.barcodeHighlight.layoutParams = layoutParams
            binding.barcodeHighlight.visibility = View.VISIBLE

            Log.d(TAG, "Barcode highlight (RED): pos=(${layoutParams.leftMargin},${layoutParams.topMargin}), size=(${layoutParams.width}x${layoutParams.height})")
        } ?: run {
            binding.barcodeHighlight.visibility = View.GONE
        }

        // Update PIN search region highlight (BLUE) - where we expect the PIN
        pinSearchRegion?.let { region ->
            val transformed = transformRegion(region)

            val layoutParams = binding.pinHighlight.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.leftMargin = transformed.left.toInt().coerceAtLeast(0)
            layoutParams.topMargin = transformed.top.toInt().coerceAtLeast(0)
            layoutParams.width = transformed.width().toInt().coerceAtLeast(1)
            layoutParams.height = transformed.height().toInt().coerceAtLeast(1)
            binding.pinHighlight.layoutParams = layoutParams
            binding.pinHighlight.visibility = View.VISIBLE

            Log.d(TAG, "PIN highlight (BLUE): pos=(${layoutParams.leftMargin},${layoutParams.topMargin}), size=(${layoutParams.width}x${layoutParams.height})")
        } ?: run {
            binding.pinHighlight.visibility = View.GONE
        }

        Log.d(TAG, "=================================")
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
            Toast.makeText(this, getString(R.string.please_scan_barcode), Toast.LENGTH_SHORT).show()
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
                
                // Create OutputTransform for ML Kit coordinate system
                // ML Kit coordinates are already in display orientation (after rotation correction)
                // So we use an identity matrix (no rotation) with ML Kit dimensions
                // This represents ML Kit's coordinate system directly - no transformation needed
                val mlKitMatrix = Matrix() // Identity matrix - ML Kit coords are already display-oriented
                imageAnalysisOutputTransform = OutputTransform(
                    mlKitMatrix,
                    android.util.Size(mlKitWidth, mlKitHeight)
                )
                
                Log.d(TAG, "Created OutputTransform: ML Kit size=${mlKitWidth}x${mlKitHeight}, ImageProxy=${imageProxy.width}x${imageProxy.height}, rotation=$rotationDegrees°")

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
         * Process PIN detection with tight region-of-interest based on measured card layouts.
         *
         * Measurements from actual cards (as ratios of barcode dimensions):
         *
         * REWE (both types): PIN is LEFT of barcode
         *   - PIN width: 35% of barcode width
         *   - Gap from barcode: 15% of barcode width
         *   - Vertically centered with barcode
         *   - PIN height: 50% of barcode height
         *
         * ALDI: PIN is UPPER-RIGHT of barcode
         *   - PIN width: 25% of barcode width
         *   - Aligned with barcode's right edge
         *   - Vertical gap: 15% of barcode height
         *   - PIN height: 40% of barcode height
         *
         * Lidl: PIN is UPPER-RIGHT of barcode
         *   - PIN width: 25% of barcode width
         *   - Aligned with barcode's right edge
         *   - Vertical gap: 10% of barcode height
         *   - PIN height: 45% of barcode height
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
                val barcodeWidth = barcodeBox.width()
                val barcodeHeight = barcodeBox.height()
                val isPortraitMode = rotationDegrees == 90 || rotationDegrees == 270

                val pinRegionLeft: Int
                val pinRegionTop: Int
                val pinRegionRight: Int
                val pinRegionBottom: Int

                when (marketType) {
                    MarketType.REWE -> {
                        // REWE: PIN is to the LEFT of barcode
                        // Same layout for Type 1 (standard barcode) and Type 2 (Aztec/QR)
                        val cardType = detectedReweCardType ?: ReweCardType.TYPE_1

                        // Tight PIN region dimensions
                        val pinWidth = (barcodeWidth * 0.35f).toInt()
                        val pinHeight = (barcodeHeight * 0.50f).toInt()
                        val gapFromBarcode = (barcodeWidth * 0.15f).toInt()

                        if (isPortraitMode) {
                            // Portrait phone + landscape card: "left" becomes "above"
                            pinRegionLeft = barcodeBox.centerX() - pinWidth / 2
                            pinRegionRight = barcodeBox.centerX() + pinWidth / 2
                            pinRegionBottom = barcodeBox.top - gapFromBarcode
                            pinRegionTop = pinRegionBottom - pinHeight

                            Log.d(TAG, "REWE $cardType (portrait): PIN region ABOVE barcode")
                        } else {
                            // Landscape: PIN is to the LEFT
                            pinRegionRight = barcodeBox.left - gapFromBarcode
                            pinRegionLeft = pinRegionRight - pinWidth
                            pinRegionTop = barcodeBox.centerY() - pinHeight / 2
                            pinRegionBottom = barcodeBox.centerY() + pinHeight / 2

                            Log.d(TAG, "REWE $cardType (landscape): PIN region LEFT of barcode")
                        }
                    }

                    MarketType.ALDI -> {
                        // ALDI: PIN "9292" is UPPER-RIGHT of barcode
                        val pinWidth = (barcodeWidth * 0.25f).toInt()
                        val pinHeight = (barcodeHeight * 0.40f).toInt()
                        val verticalGap = (barcodeHeight * 0.15f).toInt()

                        // Aligned with barcode's right edge, above barcode
                        pinRegionRight = barcodeBox.right
                        pinRegionLeft = pinRegionRight - pinWidth
                        pinRegionBottom = barcodeBox.top - verticalGap
                        pinRegionTop = pinRegionBottom - pinHeight

                        Log.d(TAG, "ALDI: PIN region UPPER-RIGHT of barcode")
                    }

                    MarketType.LIDL -> {
                        // Lidl: PIN "3478" is UPPER-RIGHT of barcode (similar to ALDI)
                        detectedLidlCardType = LidlCardType.TYPE_1

                        val pinWidth = (barcodeWidth * 0.25f).toInt()
                        val pinHeight = (barcodeHeight * 0.45f).toInt()
                        val verticalGap = (barcodeHeight * 0.10f).toInt()

                        // Aligned with barcode's right edge, above barcode
                        pinRegionRight = barcodeBox.right
                        pinRegionLeft = pinRegionRight - pinWidth
                        pinRegionBottom = barcodeBox.top - verticalGap
                        pinRegionTop = pinRegionBottom - pinHeight

                        Log.d(TAG, "Lidl: PIN region UPPER-RIGHT of barcode")
                    }
                }

                // Clamp to image bounds and validate
                val finalLeft = pinRegionLeft.coerceIn(0, imageWidth - 1)
                val finalTop = pinRegionTop.coerceIn(0, imageHeight - 1)
                val finalRight = pinRegionRight.coerceIn(1, imageWidth)
                val finalBottom = pinRegionBottom.coerceIn(1, imageHeight)

                if (finalRight <= finalLeft || finalBottom <= finalTop) {
                    Log.w(TAG, "Invalid PIN region dimensions after clamping")
                    onComplete(null, null)
                    return
                }

                val pinRegion = Rect(finalLeft, finalTop, finalRight, finalBottom)
                pinSearchRegion = pinRegion
                runOnUiThread { updateHighlights() }

                Log.d(TAG, "Tight PIN region: $pinRegion (${pinRegion.width()}x${pinRegion.height()})")
                Log.d(TAG, "Barcode: $barcodeBox (${barcodeWidth}x${barcodeHeight})")

                // Process the cropped region
                val bitmap = imageToBitmap(mediaImage)
                if (bitmap != null) {
                    try {
                        val (cropCoords, cropSize) = transformCoordinatesForRotation(
                            finalLeft, finalTop, pinRegion.width(), pinRegion.height(),
                            bitmap.width, bitmap.height, rotationDegrees
                        )

                        val cropLeft = cropCoords.first.coerceIn(0, bitmap.width - 1)
                        val cropTop = cropCoords.second.coerceIn(0, bitmap.height - 1)
                        val cropWidth = cropSize.first.coerceIn(1, bitmap.width - cropLeft)
                        val cropHeight = cropSize.second.coerceIn(1, bitmap.height - cropTop)

                        val croppedBitmap = Bitmap.createBitmap(
                            bitmap, cropLeft, cropTop, cropWidth, cropHeight
                        )

                        processCroppedBitmapWithMultipleOrientations(croppedBitmap) { pin, _ ->
                            if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
                            if (!bitmap.isRecycled) bitmap.recycle()

                            if (pin != null) {
                                Log.d(TAG, "PIN found in tight region: $pin")
                                onComplete(pin, pinRegion)
                            } else {
                                // With tight regions, don't fall back - if not found, PIN isn't visible
                                Log.d(TAG, "No PIN in tight region - not falling back")
                                onComplete(null, null)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cropping for PIN", e)
                        if (!bitmap.isRecycled) bitmap.recycle()
                        onComplete(null, null)
                    }
                } else {
                    onComplete(null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in processPinWithRegionOfInterest", e)
                onComplete(null, null)
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

