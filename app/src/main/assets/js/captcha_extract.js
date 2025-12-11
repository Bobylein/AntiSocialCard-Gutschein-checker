/**
 * CAPTCHA Image Extraction Script
 * Extracts the CAPTCHA image URL/data from the tx-gate.com balance check form.
 * Used by the auto-CAPTCHA solver to get the image for AI recognition.
 */
(function() {
    var result = {
        found: false,
        imageUrl: null,
        imageBase64: null,
        error: null,
        debug: {
            url: window.location.href,
            allImages: [],
            captchaInputFound: false
        }
    };

    try {
        // Find the CAPTCHA input field first
        var captchaInput = document.querySelector('input[name="input"]');
        result.debug.captchaInputFound = captchaInput !== null;

        if (!captchaInput) {
            result.error = 'captcha_input_not_found';
            return JSON.stringify(result);
        }

        // Find the CAPTCHA image
        // Strategy 1: Look for img element in the same table row
        var container = captchaInput.closest('tr') || captchaInput.closest('td') || captchaInput.parentElement;
        var captchaImg = null;

        if (container) {
            // Check previous sibling cells/rows for image
            var prevRow = container.previousElementSibling;
            while (prevRow && !captchaImg) {
                captchaImg = prevRow.querySelector('img');
                prevRow = prevRow.previousElementSibling;
            }

            // Check same row
            if (!captchaImg) {
                captchaImg = container.querySelector('img');
            }
        }

        // Strategy 2: Find images near the CAPTCHA input
        if (!captchaImg) {
            var allImages = document.querySelectorAll('img');
            for (var i = 0; i < allImages.length; i++) {
                var img = allImages[i];
                var src = (img.src || '').toLowerCase();
                var width = img.naturalWidth || img.width;
                var height = img.naturalHeight || img.height;

                result.debug.allImages.push({
                    src: img.src,
                    width: width,
                    height: height
                });

                // Look for typical CAPTCHA image characteristics:
                // - Contains 'captcha', 'image.php', 'code', 'verify' in URL
                // - Or has typical CAPTCHA dimensions (100-300 wide, 30-100 tall)
                if (src.indexOf('captcha') !== -1 ||
                    src.indexOf('image.php') !== -1 ||
                    src.indexOf('code') !== -1 ||
                    src.indexOf('verify') !== -1 ||
                    (width > 80 && width < 350 && height > 25 && height < 120)) {
                    captchaImg = img;
                    break;
                }
            }
        }

        // Strategy 3: Look for images by position relative to CAPTCHA input
        if (!captchaImg) {
            var inputRect = captchaInput.getBoundingClientRect();
            var allImages = document.querySelectorAll('img');
            var closestDist = Infinity;

            for (var i = 0; i < allImages.length; i++) {
                var img = allImages[i];
                var imgRect = img.getBoundingClientRect();

                // Check if image is above or to the left of the input (typical CAPTCHA layout)
                if (imgRect.bottom <= inputRect.top + 100 || imgRect.right <= inputRect.left + 50) {
                    var dist = Math.abs(imgRect.bottom - inputRect.top) + Math.abs(imgRect.left - inputRect.left);
                    if (dist < closestDist) {
                        closestDist = dist;
                        captchaImg = img;
                    }
                }
            }
        }

        if (captchaImg) {
            result.found = true;
            result.imageUrl = captchaImg.src;

            // Try to extract base64 data using canvas (works for same-origin images)
            try {
                // Wait for image to be fully loaded
                if (captchaImg.complete && captchaImg.naturalWidth > 0) {
                    var canvas = document.createElement('canvas');
                    canvas.width = captchaImg.naturalWidth || captchaImg.width;
                    canvas.height = captchaImg.naturalHeight || captchaImg.height;
                    var ctx = canvas.getContext('2d');
                    ctx.drawImage(captchaImg, 0, 0);
                    result.imageBase64 = canvas.toDataURL('image/png');
                }
            } catch (canvasError) {
                // Cross-origin image, can't convert to base64
                // Will fall back to URL download
                result.error = 'canvas_cross_origin';
            }
        } else {
            result.error = 'captcha_image_not_found';
        }

    } catch (e) {
        result.error = 'script_error: ' + e.message;
    }

    return JSON.stringify(result);
})();
