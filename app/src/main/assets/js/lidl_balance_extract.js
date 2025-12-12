/**
 * Lidl - Balance Extraction Script
 * Extracts the balance from the result page.
 * Calls Android.onBalanceResult(jsonString) with the result.
 */
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

        // Check for CAPTCHA error FIRST (before general error check)
        // CAPTCHA error messages can be:
        // - "Lösung ist falsch" (solution is wrong)
        // - "Fehler beim Lösen des CAPTCHA" (error solving the CAPTCHA)
        var hasLoesung = bodyText.indexOf('Lösung') !== -1 || bodyText.indexOf('lösung') !== -1;
        var hasFalsch = bodyText.indexOf('falsch') !== -1;
        var hasFehler = bodyText.indexOf('Fehler') !== -1 || bodyText.indexOf('fehler') !== -1;
        var hasCaptcha = bodyText.indexOf('CAPTCHA') !== -1 || bodyText.indexOf('captcha') !== -1;

        if ((hasLoesung && hasFalsch) || (hasCaptcha && hasFalsch) ||
            (hasFehler && hasCaptcha) || (hasFehler && hasLoesung)) {
            result.error = 'captcha_error';
            Android.onBalanceResult(JSON.stringify(result));
            return;
        }

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
