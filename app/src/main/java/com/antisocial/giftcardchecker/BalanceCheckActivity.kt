package com.antisocial.giftcardchecker

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antisocial.giftcardchecker.databinding.ActivityBalanceCheckBinding
import com.antisocial.giftcardchecker.markets.Market
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.BalanceStatus
import com.antisocial.giftcardchecker.model.GiftCard
import org.json.JSONObject

/**
 * Activity for checking gift card balance using WebView.
 * Loads the retailer's balance check page, fills in the form with JavaScript,
 * and extracts the balance result.
 */
class BalanceCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBalanceCheckBinding
    private lateinit var giftCard: GiftCard
    private lateinit var market: Market
    
    private val handler = Handler(Looper.getMainLooper())
    private var pageLoadAttempts = 0
    private var formFilled = false
    private var checkingBalance = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalanceCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get gift card from intent
        giftCard = intent.getParcelableExtra(GiftCard.EXTRA_GIFT_CARD) 
            ?: run {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
                finish()
                return
            }

        // Get market implementation
        market = Market.forType(giftCard.marketType)

        setupUI()
        setupWebView()
        loadBalanceCheckPage()
    }

    private fun setupUI() {
        binding.tvTitle.text = "${market.displayName} - Balance Check"
        
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnScanAnother.setOnClickListener {
            // Go back to main activity to scan another card
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }

        binding.btnDone.setOnClickListener {
            // Return to main activity
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                
                // Set user agent to appear as a regular browser
                userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
            }

            // Add JavaScript interface for callbacks
            addJavascriptInterface(WebAppInterface(), "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page finished loading: $url")
                    
                    if (!formFilled) {
                        // Wait a bit for any dynamic content to load
                        handler.postDelayed({
                            fillFormFields()
                        }, 1500)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error: ${error?.description}")
                    
                    if (request?.isForMainFrame == true) {
                        showError(BalanceResult.networkError())
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    // Update loading progress if needed
                }
            }
        }
    }

    private fun loadBalanceCheckPage() {
        showLoading(true)
        binding.webView.loadUrl(market.balanceCheckUrl)
    }

    private fun fillFormFields() {
        if (formFilled) return
        
        Log.d(TAG, "Filling form fields...")
        val script = market.getFormFillScript(giftCard)
        
        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Form fill result: $result")
            
            try {
                val json = JSONObject(result.trim('"').replace("\\\"", "\""))
                val success = json.optBoolean("success", false)
                
                if (success) {
                    formFilled = true
                    
                    // Check if there's a CAPTCHA
                    val hasCaptcha = json.optBoolean("captchaFound", false)
                    if (hasCaptcha) {
                        showCaptchaMode()
                    } else {
                        // Auto-submit after a short delay
                        handler.postDelayed({
                            submitForm()
                        }, 500)
                    }
                } else {
                    // Form fields not found, retry
                    pageLoadAttempts++
                    if (pageLoadAttempts < MAX_ATTEMPTS) {
                        handler.postDelayed({
                            fillFormFields()
                        }, 1000)
                    } else {
                        showError(BalanceResult.websiteChanged("Form fields not found"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing form fill result", e)
                showError(BalanceResult.error(e.message))
            }
        }
    }

    private fun submitForm() {
        if (checkingBalance) return
        checkingBalance = true
        
        Log.d(TAG, "Submitting form...")
        val script = market.getFormSubmitScript()
        
        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Form submit result: $result")
            
            // Wait for page to load/update, then extract balance
            handler.postDelayed({
                extractBalance()
            }, 3000)
        }
    }

    private fun extractBalance() {
        Log.d(TAG, "Extracting balance...")
        
        // Get the page HTML and check for balance
        binding.webView.evaluateJavascript(
            "(function() { return document.body.innerText; })();"
        ) { result ->
            val pageText = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
            Log.d(TAG, "Page text length: ${pageText.length}")
            
            // Parse the response using market-specific logic
            val balanceResult = market.parseBalanceResponse(pageText)
            
            if (balanceResult.isSuccess()) {
                showResult(balanceResult)
            } else if (market.isErrorPageLoaded(pageText)) {
                showError(BalanceResult.invalidCard())
            } else {
                // Try JavaScript extraction
                val extractScript = market.getBalanceExtractionScript()
                binding.webView.evaluateJavascript(extractScript, null)
                
                // If no callback received within timeout, show error
                handler.postDelayed({
                    if (binding.resultCard.visibility != View.VISIBLE) {
                        showError(BalanceResult.parsingError("Could not extract balance"))
                    }
                }, 5000)
            }
        }
    }

    private fun showCaptchaMode() {
        showLoading(false)
        binding.webView.visibility = View.VISIBLE
        binding.tvCaptchaInstruction.visibility = View.VISIBLE
        
        // Show buttons for manual interaction
        binding.buttonsLayout.visibility = View.VISIBLE
        binding.btnScanAnother.text = "Check Balance"
        binding.btnScanAnother.setOnClickListener {
            binding.tvCaptchaInstruction.visibility = View.GONE
            showLoading(true)
            submitForm()
        }
    }

    private fun showLoading(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvLoadingText.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showResult(result: BalanceResult) {
        showLoading(false)
        binding.webView.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE
        binding.buttonsLayout.visibility = View.VISIBLE
        binding.tvCaptchaInstruction.visibility = View.GONE
        
        // Reset button
        binding.btnScanAnother.text = getString(R.string.scan_another)
        binding.btnScanAnother.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }

        if (result.isSuccess()) {
            binding.ivResultIcon.setImageResource(android.R.drawable.ic_dialog_info)
            binding.ivResultIcon.setColorFilter(getColor(R.color.success))
            binding.tvResultStatus.text = "Balance found"
            binding.tvBalance.text = result.getFormattedBalance()
            binding.tvBalance.visibility = View.VISIBLE
            binding.tvErrorMessage.visibility = View.GONE
        } else {
            showError(result)
        }

        binding.tvCardInfo.text = "Card: ${giftCard.getMaskedCardNumber()}"
    }

    private fun showError(result: BalanceResult) {
        showLoading(false)
        binding.webView.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE
        binding.buttonsLayout.visibility = View.VISIBLE
        binding.tvCaptchaInstruction.visibility = View.GONE

        // Reset button
        binding.btnScanAnother.text = getString(R.string.scan_another)
        binding.btnScanAnother.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }

        val iconRes = when (result.status) {
            BalanceStatus.INVALID_CARD, BalanceStatus.INVALID_PIN -> android.R.drawable.ic_dialog_alert
            BalanceStatus.NETWORK_ERROR -> android.R.drawable.stat_notify_sync_noanim
            else -> android.R.drawable.ic_dialog_alert
        }

        binding.ivResultIcon.setImageResource(iconRes)
        binding.ivResultIcon.setColorFilter(getColor(R.color.error))
        binding.tvResultStatus.text = "Error"
        binding.tvBalance.visibility = View.GONE
        binding.tvErrorMessage.text = result.getDisplayMessage()
        binding.tvErrorMessage.visibility = View.VISIBLE
        binding.tvCardInfo.text = "Card: ${giftCard.getMaskedCardNumber()}"
    }

    /**
     * JavaScript interface for callbacks from WebView.
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun onBalanceResult(jsonString: String) {
            Log.d(TAG, "Balance result received: $jsonString")
            
            handler.post {
                try {
                    val json = JSONObject(jsonString)
                    val success = json.optBoolean("success", false)
                    val balance = json.optString("balance", null)
                    val error = json.optString("error", null)

                    val result = when {
                        success && balance != null -> BalanceResult.success(balance)
                        error == "invalid_card" -> BalanceResult.invalidCard()
                        error == "captcha_error" -> BalanceResult.error("CAPTCHA solution incorrect")
                        else -> BalanceResult.parsingError("Could not extract balance")
                    }

                    showResult(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing balance result", e)
                    showError(BalanceResult.error(e.message))
                }
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "WebView JS: $message")
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        binding.webView.destroy()
    }

    companion object {
        private const val TAG = "BalanceCheckActivity"
        private const val MAX_ATTEMPTS = 5
    }
}

