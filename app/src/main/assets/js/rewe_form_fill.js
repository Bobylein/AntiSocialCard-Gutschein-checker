/**
 * REWE - Form Fill Script
 * Fills the Kartennummer (card number) and PIN fields on the REWE balance check form.
 *
 * Placeholders:
 * - {{CARD_NUMBER}}: Card number
 * - {{PIN}}: PIN
 */
(function() {
    function scrollElementIntoView(el, block) {
        if (!el) return false;
        try {
            el.scrollIntoView({ behavior: 'smooth', block: block || 'center' });
            return true;
        } catch (e) {
            try {
                var rect = el.getBoundingClientRect();
                window.scrollTo({
                    top: rect.top + (window.scrollY || window.pageYOffset || 0) - 40,
                    behavior: 'smooth'
                });
                return true;
            } catch (err) {
                return false;
            }
        }
    }

    function findCaptchaInput(doc) {
        doc = doc || document;
        var selectors = [
            'input[name="input"]',
            'input[name="captcha"]',
            'input[name*="captcha" i]',
            'input[id*="captcha" i]',
            'input[aria-label*="captcha" i]',
            'input[placeholder*="captcha" i]',
            'input[name*="sicher" i]',
            'input[placeholder*="sicherheits" i]',
            'input[name*="code" i]'
        ];

        for (var i = 0; i < selectors.length; i++) {
            var el = doc.querySelector(selectors[i]);
            if (el) return el;
        }

        var labels = doc.querySelectorAll('label');
        for (var j = 0; j < labels.length; j++) {
            var text = (labels[j].textContent || '').toLowerCase();
            if (text.indexOf('captcha') !== -1 ||
                text.indexOf('sicherheits') !== -1 ||
                text.indexOf('prüfcode') !== -1 ||
                text.indexOf('pruefcode') !== -1 ||
                text.indexOf('security') !== -1 ||
                text.indexOf('code eingeben') !== -1) {
                var forAttr = labels[j].getAttribute('for');
                if (forAttr) {
                    var labeled = doc.getElementById(forAttr);
                    if (labeled) return labeled;
                }
                var nestedInput = labels[j].parentElement ? labels[j].parentElement.querySelector('input') : null;
                if (nestedInput) return nestedInput;
            }
        }

        return null;
    }

    var result = {
        success: false,
        cardNumberFound: false,
        pinFound: false,
        captchaFound: false,
        scrolledToForm: false,
        scrolledToCaptcha: false
    };

    // Find the Kartennummer (card number) field
    var cardNumberInput = document.querySelector('input[name="cardNumber"]');
    if (!cardNumberInput) {
        cardNumberInput = document.querySelector('input[placeholder*="Kartennummer"]');
    }
    if (!cardNumberInput) {
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
        cardNumberInput.value = '{{CARD_NUMBER}}';
        cardNumberInput.dispatchEvent(new Event('input', { bubbles: true }));
        cardNumberInput.dispatchEvent(new Event('change', { bubbles: true }));
        result.cardNumberFound = true;
    }

    if (pinInput) {
        pinInput.value = '{{PIN}}';
        pinInput.dispatchEvent(new Event('input', { bubbles: true }));
        pinInput.dispatchEvent(new Event('change', { bubbles: true }));
        result.pinFound = true;
    }

    var captchaInput = findCaptchaInput(document);
    if (captchaInput) {
        result.captchaFound = true;
    }

    var formSection = (cardNumberInput && cardNumberInput.closest && cardNumberInput.closest('form')) ||
                      (pinInput && pinInput.closest && pinInput.closest('form')) ||
                      (captchaInput && captchaInput.closest && captchaInput.closest('form')) ||
                      cardNumberInput || pinInput || captchaInput ||
                      document.querySelector('form') ||
                      document.body;

    if (formSection) {
        result.scrolledToForm = scrollElementIntoView(formSection, 'start');
    }

    if (captchaInput) {
        result.scrolledToCaptcha = scrollElementIntoView(captchaInput, 'center');
    } else {
        var captchaImage = document.querySelector('img[src*="captcha" i], img[alt*="captcha" i], canvas[id*="captcha" i]');
        if (captchaImage) {
            result.scrolledToCaptcha = scrollElementIntoView(captchaImage, 'center');
        }
    }

    result.success = result.cardNumberFound && result.pinFound;

    return JSON.stringify(result);
})();
