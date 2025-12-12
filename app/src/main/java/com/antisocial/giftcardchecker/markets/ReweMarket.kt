package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.BalanceResult
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType

/**
 * Market implementation for REWE gift card balance checking.
 * Uses the kartenwelt.rewe.de website for balance inquiries.
 *
 * Unlike ALDI/Lidl, REWE uses its own balance check system (not tx-gate.com).
 */
class ReweMarket : Market() {

    override val marketType: MarketType = MarketType.REWE

    override val displayName: String = "REWE"

    override val balanceCheckUrl: String = "https://kartenwelt.rewe.de/rewe-geschenkkarte.html"

    override val brandColor: Int = Color.parseColor("#CC071E") // REWE Red

    override fun getFormFillScript(card: GiftCard): String {
        return """
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

                var cardNumberInput = document.querySelector('input[name="cardNumber"]') ||
                                    document.querySelector('input[placeholder*="Kartennummer"]');

                if (!cardNumberInput) {
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.indexOf('Kartennummer') !== -1) {
                            var forAttr = labels[i].getAttribute('for');
                            if (forAttr) {
                                cardNumberInput = document.getElementById(forAttr);
                            }
                            if (!cardNumberInput && labels[i].parentElement) {
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

                var pinInput = document.querySelector('input[name="pin"]') ||
                              document.querySelector('input[placeholder*="PIN"]');

                if (!pinInput) {
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.trim() === 'PIN') {
                            var pinFor = labels[i].getAttribute('for');
                            if (pinFor) {
                                pinInput = document.getElementById(pinFor);
                            }
                            if (!pinInput && labels[i].parentElement) {
                                pinInput = labels[i].parentElement.querySelector('input');
                            }
                            break;
                        }
                    }
                }

                if (!pinInput) {
                    var pinCandidates = document.querySelectorAll('form input[type="text"], form input[type="password"]');
                    if (pinCandidates.length > 1) {
                        pinInput = pinCandidates[1];
                    }
                }

                if (cardNumberInput) {
                    cardNumberInput.value = '${card.cardNumber}';
                    cardNumberInput.dispatchEvent(new Event('input', { bubbles: true }));
                    cardNumberInput.dispatchEvent(new Event('change', { bubbles: true }));
                    result.cardNumberFound = true;
                }

                if (pinInput) {
                    pinInput.value = '${card.pin}';
                    pinInput.dispatchEvent(new Event('input', { bubbles: true }));
                    pinInput.dispatchEvent(new Event('change', { bubbles: true }));
                    result.pinFound = true;
                }

                var captchaInput = findCaptchaInput(document);
                if (captchaInput) {
                    result.captchaFound = true;
                }

                // Make sure the user sees the form and captcha area without manual scrolling
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
        """.trimIndent()
    }

    override fun getFormSubmitScript(): String {
        return """
            (function() {
                var submitButton = document.querySelector('button[type="submit"]');

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
        """.trimIndent()
    }

    override fun getBalanceExtractionScript(): String {
        return """
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
        """.trimIndent()
    }

    override fun parseBalanceResponse(response: String): BalanceResult {
        try {
            val lowerResponse = response.lowercase()

            // Check for error indicators
            if (lowerResponse.contains("ungültig") ||
                lowerResponse.contains("nicht gefunden") ||
                lowerResponse.contains("falsch")) {
                return BalanceResult.invalidCard("Kartennummer oder PIN ungültig")
            }

            val normalizedResponse = response.replace("\u00A0", " ")
            val containsBalanceKeyword = lowerResponse.contains("guthaben") ||
                lowerResponse.contains("saldo") ||
                lowerResponse.contains("restbetrag") ||
                lowerResponse.contains("verfügbar")
            val keywordRegex = Regex("(guthaben|saldo|verf[uü]gbar|restbetrag|betrag)", RegexOption.IGNORE_CASE)
            val amountWithCurrency = Regex("(?:€\\s*|eur\\s*)?([0-9]{1,3}(?:[.,][0-9]{2}))\\s*(?:€|eur)?", RegexOption.IGNORE_CASE)
            val contextPatterns = listOf(
                Regex("guthaben[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))", RegexOption.IGNORE_CASE),
                Regex("saldo[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))", RegexOption.IGNORE_CASE),
                Regex("verf[uü]gbar[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))", RegexOption.IGNORE_CASE),
                Regex("restbetrag[^0-9]{0,60}([0-9]{1,3}(?:[.,][0-9]{2}))", RegexOption.IGNORE_CASE)
            )

            normalizedResponse.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && keywordRegex.containsMatchIn(it) }
                .forEach { line ->
                    val match = amountWithCurrency.find(line)
                    if (match != null) {
                        val balance = match.groupValues[1].replace(",", ".")
                        return BalanceResult.success(balance, "EUR")
                    }
                }

            for (pattern in contextPatterns) {
                val match = pattern.find(normalizedResponse)
                if (match != null) {
                    val balance = match.groupValues[1].replace(",", ".")
                    return BalanceResult.success(balance, "EUR")
                }
            }

            val amounts = amountWithCurrency.findAll(normalizedResponse)
                .map { it.groupValues[1].replace(",", ".") }
                .toList()

            val distinctAmounts = amounts.distinct()
            if (containsBalanceKeyword && distinctAmounts.size == 1 && distinctAmounts.isNotEmpty()) {
                return BalanceResult.success(distinctAmounts.first(), "EUR")
            }

            return BalanceResult.parsingError("Guthaben konnte nicht gelesen werden", response)
        } catch (e: Exception) {
            return BalanceResult.error(e.message)
        }
    }

    override fun isBalancePageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("guthaben") &&
               (lowerHtml.contains("€") || lowerHtml.contains("eur"))
    }

    override fun isErrorPageLoaded(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return lowerHtml.contains("ungültig") ||
               lowerHtml.contains("nicht gefunden") ||
               lowerHtml.contains("fehler") ||
               lowerHtml.contains("falsch")
    }
}
