package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import java.util.regex.Pattern

/**
 * Market implementation for Lidl gift card balance checking.
 * Uses the lidl.de website for balance inquiries.
 *
 * Card format:
 * - Card number: 20 digits
 * - PIN: 4 digits
 */
class LidlMarket : Market() {

    override val marketType: MarketType = MarketType.LIDL

    override val displayName: String = "Lidl"

    override val balanceCheckUrl: String = "https://www.lidl.de/c/lidl-geschenkkarten/s10007775"

    override val brandColor: Int = Color.parseColor("#0050AA") // Lidl Blue

    /**
     * JavaScript to fill in the card number and PIN fields on the Lidl balance check form.
     * Tries multiple selector strategies to find form fields.
     */
    override fun getFormFillScript(card: GiftCard): String {
        return """
            (function() {
                try {
                    // Check if Android interface is available
                    if (typeof Android === 'undefined') {
                        return JSON.stringify({
                            success: false,
                            error: 'android_interface_not_available',
                            cardNumberFound: false,
                            pinFound: false,
                            debug: {
                                url: window.location.href,
                                allInputs: [],
                                foundInputs: []
                            }
                        });
                    }
                    
                    var result = {
                        success: false,
                        cardNumberFound: false,
                        pinFound: false,
                        debug: {
                            url: window.location.href,
                            allInputs: [],
                            foundInputs: [],
                            formExists: document.querySelector('form') !== null,
                            iframeFound: false,
                            iframeUrl: null,
                            iframeAccessible: false
                        }
                    };
                    
                    // First, check for iframes (Lidl might use iframes like ALDI)
                    var iframe = document.querySelector('iframe') || 
                                 document.querySelector('iframe[src*="lidl"]') ||
                                 document.querySelector('iframe[name*="form"]');
                    
                    if (iframe) {
                        result.debug.iframeFound = true;
                        result.debug.iframeUrl = iframe.src || iframe.getAttribute('src');
                        
                        try {
                            var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                            if (iframeDoc) {
                                result.debug.iframeAccessible = true;
                                
                                // Get all inputs from iframe for debugging
                                var iframeInputs = iframeDoc.querySelectorAll('input');
                                for (var i = 0; i < iframeInputs.length; i++) {
                                    result.debug.allInputs.push({
                                        name: iframeInputs[i].name || 'no-name',
                                        id: iframeInputs[i].id || 'no-id',
                                        type: iframeInputs[i].type || 'no-type',
                                        className: iframeInputs[i].className || 'no-class',
                                        placeholder: iframeInputs[i].placeholder || 'no-placeholder'
                                    });
                                }
                                
                                // Try to find fields in iframe
                                var cardNumberInput = iframeDoc.querySelector('input[name*="karten" i]') ||
                                                     iframeDoc.querySelector('input[name*="geschenk" i]') ||
                                                     iframeDoc.querySelector('input[name*="card" i]') ||
                                                     iframeDoc.querySelector('input[placeholder*="Kartennummer" i]') ||
                                                     iframeDoc.querySelector('input[placeholder*="20" i]') ||
                                                     iframeDoc.querySelector('input[type="text"]') ||
                                                     iframeDoc.querySelector('input[type="number"]');
                                
                                var pinInput = iframeDoc.querySelector('input[name*="pin" i]') ||
                                              iframeDoc.querySelector('input[placeholder*="PIN" i]') ||
                                              iframeDoc.querySelector('input[type="password"]');
                                
                                // If not found, try by position
                                if (!cardNumberInput || !pinInput) {
                                    var allIframeInputs = iframeDoc.querySelectorAll('input[type="text"], input[type="number"], input[type="password"]');
                                    if (allIframeInputs.length >= 2) {
                                        if (!cardNumberInput) cardNumberInput = allIframeInputs[0];
                                        if (!pinInput) pinInput = allIframeInputs[1];
                                    }
                                }
                                
                                // Fill fields in iframe
                                if (cardNumberInput) {
                                    cardNumberInput.value = '${card.cardNumber}';
                                    cardNumberInput.dispatchEvent(new Event('input', { bubbles: true }));
                                    cardNumberInput.dispatchEvent(new Event('change', { bubbles: true }));
                                    result.cardNumberFound = true;
                                    result.debug.foundInputs.push('cardNumber in iframe');
                                }
                                
                                if (pinInput) {
                                    pinInput.value = '${card.pin}';
                                    pinInput.dispatchEvent(new Event('input', { bubbles: true }));
                                    pinInput.dispatchEvent(new Event('change', { bubbles: true }));
                                    result.pinFound = true;
                                    result.debug.foundInputs.push('pin in iframe');
                                }
                                
                                result.success = result.cardNumberFound && result.pinFound;
                                return JSON.stringify(result);
                            }
                        } catch (e) {
                            result.debug.error = 'iframe_access_error: ' + e.toString();
                        }
                    }
                    
                    // Get all inputs from main page for debugging
                    var allInputs = document.querySelectorAll('input');
                    for (var i = 0; i < allInputs.length; i++) {
                        result.debug.allInputs.push({
                            name: allInputs[i].name || 'no-name',
                            id: allInputs[i].id || 'no-id',
                            type: allInputs[i].type || 'no-type',
                            className: allInputs[i].className || 'no-class',
                            placeholder: allInputs[i].placeholder || 'no-placeholder'
                        });
                    }
                    
                    // Find the card number (Kartennummer/Geschenkkartennummer) field
                    var cardNumberInput = document.querySelector('input[name="cardNumber"]') ||
                                         document.querySelector('input[name*="karten" i]') ||
                                         document.querySelector('input[name*="geschenk" i]') ||
                                         document.querySelector('input[name*="card" i]') ||
                                         document.querySelector('input[id*="karten" i]') ||
                                         document.querySelector('input[id*="geschenk" i]') ||
                                         document.querySelector('input[placeholder*="Kartennummer" i]') ||
                                         document.querySelector('input[placeholder*="20" i]') ||
                                         document.querySelector('input[placeholder*="Geschenkkarte" i]');
                    
                    if (!cardNumberInput) {
                        // Try finding by label
                        var labels = document.querySelectorAll('label');
                        for (var i = 0; i < labels.length; i++) {
                            var labelText = labels[i].textContent.toLowerCase();
                            if (labelText.indexOf('kartennummer') !== -1 ||
                                labelText.indexOf('geschenkkarte') !== -1 ||
                                labelText.indexOf('card number') !== -1 ||
                                labelText.indexOf('gutschein') !== -1) {
                                var forAttr = labels[i].getAttribute('for');
                                if (forAttr) {
                                    cardNumberInput = document.getElementById(forAttr);
                                }
                                if (!cardNumberInput) {
                                    cardNumberInput = labels[i].parentElement.querySelector('input');
                                }
                                if (!cardNumberInput) {
                                    cardNumberInput = labels[i].closest('form')?.querySelector('input[type="text"], input[type="number"]');
                                }
                                break;
                            }
                        }
                    }
                    
                    if (!cardNumberInput) {
                        // Last resort: find all text inputs and use the first one
                        var formInputs = document.querySelectorAll('form input[type="text"], form input[type="number"], input[type="text"], input[type="number"]');
                        if (formInputs.length > 0) {
                            cardNumberInput = formInputs[0];
                        }
                    }

                    // Find the PIN field
                    var pinInput = document.querySelector('input[name="pin"]') ||
                                  document.querySelector('input[name*="pin" i]') ||
                                  document.querySelector('input[id*="pin" i]') ||
                                  document.querySelector('input[placeholder*="PIN" i]') ||
                                  document.querySelector('input[type="password"]');
                    
                    if (!pinInput) {
                        var labels = document.querySelectorAll('label');
                        for (var i = 0; i < labels.length; i++) {
                            var labelText = labels[i].textContent.toLowerCase();
                            if (labelText.trim() === 'pin' ||
                                labelText.indexOf('pin') !== -1) {
                                var forAttr = labels[i].getAttribute('for');
                                if (forAttr) {
                                    pinInput = document.getElementById(forAttr);
                                }
                                if (!pinInput) {
                                    pinInput = labels[i].parentElement.querySelector('input');
                                }
                                if (!pinInput) {
                                    pinInput = labels[i].closest('form')?.querySelector('input[type="password"], input[type="text"]');
                                }
                                break;
                            }
                        }
                    }
                    
                    if (!pinInput) {
                        var formInputs = document.querySelectorAll('form input[type="text"], form input[type="password"], form input[type="number"], input[type="password"]');
                        if (formInputs.length > 1) {
                            pinInput = formInputs[1];
                        } else if (formInputs.length === 1 && cardNumberInput && formInputs[0] !== cardNumberInput) {
                            pinInput = formInputs[0];
                        }
                    }

                    // Fill the fields
                    if (cardNumberInput) {
                        cardNumberInput.value = '${card.cardNumber}';
                        cardNumberInput.dispatchEvent(new Event('input', { bubbles: true }));
                        cardNumberInput.dispatchEvent(new Event('change', { bubbles: true }));
                        cardNumberInput.dispatchEvent(new Event('blur', { bubbles: true }));
                        result.cardNumberFound = true;
                        result.debug.foundInputs.push('cardNumber: ' + (cardNumberInput.name || cardNumberInput.id || 'found'));
                    }

                    if (pinInput) {
                        pinInput.value = '${card.pin}';
                        pinInput.dispatchEvent(new Event('input', { bubbles: true }));
                        pinInput.dispatchEvent(new Event('change', { bubbles: true }));
                        pinInput.dispatchEvent(new Event('blur', { bubbles: true }));
                        result.pinFound = true;
                        result.debug.foundInputs.push('pin: ' + (pinInput.name || pinInput.id || 'found'));
                    }

                    result.success = result.cardNumberFound && result.pinFound;
                    return JSON.stringify(result);
                } catch (e) {
                    return JSON.stringify({
                        success: false,
                        error: 'javascript_error',
                        errorMessage: e.toString(),
                        cardNumberFound: false,
                        pinFound: false,
                        debug: {
                            url: window.location.href,
                            allInputs: [],
                            foundInputs: [],
                            error: e.toString()
                        }
                    });
                }
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
                    submitButton = document.querySelector('input[type="submit"]');
                }
                if (!submitButton) {
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                        var buttonText = buttons[i].textContent.toLowerCase();
                        if (buttonText.indexOf('guthaben') !== -1 ||
                            buttonText.indexOf('abfragen') !== -1 ||
                            buttonText.indexOf('prüfen') !== -1 ||
                            buttonText.indexOf('check') !== -1 ||
                            buttonText.indexOf('submit') !== -1) {
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
                try {
                    var result = {
                        success: false,
                        balance: null,
                        error: null,
                        html: document.body ? document.body.innerHTML : ''
                    };

                    if (!document.body) {
                        result.error = 'page_not_loaded';
                        Android.onBalanceResult(JSON.stringify(result));
                        return;
                    }

                    var bodyText = document.body.innerText || '';

                // Check for error messages (German)
                if (bodyText.indexOf('ungültig') !== -1 ||
                    bodyText.indexOf('nicht gefunden') !== -1 ||
                    bodyText.indexOf('falsch') !== -1 ||
                    bodyText.indexOf('Fehler') !== -1 ||
                    bodyText.indexOf('inkorrekt') !== -1) {
                    result.error = 'invalid_card';
                    Android.onBalanceResult(JSON.stringify(result));
                    return;
                }

                // Try to find balance patterns (German currency format)
                // Pattern: "Guthaben: 25,00 €" or "25,00 EUR" or "€ 25,00"
                var balancePatterns = [
                    /Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /Restguthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
                    /Aktuelles Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
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
                } catch (e) {
                    var errorResult = {
                        success: false,
                        balance: null,
                        error: 'javascript_error',
                        errorMessage: e.toString(),
                        html: document.body ? document.body.innerHTML : ''
                    };
                    Android.onBalanceResult(JSON.stringify(errorResult));
                }
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
                lowerResponse.contains("falsch") ||
                lowerResponse.contains("fehler") ||
                lowerResponse.contains("inkorrekt")) {
                return BalanceResult.invalidCard("Kartennummer oder PIN ungültig")
            }

            // Try to extract balance using regex patterns
            val balancePatterns = listOf(
                Pattern.compile("""Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Restguthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
                Pattern.compile("""Aktuelles Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€""", Pattern.CASE_INSENSITIVE),
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
                lowerHtml.contains("restguthaben")) &&
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
               lowerHtml.contains("inkorrekt")
    }
}
