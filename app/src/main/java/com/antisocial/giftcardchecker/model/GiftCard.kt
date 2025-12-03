package com.antisocial.giftcardchecker.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the type of market/retailer for a gift card.
 */
enum class MarketType {
    REWE,
    ALDI
}

/**
 * Data class representing a gift card with its barcode number, PIN, and associated market.
 */
@Parcelize
data class GiftCard(
    val cardNumber: String,
    val pin: String,
    val marketType: MarketType,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {
    
    companion object {
        const val EXTRA_GIFT_CARD = "extra_gift_card"
        const val EXTRA_CARD_NUMBER = "extra_card_number"
        const val EXTRA_MARKET_TYPE = "extra_market_type"
    }
    
    /**
     * Returns a masked version of the card number for display purposes.
     * Shows first 4 and last 4 digits, masks the rest.
     */
    fun getMaskedCardNumber(): String {
        return if (cardNumber.length > 8) {
            val first = cardNumber.take(4)
            val last = cardNumber.takeLast(4)
            val masked = "*".repeat(cardNumber.length - 8)
            "$first$masked$last"
        } else {
            cardNumber
        }
    }
    
    /**
     * Validates the card number format based on market type.
     */
    fun isValidCardNumber(): Boolean {
        return when (marketType) {
            MarketType.REWE -> cardNumber.length in 13..19 && cardNumber.all { it.isDigit() }
            MarketType.ALDI -> cardNumber.length == 19 && cardNumber.all { it.isDigit() }
        }
    }
    
    /**
     * Validates the PIN format based on market type.
     */
    fun isValidPin(): Boolean {
        return when (marketType) {
            MarketType.REWE -> pin.length in 4..8 && pin.all { it.isDigit() || it.isLetter() }
            MarketType.ALDI -> pin.length == 4 && pin.all { it.isDigit() }
        }
    }
}

