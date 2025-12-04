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
 * - Gutschein: 20-digit voucher number
 * - PIN: 4-digit PIN
 * - Lösung: CAPTCHA solution
 *
 * Note: This market requires manual user interaction due to iframe cross-origin restrictions.
 */
class AldiMarket : Market() {
    
    override val marketType: MarketType = MarketType.ALDI
    
    override val displayName: String = "ALDI Nord"
    
    // Official ALDI Nord gift card balance check URL
    // Note: The form is in a cross-origin iframe from balancechecks.tx-gate.com
    // which cannot be accessed by JavaScript due to same-origin policy.
    // The iframe URL cannot be loaded directly (returns blank page due to referrer check).
    override val balanceCheckUrl: String = "https://www.helaba.com/de/aldi/"
    
    override val brandColor: Int = Color.parseColor("#00529B") // ALDI Blue
    
    // ALDI uses a cross-origin iframe, but we can navigate to the iframe URL directly
    // after loading the parent page (cookies will be shared), then auto-fill the form.
    override val requiresManualEntry: Boolean = false
    
    // The iframe URL that contains the actual form
    val iframeFormUrl: String = "https://balancechecks.tx-gate.com/balance.php?cid=59"
    
    /**
     * JavaScript to fill in the Gutschein (voucher number) and PIN fields.
     * ALDI cards use a 20-digit voucher number and 4-digit PIN.
     *
     * Note: Due to cross-origin iframe restrictions, this script typically cannot
     * access the form fields. Manual entry is required (see requiresManualEntry).
     */
    override fun getFormFillScript(card: GiftCard): String {
        return """
            (function() {
                var result = {
                    success: false,
                    gutscheinFound: false,
                    pinFound: false,
                    captchaFound: false,
                    cookieBannerAccepted: false,
                    tabClicked: false,
                    iframeLoaded: false,
                    debug: {
                        url: window.location.href,
                        iframeFound: false,
                        iframeUrl: null,
                        iframeAccessible: false,
                        error: null,
                        allInputs: []
                    }
                };
                
                // First, try to accept cookie banner if present
                try {
                    // Look for cookie banner accept button (common patterns)
                    var cookieAcceptButton = document.querySelector('button[id*="cookie" i][id*="accept" i]') ||
                                           document.querySelector('button[id="cookiebannerAccept"]') ||
                                           document.querySelector('#cookiebannerAccept');
                    
                    // Try by text content
                    if (!cookieAcceptButton) {
                        var buttons = document.querySelectorAll('button');
                        for (var i = 0; i < buttons.length; i++) {
                            var text = (buttons[i].textContent || '').toLowerCase();
                            if ((text.indexOf('annehmen') !== -1 || text.indexOf('accept') !== -1) && 
                                (text.indexOf('cookie') !== -1 || buttons[i].id.indexOf('cookie') !== -1)) {
                                cookieAcceptButton = buttons[i];
                                break;
                            }
                        }
                    }
                    
                    if (cookieAcceptButton) {
                        cookieAcceptButton.click();
                        result.cookieBannerAccepted = true;
                        // Wait a bit for banner to disappear
                        setTimeout(function() {}, 500);
                    }
                } catch (e) {
                    // Ignore cookie banner errors
                }
                
                // Check if we're on the Helaba page with tabs - "Guthaben abfragen" tab is active by default
                // IMPORTANT: Only use "Guthaben abfragen" (balance inquiry), NEVER use "Gutschein sperren" (voucher lock)
                // Only click if it's not already active
                var guthabenTab = null;
                try {
                    // Look for tab with "Guthaben abfragen" text
                    // Explicitly avoid "Gutschein sperren" tab
                    var tabs = document.querySelectorAll('.tab, [class*="tab"], [id*="tab"], button, a');
                    for (var t = 0; t < tabs.length; t++) {
                        var tabText = (tabs[t].textContent || '').toLowerCase();
                        // Explicitly check that this is NOT the "Gutschein sperren" tab
                        var isSperrenTab = tabText.indexOf('sperren') !== -1 || 
                                          tabText.indexOf('lock') !== -1 ||
                                          tabText.indexOf('block') !== -1;
                        if (isSperrenTab) {
                            // Skip this tab - we only want "Guthaben abfragen"
                            continue;
                        }
                        // Look for "Guthaben abfragen" tab
                        if ((tabText.indexOf('guthaben abfragen') !== -1 || 
                             tabText.indexOf('guthabenabfrage') !== -1 ||
                             (tabText.indexOf('guthaben') !== -1 && tabText.indexOf('abfragen') !== -1)) &&
                            tabs[t].offsetParent !== null) { // Element is visible
                            guthabenTab = tabs[t];
                            break;
                        }
                    }
                    
                    if (guthabenTab) {
                        // Check if tab is already active (has active class, selected class, or aria-selected)
                        var isActive = guthabenTab.classList.contains('active') ||
                                      guthabenTab.classList.contains('selected') ||
                                      guthabenTab.classList.contains('ui-tabs-active') ||
                                      guthabenTab.getAttribute('aria-selected') === 'true' ||
                                      guthabenTab.getAttribute('aria-current') === 'page' ||
                                      guthabenTab.getAttribute('class') && guthabenTab.getAttribute('class').indexOf('active') !== -1;
                        
                        // Also check if iframe is already visible (means tab is already active)
                        var iframeVisible = false;
                        var iframe = document.querySelector('iframe[src*="balancechecks.tx-gate.com"]') ||
                                     document.querySelector('iframe[name="IR-iFrame"]') ||
                                     document.querySelector('iframe[src*="balance.php"]');
                        if (iframe) {
                            var iframeStyle = window.getComputedStyle(iframe);
                            iframeVisible = iframeStyle.display !== 'none' && iframeStyle.visibility !== 'hidden' && iframeStyle.opacity !== '0';
                        }
                        
                        if (!isActive && !iframeVisible) {
                            // Tab exists but is not active, click it
                            // Prevent default navigation if it's a link
                            try {
                                var event = new MouseEvent('click', {
                                    bubbles: true,
                                    cancelable: true,
                                    view: window
                                });
                                guthabenTab.dispatchEvent(event);
                                // Also try regular click but prevent default
                                if (guthabenTab.href) {
                                    // It's a link, prevent navigation
                                    event.preventDefault();
                                }
                                guthabenTab.click();
                            } catch (e) {
                                // Fallback to regular click
                                guthabenTab.click();
                            }
                            result.tabClicked = true;
                            // Wait a bit for tab to switch and iframe to load
                            setTimeout(function() {}, 1000);
                        } else {
                            // Tab already active or iframe already visible, no need to click
                            result.tabClicked = false;
                            result.iframeLoaded = iframeVisible; // If iframe is visible, it's loaded
                        }
                    } else {
                        // No tab found, assume we're already on the right page
                        result.tabClicked = false;
                    }
                } catch (e) {
                    // Ignore tab click errors
                    result.tabClicked = false;
                }
                
                // First, check if form fields are directly on the page (mobile view)
                // Try multiple selectors for card number field
                var gutscheinInputDirect = document.querySelector('input[name="cardnumberfield"]') ||
                                          document.querySelector('input[name*="card" i]') ||
                                          document.querySelector('input[name*="gutschein" i]') ||
                                          document.querySelector('input[placeholder*="0000"]') ||
                                          null;
                
                // If not found, try finding by table structure
                if (!gutscheinInputDirect) {
                    var rows = document.querySelectorAll('tr');
                    for (var r = 0; r < rows.length; r++) {
                        var rowText = (rows[r].textContent || '').toLowerCase();
                        if (rowText.indexOf('gutschein') !== -1 || rowText.indexOf('guthaben') !== -1) {
                            var inputs = rows[r].querySelectorAll('input[type="text"]');
                            if (inputs.length > 0) {
                                gutscheinInputDirect = inputs[0];
                                break;
                            }
                        }
                    }
                }
                
                // Try multiple selectors for PIN field
                var pinInputDirect = document.querySelector('input[name="pin"]') ||
                                    document.getElementById('myPw') ||
                                    document.querySelector('input[name*="pin" i]') ||
                                    document.querySelector('input[id*="pin" i]') ||
                                    document.querySelector('input[id*="pw" i]') ||
                                    document.querySelector('input[type="password"]') ||
                                    null;
                
                // If not found, try finding by table structure
                if (!pinInputDirect) {
                    var rows = document.querySelectorAll('tr');
                    for (var r = 0; r < rows.length; r++) {
                        var rowText = (rows[r].textContent || '').toLowerCase();
                        if (rowText.indexOf('pin') !== -1 && rowText.indexOf('anzeigen') === -1) {
                            var inputs = rows[r].querySelectorAll('input');
                            for (var i = 0; i < inputs.length; i++) {
                                if (inputs[i].type === 'password' || inputs[i].type === 'text') {
                                    pinInputDirect = inputs[i];
                                    break;
                                }
                            }
                            if (pinInputDirect) break;
                        }
                    }
                }
                
                // If form fields are directly accessible, fill them (mobile view)
                if (gutscheinInputDirect || pinInputDirect) {
                    result.debug.iframeFound = false;
                    result.debug.iframeAccessible = false;
                    result.iframeLoaded = true; // No iframe needed
                    
                    // Fill form directly on page
                    if (gutscheinInputDirect) {
                        gutscheinInputDirect.focus();
                        gutscheinInputDirect.value = '${card.cardNumber}';
                        try {
                            gutscheinInputDirect.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                            gutscheinInputDirect.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                            gutscheinInputDirect.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
                        } catch(e) {}
                        gutscheinInputDirect.setAttribute('value', '${card.cardNumber}');
                        result.gutscheinFound = true;
                    }
                    
                    if (pinInputDirect) {
                        pinInputDirect.focus();
                        pinInputDirect.value = '${card.pin}';
                        try {
                            pinInputDirect.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                            pinInputDirect.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                            pinInputDirect.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
                        } catch(e) {}
                        pinInputDirect.setAttribute('value', '${card.pin}');
                        result.pinFound = true;
                    }
                    
                    var captchaInputDirect = document.querySelector('input[name="input"]');
                    result.captchaFound = captchaInputDirect !== null;
                    result.success = result.gutscheinFound && result.pinFound;
                    
                    // Focus on CAPTCHA field to open keyboard after auto-fill
                    if (result.success && captchaInputDirect) {
                        // Use multiple attempts with increasing delays to ensure focus works
                        var focusAttempts = 0;
                        var maxFocusAttempts = 5;
                        
                        function tryFocusCaptcha() {
                            focusAttempts++;
                            try {
                                // Check if field is ready (visible, enabled, not disabled)
                                var isReady = captchaInputDirect.offsetParent !== null &&
                                             !captchaInputDirect.disabled &&
                                             captchaInputDirect.style.display !== 'none' &&
                                             captchaInputDirect.style.visibility !== 'hidden';
                                
                                if (isReady) {
                                    // Scroll into view first
                                    captchaInputDirect.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                    
                                    // Try focus and click multiple times
                                    captchaInputDirect.focus();
                                    captchaInputDirect.click();
                                    
                                    // Dispatch focus events
                                    var focusEvent = new Event('focus', { bubbles: true, cancelable: true });
                                    captchaInputDirect.dispatchEvent(focusEvent);
                                    
                                    // Also try touch events for mobile (if supported)
                                    try {
                                        if (typeof TouchEvent !== 'undefined') {
                                            var touchStart = new TouchEvent('touchstart', { bubbles: true, cancelable: true });
                                            var touchEnd = new TouchEvent('touchend', { bubbles: true, cancelable: true });
                                            captchaInputDirect.dispatchEvent(touchStart);
                                            captchaInputDirect.dispatchEvent(touchEnd);
                                        } else {
                                            // Fallback to mouse events for mobile WebView
                                            var mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                                            var mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                                            captchaInputDirect.dispatchEvent(mouseDown);
                                            captchaInputDirect.dispatchEvent(mouseUp);
                                        }
                                    } catch(e) {}
                                    
                                    // Get coordinates and call Android to simulate touch
                                    try {
                                        var rect = captchaInputDirect.getBoundingClientRect();
                                        var x = Math.round(rect.left + rect.width / 2 + (window.scrollX || window.pageXOffset || 0));
                                        var y = Math.round(rect.top + rect.height / 2 + (window.scrollY || window.pageYOffset || 0));
                                        
                                        if (typeof Android !== 'undefined' && Android.simulateTouch) {
                                            Android.simulateTouch(x, y);
                                        }
                                    } catch(e) {}
                                    
                                    // Verify focus worked
                                    setTimeout(function() {
                                        if (document.activeElement === captchaInputDirect) {
                                            // Success - field is focused
                                            if (typeof Android !== 'undefined' && Android.log) {
                                                Android.log('CAPTCHA field focused successfully');
                                            }
                                        } else if (focusAttempts < maxFocusAttempts) {
                                            // Retry if not focused yet
                                            setTimeout(tryFocusCaptcha, 200);
                                        }
                                    }, 100);
                                } else if (focusAttempts < maxFocusAttempts) {
                                    // Field not ready yet, retry
                                    setTimeout(tryFocusCaptcha, 300);
                                }
                            } catch(e) {
                                if (focusAttempts < maxFocusAttempts) {
                                    setTimeout(tryFocusCaptcha, 300);
                                }
                            }
                        }
                        
                        // Start focusing after a short delay
                        setTimeout(tryFocusCaptcha, 500);
                    }
                    
                    // Get all inputs for debugging
                    var allInputs = document.querySelectorAll('input');
                    for (var i = 0; i < allInputs.length; i++) {
                        result.debug.allInputs.push({
                            name: allInputs[i].name || 'no-name',
                            id: allInputs[i].id || 'no-id',
                            type: allInputs[i].type || 'no-type',
                            className: allInputs[i].className || 'no-class'
                        });
                    }
                    
                    return JSON.stringify(result);
                }
                
                // If no direct form fields, try to find the iframe containing the form (desktop view)
                var iframe = document.querySelector('iframe[src*="balancechecks.tx-gate.com"]') ||
                             document.querySelector('iframe[name="IR-iFrame"]') ||
                             document.querySelector('iframe[src*="balance.php"]') ||
                             document.querySelector('iframe');
                
                if (iframe) {
                    result.debug.iframeFound = true;
                    result.debug.iframeUrl = iframe.src || iframe.getAttribute('src');
                    
                    // Check if iframe is loaded (has content)
                    try {
                        var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                        if (iframeDoc && iframeDoc.body && iframeDoc.body.children.length > 0) {
                            result.iframeLoaded = true;
                            result.debug.iframeAccessible = true;
                        } else {
                            // Iframe exists but not loaded yet
                            result.iframeLoaded = false;
                            return JSON.stringify(result);
                        }
                    } catch (e) {
                        // Cross-origin - check if iframe src is set (means it's loading)
                        if (iframe.src && iframe.src.length > 0) {
                            result.iframeLoaded = true; // Assume loaded if src is set
                        } else {
                            result.iframeLoaded = false;
                            return JSON.stringify(result);
                        }
                    }
                    
                    // If we found an iframe and it's loaded, try to access it
                    try {
                        var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                        if (iframeDoc) {
                            
                            // Try to accept cookie banner in iframe if present
                            try {
                                var iframeCookieButton = iframeDoc.querySelector('button[id*="cookie" i][id*="accept" i]') ||
                                                       iframeDoc.querySelector('#cookiebannerAccept');
                                if (!iframeCookieButton) {
                                    var iframeButtons = iframeDoc.querySelectorAll('button');
                                    for (var j = 0; j < iframeButtons.length; j++) {
                                        var btnText = (iframeButtons[j].textContent || '').toLowerCase();
                                        if ((btnText.indexOf('annehmen') !== -1 || btnText.indexOf('accept') !== -1) && 
                                            (btnText.indexOf('cookie') !== -1 || iframeButtons[j].id.indexOf('cookie') !== -1)) {
                                            iframeCookieButton = iframeButtons[j];
                                            break;
                                        }
                                    }
                                }
                                if (iframeCookieButton) {
                                    iframeCookieButton.click();
                                    result.cookieBannerAccepted = true;
                                }
                            } catch (e) {}
                            
                            // Get all inputs from iframe for debugging
                            var iframeInputs = iframeDoc.querySelectorAll('input');
                            for (var i = 0; i < iframeInputs.length; i++) {
                                result.debug.allInputs.push({
                                    name: iframeInputs[i].name || 'no-name',
                                    id: iframeInputs[i].id || 'no-id',
                                    type: iframeInputs[i].type || 'no-type',
                                    className: iframeInputs[i].className || 'no-class'
                                });
                            }
                            
                            // Find and fill form fields in iframe
                            // Try multiple selectors for card number field
                            var gutscheinInput = iframeDoc.querySelector('input[name="cardnumberfield"]') ||
                                                iframeDoc.querySelector('input[name*="card" i]') ||
                                                iframeDoc.querySelector('input[name*="gutschein" i]') ||
                                                iframeDoc.querySelector('input[placeholder*="0000"]') ||
                                                null;
                            
                            // If not found, try finding by table structure
                            if (!gutscheinInput) {
                                var rows = iframeDoc.querySelectorAll('tr');
                                for (var r = 0; r < rows.length; r++) {
                                    var rowText = (rows[r].textContent || '').toLowerCase();
                                    if (rowText.indexOf('gutschein') !== -1 || rowText.indexOf('guthaben') !== -1) {
                                        var inputs = rows[r].querySelectorAll('input[type="text"]');
                                        if (inputs.length > 0) {
                                            gutscheinInput = inputs[0];
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            // Try multiple selectors for PIN field
                            var pinInput = iframeDoc.querySelector('input[name="pin"]') ||
                                          iframeDoc.getElementById('myPw') ||
                                          iframeDoc.querySelector('input[name*="pin" i]') ||
                                          iframeDoc.querySelector('input[id*="pin" i]') ||
                                          iframeDoc.querySelector('input[id*="pw" i]') ||
                                          iframeDoc.querySelector('input[type="password"]') ||
                                          null;
                            
                            // If not found, try finding by table structure
                            if (!pinInput) {
                                var rows = iframeDoc.querySelectorAll('tr');
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
                            
                            var captchaInput = iframeDoc.querySelector('input[name="input"]');
                            
                            // Fill Gutschein field
                            if (gutscheinInput) {
                                gutscheinInput.focus();
                                gutscheinInput.value = '${card.cardNumber}';
                                try {
                                    gutscheinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                                    gutscheinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                                    gutscheinInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
                                } catch(e) {}
                                gutscheinInput.setAttribute('value', '${card.cardNumber}');
                                result.gutscheinFound = true;
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
                            result.success = result.gutscheinFound && result.pinFound;
                            
                            // Focus on CAPTCHA field to open keyboard after auto-fill
                            if (result.success && captchaInput) {
                                // Use multiple attempts with increasing delays to ensure focus works
                                var focusAttempts = 0;
                                var maxFocusAttempts = 5;
                                
                                function tryFocusCaptcha() {
                                    focusAttempts++;
                                    try {
                                        // Check if field is ready (visible, enabled, not disabled)
                                        var isReady = captchaInput.offsetParent !== null &&
                                                     !captchaInput.disabled &&
                                                     captchaInput.style.display !== 'none' &&
                                                     captchaInput.style.visibility !== 'hidden';
                                        
                                        if (isReady) {
                                            // Scroll into view first
                                            captchaInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                            
                                            // Try focus and click multiple times
                                            captchaInput.focus();
                                            captchaInput.click();
                                            
                                            // Dispatch focus events
                                            var focusEvent = new Event('focus', { bubbles: true, cancelable: true });
                                            captchaInput.dispatchEvent(focusEvent);
                                            
                                            // Also try touchstart/touchend for mobile
                                            try {
                                                var touchStart = new TouchEvent('touchstart', { bubbles: true, cancelable: true });
                                                var touchEnd = new TouchEvent('touchend', { bubbles: true, cancelable: true });
                                                captchaInput.dispatchEvent(touchStart);
                                                captchaInput.dispatchEvent(touchEnd);
                                            } catch(e) {}
                                            
                                            // Get coordinates and call Android to simulate touch
                                            try {
                                                var iframeRect = iframe.getBoundingClientRect();
                                                var captchaRect = captchaInput.getBoundingClientRect();
                                                var x = Math.round(iframeRect.left + captchaRect.left + captchaRect.width / 2 + (window.scrollX || window.pageXOffset || 0));
                                                var y = Math.round(iframeRect.top + captchaRect.top + captchaRect.height / 2 + (window.scrollY || window.pageYOffset || 0));
                                                
                                                if (typeof Android !== 'undefined' && Android.simulateTouch) {
                                                    Android.simulateTouch(x, y);
                                                }
                                            } catch(e) {}
                                            
                                            // Verify focus worked
                                            setTimeout(function() {
                                                if (iframeDoc.activeElement === captchaInput) {
                                                    // Success - field is focused
                                                    if (typeof Android !== 'undefined' && Android.log) {
                                                        Android.log('CAPTCHA field focused successfully in iframe');
                                                    }
                                                } else if (focusAttempts < maxFocusAttempts) {
                                                    // Retry if not focused yet
                                                    setTimeout(tryFocusCaptcha, 200);
                                                }
                                            }, 100);
                                        } else if (focusAttempts < maxFocusAttempts) {
                                            // Field not ready yet, retry
                                            setTimeout(tryFocusCaptcha, 300);
                                        }
                                    } catch(e) {
                                        if (focusAttempts < maxFocusAttempts) {
                                            setTimeout(tryFocusCaptcha, 300);
                                        }
                                    }
                                }
                                
                                // Start focusing after a short delay
                                setTimeout(tryFocusCaptcha, 500);
                            }
                        }
                    } catch (e) {
                        // Cross-origin - we're probably on the direct URL, so fill form directly
                        result.debug.error = 'Cross-origin iframe - filling form directly';
                    }
                }
                
                // Fill form directly on page (when on balancechecks.tx-gate.com directly)
                // Try multiple selectors to find the card number field
                var gutscheinInput = document.querySelector('input[name="cardnumberfield"]') ||
                                    document.querySelector('input[name*="card" i]') ||
                                    document.querySelector('input[name*="gutschein" i]') ||
                                    document.querySelector('input[placeholder*="0000"]') ||
                                    null;
                
                // If not found by name/placeholder, try finding by table structure (ALDI form uses tables)
                if (!gutscheinInput) {
                    var rows = document.querySelectorAll('tr');
                    for (var r = 0; r < rows.length; r++) {
                        var rowText = (rows[r].textContent || '').toLowerCase();
                        if (rowText.indexOf('gutschein') !== -1 || rowText.indexOf('guthaben') !== -1) {
                            var inputs = rows[r].querySelectorAll('input[type="text"]');
                            if (inputs.length > 0) {
                                gutscheinInput = inputs[0];
                                break;
                            }
                        }
                    }
                }
                
                // Try multiple selectors to find the PIN field
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
                
                // Fill Gutschein field
                if (gutscheinInput) {
                    gutscheinInput.focus();
                    gutscheinInput.value = '${card.cardNumber}';
                    try {
                        gutscheinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                        gutscheinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                        gutscheinInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
                    } catch(e) {}
                    gutscheinInput.setAttribute('value', '${card.cardNumber}');
                    result.gutscheinFound = true;
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
                result.success = result.gutscheinFound && result.pinFound;
                
                // Focus on CAPTCHA field to open keyboard after auto-fill
                if (result.success && captchaInput) {
                    // Use multiple attempts with increasing delays to ensure focus works
                    var focusAttempts = 0;
                    var maxFocusAttempts = 5;
                    
                    function tryFocusCaptcha() {
                        focusAttempts++;
                        try {
                            // Check if field is ready (visible, enabled, not disabled)
                            var isReady = captchaInput.offsetParent !== null &&
                                         !captchaInput.disabled &&
                                         captchaInput.style.display !== 'none' &&
                                         captchaInput.style.visibility !== 'hidden';
                            
                            if (isReady) {
                                // Scroll into view first
                                captchaInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                
                                // Try focus and click multiple times
                                captchaInput.focus();
                                captchaInput.click();
                                
                                // Dispatch focus events
                                var focusEvent = new Event('focus', { bubbles: true, cancelable: true });
                                captchaInput.dispatchEvent(focusEvent);
                                
                                // Also try touchstart/touchend for mobile
                                try {
                                    if (typeof TouchEvent !== 'undefined') {
                                        var touchStart = new TouchEvent('touchstart', { bubbles: true, cancelable: true });
                                        var touchEnd = new TouchEvent('touchend', { bubbles: true, cancelable: true });
                                        captchaInput.dispatchEvent(touchStart);
                                        captchaInput.dispatchEvent(touchEnd);
                                    } else {
                                        // Fallback to mouse events for mobile WebView
                                        var mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                                        var mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                                        captchaInput.dispatchEvent(mouseDown);
                                        captchaInput.dispatchEvent(mouseUp);
                                    }
                                } catch(e) {}
                                
                                // Get coordinates and call Android to simulate touch
                                try {
                                    var rect = captchaInput.getBoundingClientRect();
                                    var x = Math.round(rect.left + rect.width / 2 + (window.scrollX || window.pageXOffset || 0));
                                    var y = Math.round(rect.top + rect.height / 2 + (window.scrollY || window.pageYOffset || 0));
                                    
                                    if (typeof Android !== 'undefined' && Android.simulateTouch) {
                                        Android.simulateTouch(x, y);
                                    }
                                } catch(e) {}
                                
                                // Verify focus worked
                                setTimeout(function() {
                                    if (document.activeElement === captchaInput) {
                                        // Success - field is focused
                                        if (typeof Android !== 'undefined' && Android.log) {
                                            Android.log('CAPTCHA field focused successfully');
                                        }
                                    } else if (focusAttempts < maxFocusAttempts) {
                                        // Retry if not focused yet
                                        setTimeout(tryFocusCaptcha, 200);
                                    }
                                }, 100);
                            } else if (focusAttempts < maxFocusAttempts) {
                                // Field not ready yet, retry
                                setTimeout(tryFocusCaptcha, 300);
                            }
                        } catch(e) {
                            if (focusAttempts < maxFocusAttempts) {
                                setTimeout(tryFocusCaptcha, 300);
                            }
                        }
                    }
                    
                    // Start focusing after a short delay
                    setTimeout(tryFocusCaptcha, 500);
                }
                
                // Get all inputs for debugging
                var allInputs = document.querySelectorAll('input');
                for (var i = 0; i < allInputs.length; i++) {
                    result.debug.allInputs.push({
                        name: allInputs[i].name || 'no-name',
                        id: allInputs[i].id || 'no-id',
                        type: allInputs[i].type || 'no-type',
                        className: allInputs[i].className || 'no-class'
                    });
                }
                
                // Add debug info about found inputs
                if (result.gutscheinFound) {
                    if (!result.debug.foundInputs) {
                        result.debug.foundInputs = [];
                    }
                    result.debug.foundInputs.push('gutschein: found');
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
                
                // Log to Android console for debugging
                try {
                    if (typeof Android !== 'undefined' && Android.log) {
                        var logResult = JSON.stringify(result);
                        Android.log('Form fill result: ' + logResult);
                    }
                } catch (logErr) {
                    // Ignore logging errors
                }
                
                try {
                    return JSON.stringify(result);
                } catch (jsonErr) {
                    // Fallback if JSON.stringify fails - create a simple safe object
                    try {
                        return JSON.stringify({
                            success: false,
                            error: 'JSON serialization failed',
                            gutscheinFound: result.gutscheinFound || false,
                            pinFound: result.pinFound || false,
                            captchaFound: result.captchaFound || false,
                            redirect: result.redirect || false,
                            redirectUrl: result.redirectUrl || null
                        });
                    } catch (fallbackErr) {
                        // Last resort - return minimal safe JSON
                        return '{"success":false,"error":"JSON serialization failed"}';
                    }
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
                // Find the Guthabenabfrage (balance check) submit button
                var submitButton = null;
                
                // Strategy 1: By exact name attribute (ALDI form uses name="check")
                submitButton = document.querySelector('input[name="check"]');
                
                // Strategy 2: By class name
                if (!submitButton) {
                    submitButton = document.querySelector('input.check');
                }
                
                // Strategy 3: By value attribute
                if (!submitButton) {
                    var buttons = document.querySelectorAll('input[type="submit"], button[type="submit"]');
                    for (var i = 0; i < buttons.length; i++) {
                        var buttonValue = (buttons[i].value || buttons[i].textContent || '').toLowerCase();
                        if (buttonValue.indexOf('guthabenabfrage') !== -1 || 
                            buttonValue.indexOf('guthaben') !== -1 ||
                            buttonValue.indexOf('abfragen') !== -1 ||
                            buttonValue.indexOf('prüfen') !== -1 ||
                            buttonValue.indexOf('check') !== -1) {
                            submitButton = buttons[i];
                            break;
                        }
                    }
                }
                
                // Strategy 4: First submit button in form
                if (!submitButton) {
                    submitButton = document.querySelector('input[type="submit"]') ||
                                   document.querySelector('button[type="submit"]');
                }
                
                if (submitButton) {
                    // Trigger click event
                    submitButton.click();
                    return JSON.stringify({ success: true, method: 'button_click' });
                } else {
                    // Try submitting the form directly
                    var form = document.querySelector('form');
                    if (form) {
                        form.submit();
                        return JSON.stringify({ success: true, method: 'form_submit' });
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
                    bodyText.indexOf('unbekannt') !== -1 ||
                    bodyText.indexOf('gesperrt') !== -1) {
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
                lowerResponse.contains("unbekannt") ||
                lowerResponse.contains("gesperrt")) {
                return BalanceResult.invalidCard("Gutscheinnummer oder PIN ungültig oder Gutschein gesperrt")
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
               lowerHtml.contains("unbekannt") ||
               lowerHtml.contains("gesperrt")
    }
}
