# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AntiSocialCard-Checker is an Android application for checking gift card balances via barcode/PIN scanning and WebView automation. Currently supports REWE and ALDI Nord gift cards.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install on connected device/emulator
./gradlew installDebug

# Run tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

## Key Technologies

- **Language**: Kotlin 1.9.20
- **Min SDK**: 24 (Android 7.0), Target SDK: 34 (Android 14)
- **Camera**: CameraX for barcode/PIN capture
- **ML**: ML Kit for barcode scanning and text recognition (OCR)
- **WebView**: Automated form filling via JavaScript injection
- **Build**: Gradle 8.2 with Kotlin DSL, ViewBinding enabled

## Architecture

### Activity Flow
The app follows a linear activity flow:
1. **MainActivity** - Market selection (REWE/ALDI)
2. **ScannerActivity** - Barcode scanning with CameraX + ML Kit
3. **PinEntryActivity** - PIN entry (manual or OCR)
4. **ConfirmationActivity** - Confirm scanned data before balance check
5. **BalanceCheckActivity** - WebView automation for balance checking

### Market Abstraction Pattern

All market implementations extend the abstract `Market` class (`markets/Market.kt`). This provides:
- JavaScript injection scripts for form filling/submission
- Balance extraction and parsing logic
- Error detection (invalid card, network issues, website changes)
- Factory method: `Market.forType(marketType)`

**Critical**: Each market provides JavaScript that must locate form fields using various selectors (name, placeholder, label text). When websites change their HTML structure, update the JavaScript in the specific market implementation.

### Data Models

**GiftCard** (`model/GiftCard.kt`):
- Holds card number, PIN, market type, timestamp
- Parcelable for passing between activities
- No local persistence (security consideration)

**BalanceResult** (`model/BalanceResult.kt`):
- Status enum: SUCCESS, INVALID_CARD, NETWORK_ERROR, PARSING_ERROR, WEBSITE_CHANGED, UNKNOWN_ERROR
- Contains balance, currency, error messages
- Helper methods for formatted display

### WebView JavaScript Injection Pattern

`BalanceCheckActivity` implements a multi-step process:
1. Load retailer's balance check URL
2. Wait for page load (with retry logic, up to 5 attempts)
3. Inject JavaScript to fill form fields
4. Handle CAPTCHA manually (ALDI) or auto-submit (REWE)
5. Extract balance using JavaScript or HTML parsing
6. Parse and display result

**Important considerations**:
- ALDI uses cross-origin iframe (`balancechecks.tx-gate.com`), requiring manual entry
- CAPTCHA requires user interaction
- URL normalization prevents duplicate processing
- Form fill retry logic with configurable delays

## Adding New Markets

1. Create new class extending `Market` in `markets/` package
2. Implement abstract methods:
   - `getFormFillScript()` - JavaScript to locate and fill form fields
   - `getFormSubmitScript()` - JavaScript to submit form
   - `getBalanceExtractionScript()` - JavaScript to extract balance
   - `parseBalanceResponse()` - Parse HTML/text response
   - `isBalancePageLoaded()` - Detect success page
   - `isErrorPageLoaded()` - Detect error page
3. Add market type to `MarketType` enum in `model/MarketType.kt`
4. Update `Market.forType()` factory method
5. Update `Market.getAllMarkets()` list
6. Add UI card in `activity_main.xml`

## Known Issues & Limitations

### Cross-Origin Iframes
ALDI uses a cross-origin iframe from tx-gate.com for the balance check form. JavaScript cannot access cross-origin iframe content due to browser security policies. Set `requiresManualEntry = true` in market implementation to show card details for manual entry.

### CAPTCHA Handling
Both markets may require CAPTCHA solving. The app displays the WebView to the user with pre-filled form fields and waits for manual CAPTCHA completion. Do not attempt to automate CAPTCHA solving.

### Website Changes
JavaScript selectors are fragile and break when retailers update their websites. The app includes retry logic and detailed logging. When selectors break:
1. Check Chrome DevTools on the actual website
2. Update JavaScript in the market implementation
3. Test with multiple form field variations (name, id, placeholder, label text)

### OCR Accuracy
ML Kit text recognition quality depends on:
- Image quality and lighting
- PIN font style (serif/sans-serif)
- Card surface (matte vs glossy)

Provide manual entry fallback for all OCR features.

## Security Considerations

- No card data is persisted locally (stored only in memory during session)
- WebView uses HTTPS enforcement
- Card numbers displayed masked (first/last 4 digits only)
- User agent set to appear as regular mobile browser
- ProGuard rules preserve sensitive classes

## Debugging WebView Issues

The app includes extensive logging in `BalanceCheckActivity`:
- Page load events with URLs
- Form field detection (counts, names, IDs)
- JavaScript execution results
- iframe accessibility checks

Check logcat with tag `BalanceCheckActivity` when debugging form fill issues.
