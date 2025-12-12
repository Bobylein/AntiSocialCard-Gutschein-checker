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

    var bodyText = (document.body.innerText || '').replace(/\u00a0/g, ' ');
    var keywordRegex = /(guthaben|saldo|verf[uü]gbar|restbetrag|betrag)/i;
    var amountPattern = /(?:€\s*|eur\s*)?([0-9]{1,3}(?:[.,][0-9]{2}))\s*(?:€|eur)?/i;

    function normalizeAmount(value) {
        return value ? value.replace(',', '.') : null;
    }

    // Check for error messages
    if (bodyText.indexOf('ungültig') !== -1 ||
        bodyText.indexOf('nicht gefunden') !== -1 ||
        bodyText.indexOf('falsch') !== -1) {
        result.error = 'invalid_card';
        Android.onBalanceResult(JSON.stringify(result));
        return;
    }

    function extractFromBalanceElements() {
        var balanceElements = document.querySelectorAll('[class*="balance" i], [class*="guthaben" i], [id*="balance" i], [id*="guthaben" i]');
        for (var i = 0; i < balanceElements.length; i++) {
            var text = (balanceElements[i].innerText || '').trim();
            if (!keywordRegex.test(text)) continue;
            var match = text.match(amountPattern);
            if (match && match[1]) {
                return normalizeAmount(match[1]);
            }
        }
        return null;
    }

    function extractFromKeywordLines(text) {
        var lines = text.split(/[\r\n]+/);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line) continue;
            if (!keywordRegex.test(line)) continue;
            var match = line.match(amountPattern);
            if (match && match[1]) {
                return normalizeAmount(match[1]);
            }
        }
        return null;
    }

    function extractFromContextPatterns(text) {
        var patterns = [
            /guthaben[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))/i,
            /saldo[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))/i,
            /verf[uü]gbar[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))/i,
            /restbetrag[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))/i
        ];

        for (var i = 0; i < patterns.length; i++) {
            var match = text.match(patterns[i]);
            if (match && match[1]) {
                return normalizeAmount(match[1]);
            }
        }
        return null;
    }

    function extractIfSingleAmount(text) {
        var matches = text.match(/([0-9]{1,3}(?:[.,][0-9]{2}))\s*(?:€|eur)/gi);
        if (matches && matches.length > 0) {
            var unique = [];
            for (var i = 0; i < matches.length; i++) {
                var amountOnly = matches[i].match(/([0-9]{1,3}(?:[.,][0-9]{2}))/);
                if (amountOnly && unique.indexOf(amountOnly[1]) === -1) {
                    unique.push(amountOnly[1]);
                }
            }
            if (unique.length === 1) {
                return normalizeAmount(unique[0]);
            }
        }
        return null;
    }

    var hasBalanceKeyword = keywordRegex.test(bodyText);

    // Prefer balance text that is explicitly labeled
    result.balance = extractFromBalanceElements() ||
                     extractFromKeywordLines(bodyText) ||
                     extractFromContextPatterns(bodyText) ||
                     (hasBalanceKeyword ? extractIfSingleAmount(bodyText) : null);

    if (result.balance) {
        result.success = true;
    }

    if (!result.success && !result.error) {
        result.error = 'balance_not_found';
    }

    Android.onBalanceResult(JSON.stringify(result));
})();
