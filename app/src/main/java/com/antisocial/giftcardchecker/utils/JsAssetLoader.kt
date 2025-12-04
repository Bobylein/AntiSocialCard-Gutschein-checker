package com.antisocial.giftcardchecker.utils

import android.content.Context
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility class for loading JavaScript files from assets and applying template replacements.
 * Supports placeholder tokens: {{CARD_NUMBER}}, {{PIN}}
 */
class JsAssetLoader(private val context: Context) {

    /**
     * Loads a JavaScript file from assets and optionally applies replacements.
     *
     * @param assetPath Path to the JavaScript file in assets (e.g., "js/aldi_form_fill.js")
     * @param replacements Map of placeholder tokens to their replacement values
     * @return The JavaScript code with replacements applied
     */
    fun loadScript(assetPath: String, replacements: Map<String, String> = emptyMap()): String {
        val scriptContent = readAssetFile(assetPath)
        return applyReplacements(scriptContent, replacements)
    }

    /**
     * Loads the form fill script for a specific market.
     *
     * @param market The market type (ALDI, LIDL, REWE)
     * @param card The gift card with number and PIN to fill in
     * @return JavaScript code with card data interpolated
     */
    fun loadFormFillScript(market: MarketType, card: GiftCard): String {
        val assetPath = when (market) {
            MarketType.ALDI -> "js/aldi_form_fill.js"
            MarketType.LIDL -> "js/lidl_form_fill.js"
            MarketType.REWE -> "js/rewe_form_fill.js"
        }

        val replacements = mapOf(
            "{{CARD_NUMBER}}" to card.cardNumber,
            "{{PIN}}" to card.pin
        )

        return loadScript(assetPath, replacements)
    }

    /**
     * Loads the form submit script for a specific market.
     *
     * @param market The market type (ALDI, LIDL, REWE)
     * @return JavaScript code to submit the form
     */
    fun loadFormSubmitScript(market: MarketType): String {
        val assetPath = when (market) {
            MarketType.ALDI -> "js/aldi_form_submit.js"
            MarketType.LIDL -> "js/lidl_form_submit.js"
            MarketType.REWE -> "js/rewe_form_submit.js"
        }

        return loadScript(assetPath)
    }

    /**
     * Loads the balance extraction script for a specific market.
     *
     * @param market The market type (ALDI, LIDL, REWE)
     * @return JavaScript code to extract balance from result page
     */
    fun loadBalanceExtractionScript(market: MarketType): String {
        val assetPath = when (market) {
            MarketType.ALDI -> "js/aldi_balance_extract.js"
            MarketType.LIDL -> "js/lidl_balance_extract.js"
            MarketType.REWE -> "js/rewe_balance_extract.js"
        }

        return loadScript(assetPath)
    }

    /**
     * Reads a file from assets and returns its content as a string.
     */
    private fun readAssetFile(path: String): String {
        return context.assets.open(path).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        }
    }

    /**
     * Applies string replacements to a template.
     * Replaces all occurrences of keys with their corresponding values.
     */
    private fun applyReplacements(template: String, replacements: Map<String, String>): String {
        var result = template
        for ((placeholder, value) in replacements) {
            result = result.replace(placeholder, value)
        }
        return result
    }
}
