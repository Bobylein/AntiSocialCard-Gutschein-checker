package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import java.util.regex.Pattern

/**
 * Market implementation for ALDI Nord gift card balance checking.
 * Uses the Helaba bank service for ALDI gift card balance inquiries.
 * 
 * IMPORTANT: The balance check form is loaded in an iframe from tx-gate.com,
 * which prevents automatic JavaScript injection due to cross-origin restrictions.
 * The user must manually enter the card details in the WebView form.
 * 
 * Form fields (visible in the iframe):
 * - Gutschein: 19-digit voucher number (format: 0000 0000 0000 0000 0000)
 * - PIN: 4-digit PIN
 * - Lösung: CAPTCHA solution
 * 
 * Note: This market requires manual user interaction due to iframe cross-origin restrictions.
 */
class AldiMarket : Market() {
    
    override val marketType: MarketType = MarketType.ALDI
    
    override val displayName: String = "ALDI Nord"
    
    // Official ALDI Nord gift card balance check URL provided by Helaba
    // The actual form is in an iframe from balancechecks.tx-gate.com
    override val balanceCheckUrl: String = "https://www.helaba.com/de/aldi/"
    
    override val brandColor: Int = Color.parseColor("#00529B") // ALDI Blue
    
    // ALDI requires manual form entry due to cross-origin iframe
    override val requiresManualEntry: Boolean = true
    
    /**
     * JavaScript to fill in the Gutschein (voucher number) and PIN fields.
     * ALDI cards use a 19-digit voucher number and 4-digit PIN.
     * 
     * Note: The CAPTCHA (Lösung field) must be filled by the user manually.
     */
    override fun getFormFillScript(card: GiftCard): String {
        return """
            (function() {
                var result = {
                    success: false,
                    gutscheinFound: false,
                    pinFound: false,
                    captchaFound: false
                };
                
                // Find the Gutschein (voucher number) field
                var gutscheinInput = document.querySelector('input[placeholder*="0000 0000"]');
                if (!gutscheinInput) {
                    gutscheinInput = document.querySelector('input[name*="gutschein" i]');
                }
                if (!gutscheinInput) {
                    gutscheinInput = document.querySelector('input[name*="voucher" i]');
                }
                if (!gutscheinInput) {
                    // Try finding by label text
                    var labels = document.querySelectorAll('label, td, th');
                    for (var i = 0; i < labels.length; i++) {
                        var labelText = labels[i].textContent.toLowerCase();
                        if (labelText.indexOf('gutschein') !== -1 && labelText.indexOf('sperren') === -1) {
                            var nextInput = labels[i].parentElement.querySelector('input[type="text"]');
                            if (!nextInput) {
                                nextInput = labels[i].nextElementSibling;
                                if (nextInput && nextInput.tagName !== 'INPUT') {
                                    nextInput = nextInput.querySelector('input');
                                }
                            }
                            if (nextInput && nextInput.tagName === 'INPUT') {
                                gutscheinInput = nextInput;
                                break;
                            }
                        }
                    }
                }
                if (!gutscheinInput) {
                    // Find inputs with 19-digit placeholder
                    var inputs = document.querySelectorAll('input[type="text"]');
                    for (var j = 0; j < inputs.length; j++) {
                        var placeholder = inputs[j].placeholder || '';
                        if (placeholder.replace(/\s/g, '').length >= 16) {
                            gutscheinInput = inputs[j];
                            break;
                        }
                    }
                }
                
                // Find the PIN field
                var pinInput = document.querySelector('input[placeholder="0000"]');
                if (!pinInput) {
                    pinInput = document.querySelector('input[name*="pin" i]');
                }
                if (!pinInput) {
                    pinInput = document.querySelector('input[type="password"]');
                }
                if (!pinInput) {
                    var labels = document.querySelectorAll('label, td, th');
                    for (var k = 0; k < labels.length; k++) {
                        var labelText = labels[k].textContent.trim().toLowerCase();
                        if (labelText === 'pin' || labelText === 'pin:') {
                            var nextInput = labels[k].parentElement.querySelector('input');
                            if (!nextInput) {
                                nextInput = labels[k].nextElementSibling;
                                if (nextInput && nextInput.tagName !== 'INPUT') {
                                    nextInput = nextInput.querySelector('input');
                                }
                            }
                            if (nextInput && nextInput.tagName === 'INPUT') {
                                pinInput = nextInput;
                                break;
                            }
                        }
                    }
                }
                
                // Check for CAPTCHA field (Lösung)
                var captchaInput = document.querySelector('input[name*="captcha" i]');
                if (!captchaInput) {
                    var labels = document.querySelectorAll('label, td, th');
                    for (var m = 0; m < labels.length; m++) {
                        var labelText = labels[m].textContent.toLowerCase();
                        if (labelText.indexOf('lösung') !== -1 || labelText.indexOf('loesung') !== -1) {
                            var nextInput = labels[m].parentElement.querySelector('input');
                            if (!nextInput) {
                                nextInput = labels[m].nextElementSibling;
                                if (nextInput && nextInput.tagName !== 'INPUT') {
                                    nextInput = nextInput.querySelector('input');
                                }
                            }
                            if (nextInput && nextInput.tagName === 'INPUT') {
                                captchaInput = nextInput;
                                break;
                            }
                        }
                    }
                }
                
                // Fill the Gutschein field
                if (gutscheinInput) {
                    gutscheinInput.value = '${card.cardNumber}';
                    gutscheinInput.dispatchEvent(new Event('input', { bubbles: true }));
                    gutscheinInput.dispatchEvent(new Event('change', { bubbles: true }));
                    result.gutscheinFound = true;
                }
                
                // Fill the PIN field
                if (pinInput) {
                    pinInput.value = '${card.pin}';
                    pinInput.dispatchEvent(new Event('input', { bubbles: true }));
                    pinInput.dispatchEvent(new Event('change', { bubbles: true }));
                    result.pinFound = true;
                }
                
                result.captchaFound = captchaInput !== null;
                result.success = result.gutscheinFound && result.pinFound;
                
                return JSON.stringify(result);
            })();
        """.trimIndent()
    }
    
    /**
     * JavaScript to submit the balance check form.
     */
    override fun getFormSubmitScript(): String {
        return """
            (function() {
                // Find the Guthabenabfrage (balance check) submit button
                var submitButton = null;
                var buttons = document.querySelectorAll('button, input[type="submit"], input[type="button"]');
                
                for (var i = 0; i < buttons.length; i++) {
                    var buttonText = (buttons[i].textContent || buttons[i].value || '').toLowerCase();
                    if (buttonText.indexOf('guthabenabfrage') !== -1 || 
                        buttonText.indexOf('guthaben') !== -1 ||
                        buttonText.indexOf('abfragen') !== -1 ||
                        buttonText.indexOf('prüfen') !== -1 ||
                        buttonText.indexOf('check') !== -1) {
                        submitButton = buttons[i];
                        break;
                    }
                }
                
                if (!submitButton) {
                    submitButton = document.querySelector('button[type="submit"]');
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
                
                var bodyText = document.body.innerText;
                
                // Check for error messages (German)
                if (bodyText.indexOf('ungültig') !== -1 || 
                    bodyText.indexOf('nicht gefunden') !== -1 ||
                    bodyText.indexOf('falsch') !== -1 ||
                    bodyText.indexOf('Fehler') !== -1 ||
                    bodyText.indexOf('inkorrekt') !== -1 ||
                    bodyText.indexOf('unbekannt') !== -1) {
                    result.error = 'invalid_card';
                    Android.onBalanceResult(JSON.stringify(result));
                    return;
                }
                
                // Check for CAPTCHA error
                if (bodyText.indexOf('Lösung') !== -1 && bodyText.indexOf('falsch') !== -1) {
                    result.error = 'captcha_error';
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
                var balanceElements = document.querySelectorAll('[class*="balance"], [class*="guthaben"], [class*="betrag"], [id*="balance"], [id*="guthaben"]');
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
            val lowerResponse = response.lowercase()
            
            // Check for error indicators (German)
            if (lowerResponse.contains("ungültig") || 
                lowerResponse.contains("nicht gefunden") ||
                lowerResponse.contains("falsch") ||
                lowerResponse.contains("fehler") ||
                lowerResponse.contains("inkorrekt") ||
                lowerResponse.contains("unbekannt")) {
                return BalanceResult.invalidCard("Gutscheinnummer oder PIN ungültig")
            }
            
            // Check for CAPTCHA error
            if (lowerResponse.contains("lösung") && lowerResponse.contains("falsch")) {
                return BalanceResult.error("CAPTCHA-Lösung falsch")
            }
            
            // Try to extract balance using regex patterns
            val balancePatterns = listOf(
                Pattern.compile("""Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Restguthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Kontostand[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
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
     * Checks if the current page shows an error.
     */
    override fun isErrorPageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("ungültig") ||
               lowerHtml.contains("nicht gefunden") ||
               lowerHtml.contains("fehler") ||
               lowerHtml.contains("falsch") ||
               lowerHtml.contains("inkorrekt") ||
               lowerHtml.contains("unbekannt")
    }
}
