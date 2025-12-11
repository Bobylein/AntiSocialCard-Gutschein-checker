/**
 * ALDI Nord - Balance Extraction Script
 * Extracts the balance from the result page.
 * Calls Android.onBalanceResult(jsonString) with the result.
 */
(function() {
    var result = {
        success: false,
        balance: null,
        error: null,
        html: document.body.innerHTML
    };

    var bodyText = document.body.innerText;

    // Check for CAPTCHA error FIRST (before general error check)
    // CAPTCHA errors contain "Lösung" (solution) and "falsch" (wrong)
    if ((bodyText.indexOf('Lösung') !== -1 || bodyText.indexOf('lösung') !== -1) &&
        bodyText.indexOf('falsch') !== -1) {
        result.error = 'captcha_error';
        Android.onBalanceResult(JSON.stringify(result));
        return;
    }

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
