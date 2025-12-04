package com.antisocial.giftcardchecker

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.antisocial.giftcardchecker.markets.Market
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import com.antisocial.giftcardchecker.utils.getSerializableExtraCompat

/**
 * Activity for confirming and editing scanned gift card data before checking balance.
 * Now automatically proceeds without requiring user confirmation - manual entry is not acceptable.
 */
class ConfirmationActivity : AppCompatActivity() {

    private var marketType: MarketType = MarketType.REWE
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get data from intent
        val cardNumber = intent.getStringExtra(GiftCard.EXTRA_CARD_NUMBER) ?: ""
        val pin = intent.getStringExtra(GiftCard.EXTRA_PIN) ?: ""
        marketType = intent.getSerializableExtraCompat<MarketType>(GiftCard.EXTRA_MARKET_TYPE)
            ?: MarketType.REWE

        // Validate data
        if (cardNumber.isEmpty() || pin.isEmpty()) {
            finish()
            return
        }

        // Automatically proceed without user interaction - manual entry is not acceptable
        // Use detected data directly
        val giftCard = GiftCard(
            cardNumber = cardNumber.trim().replace(" ", ""),
            pin = pin.trim(),
            marketType = marketType
        )

        // Navigate immediately to balance check
        val intent = Intent(this, BalanceCheckActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_GIFT_CARD, giftCard)
        }
        startActivity(intent)
        finish()
    }
}

