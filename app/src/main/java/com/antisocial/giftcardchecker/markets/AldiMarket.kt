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
    
    // Official ALDI Nord gift card balance check URL
    // Navigate directly to the iframe URL to avoid cross-origin restrictions
    // The form is at balancechecks.tx-gate.com/balance.php?cid=59
    override val balanceCheckUrl: String = "https://balancechecks.tx-gate.com/balance.php?cid=59"
    
    override val brandColor: Int = Color.parseColor("#00529B") // ALDI Blue
    
    // Now we can automate form filling since we're navigating directly to the form page
    override val requiresManualEntry: Boolean = false
    
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
                
                // Find the Gutschein (voucher number) field - try multiple strategies
                var gutscheinInput = null;
                
                // Strategy 1: By ID
                gutscheinInput = document.getElementById('gutschein') || 
                                 document.getElementById('voucher') ||
                                 document.getElementById('cardnumber') ||
                                 document.getElementById('card_number');
                
                // Strategy 2: By name attribute
                if (!gutscheinInput) {
                    gutscheinInput = document.querySelector('input[name*="gutschein" i]') ||
                                    document.querySelector('input[name*="voucher" i]') ||
                                    document.querySelector('input[name*="cardnumber" i]');
                }
                
                // Strategy 3: By placeholder
                if (!gutscheinInput) {
                    gutscheinInput = document.querySelector('input[placeholder*="0000 0000"]') ||
                                    document.querySelector('input[placeholder*="Gutschein" i]');
                }
                
                // Strategy 4: By label text
                if (!gutscheinInput) {
                    var labels = document.querySelectorAll('label, td, th, span');
                    for (var i = 0; i < labels.length; i++) {
                        var labelText = labels[i].textContent.toLowerCase();
                        if ((labelText.indexOf('gutschein') !== -1 || labelText.indexOf('gutscheinnummer') !== -1) && 
                            labelText.indexOf('sperren') === -1) {
                            // Try to find input in same row/container
                            var container = labels[i].closest('tr, div, form, table');
                            if (container) {
                                gutscheinInput = container.querySelector('input[type="text"]');
                            }
                            if (!gutscheinInput) {
                                gutscheinInput = labels[i].nextElementSibling;
                                if (gutscheinInput && gutscheinInput.tagName !== 'INPUT') {
                                    gutscheinInput = gutscheinInput.querySelector('input[type="text"]');
                                }
                            }
                            if (gutscheinInput && gutscheinInput.tagName === 'INPUT') {
                                break;
                            }
                        }
                    }
                }
                
                // Strategy 5: Find first text input with long placeholder (likely voucher field)
                if (!gutscheinInput) {
                    var inputs = document.querySelectorAll('input[type="text"]');
                    for (var j = 0; j < inputs.length; j++) {
                        var placeholder = (inputs[j].placeholder || '').replace(/\s/g, '');
                        if (placeholder.length >= 16) {
                            gutscheinInput = inputs[j];
                            break;
                        }
                    }
                }
                
                // Strategy 6: First text input in form (fallback)
                if (!gutscheinInput) {
                    var form = document.querySelector('form');
                    if (form) {
                        gutscheinInput = form.querySelector('input[type="text"]:not([type="hidden"])');
                    }
                }
                
                // Find the PIN field - try multiple strategies
                var pinInput = null;
                
                // Strategy 1: By ID
                pinInput = document.getElementById('pin') || 
                          document.getElementById('cardpin') ||
                          document.getElementById('card_pin');
                
                // Strategy 2: By name attribute
                if (!pinInput) {
                    pinInput = document.querySelector('input[name*="pin" i]');
                }
                
                // Strategy 3: By placeholder
                if (!pinInput) {
                    pinInput = document.querySelector('input[placeholder*="0000"]') ||
                               document.querySelector('input[placeholder*="PIN" i]');
                }
                
                // Strategy 4: Password input (some forms use password type for PIN)
                if (!pinInput) {
                    pinInput = document.querySelector('input[type="password"]');
                }
                
                // Strategy 5: By label text
                if (!pinInput) {
                    var labels = document.querySelectorAll('label, td, th, span');
                    for (var k = 0; k < labels.length; k++) {
                        var labelText = labels[k].textContent.trim().toLowerCase();
                        if (labelText === 'pin' || labelText === 'pin:' || labelText.indexOf('pin') !== -1) {
                            var container = labels[k].closest('tr, div, form, table');
                            if (container) {
                                pinInput = container.querySelector('input[type="text"], input[type="password"]');
                            }
                            if (!pinInput) {
                                pinInput = labels[k].nextElementSibling;
                                if (pinInput && pinInput.tagName !== 'INPUT') {
                                    pinInput = pinInput.querySelector('input[type="text"], input[type="password"]');
                                }
                            }
                            if (pinInput && pinInput.tagName === 'INPUT') {
                                break;
                            }
                        }
                    }
                }
                
                // Strategy 6: Second text input in form (fallback - usually PIN comes after voucher)
                if (!pinInput && gutscheinInput) {
                    var form = document.querySelector('form');
                    if (form) {
                        var allInputs = Array.from(form.querySelectorAll('input[type="text"], input[type="password"]'));
                        var gutscheinIndex = allInputs.indexOf(gutscheinInput);
                        if (gutscheinIndex !== -1 && gutscheinIndex + 1 < allInputs.length) {
                            pinInput = allInputs[gutscheinIndex + 1];
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
                
                // Fill the Gutschein field with proper event triggering
                if (gutscheinInput) {
                    gutscheinInput.focus();
                    gutscheinInput.value = '${card.cardNumber}';
                    // Trigger multiple events to ensure form validation recognizes the value
                    gutscheinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                    gutscheinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    gutscheinInput.dispatchEvent(new Event('blur', { bubbles: true, cancelable: true }));
                    // Set value directly as well (some forms check .value directly)
                    gutscheinInput.setAttribute('value', '${card.cardNumber}');
                    result.gutscheinFound = true;
                }
                
                // Fill the PIN field with proper event triggering
                if (pinInput) {
                    pinInput.focus();
                    pinInput.value = '${card.pin}';
                    // Trigger multiple events to ensure form validation recognizes the value
                    pinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                    pinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    pinInput.dispatchEvent(new Event('blur', { bubbles: true, cancelable: true }));
                    // Set value directly as well (some forms check .value directly)
                    pinInput.setAttribute('value', '${card.pin}');
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
