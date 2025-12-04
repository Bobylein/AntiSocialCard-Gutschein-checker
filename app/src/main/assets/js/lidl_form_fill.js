/**
 * Lidl - Form Fill Script
 * Fills the card number and PIN fields on the tx-gate.com balance check form.
 * Uses the same form provider as ALDI (tx-gate.com with cid=79).
 *
 * Placeholders:
 * - {{CARD_NUMBER}}: 20-digit card number
 * - {{PIN}}: 4-digit PIN
 */
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

    // Try multiple selectors to find the card number field
    var cardNumberInput = document.querySelector('input[name="cardnumberfield"]') ||
                        document.querySelector('input[name*="card" i]') ||
                        document.querySelector('input[name*="gutschein" i]') ||
                        document.querySelector('input[name*="karten" i]') ||
                        document.querySelector('input[placeholder*="0000"]') ||
                        null;

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

    // Try multiple selectors to find the PIN field
    var pinInput = document.querySelector('input[name="pin"]') ||
                  document.getElementById('myPw') ||
                  document.querySelector('input[name*="pin" i]') ||
                  document.querySelector('input[id*="pin" i]') ||
                  document.querySelector('input[id*="pw" i]') ||
                  document.querySelector('input[type="password"]') ||
                  null;

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
        cardNumberInput.value = '{{CARD_NUMBER}}';
        try {
            cardNumberInput.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
            cardNumberInput.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            cardNumberInput.dispatchEvent(new Event('keyup', { bubbles: true, cancelable: true }));
        } catch(e) {}
        cardNumberInput.setAttribute('value', '{{CARD_NUMBER}}');
        result.cardNumberFound = true;
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
    result.success = result.cardNumberFound && result.pinFound;

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

    // Get only relevant form inputs for debugging (limit to prevent huge JSON)
    var allInputs = document.querySelectorAll('input');
    var relevantInputs = [];
    for (var i = 0; i < allInputs.length; i++) {
        var input = allInputs[i];
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
        if (!result.debug.foundInputs) result.debug.foundInputs = [];
        result.debug.foundInputs.push('cardNumber: found');
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
