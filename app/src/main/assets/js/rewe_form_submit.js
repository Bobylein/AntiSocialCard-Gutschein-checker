/**
 * REWE - Form Submit Script
 * Submits the balance check form.
 */
(function() {
    var submitButton = document.querySelector('button[type="submit"]');
    if (!submitButton) {
        submitButton = document.querySelector('button:contains("Guthaben")');
    }
    if (!submitButton) {
        var buttons = document.querySelectorAll('button');
        for (var i = 0; i < buttons.length; i++) {
            if (buttons[i].textContent.indexOf('Guthaben') !== -1 ||
                buttons[i].textContent.indexOf('prüfen') !== -1) {
                submitButton = buttons[i];
                break;
            }
        }
    }

    if (submitButton) {
        submitButton.click();
        return JSON.stringify({ success: true });
    } else {
        var form = document.querySelector('form');
        if (form) {
            form.submit();
            return JSON.stringify({ success: true, method: 'form' });
        }
    }

    return JSON.stringify({ success: false, error: 'Submit button not found' });
})();
