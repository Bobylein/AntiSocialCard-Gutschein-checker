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
 * IMPORTANT: The balance check form is loaded in an iframe from tx-gate.com,
 * similar to ALDI. We navigate directly to the iframe URL for form filling.
 *
 * Card format:
 * - Card number: 18 digits
 * - PIN: 4 digits
 */
class LidlMarket : Market() {

    override val marketType: MarketType = MarketType.LIDL

    override val displayName: String = "Lidl"

    override val balanceCheckUrl: String = "https://www.lidl.de/c/lidl-geschenkkarten/s10007775"

    override val brandColor: Int = Color.parseColor("#0050AA") // Lidl Blue
    
    // Lidl uses a cross-origin iframe from balancechecks.tx-gate.com (same as ALDI)
    // The iframe URL that contains the actual form (cid=79 for Lidl)
    val iframeFormUrl: String = "https://balancechecks.tx-gate.com/balance.php?cid=79"

    /**
     * JavaScript to fill in the card number and PIN fields on the Lidl balance check form.
     * Lidl uses the same iframe approach as ALDI (balancechecks.tx-gate.com with cid=79).
     * This script uses the same selectors as ALDI since they use the same form provider.
     */
    override fun getFormFillScript(card: GiftCard): String {
        // Use the same script as ALDI since they use the same form provider (tx-gate.com)
        // Just adapt the field names if needed - but they should be the same
        return """
            (function() {
                var result = {
                    success: false,
                    cardNumberFound: false,
                    pinFound: false,
                    captchaFound: false,
                    debug: {
                        url: window.location.href,
                        iframeFound: false,
                        iframeUrl: null,
                        iframeAccessible: false,
                        error: null,
                        allInputs: []
                    }
                };
                
                // Fill form directly on page (when on balancechecks.tx-gate.com directly)
                // Try multiple selectors to find the card number field (same as ALDI)
                var cardNumberInput = document.querySelector('input[name="cardnumberfield"]') ||
                                    document.querySelector('input[name*="card" i]') ||
                                    document.querySelector('input[name*="gutschein" i]') ||
                                    document.querySelector('input[name*="karten" i]') ||
                                    document.querySelector('input[placeholder*="0000"]') ||
                                    null;
                
                // If not found by name/placeholder, try finding by table structure (same as ALDI)
                if (!cardNumberInput) {
                    var rows = document.querySelectorAll('tr');
                    for (var r = 0; r < rows.length; r++) {
                        var rowText = (rows[r].textContent || '').toLowerCase();
                        if (rowText.indexOf('gutschein') !== -1 || 
                            rowText.indexOf('guthaben') !== -1 ||
                            rowText.indexOf('kartennummer') !== -1 ||
                            rowText.indexOf('geschenkkarte') !== -1) {
                            var inputs = rows[r].querySelectorAll('input[type="text"]');
                            if (inputs.length > 0) {
                                cardNumberInput = inputs[0];
                                break;
                            }
                        }
                    }
                }
                
                // Try multiple selectors to find the PIN field (same as ALDI)
                var pinInput = document.querySelector('input[name="pin"]') ||
                              document.getElementById('myPw') ||
                              document.querySelector('input[name*="pin" i]') ||
                              document.querySelector('input[id*="pin" i]') ||
                              document.querySelector('input[id*="pw" i]') ||
                              document.querySelector('input[type="password"]') ||
                              null;
                
                // If not found, try finding by table structure
                if (!pinInput) {
                    var rows = document.querySelectorAll('tr');
                    for (var r = 0; r < rows.length; r++) {
                        var rowText = (rows[r].textContent || '').toLowerCase();
                        if (rowText.indexOf('pin') !== -1 && rowText.indexOf('anzeigen') === -1) {
                            var inputs = rows[r].querySelectorAll('input');
                            for (var i = 0; i < inputs.length; i++) {
                                if (inputs[i].type === 'password' || inputs[i].type === 'text') {
                                    pinInput = inputs[i];
                                    break;
                                }
                            }
                            if (pinInput) break;
                        }
                    }
                }
                
                var captchaInput = document.querySelector('input[name="input"]');
                
                // Fill card number field
                if (cardNumberInput) {
                    cardNumberInput.focus();
                    cardNumberInput.value = '${card.cardNumber}';
                    try {
                        cardNumberInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                        cardNumberInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                        cardNumberInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
                    } catch(e) {}
                    cardNumberInput.setAttribute('value', '${card.cardNumber}');
                    result.cardNumberFound = true;
                }
                
                // Fill PIN field
                if (pinInput) {
                    pinInput.focus();
                    pinInput.value = '${card.pin}';
                    try {
                        pinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                        pinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                        pinInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
                    } catch(e) {}
                    pinInput.setAttribute('value', '${card.pin}');
                    result.pinFound = true;
                }
                
                result.captchaFound = captchaInput !== null;
                result.success = result.cardNumberFound && result.pinFound;
                
                // Focus on CAPTCHA field to open keyboard after auto-fill (same as ALDI)
                if (result.success && captchaInput) {
                    var focusAttempts = 0;
                    var maxFocusAttempts = 5;
                    
                    function tryFocusCaptcha() {
                        focusAttempts++;
                        try {
                            var isReady = captchaInput.offsetParent !== null &&
                                         !captchaInput.disabled &&
                                         captchaInput.style.display !== 'none' &&
                                         captchaInput.style.visibility !== 'hidden';
                            
                            if (isReady) {
                                captchaInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                captchaInput.focus();
                                captchaInput.click();
                                
                                var focusEvent = new Event('focus', { bubbles: true, cancelable: true });
                                captchaInput.dispatchEvent(focusEvent);
                                
                                try {
                                    if (typeof TouchEvent !== 'undefined') {
                                        var touchStart = new TouchEvent('touchstart', { bubbles: true, cancelable: true });
                                        var touchEnd = new TouchEvent('touchend', { bubbles: true, cancelable: true });
                                        captchaInput.dispatchEvent(touchStart);
                                        captchaInput.dispatchEvent(touchEnd);
                                    } else {
                                        var mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                                        var mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                                        captchaInput.dispatchEvent(mouseDown);
                                        captchaInput.dispatchEvent(mouseUp);
                                    }
                                } catch(e) {}
                                
                                try {
                                    var rect = captchaInput.getBoundingClientRect();
                                    var x = Math.round(rect.left + rect.width / 2 + (window.scrollX || window.pageXOffset || 0));
                                    var y = Math.round(rect.top + rect.height / 2 + (window.scrollY || window.pageYOffset || 0));
                                    
                                    if (typeof Android !== 'undefined' && Android.simulateTouch) {
                                        Android.simulateTouch(x, y);
                                    }
                                } catch(e) {}
                                
                                setTimeout(function() {
                                    if (document.activeElement === captchaInput) {
                                        if (typeof Android !== 'undefined' && Android.log) {
                                            Android.log('CAPTCHA field focused successfully');
                                        }
                                    } else if (focusAttempts < maxFocusAttempts) {
                                        setTimeout(tryFocusCaptcha, 200);
                                    }
                                }, 100);
                            } else if (focusAttempts < maxFocusAttempts) {
                                setTimeout(tryFocusCaptcha, 300);
                            }
                        } catch(e) {
                            if (focusAttempts < maxFocusAttempts) {
                                setTimeout(tryFocusCaptcha, 300);
                            }
                        }
                    }
                    
                    setTimeout(tryFocusCaptcha, 500);
                }
                
                // Get only relevant form inputs for debugging (limit to prevent huge JSON)
                var allInputs = document.querySelectorAll('input');
                var relevantInputs = [];
                for (var i = 0; i < allInputs.length; i++) {
                    var input = allInputs[i];
                    // Only collect text, password, number, submit inputs (limit to 20)
                    if ((input.type === 'text' || input.type === 'password' || 
                         input.type === 'number' || input.type === 'submit' ||
                         input.type === 'button') && relevantInputs.length < 20) {
                        relevantInputs.push({
                            name: input.name || 'no-name',
                            id: input.id || 'no-id',
                            type: input.type || 'no-type',
                            className: input.className || 'no-class'
                        });
                    }
                }
                result.debug.allInputs = relevantInputs;
                
                if (result.cardNumberFound) {
                    if (!result.debug.foundInputs) {
                        result.debug.foundInputs = [];
                    }
                    result.debug.foundInputs.push('cardNumber: found');
                }
                if (result.pinFound) {
                    if (!result.debug.foundInputs) {
                        result.debug.foundInputs = [];
                    }
                    result.debug.foundInputs.push('pin: found');
                }
                if (result.captchaFound) {
                    if (!result.debug.foundInputs) {
                        result.debug.foundInputs = [];
                    }
                    result.debug.foundInputs.push('captcha: found');
                }
                
                try {
                    return JSON.stringify(result);
                } catch (jsonErr) {
                    return JSON.stringify({
                        success: false,
                        error: 'JSON serialization failed',
                        cardNumberFound: result.cardNumberFound || false,
                        pinFound: result.pinFound || false,
                        captchaFound: result.captchaFound || false
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
                // Find the submit button (same as ALDI)
                var submitButton = document.querySelector('input[name="check"]') ||
                                  document.querySelector('input[type="submit"]') ||
                                  document.querySelector('button[type="submit"]');
                
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
                    // Truncate long error messages to prevent UI issues
                    var errorMsg = e.toString();
                    if (errorMsg.length > 200) {
                        errorMsg = errorMsg.substring(0, 200) + '...';
                    }
                    var errorResult = {
                        success: false,
                        balance: null,
                        error: 'javascript_error',
                        errorMessage: errorMsg,
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
