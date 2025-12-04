/**
 * Lidl - Form Submit Script
 * Submits the balance check form after CAPTCHA is solved.
 */
(function() {
    var submitButton = document.querySelector('input[name="check"]') ||
                      document.querySelector('input[type="submit"]') ||
                      document.querySelector('button[type="submit"]');

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
