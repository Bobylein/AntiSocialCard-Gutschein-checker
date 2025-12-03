package com.antisocial.giftcardchecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.antisocial.giftcardchecker.databinding.ActivityPinEntryBinding
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Activity for entering or scanning the PIN for a gift card.
 * Supports manual entry and OCR (Optical Character Recognition) from camera.
 */
class PinEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPinEntryBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private var imageCapture: ImageCapture? = null

    private var cardNumber: String = ""
    private var marketType: MarketType = MarketType.REWE
    private var isEditingCardNumber = false
    private var isOcrMode = false
    private var detectedPinText: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startOcrCamera()
        } else {
            Toast.makeText(this, R.string.scanner_permission_denied, Toast.LENGTH_LONG).show()
            hideOcrMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        cardNumber = intent.getStringExtra(GiftCard.EXTRA_CARD_NUMBER) ?: ""
        marketType = intent.getSerializableExtra(GiftCard.EXTRA_MARKET_TYPE) as? MarketType
            ?: MarketType.REWE

        setupTextRecognizer()
        setupUI()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupTextRecognizer() {
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun setupUI() {
        // Display card number
        if (cardNumber.isNotEmpty()) {
            binding.tvCardNumber.text = formatCardNumber(cardNumber)
            binding.cardNumber.visibility = View.VISIBLE
            binding.tilCardNumber.visibility = View.GONE
        } else {
            binding.cardNumber.visibility = View.GONE
            binding.tilCardNumber.visibility = View.VISIBLE
            isEditingCardNumber = true
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnEditCardNumber.setOnClickListener {
            toggleCardNumberEdit()
        }

        binding.btnScanPin.setOnClickListener {
            startOcrMode()
        }

        binding.btnCaptureOcr.setOnClickListener {
            captureAndRecognize()
        }

        binding.btnUseOcr.setOnClickListener {
            detectedPinText?.let { pin ->
                binding.etPin.setText(pin)
                hideOcrMode()
            }
        }

        binding.btnRetryOcr.setOnClickListener {
            binding.cardOcrResult.visibility = View.GONE
            detectedPinText = null
        }

        binding.btnContinue.setOnClickListener {
            validateAndContinue()
        }
    }

    private fun formatCardNumber(number: String): String {
        // Format card number with spaces every 4 digits
        return number.chunked(4).joinToString(" ")
    }

    private fun toggleCardNumberEdit() {
        isEditingCardNumber = !isEditingCardNumber
        if (isEditingCardNumber) {
            binding.etCardNumber.setText(cardNumber)
            binding.tilCardNumber.visibility = View.VISIBLE
            binding.btnEditCardNumber.text = "Save"
        } else {
            cardNumber = binding.etCardNumber.text.toString().replace(" ", "")
            binding.tvCardNumber.text = formatCardNumber(cardNumber)
            binding.tilCardNumber.visibility = View.GONE
            binding.btnEditCardNumber.text = "Edit"
        }
    }

    private fun startOcrMode() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startOcrCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startOcrCamera() {
        isOcrMode = true
        binding.ocrPreviewView.visibility = View.VISIBLE
        binding.btnCaptureOcr.visibility = View.VISIBLE
        binding.btnScanPin.visibility = View.GONE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.ocrPreviewView.surfaceProvider)
                }

            // Image capture use case
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, R.string.error_camera, Toast.LENGTH_SHORT).show()
                hideOcrMode()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun hideOcrMode() {
        isOcrMode = false
        binding.ocrPreviewView.visibility = View.GONE
        binding.btnCaptureOcr.visibility = View.GONE
        binding.btnScanPin.visibility = View.VISIBLE
        binding.cardOcrResult.visibility = View.GONE

        // Unbind camera
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind camera", e)
        }
    }

    private fun captureAndRecognize() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processImageForText(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                    Toast.makeText(
                        this@PinEntryActivity,
                        R.string.error_ocr,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageForText(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val recognizedText = visionText.text
                    val potentialPin = extractPotentialPin(recognizedText)
                    
                    if (potentialPin != null) {
                        detectedPinText = potentialPin
                        binding.tvOcrResult.text = potentialPin
                        binding.cardOcrResult.visibility = View.VISIBLE
                    } else {
                        Toast.makeText(
                            this,
                            "No PIN detected. Try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    Toast.makeText(this, R.string.error_ocr, Toast.LENGTH_SHORT).show()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
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

    private fun validateAndContinue() {
        // Get card number
        val finalCardNumber = if (isEditingCardNumber || cardNumber.isEmpty()) {
            binding.etCardNumber.text.toString().replace(" ", "")
        } else {
            cardNumber
        }

        // Get PIN
        val pin = binding.etPin.text.toString()

        // Validate
        if (finalCardNumber.isEmpty()) {
            binding.tilCardNumber.error = "Card number is required"
            return
        }

        if (pin.isEmpty()) {
            binding.tilPin.error = "PIN is required"
            return
        }

        // Clear errors
        binding.tilCardNumber.error = null
        binding.tilPin.error = null

        // Create gift card and navigate to balance check
        val giftCard = GiftCard(
            cardNumber = finalCardNumber,
            pin = pin,
            marketType = marketType
        )

        val intent = Intent(this, BalanceCheckActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_GIFT_CARD, giftCard)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textRecognizer.close()
    }

    companion object {
        private const val TAG = "PinEntryActivity"
    }
}

