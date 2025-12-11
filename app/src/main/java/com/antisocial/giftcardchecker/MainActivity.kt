package com.antisocial.giftcardchecker

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.antisocial.giftcardchecker.databinding.ActivityMainBinding
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import com.antisocial.giftcardchecker.markets.Market

/**
 * Main activity for the Gift Card Balance Checker app.
 * Allows users to select a market (REWE or ALDI) and start the scanning process.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var preloadWebView: WebView? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Settings button click
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // REWE card click
        binding.cardRewe.setOnClickListener {
            startScanner(MarketType.REWE)
        }

        // ALDI card click - preload the page in background
        binding.cardAldi.setOnClickListener {
            preloadAldiPage()
            startScanner(MarketType.ALDI)
        }

        // Lidl card click
        binding.cardLidl.setOnClickListener {
            startScanner(MarketType.LIDL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun preloadAldiPage() {
        try {
            val market = Market.forType(MarketType.ALDI)
            val aldiMarket = market as? com.antisocial.giftcardchecker.markets.AldiMarket
            
            val urlToPreload = aldiMarket?.iframeFormUrl ?: market.balanceCheckUrl
            
            Log.d(TAG, "Preloading ALDI page: $urlToPreload")
            
            // Create a hidden WebView to preload the page
            preloadWebView = WebView(this).apply {
                visibility = View.GONE // Hidden
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }
                
                // Load with referrer header for ALDI iframe URL
                if (urlToPreload.contains("balancechecks.tx-gate.com")) {
                    val headers = mapOf(
                        "Referer" to "https://www.helaba.com/de/aldi/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                        "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8"
                    )
                    loadUrl(urlToPreload, headers)
                } else {
                    loadUrl(urlToPreload)
                }
            }
            
            // Add to parent to ensure it's part of the view hierarchy (required for WebView)
            binding.root.addView(preloadWebView)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading ALDI page", e)
            // Continue anyway - preloading is optional
        }
    }

    private fun startScanner(marketType: MarketType) {
        val intent = Intent(this, ScannerActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_MARKET_TYPE, marketType)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up preload WebView
        preloadWebView?.let {
            try {
                it.stopLoading()
                it.destroy()
                binding.root.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up preload WebView", e)
            }
        }
        preloadWebView = null
    }
}


