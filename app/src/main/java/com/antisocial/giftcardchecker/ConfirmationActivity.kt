package com.antisocial.giftcardchecker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antisocial.giftcardchecker.databinding.ActivityConfirmationBinding
import com.antisocial.giftcardchecker.markets.Market
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType

/**
 * Activity for confirming and editing scanned gift card data before checking balance.
 */
class ConfirmationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmationBinding
    private lateinit var market: Market
    private var marketType: MarketType = MarketType.REWE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        val cardNumber = intent.getStringExtra(GiftCard.EXTRA_CARD_NUMBER) ?: ""
        val pin = intent.getStringExtra(GiftCard.EXTRA_PIN) ?: ""
        marketType = intent.getSerializableExtra(GiftCard.EXTRA_MARKET_TYPE) as? MarketType
            ?: MarketType.REWE

        // Get market implementation
        market = Market.forType(marketType)

        setupUI(cardNumber, pin)
    }

    private fun setupUI(cardNumber: String, pin: String) {
        // Set market name
        binding.tvMarket.text = market.displayName

        // Set card number
        binding.etCardNumber.setText(cardNumber)

        // Set PIN
        binding.etPin.setText(pin)

        // Setup button listeners
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnConfirm.setOnClickListener {
            validateAndContinue()
        }
    }

    private fun validateAndContinue() {
        val cardNumber = binding.etCardNumber.text.toString().trim().replace(" ", "")
        val pin = binding.etPin.text.toString().trim()

        // Validate card number
        if (cardNumber.isEmpty()) {
            binding.tilCardNumber.error = "Gutscheinnummer ist erforderlich"
            return
        } else if (cardNumber.length < 8) {
            binding.tilCardNumber.error = "Gutscheinnummer zu kurz"
            return
        }

        // Validate PIN
        if (pin.isEmpty()) {
            binding.tilPin.error = "PIN ist erforderlich"
            return
        } else if (pin.length < 4) {
            binding.tilPin.error = "PIN muss mindestens 4 Ziffern haben"
            return
        }

        // Clear errors
        binding.tilCardNumber.error = null
        binding.tilPin.error = null

        // Create gift card and navigate to balance check
        val giftCard = GiftCard(
            cardNumber = cardNumber,
            pin = pin,
            marketType = marketType
        )

        val intent = Intent(this, BalanceCheckActivity::class.java).apply {
            putExtra(GiftCard.EXTRA_GIFT_CARD, giftCard)
        }
        startActivity(intent)
        finish()
    }
}

