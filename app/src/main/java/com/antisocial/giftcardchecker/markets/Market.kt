package com.antisocial.giftcardchecker.markets

import android.webkit.WebView
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType

/**
 * Abstract class representing a market/retailer for gift card balance checking.
 * Each market implementation handles the specific website interaction for that retailer.
 */
abstract class Market {
    
    /**
     * The type of market this implementation handles.
     */
    abstract val marketType: MarketType
    
    /**
     * Display name of the market.
     */
    abstract val displayName: String
    
    /**
     * URL for the balance check page.
     */
    abstract val balanceCheckUrl: String
    
    /**
     * Primary brand color for UI theming.
     */
    abstract val brandColor: Int
    
    /**
     * Whether this market requires manual form entry.
     * Some markets use cross-origin iframes which prevent JavaScript injection.
     * Default is false (automatic form filling).
     */
    open val requiresManualEntry: Boolean = false
    
    /**
     * Returns the JavaScript code to fill in the form fields on the balance check page.
     * This will be injected into the WebView after the page loads.
     * 
     * @param card The gift card with number and PIN to fill in
     * @return JavaScript code string to execute
     */
    abstract fun getFormFillScript(card: GiftCard): String
    
    /**
     * Returns the JavaScript code to submit the form.
     * 
     * @return JavaScript code string to execute
     */
    abstract fun getFormSubmitScript(): String
    
    /**
     * Returns the JavaScript code to extract the balance from the result page.
     * The script should call Android.onBalanceResult(jsonString) with the result.
     * 
     * @return JavaScript code string to execute
     */
    abstract fun getBalanceExtractionScript(): String
    
    /**
     * Parses the raw response from the website to extract balance information.
     * 
     * @param response The raw response/HTML from the website
     * @return BalanceResult with the parsed balance or error information
     */
    abstract fun parseBalanceResponse(response: String): BalanceResult
    
    /**
     * Returns true if the current page indicates a successful balance check.
     * 
     * @param html The HTML content of the current page
     * @return true if balance was found successfully
     */
    abstract fun isBalancePageLoaded(html: String): Boolean
    
    /**
     * Returns true if the current page indicates an error (invalid card/PIN).
     * 
     * @param html The HTML content of the current page
     * @return true if an error was detected
     */
    abstract fun isErrorPageLoaded(html: String): Boolean
    
    companion object {
        /**
         * Factory method to get a Market implementation for a given market type.
         */
        fun forType(type: MarketType): Market {
            return when (type) {
                MarketType.REWE -> ReweMarket()
                MarketType.ALDI -> AldiMarket()
            }
        }
        
        /**
         * Returns all available market implementations.
         */
        fun getAllMarkets(): List<Market> {
            return listOf(ReweMarket(), AldiMarket())
        }
    }
}

