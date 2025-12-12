package com.antisocial.giftcardchecker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antisocial.giftcardchecker.captcha.CaptchaImageExtractor
import com.antisocial.giftcardchecker.captcha.CaptchaSolver
import com.antisocial.giftcardchecker.databinding.ActivityBalanceCheckBinding
import com.antisocial.giftcardchecker.markets.Market
import com.antisocial.giftcardchecker.markets.TxGateMarket
import com.antisocial.giftcardchecker.model.BalanceCheckState
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.BalanceStatus
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.settings.SettingsPreferences
import com.antisocial.giftcardchecker.utils.StateManager
import com.antisocial.giftcardchecker.utils.getParcelableExtraCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * Activity for checking gift card balance using WebView.
 * Loads the retailer's balance check page, fills in the form with JavaScript,
 * and extracts the balance result.
 */
@AndroidEntryPoint
class BalanceCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBalanceCheckBinding
    private lateinit var giftCard: GiftCard
    private lateinit var market: Market

    @Inject
    lateinit var captchaSolver: CaptchaSolver

    @Inject
    lateinit var captchaImageExtractor: CaptchaImageExtractor

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private val handler = Handler(Looper.getMainLooper())
    private val stateManager = StateManager(TAG)
    private var pageLoadAttempts = 0
    private var tabClickAttempts = 0
    private var captchaSolveAttempts = 0
    private val MAX_TAB_CLICK_ATTEMPTS = 5
    private val MAX_CAPTCHA_SOLVE_ATTEMPTS = 3
    private val processedUrls = mutableSetOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalanceCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get gift card from intent
        giftCard = intent.getParcelableExtraCompat(GiftCard.EXTRA_GIFT_CARD) ?: run {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Get market implementation
        market = Market.forType(giftCard.marketType)

        setupUI()
        setupWebView()
        setupBackPressedHandler()
        observeState()

        // Check network connectivity before loading
        if (!isNetworkAvailable()) {
            stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.networkError(getString(R.string.no_internet_connection))))
            return
        }

        // Always use auto-fill - manual entry is not acceptable
        loadBalanceCheckPage()
    }

    /**
     * Sets up the back button handler to navigate WebView history.
     */
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Observes state changes and updates UI accordingly
     */
    private fun observeState() {
        lifecycleScope.launch {
            stateManager.state.collect { state ->
                Log.d(TAG, "State changed to: $state")
                updateUI(state)
            }
        }
    }

    /**
     * Updates the entire UI based on the current state
     */
    private fun updateUI(state: BalanceCheckState) {
        when (state) {
            is BalanceCheckState.Loading -> {
                showLoading(true)
                binding.webView.visibility = View.GONE
                binding.resultCard.visibility = View.GONE
                binding.tvCaptchaInstruction.visibility = View.GONE
                binding.buttonsLayout.visibility = View.GONE
            }
            is BalanceCheckState.FillingForm -> {
                showLoading(true)
                binding.tvLoadingText.text = getString(R.string.form_filling_attempt, state.attemptNumber)
            }
            is BalanceCheckState.SolvingCaptcha -> {
                showLoading(true)
                binding.tvLoadingText.text = getString(R.string.captcha_solving)
                binding.tvCaptchaInstruction.visibility = View.GONE
            }
            is BalanceCheckState.WaitingForCaptcha -> {
                showLoading(false)
                binding.webView.visibility = View.VISIBLE
                binding.tvCaptchaInstruction.visibility = View.VISIBLE
                binding.tvCaptchaInstruction.text = getString(R.string.captcha_instruction)
                binding.buttonsLayout.visibility = View.VISIBLE
                binding.btnScanAnother.text = getString(R.string.done)
                binding.btnScanAnother.setOnClickListener { finish() }
            }
            is BalanceCheckState.CheckingBalance -> {
                showLoading(true)
                binding.tvLoadingText.text = getString(R.string.balance_retrieving)
                binding.tvCaptchaInstruction.visibility = View.GONE
            }
            is BalanceCheckState.Success -> {
                showResult(state.result)
            }
            is BalanceCheckState.Error -> {
                showError(state.result)
            }
        }
    }
    

    private fun setupUI() {
        binding.tvTitle.text = "${market.displayName} - ${getString(R.string.balance_check)}"
        
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
                    if (request != null) {
                        val url = request.url.toString()
                        
                        // Block images for REWE except captcha images and form-related images
                        if (market.marketType == com.antisocial.giftcardchecker.model.MarketType.REWE) {
                            // Only block if it's clearly an image file (by extension)
                            // Don't block based on Accept header or path alone to avoid false positives
                            val isImageFile = url.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|bmp|ico)(\\?.*)?$", RegexOption.IGNORE_CASE))
                            
                            if (isImageFile) {
                                // Allow captcha images - check for common captcha URL patterns
                                val isCaptchaImage = url.contains("captcha", ignoreCase = true) ||
                                                    url.contains("security", ignoreCase = true) ||
                                                    url.contains("verify", ignoreCase = true) ||
                                                    url.contains("challenge", ignoreCase = true) ||
                                                    url.contains("recaptcha", ignoreCase = true) ||
                                                    url.contains("hcaptcha", ignoreCase = true) ||
                                                    url.contains("turnstile", ignoreCase = true) ||
                                                    url.contains("code", ignoreCase = true) // Some captchas use "code" in URL
                                
                                // Allow form-related images (icons, sprites that might be needed for form rendering)
                                val isFormRelated = url.contains("/form/", ignoreCase = true) ||
                                                   url.contains("/input/", ignoreCase = true) ||
                                                   url.contains("/field/", ignoreCase = true) ||
                                                   url.contains("icon", ignoreCase = true) ||
                                                   url.contains("sprite", ignoreCase = true)
                                
                                // Only block decorative images (logos, banners, etc.) in common image directories
                                val isDecorativeImage = (url.contains("/images/", ignoreCase = true) ||
                                                        url.contains("/img/", ignoreCase = true) ||
                                                        url.contains("/assets/images/", ignoreCase = true) ||
                                                        url.contains("/static/images/", ignoreCase = true) ||
                                                        url.contains("/media/", ignoreCase = true) ||
                                                        url.contains("/uploads/", ignoreCase = true)) &&
                                                       !isCaptchaImage && !isFormRelated
                                
                                if (isDecorativeImage) {
                                    // Block decorative images only
                                    Log.d(TAG, "Blocking decorative image for REWE: $url")
                                    return android.webkit.WebResourceResponse(
                                        "image/png",
                                        "utf-8",
                                        java.io.ByteArrayInputStream(ByteArray(0))
                                    )
                                } else if (isCaptchaImage) {
                                    Log.d(TAG, "Allowing captcha image for REWE: $url")
                                } else if (isFormRelated) {
                                    Log.d(TAG, "Allowing form-related image for REWE: $url")
                                }
                            }
                        }
                        
                        // Add referrer header for ALDI iframe URL to prevent blank page
                        if (url.contains("balancechecks.tx-gate.com")) {
                            val headers = request.requestHeaders.toMutableMap()
                            // Add referrer header to make it look like we came from the parent page
                            if (!headers.containsKey("Referer")) {
                                headers["Referer"] = "https://www.helaba.com/de/aldi/"
                            }
                            // Ensure we have proper headers
                            headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                            headers["Accept-Language"] = "de-DE,de;q=0.9,en;q=0.8"
                            
                            Log.d(TAG, "Intercepting request to $url with referrer: ${headers["Referer"]}")
                            
                            // Note: We can't modify headers in shouldInterceptRequest easily
                            // So we'll handle this in loadUrl instead
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
                
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Only allow navigation for main frame requests
                    if (request?.isForMainFrame == true) {
                        val url = request.url.toString()
                        Log.d(TAG, "Navigation requested to: $url")
                        
                        // Prevent navigation if we've already processed this URL and form is filled
                        if (processedUrls.contains(url) && stateManager.currentState.isWaitingForCaptcha()) {
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
                            // URL changed to a new page - state will be managed by state machine
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

                    // Ignore iframe, resource URLs, and blank pages - only process main frame URLs
                    if (url == null || url == "about:blank" || url.startsWith("data:") || url.startsWith("javascript:") ||
                        url.contains("_Incapsula_Resource") || url.contains("csp_report") ||
                        url.contains("mell-my-spricking")) {
                        Log.d(TAG, "Ignoring resource/iframe/blank URL: $url")
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
                    
                    // Inject script to suppress/hide long error messages in webview (especially for Lidl)
                    if (giftCard.marketType == com.antisocial.giftcardchecker.model.MarketType.LIDL) {
                        view?.evaluateJavascript("""
                            (function() {
                                // Hide any error messages that might be displayed in the DOM
                                function hideLongErrorMessages() {
                                    var allElements = document.querySelectorAll('*');
                                    for (var i = 0; i < allElements.length; i++) {
                                        var el = allElements[i];
                                        var text = el.textContent || el.innerText || '';
                                        // Hide elements with very long error-like text (stack traces, etc.)
                                        if (text.length > 500 && (
                                            text.indexOf('Error') !== -1 ||
                                            text.indexOf('error') !== -1 ||
                                            text.indexOf('Exception') !== -1 ||
                                            text.indexOf('at ') !== -1 || // Stack trace
                                            text.indexOf('TypeError') !== -1 ||
                                            text.indexOf('ReferenceError') !== -1
                                        )) {
                                            el.style.display = 'none';
                                            el.style.visibility = 'hidden';
                                        }
                                    }
                                }
                                
                                // Run immediately
                                hideLongErrorMessages();
                                
                                // Also watch for new elements being added
                                var observer = new MutationObserver(function(mutations) {
                                    hideLongErrorMessages();
                                });
                                
                                if (document.body) {
                                    observer.observe(document.body, {
                                        childList: true,
                                        subtree: true
                                    });
                                } else {
                                    document.addEventListener('DOMContentLoaded', function() {
                                        hideLongErrorMessages();
                                        if (document.body) {
                                            observer.observe(document.body, {
                                                childList: true,
                                                subtree: true
                                            });
                                        }
                                    });
                                }
                            })();
                        """.trimIndent(), null)
                    }
                    
                    // If we're checking balance (form was submitted), don't fill form again
                    // Note: extractBalance() is called from submitForm() callback, not here,
                    // to avoid race conditions between multiple extractBalance() calls
                    if (stateManager.currentState.isCheckingBalance()) {
                        Log.d(TAG, "Form was submitted, waiting for submitForm callback to extract balance")
                        return
                    }
                    
                    // Check if we're on the iframe URL (for ALDI, we load this directly now)
                    val isIframeUrl = normalizedUrl.contains("balancechecks.tx-gate.com")
                    
                    if (isIframeUrl) {
                        Log.d(TAG, "Loaded iframe URL directly (same as browser), will fill form")
                    }
                    
                    val currentState = stateManager.currentState
                    if (!currentState.isWaitingForCaptcha() && !currentState.isFillingForm() && !currentState.isTerminal()) {
                        // Wait for form to be fully loaded and rendered
                        // The ALDI form might need more time to initialize, especially if it uses JavaScript
                        // REWE may need more time after image blocking optimizations
                        // If we're on the iframe URL, wait 2 seconds since form should be ready
                        val waitTime = when {
                            isIframeUrl -> 2000L // 2 seconds for direct iframe URL
                            market.marketType == com.antisocial.giftcardchecker.model.MarketType.ALDI -> 3000L // 3 seconds for ALDI main page (to allow tab click)
                            market.marketType == com.antisocial.giftcardchecker.model.MarketType.REWE -> 3000L // 3 seconds for REWE (page may need time to render)
                            else -> 2000L // 2 seconds for others
                        }

                        Log.d(TAG, "Waiting $waitTime ms before filling form")
                        handler.postDelayed({
                            fillFormFields()
                        }, waitTime)
                    } else {
                        Log.d(TAG, "Skipping fillFormFields - currentState=$currentState")
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
                        // Truncate long error descriptions
                        val truncatedDesc = if (errorDescription.length > 200) {
                            errorDescription.take(200) + "..."
                        } else {
                            errorDescription
                        }
                        stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.networkError("Failed to load page: $truncatedDesc")))
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
                        stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.networkError("HTTP error $statusCode")))
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
                        
                        // Truncate long messages to prevent log spam (especially for Lidl)
                        val messageText = it.message() ?: ""
                        val truncatedMessage = if (messageText.length > 500) {
                            messageText.take(500) + "... (truncated, ${messageText.length} chars total)"
                        } else {
                            messageText
                        }
                        
                        android.util.Log.println(logLevel, TAG, "WebView Console [$level]: [${it.sourceId()}:${it.lineNumber()}] $truncatedMessage")
                        
                        // If it's an error and we're checking Lidl, log it prominently (truncated)
                        if (it.messageLevel() == ConsoleMessage.MessageLevel.ERROR && 
                            giftCard.marketType == com.antisocial.giftcardchecker.model.MarketType.LIDL) {
                            Log.e(TAG, "LIDL JavaScript Error: $truncatedMessage at ${it.sourceId()}:${it.lineNumber()}")
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
        stateManager.transitionTo(BalanceCheckState.Loading)
        pageLoadAttempts = 0
        tabClickAttempts = 0
        processedUrls.clear() // Reset processed URLs

        // Reset WebView zoom to default before loading
        // This ensures consistent zoom behavior across activity recreations
        binding.webView.setInitialScale(0) // 0 = use default zoom
        binding.webView.settings.loadWithOverviewMode = true
        binding.webView.settings.useWideViewPort = true

        // Clear any previous page content to ensure clean zoom state
        // Load about:blank first, then the actual URL after a short delay
        binding.webView.loadUrl("about:blank")

        // For ALDI and Lidl, navigate directly to the iframe URL (same as browser)
        // This avoids the blank page issue and works immediately
        val urlToLoad = when (market.marketType) {
            com.antisocial.giftcardchecker.model.MarketType.ALDI -> {
                val iframeUrl = (market as? com.antisocial.giftcardchecker.markets.AldiMarket)?.iframeFormUrl
                iframeUrl ?: market.balanceCheckUrl
            }
            com.antisocial.giftcardchecker.model.MarketType.LIDL -> {
                val iframeUrl = (market as? com.antisocial.giftcardchecker.markets.LidlMarket)?.iframeFormUrl
                iframeUrl ?: market.balanceCheckUrl
            }
            else -> market.balanceCheckUrl
        }

        Log.d(TAG, "Loading balance check URL: $urlToLoad")

        // Delay actual page load slightly to ensure blank page clears zoom state
        handler.postDelayed({
            // For ALDI and Lidl iframe URLs, load with referrer header to prevent blank page
            if ((market.marketType == com.antisocial.giftcardchecker.model.MarketType.ALDI ||
                 market.marketType == com.antisocial.giftcardchecker.model.MarketType.LIDL) &&
                urlToLoad.contains("balancechecks.tx-gate.com")) {
                // Use loadUrl with additional headers (requires API 21+)
                val referrer = when (market.marketType) {
                    com.antisocial.giftcardchecker.model.MarketType.ALDI -> "https://www.helaba.com/de/aldi/"
                    com.antisocial.giftcardchecker.model.MarketType.LIDL -> "https://www.lidl.de/c/lidl-geschenkkarten/s10007775"
                    else -> ""
                }
                val headers = mapOf(
                    "Referer" to referrer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                    "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8"
                )
                Log.d(TAG, "Loading with headers: $headers")
                binding.webView.loadUrl(urlToLoad, headers)
            } else {
                binding.webView.loadUrl(urlToLoad)
            }
        }, 100) // 100ms delay to allow blank page to reset zoom state
    }

    private fun fillFormFields() {
        val currentState = stateManager.currentState

        // Skip if form already filled or currently filling
        if (currentState is BalanceCheckState.WaitingForCaptcha ||
            currentState is BalanceCheckState.CheckingBalance ||
            currentState.isTerminal()) {
            Log.d(TAG, "Form already filled or terminal state, skipping (current: $currentState)")
            return
        }

        if (currentState.isFillingForm()) {
            Log.d(TAG, "Already filling form, skipping duplicate call")
            return
        }

        // Always use auto-fill - manual entry is not acceptable

        val attemptNumber = pageLoadAttempts + 1
        stateManager.transitionTo(BalanceCheckState.FillingForm(attemptNumber))
        Log.d(TAG, "Filling form fields (attempt $attemptNumber)...")
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
                    stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.error("Empty response from form fill script")))
                    return@evaluateJavascript
                }
                
                Log.d(TAG, "Cleaned result length: ${cleanedResult.length}")
                // Truncate very long results before parsing to prevent JSON parsing errors
                if (cleanedResult.length > 50000) {
                    Log.w(TAG, "Result is very long (${cleanedResult.length} chars), truncating before parsing")
                    // Try to extract just the essential fields before the debug section gets too large
                    val successMatch = Regex(""""success"\s*:\s*(true|false)""").find(cleanedResult)
                    val cardFoundMatch = Regex(""""cardNumberFound"\s*:\s*(true|false)""").find(cleanedResult)
                    val pinFoundMatch = Regex(""""pinFound"\s*:\s*(true|false)""").find(cleanedResult)
                    
                    if (successMatch != null && cardFoundMatch != null && pinFoundMatch != null) {
                        // Reconstruct minimal JSON with just essential fields
                        cleanedResult = """{"success":${successMatch.groupValues[1]},"cardNumberFound":${cardFoundMatch.groupValues[1]},"pinFound":${pinFoundMatch.groupValues[1]},"debug":{"url":"${market.balanceCheckUrl}","allInputs":[],"error":"result_truncated"}}"""
                        Log.d(TAG, "Reconstructed minimal result: $cleanedResult")
                    } else {
                        // Fallback: truncate at safe point
                        cleanedResult = cleanedResult.take(10000) + "...\"}"
                        Log.w(TAG, "Truncated result to 10000 chars")
                    }
                } else if (cleanedResult.length > 1000) {
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
                            stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.error("JavaScript interface not available. Please try again.")))
                        }
                        "javascript_error" -> {
                            // Truncate long error messages to prevent UI issues
                            val truncatedError = if (errorMessage.length > 200) {
                                errorMessage.take(200) + "..."
                            } else {
                                errorMessage
                            }
                            stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.error("JavaScript error: $truncatedError")))
                        }
                        else -> {
                            stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.error("Form fill error: $error")))
                        }
                    }
                    return@evaluateJavascript
                }
                
                // Check if tab was clicked but iframe not loaded yet
                val tabClicked = json.optBoolean("tabClicked", false)
                val iframeLoaded = json.optBoolean("iframeLoaded", false)
                
                if (tabClicked && !iframeLoaded) {
                    tabClickAttempts++
                    if (tabClickAttempts < MAX_TAB_CLICK_ATTEMPTS) {
                        Log.d(TAG, "Tab clicked but iframe not loaded yet, waiting and retrying... (attempt $tabClickAttempts/$MAX_TAB_CLICK_ATTEMPTS)")
                        // State will transition back to Loading and retry
                        stateManager.transitionTo(BalanceCheckState.Loading)
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
                    val captchaFound = json.optBoolean("captchaFound", false)

                    // Check if auto-CAPTCHA solving is enabled and CAPTCHA was found
                    if (settingsPreferences.autoCaptchaEnabled && captchaFound) {
                        Log.d(TAG, "Form fields filled. Auto-CAPTCHA solving enabled, attempting to solve...")
                        captchaSolveAttempts = 0  // Reset retry counter for fresh solve
                        stateManager.transitionTo(BalanceCheckState.SolvingCaptcha)
                        extractAndSolveCaptcha()
                    } else {
                        // Manual CAPTCHA entry
                        stateManager.transitionTo(BalanceCheckState.WaitingForCaptcha)
                        Log.d(TAG, "Form fields filled successfully. User will submit manually after entering CAPTCHA.")

                        // Try to focus CAPTCHA field from Android side as well (backup to JavaScript focus)
                        handler.postDelayed({
                            focusCaptchaField()
                        }, 1000) // Wait 1 second for page to stabilize
                    }
                } else {
                    // Form fields not found, retry with longer delay
                    pageLoadAttempts++
                    
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
                    
                    // For ALDI and Lidl, wait longer as content might load dynamically
                    // For REWE, also wait longer as page might need more time to fully render after image blocking
                    val retryDelay = when (market.marketType) {
                        com.antisocial.giftcardchecker.model.MarketType.ALDI -> 3000L // 3 seconds for ALDI main page (to allow tab click)
                        com.antisocial.giftcardchecker.model.MarketType.LIDL -> 3000L // 3 seconds for Lidl (similar to ALDI)
                        com.antisocial.giftcardchecker.model.MarketType.REWE -> 3000L // 3 seconds for REWE (page may need time to render)
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
                        stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.websiteChanged(errorMsg)))
                    }
                }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing form fill result", e)
                    Log.e(TAG, "Raw result was: $result")
                    // Truncate long error messages
                    val errorMsg = e.message?.let { msg ->
                        if (msg.length > 200) msg.take(200) + "..." else msg
                    } ?: "An error occurred"
                    stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.error(errorMsg)))
                }
        }
    }

    /**
     * Sets up listener to detect when the user submits the form.
     * Does NOT auto-submit or interfere with user input - only listens for form submission.
     */
    private fun setupAutoSubmitOnCaptchaFill() {
        Log.d(TAG, "=== Setting up form submission listener ===")

        val script = """
            (function() {
                // Only listen for form submit events (user clicking submit button)
                document.addEventListener('submit', function(e) {
                    console.log('[CAPTCHA] Form submit event detected');
                    try {
                        if (typeof Android !== 'undefined' && Android.log) {
                            Android.log('FORM_SUBMITTED_DETECTED');
                        }
                    } catch (err) {}
                }, false);

                console.log('[CAPTCHA] Form submission listener active');
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Form submission listener injected: $result")
        }
    }

    private fun submitForm() {
        if (stateManager.currentState.isCheckingBalance()) {
            Log.d(TAG, "Already checking balance, skipping duplicate submit")
            return
        }

        stateManager.transitionTo(BalanceCheckState.CheckingBalance)

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
        // Guard: Only extract balance if we're in the correct state
        val currentState = stateManager.currentState
        if (currentState !is BalanceCheckState.CheckingBalance) {
            Log.d(TAG, "extractBalance() called but not in CheckingBalance state (current: $currentState), skipping")
            return
        }

        Log.d(TAG, "Extracting balance...")

        // Get the page HTML and check for balance
        binding.webView.evaluateJavascript(
            "(function() { return document.body.innerText; })();"
        ) { result ->
            // Re-check state inside callback as it may have changed
            if (stateManager.currentState !is BalanceCheckState.CheckingBalance) {
                Log.d(TAG, "State changed during extractBalance(), skipping result processing")
                return@evaluateJavascript
            }

            val pageText = result.trim('"').replace("\\n", "\n").replace("\\\"", "\"")
            Log.d(TAG, "Page text length: ${pageText.length}")
            Log.d(TAG, "Page text content: $pageText")

            // Check for CAPTCHA error first (before other error checks)
            val isCaptchaError = market is TxGateMarket && (market as TxGateMarket).isCaptchaErrorPageLoaded(pageText)
            Log.d(TAG, "isCaptchaError check: $isCaptchaError (contains lösung: ${pageText.lowercase().contains("lösung")}, contains falsch: ${pageText.lowercase().contains("falsch")})")
            if (isCaptchaError) {
                Log.d(TAG, "CAPTCHA error detected in extractBalance()")
                handleCaptchaError()
                return@evaluateJavascript
            }

            // Parse the response using market-specific logic
            val balanceResult = market.parseBalanceResponse(pageText)

            if (balanceResult.isSuccess()) {
                stateManager.transitionTo(BalanceCheckState.Success(balanceResult))
            } else if (market.isErrorPageLoaded(pageText)) {
                stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.invalidCard()))
            } else {
                // Try JavaScript extraction
                val extractScript = market.getBalanceExtractionScript()
                binding.webView.evaluateJavascript(extractScript, null)

                // If no callback received within timeout, show error
                // But only if we're still in CheckingBalance state (not retrying CAPTCHA, not already done)
                handler.postDelayed({
                    val currentState = stateManager.currentState
                    if (currentState is BalanceCheckState.CheckingBalance) {
                        Log.d(TAG, "Balance extraction timeout - no result received")
                        stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.parsingError("Could not extract balance")))
                    } else {
                        Log.d(TAG, "Balance extraction timeout fired but state already changed to: $currentState")
                    }
                }, 5000)
            }
        }
    }

    /**
     * Handle CAPTCHA error - retry auto-solving if attempts remaining, otherwise show error.
     */
    private fun handleCaptchaError() {
        Log.d(TAG, "Handling CAPTCHA error - attempts: $captchaSolveAttempts/$MAX_CAPTCHA_SOLVE_ATTEMPTS")

        if (settingsPreferences.autoCaptchaEnabled && captchaSolveAttempts < MAX_CAPTCHA_SOLVE_ATTEMPTS) {
            Log.d(TAG, "Will retry CAPTCHA solving (attempt ${captchaSolveAttempts + 1}/$MAX_CAPTCHA_SOLVE_ATTEMPTS)")
            // Delay to allow new CAPTCHA image to load
            handler.postDelayed({
                retryCaptchaSolving()
            }, 1500)
        } else {
            // No more retries - fall back to manual entry
            Log.d(TAG, "Max CAPTCHA attempts reached, falling back to manual entry")
            Toast.makeText(this, getString(R.string.auto_captcha_failed), Toast.LENGTH_SHORT).show()
            stateManager.transitionTo(BalanceCheckState.WaitingForCaptcha)
            focusCaptchaField()
        }
    }

    private fun showCaptchaMode() {
        showLoading(false)
        binding.webView.visibility = View.VISIBLE
        binding.tvCaptchaInstruction.visibility = View.VISIBLE
        binding.tvCaptchaInstruction.text = getString(R.string.captcha_instruction_short)
        
        // Show buttons for manual CAPTCHA solving
        binding.buttonsLayout.visibility = View.VISIBLE
        binding.btnScanAnother.text = getString(R.string.check_balance_button)
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
        // State transition handled by caller
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

        binding.ivResultIcon.setImageResource(android.R.drawable.ic_dialog_info)
        binding.ivResultIcon.setColorFilter(getColor(R.color.success))
        binding.tvResultStatus.text = getString(R.string.balance_found)
        binding.tvBalance.text = result.getFormattedBalance()
        binding.tvBalance.visibility = View.VISIBLE
        binding.tvErrorMessage.visibility = View.GONE
        binding.tvCardInfo.text = getString(R.string.card_label, giftCard.getMaskedCardNumber())
    }

    private fun showError(result: BalanceResult) {
        // State transition handled by caller
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
        binding.tvResultStatus.text = getString(R.string.error)
        binding.tvBalance.visibility = View.GONE
        binding.tvErrorMessage.text = result.getDisplayMessage(this)
        binding.tvErrorMessage.visibility = View.VISIBLE
        binding.tvCardInfo.text = getString(R.string.card_label, giftCard.getMaskedCardNumber())
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
                // Guard: Only process if we're in CheckingBalance state
                val currentState = stateManager.currentState
                if (currentState !is BalanceCheckState.CheckingBalance) {
                    Log.d(TAG, "onBalanceResult received but not in CheckingBalance state (current: $currentState), ignoring")
                    return@post
                }

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
                            Log.d(TAG, "CAPTCHA error detected - solution was incorrect")
                            // Check if we should retry auto-CAPTCHA solving
                            if (settingsPreferences.autoCaptchaEnabled && captchaSolveAttempts < MAX_CAPTCHA_SOLVE_ATTEMPTS) {
                                Log.d(TAG, "Will retry CAPTCHA solving (attempt ${captchaSolveAttempts + 1}/$MAX_CAPTCHA_SOLVE_ATTEMPTS)")
                                // Delay to allow new CAPTCHA image to load
                                handler.postDelayed({
                                    retryCaptchaSolving()
                                }, 1500)
                                return@post // Don't transition to Error state yet
                            }
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

                    // Transition to appropriate state based on result
                    if (result.isSuccess()) {
                        stateManager.transitionTo(BalanceCheckState.Success(result))
                    } else {
                        stateManager.transitionTo(BalanceCheckState.Error(result))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing balance result JSON: $jsonString", e)
                    // Truncate long error messages
                    val errorMsg = e.message?.let { msg ->
                        if (msg.length > 200) msg.take(200) + "..." else msg
                    } ?: "Error parsing result"
                    stateManager.transitionTo(BalanceCheckState.Error(BalanceResult.error(errorMsg)))
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
                    if (!stateManager.currentState.isCheckingBalance()) {
                        stateManager.transitionTo(BalanceCheckState.CheckingBalance)
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
        getString(R.string.captcha_instruction_short) + "\n\n" +
            getString(R.string.auto_detection_not_possible) + "\n" +
            getString(R.string.captcha_submit_instruction)
        binding.tvCaptchaInstruction.visibility = View.VISIBLE

        // Change button to manual submit
        binding.btnScanAnother.text = getString(R.string.submit)
        binding.btnScanAnother.setOnClickListener {
            Log.d(TAG, "Manual submit button clicked")
            stateManager.transitionTo(BalanceCheckState.CheckingBalance)

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

    // ==================== Auto-CAPTCHA Solving ====================

    /**
     * Extract CAPTCHA image and attempt to solve it automatically.
     */
    private fun extractAndSolveCaptcha() {
        Log.d(TAG, "Extracting CAPTCHA image...")

        // Load the CAPTCHA extraction JavaScript
        val script = loadCaptchaExtractScript()

        binding.webView.evaluateJavascript(script) { result ->
            lifecycleScope.launch {
                try {
                    val cleanResult = result?.trim('"')?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\") ?: "{}"
                    val json = JSONObject(cleanResult)

                    Log.d(TAG, "CAPTCHA extraction result: found=${json.optBoolean("found")}, error=${json.optString("error")}")

                    if (json.optBoolean("found", false)) {
                        val imageUrl = json.optString("imageUrl", "")
                        val imageBase64 = json.optString("imageBase64", "")

                        Log.d(TAG, "CAPTCHA image URL: $imageUrl")
                        Log.d(TAG, "CAPTCHA base64 available: ${imageBase64.isNotEmpty()}")

                        // Try to get the image
                        val bitmap: Bitmap? = if (imageBase64.isNotEmpty() && !imageBase64.startsWith("data:,")) {
                            // Decode base64 image
                            captchaImageExtractor.decodeBase64Image(imageBase64)
                        } else if (imageUrl.isNotEmpty()) {
                            // Download image from URL
                            captchaImageExtractor.downloadCaptchaImage(imageUrl)
                        } else {
                            null
                        }

                        if (bitmap != null) {
                            Log.d(TAG, "CAPTCHA image obtained: ${bitmap.width}x${bitmap.height}")
                            solveCaptchaWithModel(bitmap)
                        } else {
                            Log.w(TAG, "Failed to get CAPTCHA image")
                            fallbackToManualCaptcha("Could not load CAPTCHA image")
                        }
                    } else {
                        val error = json.optString("error", "Unknown error")
                        Log.w(TAG, "CAPTCHA extraction failed: $error")
                        fallbackToManualCaptcha(error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting CAPTCHA", e)
                    fallbackToManualCaptcha(e.message ?: "Extraction error")
                }
            }
        }
    }

    /**
     * Run ONNX model inference on the CAPTCHA image.
     */
    private fun solveCaptchaWithModel(bitmap: Bitmap) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Running CAPTCHA model inference...")

                val solution = withContext(Dispatchers.Default) {
                    captchaSolver.solve(bitmap)
                }

                if (solution != null && solution.isNotEmpty()) {
                    Log.d(TAG, "CAPTCHA solved: $solution")
                    // Auto-submit on retry attempts, wait for user on first attempt
                    val autoSubmit = captchaSolveAttempts > 0
                    fillCaptchaAndSubmit(solution, autoSubmit)
                } else {
                    Log.w(TAG, "Model returned empty/null solution")
                    fallbackToManualCaptcha("Model inference failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error solving CAPTCHA", e)
                fallbackToManualCaptcha(e.message ?: "Solving error")
            }
        }
    }

    /**
     * Fill the CAPTCHA field with the AI solution.
     * Simulates realistic typing to ensure the form recognizes the input.
     * @param solution The CAPTCHA solution to fill
     * @param autoSubmit If true, automatically submit the form after filling (used for retries)
     */
    private fun fillCaptchaAndSubmit(solution: String, autoSubmit: Boolean = false) {
        Log.d(TAG, "Filling CAPTCHA field with solution: $solution")

        // Script that simulates realistic keyboard input character by character
        val fillScript = """
            (function() {
                var result = { success: false, error: null };

                var captchaInput = document.querySelector('input[name="input"]');
                if (!captchaInput) {
                    result.error = 'CAPTCHA input not found';
                    return JSON.stringify(result);
                }

                // Focus the field
                captchaInput.focus();

                // Clear existing value
                captchaInput.value = '';

                // Simulate typing each character with proper keyboard events
                var solution = '$solution';
                for (var i = 0; i < solution.length; i++) {
                    var char = solution[i];
                    var keyCode = char.charCodeAt(0);

                    // KeyboardEvent for keydown
                    captchaInput.dispatchEvent(new KeyboardEvent('keydown', {
                        key: char,
                        code: 'Key' + char.toUpperCase(),
                        keyCode: keyCode,
                        which: keyCode,
                        bubbles: true,
                        cancelable: true
                    }));

                    // KeyboardEvent for keypress
                    captchaInput.dispatchEvent(new KeyboardEvent('keypress', {
                        key: char,
                        code: 'Key' + char.toUpperCase(),
                        keyCode: keyCode,
                        which: keyCode,
                        charCode: keyCode,
                        bubbles: true,
                        cancelable: true
                    }));

                    // Actually add the character to the value
                    captchaInput.value += char;

                    // Input event (most important for modern frameworks)
                    captchaInput.dispatchEvent(new InputEvent('input', {
                        data: char,
                        inputType: 'insertText',
                        bubbles: true,
                        cancelable: true
                    }));

                    // KeyboardEvent for keyup
                    captchaInput.dispatchEvent(new KeyboardEvent('keyup', {
                        key: char,
                        code: 'Key' + char.toUpperCase(),
                        keyCode: keyCode,
                        which: keyCode,
                        bubbles: true,
                        cancelable: true
                    }));
                }

                // Final change event
                captchaInput.dispatchEvent(new Event('change', { bubbles: true }));

                result.success = true;
                return JSON.stringify(result);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(fillScript) { result ->
            try {
                val cleanResult = result?.trim('"')?.replace("\\\"", "\"") ?: "{}"
                val json = JSONObject(cleanResult)

                if (json.optBoolean("success", false)) {
                    Log.d(TAG, "CAPTCHA field filled with AI solution: $solution (autoSubmit=$autoSubmit)")

                    handler.post {
                        Toast.makeText(this, "CAPTCHA: $solution", Toast.LENGTH_SHORT).show()

                        if (autoSubmit) {
                            // On retry: automatically submit the form
                            Log.d(TAG, "Auto-submitting form after CAPTCHA retry")
                            // Small delay to ensure the field value is properly registered
                            handler.postDelayed({
                                submitForm()
                            }, 500)
                        } else {
                            // First attempt: wait for user to verify and click submit
                            stateManager.transitionTo(BalanceCheckState.WaitingForCaptcha)
                            setupAutoSubmitOnCaptchaFill()
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to fill CAPTCHA field: ${json.optString("error")}")
                    fallbackToManualCaptcha("Could not fill CAPTCHA field")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error filling CAPTCHA field", e)
                fallbackToManualCaptcha(e.message ?: "Fill error")
            }
        }
    }

    /**
     * Fall back to manual CAPTCHA entry if auto-solve fails.
     */
    private fun fallbackToManualCaptcha(reason: String) {
        Log.w(TAG, "Auto-CAPTCHA failed: $reason, falling back to manual entry")

        handler.post {
            stateManager.transitionTo(BalanceCheckState.WaitingForCaptcha)
            Toast.makeText(this, getString(R.string.auto_captcha_failed), Toast.LENGTH_SHORT).show()

            // Setup auto-submit listener for manual entry
            setupAutoSubmitOnCaptchaFill()

            // Focus the CAPTCHA field
            handler.postDelayed({
                focusCaptchaField()
            }, 500)
        }
    }

    /**
     * Retry solving the CAPTCHA after an incorrect attempt.
     * Called when the CAPTCHA was wrong and a new one is loaded.
     */
    private fun retryCaptchaSolving() {
        captchaSolveAttempts++
        Log.d(TAG, "Retrying CAPTCHA solving (attempt $captchaSolveAttempts/$MAX_CAPTCHA_SOLVE_ATTEMPTS)")

        // Show a toast indicating retry
        Toast.makeText(this, getString(R.string.captcha_retry, captchaSolveAttempts, MAX_CAPTCHA_SOLVE_ATTEMPTS), Toast.LENGTH_SHORT).show()

        // Transition to SolvingCaptcha state
        stateManager.transitionTo(BalanceCheckState.SolvingCaptcha)

        // Extract and solve the new CAPTCHA
        extractAndSolveCaptcha()
    }

    /**
     * Load the CAPTCHA extraction JavaScript from assets.
     */
    private fun loadCaptchaExtractScript(): String {
        return try {
            assets.open("js/captcha_extract.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load captcha_extract.js", e)
            // Fallback inline script
            """
            (function() {
                var result = { found: false, error: 'Script load failed' };
                return JSON.stringify(result);
            })();
            """.trimIndent()
        }
    }

    companion object {
        private const val TAG = "BalanceCheckActivity"
        private const val MAX_ATTEMPTS = 5
    }
}

