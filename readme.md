# AntiSocialCard-Checker

An Android application for checking gift card balances by scanning barcodes and PINs. Currently supports REWE, ALDI Nord, and Lidl gift cards. Flow: pick a market, scan the card, confirm the values, solve or auto-solve the CAPTCHA, and view the balance inside the app.

## Features

- **Enhanced Barcode Scanning**: 
  - High-resolution scanning (1920x1080) for better distance detection
  - Auto-focus and continuous focus mode for sharp images
  - Visual guides: RED shows expected barcode area, BLUE shows expected PIN area
  - Pinch-to-zoom support for manual adjustment
  - User confirmation required before proceeding
- **Smart PIN Detection**: 
  - Rotation-aware region-of-interest detection
  - Handles portrait phone + landscape card orientation (for REWE)
  - Multi-orientation OCR (tries 4 rotations for best result)
  - Market-specific PIN location detection
- **OCR PIN Capture**: Optionally scan the PIN using OCR, or enter it manually
- **Automatic CAPTCHA Solving**: Bundled ONNX model attempts to solve CAPTCHAs (enabled by default, toggle in Settings) with manual fallback
- **WebView Balance Check**: Automatically fill balance check forms and submit when possible
- **Multi-Market Support**: Modular architecture for easy addition of new retailers
- **Settings & Support**: In-app toggle for auto-CAPTCHA and quick link to the support page
- **Dark Theme UI**: Modern, dark-themed interface

## Supported Markets

| Market | Website | Card Format | Mode |
|--------|---------|-------------|------|
| REWE | kartenwelt.rewe.de | Variable length barcode, PIN | Automatic |
| ALDI Nord | helaba.com/de/aldi | 20-digit number, 4-digit PIN | Auto-fill* |
| Lidl | lidl.de | 20-digit number, 4-digit PIN | Auto-fill* |

*ALDI and Lidl form fields are auto-filled. The app will try to solve the CAPTCHA automatically when enabled, and manual submission stays available.

## Requirements

- Android 7.0 (API 24) or higher
- Camera permission for barcode/PIN scanning
- Internet permission for balance checking

## Project Structure

- `app/` - Android app module; Kotlin sources live in `app/src/main/java/com/antisocial/giftcardchecker/`
- `app/src/main/assets/js/` - Retailer-specific JavaScript for form fill, submit, and balance extraction
- `app/src/main/assets/models/` - Bundled ONNX CAPTCHA model used for auto-solving
- `app/src/test` and `app/src/androidTest` - JVM/Robolectric and instrumented test suites
- `documentation/` - Additional technical documentation

## Building

1. Open the project in Android Studio
2. Sync Gradle files
3. Build and run on a device or emulator

### Debug APK

```bash
./gradlew assembleDebug
```

The debug APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

### Install on device/emulator

```bash
./gradlew installDebug
```

Installs the latest debug build on a connected device or emulator.

### Release APK

To build a release APK (non-debug), you need to:

1. **Set up signing configuration** (if not already done):
   - Copy `app/keystore.properties.template` to `app/keystore.properties`
   - Fill in your keystore details in `app/keystore.properties`
   - Ensure your keystore file exists (default: `app/release-key.jks`)

2. **Build the release APK**:
```bash
./gradlew assembleRelease
```

The release APK will be generated at: `app/build/outputs/apk/release/app-release.apk`

**Note**: The release APK is optimized with ProGuard and resource shrinking for smaller file size.

## Testing

- JVM/Robolectric: `./gradlew testDebugUnitTest`
- Instrumented (requires device/emulator): `./gradlew connectedAndroidTest`

## Architecture

```
app/
├── src/main/java/com/antisocial/giftcardchecker/
│   ├── MainActivity.kt          # Market selection + support/settings entry
│   ├── ScannerActivity.kt       # Barcode scanning + overlays
│   ├── PinEntryActivity.kt      # PIN entry/OCR fallback
│   ├── ConfirmationActivity.kt  # Confirm scanned data before balance check
│   ├── BalanceCheckActivity.kt  # WebView automation + balance state machine
│   ├── SettingsActivity.kt      # Auto-CAPTCHA toggle
│   ├── captcha/                 # ONNX CAPTCHA solver and image extraction
│   ├── di/                      # Hilt modules (OCR, JS assets, etc.)
│   ├── markets/                 # Market implementations (Rewe, TxGate base, Aldi, Lidl)
│   ├── model/                   # Data models and enums
│   └── utils/                   # Helpers (JsAssetLoader, StateManager, etc.)
├── src/main/assets/js/          # Form fill/submit/balance scripts per market
└── src/main/assets/models/      # Bundled CAPTCHA model
```

## Libraries Used

- **CameraX**: Camera preview and image capture
- **ML Kit Barcode Scanning**: Barcode detection
- **ML Kit Text Recognition**: OCR for PIN scanning
- **ONNX Runtime**: CAPTCHA solving on-device
- **WebKit**: WebView for balance checking
- **Material Components**: UI components
- **Hilt**: Dependency injection for activities and modules

## Adding New Markets

1. Create a new class extending `Market` in the `markets/` package
2. Implement the required abstract methods:
   - `getFormFillScript()`: JavaScript to fill form fields
   - `getFormSubmitScript()`: JavaScript to submit the form
   - `getBalanceExtractionScript()`: JavaScript to extract balance
   - `parseBalanceResponse()`: Parse the response
3. Add the market type to `MarketType` enum
4. Update `Market.forType()` factory method
5. Add UI card in `activity_main.xml`

## Known Limitations

- CAPTCHA: Automatic solving can fail; manual CAPTCHA solving/submission remains necessary when challenges change
- Website Changes: If retailers update their websites, the JavaScript selectors may need updating
- Network Required: Balance checking requires an internet connection

## License

This project is for educational purposes. Use responsibly and respect the terms of service of the respective retailers.

## Version History

- **2.6**: Auto-CAPTCHA on by default, hardened REWE parsing, removed legacy banner.
- **2.5**: Scanner overlay redesign with card cutout, PIN arrow alignment, and balanced footer buttons.
- **2.4**: Rewrote overlay transforms so barcode/PIN highlights match the camera feed.
- **2.3**: Fixed preview scaling assumptions (FILL_CENTER) for accurate overlay placement.
- **2.2**: ALDI PIN search moved to upper-left for better detection.
- **2.1**: Full German localization with string resources and localized balance messages.
- **2.0**: Hilt DI, intent compatibility helpers, and hardened release/ProGuard configuration.
- **1.x**: Added Lidl support, improved barcode/PIN detection and OCR rotations, CAPTCHA touch simulation and preload fixes, initial REWE/ALDI release.
