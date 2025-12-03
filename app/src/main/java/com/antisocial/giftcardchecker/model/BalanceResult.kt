package com.antisocial.giftcardchecker.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the status of a balance check operation.
 */
enum class BalanceStatus {
    SUCCESS,
    INVALID_CARD,
    INVALID_PIN,
    NETWORK_ERROR,
    PARSING_ERROR,
    WEBSITE_CHANGED,
    UNKNOWN_ERROR
}

/**
 * Data class representing the result of a balance check operation.
 */
@Parcelize
data class BalanceResult(
    val status: BalanceStatus,
    val balance: String? = null,
    val currency: String = "EUR",
    val errorMessage: String? = null,
    val rawResponse: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable {
    
    companion object {
        const val EXTRA_BALANCE_RESULT = "extra_balance_result"
        
        /**
         * Creates a successful balance result.
         */
        fun success(balance: String, currency: String = "EUR"): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.SUCCESS,
                balance = balance,
                currency = currency
            )
        }
        
        /**
         * Creates an error result for invalid card.
         */
        fun invalidCard(message: String? = null): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.INVALID_CARD,
                errorMessage = message ?: "Invalid card number"
            )
        }
        
        /**
         * Creates an error result for invalid PIN.
         */
        fun invalidPin(message: String? = null): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.INVALID_PIN,
                errorMessage = message ?: "Invalid PIN"
            )
        }
        
        /**
         * Creates an error result for network errors.
         */
        fun networkError(message: String? = null): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.NETWORK_ERROR,
                errorMessage = message ?: "Network error. Please check your connection."
            )
        }
        
        /**
         * Creates an error result for parsing errors.
         */
        fun parsingError(message: String? = null, rawResponse: String? = null): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.PARSING_ERROR,
                errorMessage = message ?: "Failed to parse balance",
                rawResponse = rawResponse
            )
        }
        
        /**
         * Creates an error result when website structure has changed.
         */
        fun websiteChanged(message: String? = null): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.WEBSITE_CHANGED,
                errorMessage = message ?: "Website structure may have changed"
            )
        }
        
        /**
         * Creates a generic error result.
         */
        fun error(message: String? = null): BalanceResult {
            return BalanceResult(
                status = BalanceStatus.UNKNOWN_ERROR,
                errorMessage = message ?: "An unknown error occurred"
            )
        }
    }
    
    /**
     * Returns true if the balance check was successful.
     */
    fun isSuccess(): Boolean = status == BalanceStatus.SUCCESS
    
    /**
     * Returns a formatted balance string with currency symbol.
     */
    fun getFormattedBalance(): String {
        return when {
            balance != null -> {
                val symbol = when (currency) {
                    "EUR" -> "€"
                    "USD" -> "$"
                    "GBP" -> "£"
                    else -> currency
                }
                "$balance $symbol"
            }
            else -> "N/A"
        }
    }
    
    /**
     * Returns a user-friendly error message.
     */
    fun getDisplayMessage(): String {
        return when (status) {
            BalanceStatus.SUCCESS -> "Balance: ${getFormattedBalance()}"
            BalanceStatus.INVALID_CARD -> errorMessage ?: "Invalid card number"
            BalanceStatus.INVALID_PIN -> errorMessage ?: "Invalid PIN"
            BalanceStatus.NETWORK_ERROR -> errorMessage ?: "Network error"
            BalanceStatus.PARSING_ERROR -> errorMessage ?: "Could not read balance"
            BalanceStatus.WEBSITE_CHANGED -> errorMessage ?: "Service temporarily unavailable"
            BalanceStatus.UNKNOWN_ERROR -> errorMessage ?: "An error occurred"
        }
    }
}

