# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AntiSocialCard-Checker is an Android application for checking gift card balances via barcode/PIN scanning and WebView automation. Currently supports REWE, ALDI Nord, and Lidl gift cards.

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
- **ONNX Runtime**: Custom CAPTCHA recognition model for automatic solving
- **WebView**: Automated form filling via JavaScript injection
- **Build**: Gradle 8.2 with Kotlin DSL, ViewBinding enabled

## Architecture

### Activity Flow
The app follows a linear activity flow:
1. **MainActivity** - Market selection (REWE/ALDI/LIDL) + settings access
2. **ScannerActivity** - Barcode scanning with CameraX + ML Kit
3. **PinEntryActivity** - PIN entry (manual or OCR)
4. **ConfirmationActivity** - Confirm scanned data before balance check
5. **BalanceCheckActivity** - WebView automation for balance checking with sealed class state management
6. **SettingsActivity** - App settings (auto-CAPTCHA toggle)

### Market Abstraction Pattern

All market implementations extend the abstract `Market` class (`markets/Market.kt`). This provides:
- JavaScript injection scripts for form filling/submission
- Balance extraction and parsing logic
- Error detection (invalid card, network issues, website changes)
- Factory method: `Market.forType(marketType)`

**TxGateMarket Base Class**: ALDI and Lidl both use the tx-gate.com balance check service and share common functionality via the `TxGateMarket` abstract base class (`markets/TxGateMarket.kt`). This eliminates code duplication by providing shared:
- Form filling logic for balancechecks.tx-gate.com
- Balance extraction patterns
- Error detection logic

Each subclass only needs to specify the `cid` parameter, parent page URL, and branding.

**Critical**: Each market provides JavaScript that must locate form fields using various selectors (name, placeholder, label text). When websites change their HTML structure, update the JavaScript in the specific market implementation or the corresponding JavaScript file in `assets/js/`.

### Data Models

**GiftCard** (`model/GiftCard.kt`):
- Holds card number, PIN, market type, timestamp
- Parcelable for passing between activities
- No local persistence (security consideration)

**BalanceResult** (`model/BalanceResult.kt`):
- Status enum: SUCCESS, INVALID_CARD, NETWORK_ERROR, PARSING_ERROR, WEBSITE_CHANGED, UNKNOWN_ERROR
- Contains balance, currency, error messages
- Helper methods for formatted display

### State Management Architecture

**BalanceCheckState** (`model/BalanceCheckState.kt`):
The app uses a sealed class to represent the balance check process state machine, replacing multiple boolean flags with a single, clear state:
- `Loading` - Initial page loading state
- `FillingForm(attemptNumber)` - Attempting to fill form fields with card data
- `SolvingCaptcha` - Automatically solving CAPTCHA using AI model
- `WaitingForCaptcha` - Form filled, waiting for user to solve/verify CAPTCHA
- `CheckingBalance` - Form submitted, waiting for balance result
- `Success(result)` - Balance check completed successfully
- `Error(result)` - An error occurred during balance checking

**StateManager** (`utils/StateManager.kt`):
Manages state transitions with validation and logging:
- Uses Kotlin `StateFlow` for reactive state observation
- Validates transitions to prevent invalid state changes (e.g., can't transition out of terminal states)
- Provides detailed logging for debugging state transitions
- Used in `BalanceCheckActivity` to coordinate UI updates

This architecture ensures the balance check process follows a predictable flow and makes the code more maintainable.

### JavaScript Asset Management

**JsAssetLoader** (`utils/JsAssetLoader.kt`):
JavaScript code has been extracted from embedded strings into separate asset files for better maintainability:
- **Location**: `app/src/main/assets/js/`
- **Files per market**: 3 files per market (form_fill, form_submit, balance_extract)
  - `aldi_*.js`, `lidl_*.js`, `rewe_*.js`
- **Template placeholders**: `{{CARD_NUMBER}}`, `{{PIN}}` replaced at runtime
- **JSDoc comments**: All JavaScript files include documentation

**Benefits**:
- Easier to debug and test JavaScript in isolation
- Better IDE support for JavaScript syntax
- Reduces code duplication across markets
- Clear separation of concerns

When updating market-specific logic, modify the corresponding JavaScript file in `assets/js/` rather than inline strings.

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

1. **Determine if tx-gate.com is used**: If the new market uses balancechecks.tx-gate.com, extend `TxGateMarket` instead of `Market` and only implement the `cid`, `parentPageUrl`, and `parentPageReferrer` properties. This eliminates ~400 lines of boilerplate code.

2. **Create market class** in `markets/` package:
   - If tx-gate based: Extend `TxGateMarket`
   - Otherwise: Extend `Market` and implement all abstract methods

3. **Create JavaScript assets** (if not using TxGateMarket):
   - Create 3 files in `app/src/main/assets/js/`:
     - `{market}_form_fill.js` - Locate and fill form fields (use `{{CARD_NUMBER}}` and `{{PIN}}` placeholders)
     - `{market}_form_submit.js` - Submit the form
     - `{market}_balance_extract.js` - Extract balance from result page
   - Add JSDoc comments to document the logic

4. **Update JsAssetLoader** (if not using TxGateMarket):
   - Add market case to `loadFormFillScript()`, `loadFormSubmitScript()`, `loadBalanceExtractionScript()`

5. **Update data models**:
   - Add market type to `MarketType` enum in `model/GiftCard.kt`
   - Add validation rules in `isValidCardNumber()` and `isValidPin()`

6. **Update factory and registry**:
   - Update `Market.forType()` factory method in `markets/Market.kt`
   - Update `Market.getAllMarkets()` list

7. **Add UI**:
   - Add market card in `activity_main.xml`

## Known Issues & Limitations

### Cross-Origin Iframes
ALDI and Lidl use tx-gate.com for balance checks, which was previously accessed via cross-origin iframe. The current implementation loads the tx-gate form directly (with appropriate referrer headers), avoiding cross-origin restrictions. The `TxGateMarket` base class sets `requiresManualEntry = false` since the form can be accessed and automated directly.

### CAPTCHA Handling
All markets may require CAPTCHA solving. The app displays the WebView to the user with pre-filled form fields and waits for manual CAPTCHA completion. The state machine transitions to `WaitingForCaptcha` state until the user completes the CAPTCHA. Do not attempt to automate CAPTCHA solving.

### Website Changes
JavaScript selectors are fragile and break when retailers update their websites. The app includes retry logic and detailed logging. When selectors break:
1. Check Chrome DevTools on the actual website
2. Update JavaScript in `app/src/main/assets/js/{market}_*.js` files (or inline in market implementation for TxGateMarket subclasses)
3. Test with multiple form field variations (name, id, placeholder, label text)
4. Review state transitions to ensure proper error handling

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

The app includes extensive logging in `BalanceCheckActivity` and `StateManager`:
- State transitions with validation (tag: `StateManager`)
- Page load events with URLs (tag: `BalanceCheckActivity`)
- Form field detection (counts, names, IDs)
- JavaScript execution results
- iframe accessibility checks

Check logcat with tags `BalanceCheckActivity` and `StateManager` when debugging form fill issues. State transitions are logged with clear before/after states, making it easy to track the balance check flow.
