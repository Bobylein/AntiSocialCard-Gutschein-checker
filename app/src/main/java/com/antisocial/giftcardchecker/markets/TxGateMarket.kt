package com.antisocial.giftcardchecker.markets

import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.utils.JsAssetLoader
import java.util.regex.Pattern

/**
 * Abstract base class for markets that use the tx-gate.com balance check service.
 * Both ALDI Nord and Lidl use this service with different cid parameters.
 *
 * Shared functionality:
 * - Form filling on balancechecks.tx-gate.com iframe
 * - Balance extraction logic
 * - Error detection
 *
 * Differences (implemented by subclasses):
 * - cid parameter (ALDI=59, Lidl=79)
 * - Parent page URL
 * - Brand color and display name
 */
abstract class TxGateMarket : Market() {

    /**
     * The client ID for tx-gate.com (different for each market)
     */
    abstract val cid: Int

    /**
     * The parent page URL that embeds the tx-gate iframe
     */
    abstract val parentPageUrl: String

    /**
     * The referrer to use when loading the iframe URL
     */
    abstract val parentPageReferrer: String

    /**
     * The iframe form URL with the market-specific cid
     */
    val iframeFormUrl: String
        get() = "https://balancechecks.tx-gate.com/balance.php?cid=$cid"

    /**
     * tx-gate forms can be accessed directly, so manual entry is not required
     */
    override val requiresManualEntry: Boolean = false

    /**
     * Returns the JavaScript code to fill in the form fields.
     * Uses inline JavaScript (asset-based loading would require context injection).
     */
    override fun getFormFillScript(card: GiftCard): String {
        // Note: To use JsAssetLoader, we would need to inject Context into Market classes
        // For now, we use the inline approach which is cleaner than duplicating code
        return getFormFillScriptInternal(card)
    }

    /**
     * Internal method for form filling - can be overridden by subclasses
     * In the refactored version, this would use JsAssetLoader
     */
    protected open fun getFormFillScriptInternal(card: GiftCard): String {
        // This will be loaded from assets in the final implementation
        // For now, we provide a standardized template
        return """
            (function() {
                var result = {
                    success: false,
                    cardNumberFound: false,
                    pinFound: false,
                    captchaFound: false,
                    debug: { url: window.location.href, allInputs: [] }
                };

                var cardInput = document.querySelector('input[name="cardnumberfield"]') ||
                               document.querySelector('input[name*="card" i]') ||
                               document.querySelector('input[name*="gutschein" i]');

                var pinInput = document.querySelector('input[name="pin"]') ||
                              document.getElementById('myPw') ||
                              document.querySelector('input[type="password"]');

                var captchaInput = document.querySelector('input[name="input"]');

                if (cardInput) {
                    cardInput.value = '${card.cardNumber}';
                    cardInput.dispatchEvent(new Event('input', { bubbles: true }));
                    result.cardNumberFound = true;
                }

                if (pinInput) {
                    pinInput.value = '${card.pin}';
                    pinInput.dispatchEvent(new Event('input', { bubbles: true }));
                    result.pinFound = true;
                }

                result.captchaFound = captchaInput !== null;
                result.success = result.cardNumberFound && result.pinFound;

                return JSON.stringify(result);
            })();
        """.trimIndent()
    }

    /**
     * Returns the JavaScript code to submit the form.
     */
    override fun getFormSubmitScript(): String {
        return """
            (function() {
                var submitButton = document.querySelector('input[name="check"]') ||
                                  document.querySelector('input[type="submit"]') ||
                                  document.querySelector('button[type="submit"]');

                if (submitButton) {
                    submitButton.click();
                    return JSON.stringify({ success: true });
                } else {
                    var form = document.querySelector('form');
                    if (form) {
                        form.submit();
                        return JSON.stringify({ success: true, method: 'form' });
                    }
                }

                return JSON.stringify({ success: false, error: 'Submit button not found' });
            })();
        """.trimIndent()
    }

    /**
     * Returns the JavaScript code to extract the balance from the result page.
     */
    override fun getBalanceExtractionScript(): String {
        return """
            (function() {
                var result = {
                    success: false,
                    balance: null,
                    error: null,
                    html: document.body.innerHTML
                };

                var bodyText = document.body.innerText;

                // Check for CAPTCHA error FIRST (before general error check)
                // This must come first because CAPTCHA errors also contain 'falsch'
                if (bodyText.indexOf('Lösung') !== -1 && bodyText.indexOf('falsch') !== -1) {
                    result.error = 'captcha_error';
                    Android.onBalanceResult(JSON.stringify(result));
                    return;
                }

                // Check for error messages (German)
                if (bodyText.indexOf('ungültig') !== -1 ||
                    bodyText.indexOf('nicht gefunden') !== -1 ||
                    bodyText.indexOf('falsch') !== -1 ||
                    bodyText.indexOf('Fehler') !== -1 ||
                    bodyText.indexOf('inkorrekt') !== -1 ||
                    bodyText.indexOf('gesperrt') !== -1) {
                    result.error = 'invalid_card';
                    Android.onBalanceResult(JSON.stringify(result));
                    return;
                }

                // Try to find balance patterns (German format)
                var balancePatterns = [
                    /Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /Restguthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /Kontostand[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /Betrag[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /([0-9]+[,\.][0-9]{2})\s*€/,
                    /€\s*([0-9]+[,\.][0-9]{2})/,
                    /([0-9]+[,\.][0-9]{2})\s*EUR/i
                ];

                for (var i = 0; i < balancePatterns.length; i++) {
                    var match = bodyText.match(balancePatterns[i]);
                    if (match && match[1]) {
                        result.success = true;
                        result.balance = match[1].replace(',', '.');
                        break;
                    }
                }

                // Look for specific balance elements
                var balanceElements = document.querySelectorAll('[class*="balance"], [class*="guthaben"], [class*="betrag"]');
                if (!result.success && balanceElements.length > 0) {
                    for (var j = 0; j < balanceElements.length; j++) {
                        var text = balanceElements[j].innerText;
                        var match = text.match(/([0-9]+[,\.][0-9]{2})/);
                        if (match) {
                            result.success = true;
                            result.balance = match[1].replace(',', '.');
                            break;
                        }
                    }
                }

                if (!result.success && !result.error) {
                    result.error = 'balance_not_found';
                }

                Android.onBalanceResult(JSON.stringify(result));
            })();
        """.trimIndent()
    }

    /**
     * Parses the balance response from the website.
     * Shared implementation for all tx-gate markets.
     */
    override fun parseBalanceResponse(response: String): BalanceResult {
        try {
            val lowerResponse = response.lowercase()

            // Check for CAPTCHA error FIRST (before general error check)
            // This must come first because CAPTCHA errors also contain 'falsch'
            if (lowerResponse.contains("lösung") && lowerResponse.contains("falsch")) {
                return BalanceResult.error("CAPTCHA-Lösung falsch")
            }

            // Check for error indicators (German)
            if (lowerResponse.contains("ungültig") ||
                lowerResponse.contains("nicht gefunden") ||
                lowerResponse.contains("falsch") ||
                lowerResponse.contains("fehler") ||
                lowerResponse.contains("inkorrekt") ||
                lowerResponse.contains("unbekannt") ||
                lowerResponse.contains("gesperrt")) {
                return BalanceResult.invalidCard("Gutscheinnummer oder PIN ungültig oder Gutschein gesperrt")
            }

            // Try to extract balance using regex patterns
            val balancePatterns = listOf(
                Pattern.compile("""Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Restguthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Kontostand[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Aktuelles Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Betrag[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""([0-9]+[,\.][0-9]{2})\s*€"""),
                Pattern.compile("""€\s*([0-9]+[,\.][0-9]{2})"""),
                Pattern.compile("""([0-9]+[,\.][0-9]{2})\s*EUR""", Pattern.CASE_INSENSITIVE)
            )

            for (pattern in balancePatterns) {
                val matcher = pattern.matcher(response)
                if (matcher.find()) {
                    val balance = matcher.group(1)?.replace(",", ".") ?: continue
                    return BalanceResult.success(balance, "EUR")
                }
            }

            return BalanceResult.parsingError("Guthaben konnte nicht gelesen werden", response)
        } catch (e: Exception) {
            return BalanceResult.error(e.message)
        }
    }

    /**
     * Checks if the current page shows a successful balance result.
     */
    override fun isBalancePageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return (lowerHtml.contains("guthaben") ||
                lowerHtml.contains("restguthaben") ||
                lowerHtml.contains("kontostand") ||
                lowerHtml.contains("betrag")) &&
               (lowerHtml.contains("€") || lowerHtml.contains("eur"))
    }

    /**
     * Checks if the current page shows a CAPTCHA error (wrong solution).
     * This should be checked before isErrorPageLoaded() to avoid false "invalid card" errors.
     */
    fun isCaptchaErrorPageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        // CAPTCHA error messages typically contain "Lösung" (solution) and "falsch" (wrong)
        return (lowerHtml.contains("lösung") && lowerHtml.contains("falsch")) ||
               lowerHtml.contains("captcha") && lowerHtml.contains("falsch")
    }

    /**
     * Checks if the current page shows an error (excluding CAPTCHA errors).
     */
    override fun isErrorPageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()

        // First check if this is a CAPTCHA error - if so, it's not an "invalid card" error
        if (isCaptchaErrorPageLoaded(html)) {
            return false
        }

        return lowerHtml.contains("ungültig") ||
               lowerHtml.contains("nicht gefunden") ||
               lowerHtml.contains("fehler") ||
               lowerHtml.contains("falsch") ||
               lowerHtml.contains("inkorrekt") ||
               lowerHtml.contains("unbekannt") ||
               lowerHtml.contains("gesperrt")
    }
}
