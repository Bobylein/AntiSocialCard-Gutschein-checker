package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import java.util.regex.Pattern

/**
 * Market implementation for REWE gift card balance checking.
 * Uses the kartenwelt.rewe.de website for balance inquiries.
 */
class ReweMarket : Market() {
    
    override val marketType: MarketType = MarketType.REWE
    
    override val displayName: String = "REWE"
    
    override val balanceCheckUrl: String = "https://kartenwelt.rewe.de/rewe-geschenkkarte.html"
    
    override val brandColor: Int = Color.parseColor("#CC071E") // REWE Red
    
    /**
     * JavaScript to fill in the card number and PIN fields on the REWE balance check form.
     * The form has fields for "Kartennummer" (card number) and "PIN".
     */
    override fun getFormFillScript(card: GiftCard): String {
        return """
            (function() {
                // Find the Kartennummer (card number) field
                var cardNumberInput = document.querySelector('input[name="cardNumber"]');
                if (!cardNumberInput) {
                    cardNumberInput = document.querySelector('input[placeholder*="Kartennummer"]');
                }
                if (!cardNumberInput) {
                    // Try finding by label
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.indexOf('Kartennummer') !== -1) {
                            var forAttr = labels[i].getAttribute('for');
                            if (forAttr) {
                                cardNumberInput = document.getElementById(forAttr);
                            }
                            if (!cardNumberInput) {
                                cardNumberInput = labels[i].parentElement.querySelector('input');
                            }
                            break;
                        }
                    }
                }
                if (!cardNumberInput) {
                    // Last resort: find all text inputs and use the first one in the balance form
                    var formInputs = document.querySelectorAll('form input[type="text"]');
                    if (formInputs.length > 0) {
                        cardNumberInput = formInputs[0];
                    }
                }
                
                // Find the PIN field
                var pinInput = document.querySelector('input[name="pin"]');
                if (!pinInput) {
                    pinInput = document.querySelector('input[placeholder*="PIN"]');
                }
                if (!pinInput) {
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.trim() === 'PIN') {
                            var forAttr = labels[i].getAttribute('for');
                            if (forAttr) {
                                pinInput = document.getElementById(forAttr);
                            }
                            if (!pinInput) {
                                pinInput = labels[i].parentElement.querySelector('input');
                            }
                            break;
                        }
                    }
                }
                if (!pinInput) {
                    var formInputs = document.querySelectorAll('form input[type="text"], form input[type="password"]');
                    if (formInputs.length > 1) {
                        pinInput = formInputs[1];
                    }
                }
                
                // Fill the fields
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
    
    /**
     * JavaScript to submit the balance check form.
     */
    override fun getFormSubmitScript(): String {
        return """
            (function() {
                // Find the submit button
                var submitButton = document.querySelector('button[type="submit"]');
                if (!submitButton) {
                    submitButton = document.querySelector('button:contains("Guthaben")');
                }
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
                    // Try submitting the form directly
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
     * JavaScript to extract the balance from the result page.
     * Calls Android.onBalanceResult() with the result.
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
                
                // Look for balance display elements
                // REWE typically shows "Guthaben: XX,XX €" or similar
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
                // Pattern: "Guthaben: 25,00 €" or "25,00 EUR" or "€ 25,00"
                var balancePatterns = [
                    /Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /([0-9]+[,\.][0-9]{2})\s*€/,
                    /€\s*([0-9]+[,\.][0-9]{2})/,
                    /([0-9]+[,\.][0-9]{2})\s*EUR/i,
                    /EUR\s*([0-9]+[,\.][0-9]{2})/i
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
                var balanceElements = document.querySelectorAll('[class*="balance"], [class*="guthaben"], [id*="balance"], [id*="guthaben"]');
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
     */
    override fun parseBalanceResponse(response: String): BalanceResult {
        try {
            // Check for error indicators
            val lowerResponse = response.lowercase()
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
    
    /**
     * Checks if the current page shows a successful balance result.
     */
    override fun isBalancePageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("guthaben") && 
               (lowerHtml.contains("€") || lowerHtml.contains("eur"))
    }
    
    /**
     * Checks if the current page shows an error.
     */
    override fun isErrorPageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("ungültig") ||
               lowerHtml.contains("nicht gefunden") ||
               lowerHtml.contains("fehler") ||
               lowerHtml.contains("falsch")
    }
}
