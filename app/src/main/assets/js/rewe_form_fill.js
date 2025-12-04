/**
 * REWE - Form Fill Script
 * Fills the Kartennummer (card number) and PIN fields on the REWE balance check form.
 *
 * Placeholders:
 * - {{CARD_NUMBER}}: Card number
 * - {{PIN}}: PIN
 */
(function() {
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
    }

    if (pinInput) {
        pinInput.value = '{{PIN}}';
        pinInput.dispatchEvent(new Event('input', { bubbles: true }));
        pinInput.dispatchEvent(new Event('change', { bubbles: true }));
    }

    return JSON.stringify({
        success: cardNumberInput !== null && pinInput !== null,
        cardNumberFound: cardNumberInput !== null,
        pinFound: pinInput !== null
    });
})();
