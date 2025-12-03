package com.antisocial.giftcardchecker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.antisocial.giftcardchecker.databinding.ActivityMainBinding
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType

/**
 * Main activity for the Gift Card Balance Checker app.
 * Allows users to select a market (REWE or ALDI) and start the scanning process.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // REWE card click
        binding.cardRewe.setOnClickListener {
            startScanner(MarketType.REWE)
        }

        // ALDI card click
        binding.cardAldi.setOnClickListener {
            startScanner(MarketType.ALDI)
        }
    }

    private fun startScanner(marketType: MarketType) {
        val intent = Intent(this, ScannerActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_MARKET_TYPE, marketType)
        }
        startActivity(intent)
    }
}

