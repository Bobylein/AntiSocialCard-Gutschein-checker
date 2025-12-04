/**
 * ALDI Nord - Form Fill Script
 * Fills the Gutschein (card number) and PIN fields on the tx-gate.com balance check form.
 * Supports both iframe and direct page loading.
 *
 * Placeholders:
 * - {{CARD_NUMBER}}: 20-digit voucher number
 * - {{PIN}}: 4-digit PIN
 */
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
        var cookieAcceptButton = document.querySelector('button[id*="cookie" i][id*="accept" i]') ||
                               document.querySelector('button[id="cookiebannerAccept"]') ||
                               document.querySelector('#cookiebannerAccept');

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
        }
    } catch (e) {}

    // Try multiple selectors to find the card number field
    var gutscheinInput = document.querySelector('input[name="cardnumberfield"]') ||
                        document.querySelector('input[name*="card" i]') ||
                        document.querySelector('input[name*="gutschein" i]') ||
                        document.querySelector('input[placeholder*="0000"]') ||
                        null;

    // If not found, try finding by table structure
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
        gutscheinInput.value = '{{CARD_NUMBER}}';
        try {
            gutscheinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            gutscheinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            gutscheinInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
        } catch(e) {}
        gutscheinInput.setAttribute('value', '{{CARD_NUMBER}}');
        result.gutscheinFound = true;
    }

    // Fill PIN field
    if (pinInput) {
        pinInput.focus();
        pinInput.value = '{{PIN}}';
        try {
            pinInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            pinInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            pinInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
        } catch(e) {}
        pinInput.setAttribute('value', '{{PIN}}');
        result.pinFound = true;
    }

    result.captchaFound = captchaInput !== null;
    result.success = result.gutscheinFound && result.pinFound;

    // Focus on CAPTCHA field to open keyboard after auto-fill
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

    if (result.gutscheinFound) {
        if (!result.debug.foundInputs) result.debug.foundInputs = [];
        result.debug.foundInputs.push('gutschein: found');
    }
    if (result.pinFound) {
        if (!result.debug.foundInputs) result.debug.foundInputs = [];
        result.debug.foundInputs.push('pin: found');
    }
    if (result.captchaFound) {
        if (!result.debug.foundInputs) result.debug.foundInputs = [];
        result.debug.foundInputs.push('captcha: found');
    }

    return JSON.stringify(result);
})();
