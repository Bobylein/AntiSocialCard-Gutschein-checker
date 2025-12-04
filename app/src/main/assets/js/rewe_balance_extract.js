/**
 * REWE - Balance Extraction Script
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

    // Check for error messages
    if (bodyText.indexOf('ungültig') !== -1 ||
        bodyText.indexOf('nicht gefunden') !== -1 ||
        bodyText.indexOf('falsch') !== -1) {
        result.error = 'invalid_card';
        Android.onBalanceResult(JSON.stringify(result));
        return;
    }

    // Try to find balance patterns
    var balancePatterns = [
        /Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
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
    var balanceElements = document.querySelectorAll('[class*="balance"], [class*="guthaben"], [id*="balance"], [id*="guthaben"]');
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
