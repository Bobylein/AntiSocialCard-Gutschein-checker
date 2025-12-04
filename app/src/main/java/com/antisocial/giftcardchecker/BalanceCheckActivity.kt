package com.antisocial.giftcardchecker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
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
    private var isFillingForm = false
    private var tabClickAttempts = 0
    private val MAX_TAB_CLICK_ATTEMPTS = 5
    private val processedUrls = mutableSetOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalanceCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get gift card from intent
        giftCard = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(GiftCard.EXTRA_GIFT_CARD, GiftCard::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(GiftCard.EXTRA_GIFT_CARD)
        } ?: run {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Get market implementation
        market = Market.forType(giftCard.marketType)

        setupUI()
        setupWebView()

        // Check network connectivity before loading
        if (!isNetworkAvailable()) {
            showError(BalanceResult.networkError("Keine Internetverbindung"))
            return
        }

        // Always use auto-fill - manual entry is not acceptable
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
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                
                // Enable additional features that might be needed
                javaScriptCanOpenWindowsAutomatically = true
                setSupportZoom(true)
                setSupportMultipleWindows(false)
                
                // Set user agent to appear as a regular browser (updated to more recent version)
                userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            
            // Enable hardware acceleration for better rendering
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Add JavaScript interface for callbacks
            addJavascriptInterface(WebAppInterface(), "Android")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    // Add referrer header for ALDI iframe URL to prevent blank page
                    if (request != null && request.url.toString().contains("balancechecks.tx-gate.com")) {
                        val headers = request.requestHeaders.toMutableMap()
                        // Add referrer header to make it look like we came from the parent page
                        if (!headers.containsKey("Referer")) {
                            headers["Referer"] = "https://www.helaba.com/de/aldi/"
                        }
                        // Ensure we have proper headers
                        headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                        headers["Accept-Language"] = "de-DE,de;q=0.9,en;q=0.8"
                        
                        Log.d(TAG, "Intercepting request to ${request.url} with referrer: ${headers["Referer"]}")
                        
                        // Note: We can't modify headers in shouldInterceptRequest easily
                        // So we'll handle this in loadUrl instead
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Only allow navigation for main frame requests
                    if (request?.isForMainFrame == true) {
                        val url = request.url.toString()
                        Log.d(TAG, "Navigation requested to: $url")
                        
                        // Prevent navigation if we've already processed this URL and form is filled
                        if (processedUrls.contains(url) && formFilled) {
                            Log.d(TAG, "Preventing navigation to already processed URL: $url")
                            return true // Block navigation
                        }
                        
                        // Allow navigation to the initial balance check URL
                        if (url == market.balanceCheckUrl || url.contains("helaba.com/de/aldi") || url.contains("balancechecks.tx-gate.com")) {
                            return false // Allow navigation
                        }
                    }
                    return false // Allow all other navigations
                }
                
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "Page started loading: $url")
                    
                    // Reset form filling flag when a new page starts loading
                    // But only if it's a different URL
                    if (url != null) {
                        val normalizedUrl = url.split("#").firstOrNull()?.trimEnd('/') ?: url.trimEnd('/')
                        if (!processedUrls.any { 
                            val processedNormalized = it.split("#").firstOrNull()?.trimEnd('/') ?: it.trimEnd('/')
                            processedNormalized == normalizedUrl 
                        }) {
                            isFillingForm = false
                        }
                    }
                    
                    // Check if page content is actually loading
                    handler.postDelayed({
                        view?.evaluateJavascript("""
                            (function() {
                                return {
                                    readyState: document.readyState,
                                    bodyExists: document.body !== null,
                                    bodyTextLength: document.body ? document.body.innerText.length : 0,
                                    htmlLength: document.documentElement ? document.documentElement.innerHTML.length : 0
                                };
                            })();
                        """.trimIndent()) { result ->
                            try {
                                val checkJson = JSONObject(result.trim('"').replace("\\\"", "\""))
                                Log.d(TAG, "Page check after start - readyState: ${checkJson.optString("readyState")}, " +
                                        "bodyExists: ${checkJson.optBoolean("bodyExists")}, " +
                                        "bodyTextLength: ${checkJson.optInt("bodyTextLength")}, " +
                                        "htmlLength: ${checkJson.optInt("htmlLength")}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error checking page state after start", e)
                            }
                        }
                    }, 1000)
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // Ignore iframe and resource URLs - only process main frame URLs
                    if (url == null || url.startsWith("data:") || url.startsWith("javascript:") || 
                        url.contains("_Incapsula_Resource") || url.contains("csp_report") ||
                        url.contains("mell-my-spricking")) {
                        Log.d(TAG, "Ignoring resource/iframe URL: $url")
                        return
                    }
                    
                    // Check page content to diagnose blank page issue
                    view?.evaluateJavascript("""
                        (function() {
                            return {
                                readyState: document.readyState,
                                bodyExists: document.body !== null,
                                bodyTextLength: document.body ? document.body.innerText.length : 0,
                                htmlLength: document.documentElement ? document.documentElement.innerHTML.length : 0,
                                hasContent: document.body && document.body.children.length > 0,
                                errorCount: document.querySelectorAll('script[src]').length
                            };
                        })();
                    """.trimIndent()) { result ->
                        try {
                            val checkJson = JSONObject(result.trim('"').replace("\\\"", "\""))
                            Log.d(TAG, "Page finished check - readyState: ${checkJson.optString("readyState")}, " +
                                    "bodyExists: ${checkJson.optBoolean("bodyExists")}, " +
                                    "bodyTextLength: ${checkJson.optInt("bodyTextLength")}, " +
                                    "htmlLength: ${checkJson.optInt("htmlLength")}, " +
                                    "hasContent: ${checkJson.optBoolean("hasContent")}")
                            
                            // If page appears blank, log more details
                            if (checkJson.optInt("bodyTextLength") == 0 && checkJson.optInt("htmlLength") < 1000) {
                                Log.w(TAG, "WARNING: Page appears to be blank! HTML length: ${checkJson.optInt("htmlLength")}")
                                
                                // Try to get more info about what's blocking
                                view.evaluateJavascript("""
                                    (function() {
                                        var errors = [];
                                        var scripts = document.querySelectorAll('script');
                                        for (var i = 0; i < scripts.length; i++) {
                                            if (scripts[i].src) {
                                                errors.push('Script: ' + scripts[i].src);
                                            }
                                        }
                                        return JSON.stringify({
                                            scriptCount: scripts.length,
                                            scripts: errors.slice(0, 5)
                                        });
                                    })();
                                """.trimIndent()) { scriptResult ->
                                    Log.d(TAG, "Script check result: $scriptResult")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking page state after finish", e)
                        }
                    }
                    
                    // Normalize URL (remove hash and trailing slash for comparison)
                    val normalizedUrl = url.split("#").firstOrNull()?.trimEnd('/') ?: url.trimEnd('/')
                    
                    // Check if we've already processed this URL
                    if (processedUrls.any { 
                        val processedNormalized = it.split("#").firstOrNull()?.trimEnd('/') ?: it.trimEnd('/')
                        processedNormalized == normalizedUrl 
                    }) {
                        Log.d(TAG, "Already processed URL: $normalizedUrl, skipping")
                        return
                    }
                    
                    // Only process main frame URLs
                    val currentUrl = view?.url?.split("#")?.firstOrNull()?.trimEnd('/') ?: view?.url?.trimEnd('/')
                    if (currentUrl != normalizedUrl) {
                        Log.d(TAG, "URL mismatch - current: $currentUrl, finished: $normalizedUrl, skipping")
                        return
                    }
                    
                    Log.d(TAG, "Page finished loading (main frame): $normalizedUrl")
                    processedUrls.add(normalizedUrl)
                    
                    // If we're checking balance (form was submitted), extract balance instead of filling form
                    if (checkingBalance) {
                        Log.d(TAG, "Form was submitted, extracting balance from result page")
                        handler.postDelayed({
                            extractBalance()
                        }, 2000) // Wait 2 seconds for page to fully render
                        return
                    }
                    
                    // Check if we're on the iframe URL (for ALDI, we load this directly now)
                    val isIframeUrl = normalizedUrl.contains("balancechecks.tx-gate.com")
                    
                    if (isIframeUrl) {
                        Log.d(TAG, "Loaded iframe URL directly (same as browser), will fill form")
                    }
                    
                    if (!formFilled && !isFillingForm) {
                        // Wait for form to be fully loaded and rendered
                        // The ALDI form might need more time to initialize, especially if it uses JavaScript
                        // If we're on the iframe URL, wait 2 seconds since form should be ready
                        val waitTime = when {
                            isIframeUrl -> 2000L // 2 seconds for direct iframe URL
                            market.marketType == com.antisocial.giftcardchecker.model.MarketType.ALDI -> 3000L // 3 seconds for ALDI main page (to allow tab click)
                            else -> 2000L // 2 seconds for others
                        }
                        
                        Log.d(TAG, "Waiting $waitTime ms before filling form")
                        handler.postDelayed({
                            fillFormFields()
                        }, waitTime)
                    } else {
                        Log.d(TAG, "Skipping fillFormFields - formFilled=$formFilled, isFillingForm=$isFillingForm")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val errorCode = error?.errorCode ?: -1
                    val errorDescription = error?.description?.toString() ?: "Unknown error"
                    val url = request?.url?.toString() ?: "Unknown URL"
                    
                    Log.e(TAG, "WebView error - Code: $errorCode, Description: $errorDescription, URL: $url")
                    
                    if (request?.isForMainFrame == true) {
                        Log.e(TAG, "Main frame error - showing error to user")
                        showError(BalanceResult.networkError("Failed to load page: $errorDescription"))
                    }
                }
                
                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val statusCode = errorResponse?.statusCode ?: -1
                    val url = request?.url?.toString() ?: "Unknown URL"
                    Log.e(TAG, "WebView HTTP error - Status: $statusCode, URL: $url")
                    
                    if (request?.isForMainFrame == true && statusCode >= 400) {
                        Log.e(TAG, "Main frame HTTP error - showing error to user")
                        showError(BalanceResult.networkError("HTTP error $statusCode"))
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    Log.d(TAG, "WebView progress: $newProgress%")
                    // Update loading progress if needed
                }

                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                    message?.let {
                        val level = when (it.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                            ConsoleMessage.MessageLevel.WARNING -> "WARNING"
                            ConsoleMessage.MessageLevel.LOG -> "LOG"
                            ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                            ConsoleMessage.MessageLevel.TIP -> "TIP"
                            else -> "UNKNOWN"
                        }
                        val logLevel = when (it.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            else -> Log.DEBUG
                        }
                        android.util.Log.println(logLevel, TAG, "WebView Console [$level]: [${it.sourceId()}:${it.lineNumber()}] ${it.message()}")
                        
                        // If it's an error and we're checking Lidl, log it prominently
                        if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR && 
                            giftCard.marketType == com.antisocial.giftcardchecker.model.MarketType.LIDL) {
                            Log.e(TAG, "LIDL JavaScript Error: ${it.message()} at ${it.sourceId()}:${it.lineNumber()}")
                        }
                    }
                    return true
                }
                
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    Log.d(TAG, "WebView title received: $title")
                }
                
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                    Log.d(TAG, "JavaScript alert: $message")
                    result?.confirm()
                    return true
                }
            }
        }
    }

    private fun loadBalanceCheckPage() {
        showLoading(true)
        formFilled = false
        isFillingForm = false
        pageLoadAttempts = 0
        tabClickAttempts = 0
        processedUrls.clear() // Reset processed URLs
        
        // For ALDI, navigate directly to the iframe URL (same as browser)
        // This avoids the blank page issue and works immediately
        val urlToLoad = if (market.marketType == com.antisocial.giftcardchecker.model.MarketType.ALDI) {
            val iframeUrl = (market as? com.antisocial.giftcardchecker.markets.AldiMarket)?.iframeFormUrl
            iframeUrl ?: market.balanceCheckUrl
        } else {
            market.balanceCheckUrl
        }
        
        Log.d(TAG, "Loading balance check URL: $urlToLoad")
        
        // For ALDI iframe URL, load with referrer header to prevent blank page
        if (market.marketType == com.antisocial.giftcardchecker.model.MarketType.ALDI && 
            urlToLoad.contains("balancechecks.tx-gate.com")) {
            // Use loadUrl with additional headers (requires API 21+)
            val headers = mapOf(
                "Referer" to "https://www.helaba.com/de/aldi/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8"
            )
            Log.d(TAG, "Loading with headers: $headers")
            binding.webView.loadUrl(urlToLoad, headers)
        } else {
            binding.webView.loadUrl(urlToLoad)
        }
    }

    private fun fillFormFields() {
        if (formFilled) {
            Log.d(TAG, "Form already filled, skipping")
            return
        }
        
        if (isFillingForm) {
            Log.d(TAG, "Already filling form, skipping duplicate call")
            return
        }
        
        // Always use auto-fill - manual entry is not acceptable
        
        isFillingForm = true
        Log.d(TAG, "Filling form fields...")
        Log.d(TAG, "Current URL: ${binding.webView.url}")
        
        // First, check if page is ready and has content
        binding.webView.evaluateJavascript("""
            (function() {
                return {
                    readyState: document.readyState,
                    bodyExists: document.body !== null,
                    bodyTextLength: document.body ? document.body.innerText.length : 0,
                    formExists: document.querySelector('form') !== null,
                    inputCount: document.querySelectorAll('input').length
                };
            })();
        """.trimIndent()) { checkResult ->
            try {
                val checkJson = JSONObject(checkResult.trim('"').replace("\\\"", "\""))
                Log.d(TAG, "Page check - readyState: ${checkJson.optString("readyState")}")
                Log.d(TAG, "Page check - bodyExists: ${checkJson.optBoolean("bodyExists")}")
                Log.d(TAG, "Page check - bodyTextLength: ${checkJson.optInt("bodyTextLength")}")
                Log.d(TAG, "Page check - formExists: ${checkJson.optBoolean("formExists")}")
                Log.d(TAG, "Page check - inputCount: ${checkJson.optInt("inputCount")}")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking page state", e)
            }
        }
        
        val script = market.getFormFillScript(giftCard)
        
        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Form fill result: $result")
            
            try {
                // Clean up the result string
                var cleanedResult = result
                if (cleanedResult != null && cleanedResult.isNotEmpty()) {
                    cleanedResult = cleanedResult.trim('"').replace("\\\"", "\"")
                    // Remove any leading/trailing whitespace
                    cleanedResult = cleanedResult.trim()
                } else {
                    Log.e(TAG, "Empty result from JavaScript")
                    showError(BalanceResult.error("Empty response from form fill script"))
                    return@evaluateJavascript
                }
                
                Log.d(TAG, "Cleaned result length: ${cleanedResult.length}")
                if (cleanedResult.length > 1000) {
                    Log.d(TAG, "Cleaned result (first 500 chars): ${cleanedResult.take(500)}")
                } else {
                    Log.d(TAG, "Cleaned result: $cleanedResult")
                }
                
                val json = JSONObject(cleanedResult)
                
                // Check for JavaScript errors
                if (json.has("error")) {
                    val error = json.optString("error", "")
                    val errorMessage = json.optString("errorMessage", "")
                    Log.e(TAG, "JavaScript error in form fill: $error - $errorMessage")
                    
                    when (error) {
                        "android_interface_not_available" -> {
                            showError(BalanceResult.error("JavaScript interface not available. Please try again."))
                        }
                        "javascript_error" -> {
                            showError(BalanceResult.error("JavaScript error: $errorMessage"))
                        }
                        else -> {
                            showError(BalanceResult.error("Form fill error: $error"))
                        }
                    }
                    isFillingForm = false
                    return@evaluateJavascript
                }
                
                // Check if tab was clicked but iframe not loaded yet
                val tabClicked = json.optBoolean("tabClicked", false)
                val iframeLoaded = json.optBoolean("iframeLoaded", false)
                
                if (tabClicked && !iframeLoaded) {
                    tabClickAttempts++
                    if (tabClickAttempts < MAX_TAB_CLICK_ATTEMPTS) {
                        Log.d(TAG, "Tab clicked but iframe not loaded yet, waiting and retrying... (attempt $tabClickAttempts/$MAX_TAB_CLICK_ATTEMPTS)")
                        isFillingForm = false // Reset flag to allow retry
                        handler.postDelayed({
                            fillFormFields()
                        }, 2000) // Wait 2 seconds for iframe to load
                        return@evaluateJavascript
                    } else {
                        Log.e(TAG, "Tab click retry limit reached. Proceeding with form fill anyway.")
                        // Continue with form fill even if iframe not loaded
                    }
                } else if (tabClicked && iframeLoaded) {
                    // Tab was clicked and iframe is loaded, reset attempts
                    tabClickAttempts = 0
                }
                
                val success = json.optBoolean("success", false)
                
                // Log debug information
                if (json.has("debug")) {
                    val debug = json.getJSONObject("debug")
                    Log.d(TAG, "Debug info - URL: ${debug.optString("url")}")
                    Log.d(TAG, "Debug info - Iframe found: ${debug.optBoolean("iframeFound")}")
                    Log.d(TAG, "Debug info - Iframe URL: ${debug.optString("iframeUrl")}")
                    Log.d(TAG, "Debug info - Iframe accessible: ${debug.optBoolean("iframeAccessible")}")
                    Log.d(TAG, "Debug info - Error: ${debug.optString("error")}")
                    Log.d(TAG, "Debug info - All inputs count: ${debug.optJSONArray("allInputs")?.length() ?: 0}")
                    
                    val allInputs = debug.optJSONArray("allInputs")
                    if (allInputs != null) {
                        for (i in 0 until allInputs.length()) {
                            val input = allInputs.getJSONObject(i)
                            Log.d(TAG, "Input $i: name=${input.optString("name")}, id=${input.optString("id")}, type=${input.optString("type")}, class=${input.optString("className")}")
                        }
                    }
                    
                    val foundInputs = debug.optJSONArray("foundInputs")
                    if (foundInputs != null) {
                        for (i in 0 until foundInputs.length()) {
                            Log.d(TAG, "Found input: ${foundInputs.getString(i)}")
                        }
                    }
                }
                
                Log.d(TAG, "Gutschein found: ${json.optBoolean("gutscheinFound")}")
                Log.d(TAG, "PIN found: ${json.optBoolean("pinFound")}")
                Log.d(TAG, "CAPTCHA found: ${json.optBoolean("captchaFound")}")
                
                if (success) {
                    formFilled = true
                    isFillingForm = false
                    
                    // Fields filled successfully - show the form to user
                    // User will submit manually after entering CAPTCHA
                    
                    showLoading(false)
                    binding.webView.visibility = View.VISIBLE
                    binding.tvCaptchaInstruction.visibility = View.VISIBLE
                    binding.tvCaptchaInstruction.text = 
                        "Die Gutscheinnummer und PIN wurden automatisch ausgefüllt.\n\n" +
                        "Bitte lösen Sie das CAPTCHA und klicken Sie dann auf 'Guthabenabfrage'."
                    
                    // Show buttons
                    binding.buttonsLayout.visibility = View.VISIBLE
                    binding.btnScanAnother.text = "Fertig"
                    binding.btnScanAnother.setOnClickListener {
                        finish()
                    }
                    
                    Log.d(TAG, "Form fields filled successfully. User will submit manually after entering CAPTCHA.")
                    
                    // Try to focus CAPTCHA field from Android side as well (backup to JavaScript focus)
                    handler.postDelayed({
                        focusCaptchaField()
                    }, 1000) // Wait 1 second for page to stabilize
                } else {
                    // Form fields not found, retry with longer delay
                    pageLoadAttempts++
                    isFillingForm = false // Reset flag to allow retry
                    
                    val inputCount = json.optJSONObject("debug")?.optJSONArray("allInputs")?.length() ?: 0
                    val iframeFound = json.optJSONObject("debug")?.optBoolean("iframeFound") ?: false
                    
                    Log.w(TAG, "Form fields not found, attempt $pageLoadAttempts/$MAX_ATTEMPTS")
                    Log.w(TAG, "Found $inputCount input fields, iframe found: $iframeFound")
                    
                    // Log all found inputs for debugging
                    if (json.has("debug")) {
                        val debug = json.getJSONObject("debug")
                        val allInputs = debug.optJSONArray("allInputs")
                        if (allInputs != null && allInputs.length() > 0) {
                            Log.d(TAG, "Available inputs on page:")
                            for (i in 0 until allInputs.length()) {
                                val input = allInputs.getJSONObject(i)
                                Log.d(TAG, "  Input $i: name='${input.optString("name")}', id='${input.optString("id")}', type='${input.optString("type")}', placeholder='${input.optString("placeholder")}'")
                            }
                        } else {
                            Log.w(TAG, "No inputs found on page - page may not be loaded yet or structure changed")
                        }
                    }

                    // For ALDI, show WebView after 2 attempts (so page can become interactive)
                    if (market.marketType == com.antisocial.giftcardchecker.model.MarketType.ALDI && pageLoadAttempts == 2) {
                        Log.d(TAG, "Showing WebView after 2 attempts to allow page interaction")
                        showLoading(false)
                        binding.webView.visibility = View.VISIBLE
                        // Continue retrying auto-fill in background
                    }
                    
                    // For Lidl, wait longer as content might load dynamically
                    val retryDelay = if (market.marketType == com.antisocial.giftcardchecker.model.MarketType.LIDL) {
                        3000L // Wait 3 seconds for Lidl
                    } else {
                        2000L // 2 seconds for others
                    }

                    if (pageLoadAttempts < MAX_ATTEMPTS) {
                        Log.d(TAG, "Retrying form fill in ${retryDelay}ms...")
                        handler.postDelayed({
                            fillFormFields()
                        }, retryDelay)
                    } else {
                        // Auto-fill failed - show error with more details
                        val errorMsg = if (inputCount == 0) {
                            "Form fields not found. Page may not be loaded yet or website structure changed."
                        } else {
                            "Form fields not found. Found $inputCount input fields but couldn't identify card number/PIN fields."
                        }
                        Log.w(TAG, errorMsg)
                        showError(BalanceResult.websiteChanged(errorMsg))
                    }
                }
            } catch (e: Exception) {
                isFillingForm = false // Reset flag on error
                Log.e(TAG, "Error parsing form fill result", e)
                Log.e(TAG, "Raw result was: $result")
                showError(BalanceResult.error(e.message))
            }
        }
    }

    /**
     * Sets up automatic form submission when CAPTCHA input field is filled.
     * Monitors the captcha input field and submits the form automatically when user enters the captcha solution.
     * Enhanced with comprehensive debugging and multiple detection strategies.
     */
    private fun setupAutoSubmitOnCaptchaFill() {
        Log.d(TAG, "=== Setting up auto-submit on CAPTCHA fill ===")
        
        val script = """
            (function() {
                var submitted = false;
                var monitoringAttempts = 0;
                var maxMonitoringAttempts = 30; // 30 seconds of retrying
                var iframeSrcHistory = [];
                
                // Debug logging function that sends to Android
                function debug(msg) {
                    console.log('[CAPTCHA-DEBUG] ' + msg);
                    try {
                        if (typeof Android !== 'undefined' && Android.log) {
                            Android.log('[CAPTCHA-DEBUG] ' + msg);
                        }
                    } catch (e) {}
                }
                
                debug('=== Auto-submit script initialized ===');
                debug('Current URL: ' + window.location.href);
                
                // Log all iframes on the page
                function logIframes() {
                    var iframes = document.querySelectorAll('iframe');
                    debug('Found ' + iframes.length + ' iframes on page');
                    for (var i = 0; i < iframes.length; i++) {
                        var iframe = iframes[i];
                        debug('Iframe ' + i + ': src=' + (iframe.src || 'none') + ', name=' + (iframe.name || 'none') + ', id=' + (iframe.id || 'none'));
                        
                        // Try to access iframe content
                        try {
                            var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                            if (iframeDoc) {
                                debug('  -> Iframe ' + i + ' is ACCESSIBLE (same-origin)');
                                var inputs = iframeDoc.querySelectorAll('input');
                                debug('  -> Found ' + inputs.length + ' inputs in iframe');
                                for (var j = 0; j < inputs.length; j++) {
                                    debug('     Input ' + j + ': name=' + (inputs[j].name || 'none') + ', type=' + (inputs[j].type || 'none'));
                                }
                            }
                        } catch (e) {
                            debug('  -> Iframe ' + i + ' is BLOCKED (cross-origin): ' + e.message);
                        }
                    }
                }
                
                // Log all inputs on main document
                function logMainInputs() {
                    var inputs = document.querySelectorAll('input');
                    debug('Found ' + inputs.length + ' inputs on main document');
                    for (var i = 0; i < inputs.length; i++) {
                        debug('Input ' + i + ': name=' + (inputs[i].name || 'none') + ', type=' + (inputs[i].type || 'none') + ', id=' + (inputs[i].id || 'none'));
                    }
                }
                
                function findCaptchaInput() {
                    debug('Searching for CAPTCHA input...');
                    
                    // Try to find captcha input in main document
                    var captchaInput = document.querySelector('input[name="input"]');
                    if (captchaInput) {
                        debug('Found CAPTCHA input in main document!');
                        return captchaInput;
                    }
                    debug('CAPTCHA input NOT found in main document');
                    
                    // If not found, try to find in iframe (for ALDI)
                    var iframes = document.querySelectorAll('iframe');
                    debug('Checking ' + iframes.length + ' iframes for CAPTCHA input...');
                    
                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            if (iframeDoc) {
                                debug('Iframe ' + i + ' accessible, searching for input[name="input"]...');
                                captchaInput = iframeDoc.querySelector('input[name="input"]');
                                if (captchaInput) {
                                    debug('Found CAPTCHA input in iframe ' + i + '!');
                                    return captchaInput;
                                }
                                debug('CAPTCHA input not found in iframe ' + i);
                            }
                        } catch (e) {
                            debug('Iframe ' + i + ' cross-origin blocked: ' + e.message);
                        }
                    }
                    
                    debug('CAPTCHA input NOT found anywhere');
                    return null;
                }
                
                function submitForm() {
                    if (submitted) {
                        debug('Form already submitted, skipping');
                        return;
                    }
                    
                    submitted = true;
                    debug('=== AUTO-SUBMITTING FORM ===');
                    
                    // Notify Android that form is being submitted (so it can show loading)
                    try {
                        if (typeof Android !== 'undefined' && Android.log) {
                            Android.log('AUTO_SUBMIT_START');
                        }
                    } catch (e) {
                        debug('Could not notify Android: ' + e.message);
                    }
                    
                    // Find submit button in main document
                    var submitButton = document.querySelector('input[name="check"]') ||
                                      document.querySelector('input[type="submit"]') ||
                                      document.querySelector('button[type="submit"]');
                    
                    if (submitButton) {
                        debug('Found submit button in main document, clicking...');
                        submitButton.click();
                        debug('Form submitted via button click');
                        return;
                    }
                    
                    debug('Submit button not found in main document, checking iframes...');
                    
                    // Try iframe if not found in main document
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            if (iframeDoc) {
                                submitButton = iframeDoc.querySelector('input[name="check"]') ||
                                              iframeDoc.querySelector('input[type="submit"]') ||
                                              iframeDoc.querySelector('button[type="submit"]');
                                if (submitButton) {
                                    debug('Found submit button in iframe ' + i + ', clicking...');
                                    submitButton.click();
                                    debug('Form submitted via iframe button click');
                                    return;
                                }
                                
                                // Try form submit
                                var form = iframeDoc.querySelector('form');
                                if (form) {
                                    debug('Found form in iframe ' + i + ', submitting...');
                                    form.submit();
                                    debug('Form submitted via iframe form.submit()');
                                    return;
                                }
                            }
                        } catch (e) {
                            debug('Could not access iframe ' + i + ': ' + e.message);
                        }
                    }
                    
                    // Try form submit on main document as fallback
                    var form = document.querySelector('form');
                    if (form) {
                        debug('Found form in main document, submitting...');
                        form.submit();
                        debug('Form submitted via form.submit()');
                    } else {
                        debug('ERROR: No submit button or form found!');
                    }
                }
                
                function setupCaptchaMonitoring() {
                    monitoringAttempts++;
                    debug('=== CAPTCHA monitoring attempt ' + monitoringAttempts + '/' + maxMonitoringAttempts + ' ===');
                    
                    // Log current state
                    logMainInputs();
                    logIframes();
                    
                    var captchaInput = findCaptchaInput();
                    
                    if (captchaInput) {
                        debug('SUCCESS: CAPTCHA input found! Setting up event listeners...');
                        
                        // Monitor input events
                        captchaInput.addEventListener('input', function(e) {
                            var value = e.target.value || '';
                            debug('CAPTCHA input event fired, value length: ' + value.length);
                            
                            if (value.length > 0 && !submitted) {
                                debug('Value detected, will submit in 500ms...');
                                setTimeout(function() {
                                    submitForm();
                                }, 500);
                            }
                        }, true);
                        
                        // Also monitor change events
                        captchaInput.addEventListener('change', function(e) {
                            var value = e.target.value || '';
                            debug('CAPTCHA change event fired, value length: ' + value.length);
                            
                            if (value.length > 0 && !submitted) {
                                debug('Value detected, will submit in 500ms...');
                                setTimeout(function() {
                                    submitForm();
                                }, 500);
                            }
                        }, true);
                        
                        // Monitor blur event (when user leaves the field)
                        captchaInput.addEventListener('blur', function(e) {
                            var value = e.target.value || '';
                            debug('CAPTCHA blur event fired, value length: ' + value.length);
                            
                            if (value.length > 0 && !submitted) {
                                debug('Value detected on blur, will submit in 500ms...');
                                setTimeout(function() {
                                    submitForm();
                                }, 500);
                            }
                        }, true);
                        
                        debug('Event listeners attached successfully!');
                        
                        // Notify Android that monitoring is set up
                        try {
                            if (typeof Android !== 'undefined' && Android.log) {
                                Android.log('CAPTCHA_MONITORING_ACTIVE');
                            }
                        } catch (e) {}
                        
                    } else {
                        debug('CAPTCHA input not found yet');
                        
                        if (monitoringAttempts < maxMonitoringAttempts) {
                            debug('Will retry in 1 second...');
                            setTimeout(function() {
                                setupCaptchaMonitoring();
                            }, 1000);
                        } else {
                            debug('Max monitoring attempts reached, giving up on CAPTCHA detection');
                            // Notify Android that auto-submit won't work
                            try {
                                if (typeof Android !== 'undefined' && Android.log) {
                                    Android.log('CAPTCHA_DETECTION_FAILED');
                                }
                            } catch (e) {}
                        }
                    }
                }
                
                // === ALTERNATIVE DETECTION: Monitor iframe URL changes ===
                function monitorIframeNavigation() {
                    debug('Setting up iframe navigation monitoring...');
                    
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        var iframe = iframes[i];
                        var currentSrc = iframe.src || '';
                        iframeSrcHistory[i] = currentSrc;
                        
                        // Set up load event listener
                        iframe.addEventListener('load', function(e) {
                            debug('Iframe load event detected!');
                            var newSrc = e.target.src || '';
                            debug('Iframe navigated to: ' + newSrc);
                            
                            // If iframe URL changed significantly, it might be a form submission
                            if (newSrc !== '' && newSrc !== iframeSrcHistory[i]) {
                                debug('Iframe URL changed! Old: ' + iframeSrcHistory[i] + ', New: ' + newSrc);
                                iframeSrcHistory[i] = newSrc;
                                
                                // Check if this looks like a balance result page
                                if (newSrc.indexOf('result') !== -1 || newSrc.indexOf('balance') !== -1) {
                                    debug('Possible balance result page detected!');
                                    try {
                                        if (typeof Android !== 'undefined' && Android.log) {
                                            Android.log('IFRAME_NAVIGATION_DETECTED');
                                        }
                                    } catch (e) {}
                                }
                            }
                        });
                    }
                }
                
                // === ALTERNATIVE DETECTION: MutationObserver for DOM changes ===
                function setupMutationObserver() {
                    debug('Setting up MutationObserver...');
                    
                    var observer = new MutationObserver(function(mutations) {
                        for (var i = 0; i < mutations.length; i++) {
                            var mutation = mutations[i];
                            
                            // Check for new iframes being added
                            if (mutation.type === 'childList') {
                                for (var j = 0; j < mutation.addedNodes.length; j++) {
                                    var node = mutation.addedNodes[j];
                                    if (node.tagName === 'IFRAME') {
                                        debug('New iframe added to DOM: ' + (node.src || 'no src'));
                                        // Re-setup monitoring when new iframe appears
                                        setTimeout(function() {
                                            setupCaptchaMonitoring();
                                            monitorIframeNavigation();
                                        }, 500);
                                    }
                                }
                            }
                        }
                    });
                    
                    observer.observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                    
                    debug('MutationObserver active');
                }
                
                // === ALTERNATIVE DETECTION: Listen for form submission events ===
                function setupFormSubmissionListener() {
                    debug('Setting up form submission listener...');
                    
                    // Listen for submit events on main document
                    document.addEventListener('submit', function(e) {
                        debug('Form submit event captured on main document!');
                        if (!submitted) {
                            try {
                                if (typeof Android !== 'undefined' && Android.log) {
                                    Android.log('AUTO_SUBMIT_START');
                                }
                            } catch (err) {}
                        }
                    }, true);
                    
                    debug('Form submission listener active');
                }
                
                // Start all monitoring mechanisms
                debug('Starting all monitoring mechanisms...');
                
                // Wait a short delay for page to stabilize
                setTimeout(function() {
                    setupCaptchaMonitoring();
                    monitorIframeNavigation();
                    setupMutationObserver();
                    setupFormSubmissionListener();
                }, 500);
                
                debug('Auto-submit script setup complete');
            })();
        """.trimIndent()
        
        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Auto-submit on CAPTCHA fill script injected: $result")
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
        binding.tvCaptchaInstruction.text = 
            "Bitte lösen Sie das CAPTCHA und klicken Sie dann auf 'Guthabenabfrage'.\n\n" +
            "Die Gutscheinnummer und PIN wurden bereits automatisch ausgefüllt."
        
        // Show buttons for manual CAPTCHA solving
        binding.buttonsLayout.visibility = View.VISIBLE
        binding.btnScanAnother.text = "Balance prüfen"
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
        checkingBalance = false // Reset checking flag
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
        checkingBalance = false // Reset checking flag
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
     * Setup click-triggered auto-fill that watches for user interaction with form fields.
     * When user clicks on a field, automatically fill it with the scanned data.
     */
    private fun setupClickTriggeredAutoFill() {
        // Start aggressive polling after page loads
        handler.postDelayed({
            startAggressiveAutoFill()
        }, 2000)
    }

    private fun startAggressiveAutoFill() {
        val script = """
            (function() {
                var cardNumber = '${giftCard.cardNumber}';
                var pin = '${giftCard.pin}';
                var filled = { card: false, pin: false };
                var attempts = 0;
                var maxAttempts = 60; // 60 attempts × 500ms = 30 seconds

                console.log('Starting aggressive auto-fill');

                function tryFillField(input, value, type) {
                    if (!input || !value || input.value === value) return false;

                    try {
                        input.focus();
                        input.value = value;
                        input.setAttribute('value', value);

                        // Trigger multiple events to ensure the website recognizes the input
                        ['input', 'change', 'keyup', 'blur'].forEach(function(eventType) {
                            var event = new Event(eventType, { bubbles: true, cancelable: true });
                            input.dispatchEvent(event);
                        });

                        console.log('✓ Filled ' + type + ': ' + (input.name || input.id || 'unknown'));
                        return true;
                    } catch (e) {
                        console.error('Error filling ' + type + ':', e);
                        return false;
                    }
                }

                function findAndFillFields(document) {
                    var foundCard = false;
                    var foundPin = false;

                    // Find card number field - try multiple selectors
                    if (!filled.card) {
                        var cardSelectors = [
                            'input[name="cardnumberfield"]',
                            'input[name*="card" i]',
                            'input[name*="gutschein" i]',
                            'input[placeholder*="0000"]',
                            'input[id*="card" i]',
                            'input[type="text"]'
                        ];

                        for (var i = 0; i < cardSelectors.length && !filled.card; i++) {
                            var inputs = document.querySelectorAll(cardSelectors[i]);
                            for (var j = 0; j < inputs.length && !filled.card; j++) {
                                var input = inputs[j];
                                var name = (input.name || '').toLowerCase();
                                var id = (input.id || '').toLowerCase();
                                var placeholder = (input.placeholder || '').toLowerCase();

                                // Skip search fields and other non-card fields
                                if (name.includes('search') || id.includes('search')) continue;

                                // Match card/gutschein fields
                                if (name.includes('card') || name.includes('gutschein') ||
                                    id.includes('card') || id.includes('gutschein') ||
                                    placeholder.includes('0000') || placeholder.includes('nummer')) {
                                    if (tryFillField(input, cardNumber, 'card')) {
                                        filled.card = true;
                                        foundCard = true;
                                    }
                                }
                            }
                        }
                    }

                    // Find PIN field - try multiple selectors
                    if (!filled.pin) {
                        var pinSelectors = [
                            'input[name="pin"]',
                            'input[id="myPw"]',
                            'input[name*="pin" i]',
                            'input[id*="pin" i]',
                            'input[id*="pw" i]',
                            'input[type="password"]'
                        ];

                        for (var i = 0; i < pinSelectors.length && !filled.pin; i++) {
                            var input = document.querySelector(pinSelectors[i]);
                            if (input && tryFillField(input, pin, 'PIN')) {
                                filled.pin = true;
                                foundPin = true;
                            }
                        }
                    }

                    return { foundCard: foundCard, foundPin: foundPin };
                }

                function tryFillInIframe() {
                    // Try to access iframes (will fail for cross-origin, but worth trying)
                    var iframes = document.getElementsByTagName('iframe');
                    console.log('Found ' + iframes.length + ' iframes');

                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            if (iframeDoc) {
                                console.log('Accessing iframe ' + i + ': ' + iframes[i].src);
                                var result = findAndFillFields(iframeDoc);
                                if (result.foundCard || result.foundPin) {
                                    console.log('Filled fields in iframe!');
                                    return true;
                                }
                            }
                        } catch (e) {
                            console.log('Cannot access iframe ' + i + ' (cross-origin): ' + iframes[i].src);
                        }
                    }
                    return false;
                }

                // Main polling loop
                var pollInterval = setInterval(function() {
                    attempts++;

                    // Try main document
                    var mainResult = findAndFillFields(document);

                    // Try iframes
                    var iframeResult = tryFillInIframe();

                    // Log progress
                    if (attempts % 10 === 0) {
                        console.log('Auto-fill attempt ' + attempts + '/' + maxAttempts +
                                  ' - Card: ' + filled.card + ', PIN: ' + filled.pin);
                    }

                    // Stop when both filled or max attempts reached
                    if ((filled.card && filled.pin) || attempts >= maxAttempts) {
                        clearInterval(pollInterval);
                        console.log('Auto-fill complete after ' + attempts + ' attempts. Card: ' +
                                  filled.card + ', PIN: ' + filled.pin);
                    }
                }, 500); // Check every 500ms (more aggressive)

                console.log('Auto-fill polling started (every 500ms)');
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Aggressive auto-fill script injected: $result")
        }
    }

    /**
     * JavaScript interface for callbacks from WebView.
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun simulateTouch(x: Int, y: Int) {
            Log.d(TAG, "simulateTouch called from JavaScript: ($x, $y)")
            handler.post {
                simulateTouchAt(x, y)
            }
        }
        
        @JavascriptInterface
        fun onBalanceResult(jsonString: String) {
            Log.d(TAG, "Balance result received: $jsonString")
            
            handler.post {
                try {
                    val json = JSONObject(jsonString)
                    val success = json.optBoolean("success", false)
                    val balance = json.optString("balance", "").takeIf { it.isNotEmpty() }
                    val error = json.optString("error", "").takeIf { it.isNotEmpty() }
                    val html = json.optString("html", "").takeIf { it.isNotEmpty() }

                    Log.d(TAG, "Parsed balance result - success: $success, balance: $balance, error: $error")
                    
                    // Log HTML if available for debugging (truncated)
                    html?.let {
                        if (it.isNotEmpty()) {
                            Log.d(TAG, "HTML length: ${it.length}, first 500 chars: ${it.take(500)}")
                        }
                    }

                    val result = when {
                        success && balance != null -> {
                            Log.d(TAG, "Successfully extracted balance: $balance")
                            BalanceResult.success(balance)
                        }
                        error == "invalid_card" -> {
                            Log.d(TAG, "Invalid card error detected")
                            BalanceResult.invalidCard()
                        }
                        error == "captcha_error" -> {
                            Log.d(TAG, "CAPTCHA error detected")
                            BalanceResult.error("CAPTCHA solution incorrect")
                        }
                        error == "balance_not_found" -> {
                            Log.e(TAG, "Balance not found in page")
                            // Try parsing HTML directly as fallback
                            html?.let {
                                val fallbackResult = market.parseBalanceResponse(it)
                                if (fallbackResult.isSuccess()) {
                                    Log.d(TAG, "Fallback parsing succeeded")
                                    fallbackResult
                                } else {
                                    BalanceResult.parsingError("Could not extract balance from page")
                                }
                            } ?: BalanceResult.parsingError("Could not extract balance from page")
                        }
                        else -> {
                            Log.e(TAG, "Unknown error or no balance found - error: $error")
                            BalanceResult.parsingError("Could not extract balance: ${error ?: "unknown error"}")
                        }
                    }

                    showResult(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing balance result JSON: $jsonString", e)
                    showError(BalanceResult.error("Error parsing result: ${e.message}"))
                }
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "WebView JS: $message")
            
            // Handle form submission detection (when user manually submits)
            if (message == "IFRAME_NAVIGATION_DETECTED" || message == "FORM_SUBMITTED_DETECTED") {
                handler.post {
                    Log.d(TAG, "Form submission detected - checking for balance result")
                    if (!checkingBalance) {
                        checkingBalance = true
                        showLoading(true)
                        binding.tvCaptchaInstruction.visibility = View.GONE
                    }
                    // Try to extract balance after a delay
                    handler.postDelayed({
                        extractBalance()
                    }, 2000)
                }
            }
        }
    }
    
    /**
     * Shows a manual submit button when automatic CAPTCHA detection fails.
     * This is a fallback for cross-origin iframe situations where we can't monitor the captcha input.
     */
    private fun showManualSubmitFallback() {
        Log.d(TAG, "Showing manual submit fallback button")
        
        binding.tvCaptchaInstruction.text = 
            "Die Gutscheinnummer und PIN wurden automatisch ausgefüllt.\n\n" +
            "Automatische Erkennung nicht möglich.\n" +
            "Bitte lösen Sie das CAPTCHA und tippen Sie dann auf 'Absenden'."
        binding.tvCaptchaInstruction.visibility = View.VISIBLE
        
        // Change button to manual submit
        binding.btnScanAnother.text = "Absenden"
        binding.btnScanAnother.setOnClickListener {
            Log.d(TAG, "Manual submit button clicked")
            checkingBalance = true
            showLoading(true)
            binding.tvCaptchaInstruction.visibility = View.GONE
            
            // Try to submit the form via JavaScript
            val submitScript = market.getFormSubmitScript()
            binding.webView.evaluateJavascript(submitScript) { result ->
                Log.d(TAG, "Manual form submit result: $result")
                // Wait for page to load/update, then extract balance
                handler.postDelayed({
                    extractBalance()
                }, 3000)
            }
        }
    }

    /**
     * Extracts the iframe src URL from the ALDI parent page and navigates to it.
     * This allows us to access the form directly (cookies are shared).
     */
    private fun extractAndNavigateToIframe() {
        val script = """
            (function() {
                var iframe = document.querySelector('iframe[src*="balancechecks.tx-gate.com"]') ||
                             document.querySelector('iframe[name="IR-iFrame"]') ||
                             document.querySelector('iframe');
                
                if (iframe && iframe.src) {
                    return iframe.src;
                }
                return null;
            })();
        """.trimIndent()
        
        binding.webView.evaluateJavascript(script) { result ->
            val iframeUrl = result.trim('"').replace("\\\"", "\"")
            if (iframeUrl != "null" && iframeUrl.isNotEmpty()) {
                Log.d(TAG, "Extracted iframe URL: $iframeUrl")
                handler.post {
                    binding.webView.loadUrl(iframeUrl)
                }
            } else {
                Log.w(TAG, "Could not extract iframe URL, using default")
                // Fallback to known iframe URL
                val fallbackUrl = (market as? com.antisocial.giftcardchecker.markets.AldiMarket)?.iframeFormUrl
                if (fallbackUrl != null) {
                    binding.webView.loadUrl(fallbackUrl)
                }
            }
        }
    }

    /**
     * Attempts to focus the CAPTCHA input field using a combination of JavaScript and native Android methods.
     * This ensures the keyboard opens on mobile devices.
     */
    private fun focusCaptchaField() {
        // First, ensure WebView has focus
        binding.webView.requestFocus()
        
        // Get CAPTCHA field coordinates and simulate touch
        val script = """
            (function() {
                function getCaptchaCoordinates() {
                    // Try to find CAPTCHA input in main document
                    var captchaInput = document.querySelector('input[name="input"]');
                    
                    if (captchaInput) {
                        try {
                            // Scroll into view first
                            captchaInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            
                            // Get bounding rectangle
                            var rect = captchaInput.getBoundingClientRect();
                            var x = rect.left + rect.width / 2;
                            var y = rect.top + rect.height / 2;
                            
                            // Add scroll offsets
                            x += window.scrollX || window.pageXOffset || 0;
                            y += window.scrollY || window.pageYOffset || 0;
                            
                            return { found: true, x: Math.round(x), y: Math.round(y), inIframe: false };
                        } catch(e) {
                            return { found: false, error: e.toString() };
                        }
                    }
                    
                    // Try to find in iframe
                    try {
                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            try {
                                var iframe = iframes[i];
                                var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                                if (iframeDoc) {
                                    var iframeCaptcha = iframeDoc.querySelector('input[name="input"]');
                                    if (iframeCaptcha) {
                                        // Scroll into view
                                        iframeCaptcha.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                        
                                        // Get iframe position
                                        var iframeRect = iframe.getBoundingClientRect();
                                        
                                        // Get captcha position relative to iframe
                                        var captchaRect = iframeCaptcha.getBoundingClientRect();
                                        
                                        // Calculate absolute position
                                        var x = iframeRect.left + captchaRect.left + captchaRect.width / 2;
                                        var y = iframeRect.top + captchaRect.top + captchaRect.height / 2;
                                        
                                        // Add scroll offsets
                                        x += window.scrollX || window.pageXOffset || 0;
                                        y += window.scrollY || window.pageYOffset || 0;
                                        
                                        return { found: true, x: Math.round(x), y: Math.round(y), inIframe: true };
                                    }
                                }
                            } catch(e) {
                                // Cross-origin, skip
                            }
                        }
                    } catch(e) {}
                    
                    return { found: false };
                }
                
                var coords = getCaptchaCoordinates();
                if (coords.found) {
                    // Call Android to simulate touch at coordinates
                    if (typeof Android !== 'undefined' && Android.simulateTouch) {
                        Android.simulateTouch(coords.x, coords.y);
                    }
                    
                    // Also try JavaScript focus as backup
                    setTimeout(function() {
                        var captchaInput = document.querySelector('input[name="input"]');
                        if (captchaInput) {
                            captchaInput.focus();
                            captchaInput.click();
                        }
                    }, 100);
                    
                    return JSON.stringify({ success: true, x: coords.x, y: coords.y });
                } else {
                    // Retry after delay
                    setTimeout(function() {
                        var coords2 = getCaptchaCoordinates();
                        if (coords2.found && typeof Android !== 'undefined' && Android.simulateTouch) {
                            Android.simulateTouch(coords2.x, coords2.y);
                        }
                    }, 500);
                    
                    return JSON.stringify({ success: false, retrying: true });
                }
            })();
        """.trimIndent()
        
        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "CAPTCHA coordinates result: $result")
            try {
                val json = JSONObject(result.trim('"').replace("\\\"", "\""))
                if (json.optBoolean("success", false)) {
                    val x = json.optInt("x", -1)
                    val y = json.optInt("y", -1)
                    if (x >= 0 && y >= 0) {
                        simulateTouchAt(x, y)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing CAPTCHA coordinates", e)
            }
        }
    }
    
    /**
     * Simulates a touch event at the specified coordinates to trigger keyboard.
     * Coordinates should be relative to the WebView content (from JavaScript getBoundingClientRect).
     * This is a more reliable method than JavaScript focus() on Android WebView.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun simulateTouchAt(x: Int, y: Int) {
        Log.d(TAG, "Simulating touch at WebView coordinates: ($x, $y)")
        
        // Ensure WebView has focus first
        binding.webView.requestFocus()
        
        // Create touch events - coordinates are already relative to WebView content
        val downTime = System.currentTimeMillis()
        
        try {
            // Use MotionEvent.obtain() - works on API 1+
            val downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x.toFloat(),
                y.toFloat(),
                0
            )
            
            val upEvent = MotionEvent.obtain(
                downTime,
                downTime + 100,
                MotionEvent.ACTION_UP,
                x.toFloat(),
                y.toFloat(),
                0
            )
            
            // Dispatch events on UI thread
            handler.post {
                try {
                    // First ensure WebView is focused and visible
                    binding.webView.requestFocus()
                    
                    // Dispatch touch events
                    binding.webView.dispatchTouchEvent(downEvent)
                    handler.postDelayed({
                        binding.webView.dispatchTouchEvent(upEvent)
                        downEvent.recycle()
                        upEvent.recycle()
                        Log.d(TAG, "Touch events dispatched successfully")
                    }, 100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error dispatching touch events", e)
                    downEvent.recycle()
                    upEvent.recycle()
                    // Fallback to JavaScript click
                    fallbackJavaScriptClick()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating touch events", e)
            // Fallback: use JavaScript click
            fallbackJavaScriptClick()
        }
    }
    
    /**
     * Fallback method: uses JavaScript to click the CAPTCHA field.
     */
    private fun fallbackJavaScriptClick() {
        binding.webView.requestFocus()
        handler.postDelayed({
            binding.webView.evaluateJavascript("""
                (function() {
                    var captcha = document.querySelector('input[name="input"]');
                    if (captcha) {
                        captcha.focus();
                        captcha.click();
                        return true;
                    }
                    // Try iframe
                    var iframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < iframes.length; i++) {
                        try {
                            var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                            if (iframeDoc) {
                                var iframeCaptcha = iframeDoc.querySelector('input[name="input"]');
                                if (iframeCaptcha) {
                                    iframeCaptcha.focus();
                                    iframeCaptcha.click();
                                    return true;
                                }
                            }
                        } catch(e) {}
                    }
                    return false;
                })();
            """.trimIndent()) { result ->
                Log.d(TAG, "Fallback JavaScript click result: $result")
            }
        }, 200)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)

        // Clear WebView data for privacy
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)

        binding.webView.destroy()
    }

    /**
     * Check if network is available before loading WebView.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "BalanceCheckActivity"
        private const val MAX_ATTEMPTS = 5
    }
}

