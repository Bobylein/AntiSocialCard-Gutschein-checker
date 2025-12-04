package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import java.util.regex.Pattern

/**
 * Market implementation for REWE gift card balance checking.
 * Uses the kartenwelt.rewe.de website for balance inquiries.
 *
 * Unlike ALDI/Lidl, REWE uses its own balance check system (not tx-gate.com).
 */
class ReweMarket : Market() {

    override val marketType: MarketType = MarketType.REWE

    override val displayName: String = "REWE"

    override val balanceCheckUrl: String = "https://kartenwelt.rewe.de/rewe-geschenkkarte.html"

    override val brandColor: Int = Color.parseColor("#CC071E") // REWE Red

    override fun getFormFillScript(card: GiftCard): String {
        return """
            (function() {
                var cardNumberInput = document.querySelector('input[name="cardNumber"]') ||
                                    document.querySelector('input[placeholder*="Kartennummer"]');

                if (!cardNumberInput) {
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.indexOf('Kartennummer') !== -1) {
                            cardNumberInput = labels[i].parentElement.querySelector('input');
                            break;
                        }
                    }
                }

                var pinInput = document.querySelector('input[name="pin"]') ||
                              document.querySelector('input[placeholder*="PIN"]');

                if (!pinInput) {
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.trim() === 'PIN') {
                            pinInput = labels[i].parentElement.querySelector('input');
                            break;
                        }
                    }
                }

                if (cardNumberInput) {
                    cardNumberInput.value = '${card.cardNumber}';
                    cardNumberInput.dispatchEvent(new Event('input', { bubbles: true }));
                    cardNumberInput.dispatchEvent(new Event('change', { bubbles: true }));
                }

                if (pinInput) {
                    pinInput.value = '${card.pin}';
                    pinInput.dispatchEvent(new Event('input', { bubbles: true }));
                    pinInput.dispatchEvent(new Event('change', { bubbles: true }));
                }

                return JSON.stringify({
                    success: cardNumberInput !== null && pinInput !== null,
                    cardNumberFound: cardNumberInput !== null,
                    pinFound: pinInput !== null
                });
            })();
        """.trimIndent()
    }

    override fun getFormSubmitScript(): String {
        return """
            (function() {
                var submitButton = document.querySelector('button[type="submit"]');

                if (!submitButton) {
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                        if (buttons[i].textContent.indexOf('Guthaben') !== -1 ||
                            buttons[i].textContent.indexOf('prüfen') !== -1) {
                            submitButton = buttons[i];
                            break;
                        }
                    }
                }

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

                // Check for error messages
                if (bodyText.indexOf('ungültig') !== -1 ||
                    bodyText.indexOf('nicht gefunden') !== -1 ||
                    bodyText.indexOf('falsch') !== -1) {
                    result.error = 'invalid_card';
                    Android.onBalanceResult(JSON.stringify(result));
                    return;
                }

                // Try to find balance patterns
                var balancePatterns = [
                    /Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
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
                var balanceElements = document.querySelectorAll('[class*="balance"], [class*="guthaben"]');
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

    override fun parseBalanceResponse(response: String): BalanceResult {
        try {
            val lowerResponse = response.lowercase()

            // Check for error indicators
            if (lowerResponse.contains("ungültig") ||
                lowerResponse.contains("nicht gefunden") ||
                lowerResponse.contains("falsch")) {
                return BalanceResult.invalidCard("Kartennummer oder PIN ungültig")
            }

            // Try to extract balance using regex patterns
            val balancePatterns = listOf(
                Pattern.compile("""Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
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

    override fun isBalancePageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("guthaben") &&
               (lowerHtml.contains("€") || lowerHtml.contains("eur"))
    }

    override fun isErrorPageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("ungültig") ||
               lowerHtml.contains("nicht gefunden") ||
               lowerHtml.contains("fehler") ||
               lowerHtml.contains("falsch")
    }
}
