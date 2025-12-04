/**
 * ALDI Nord - Form Submit Script
 * Submits the balance check form after CAPTCHA is solved.
 */
(function() {
    var submitButton = document.querySelector('input[name="check"]');

    if (!submitButton) {
        submitButton = document.querySelector('input.check');
    }

    if (!submitButton) {
        var buttons = document.querySelectorAll('input[type="submit"], button[type="submit"]');
        for (var i = 0; i < buttons.length; i++) {
            var buttonValue = (buttons[i].value || buttons[i].textContent || '').toLowerCase();
            if (buttonValue.indexOf('guthabenabfrage') !== -1 ||
                buttonValue.indexOf('guthaben') !== -1 ||
                buttonValue.indexOf('abfragen') !== -1 ||
                buttonValue.indexOf('prüfen') !== -1 ||
                buttonValue.indexOf('check') !== -1) {
                submitButton = buttons[i];
                break;
            }
        }
    }

    if (!submitButton) {
        submitButton = document.querySelector('input[type="submit"]') ||
                       document.querySelector('button[type="submit"]');
    }

    if (submitButton) {
        submitButton.click();
        return JSON.stringify({ success: true, method: 'button_click' });
    } else {
        var form = document.querySelector('form');
        if (form) {
            form.submit();
            return JSON.stringify({ success: true, method: 'form_submit' });
        }
    }

    return JSON.stringify({ success: false, error: 'Submit button not found' });
})();
