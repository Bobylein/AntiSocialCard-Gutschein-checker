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

- **2.6**:
  - Enabled ONNX-based auto-CAPTCHA solving by default with in-app toggle and support link on the home screen
  - Hardened REWE balance parsing to better handle response variations
  - Removed the old CAPTCHA instruction banner for a cleaner scanning UI
- **2.5**:
  - **Enhanced Scanner UI with Card Overlay**:
    - Added vertical card cutout overlay showing where to position the gift card
    - Dynamic overlay sizing that fits within display bounds while maintaining 5:8 aspect ratio
    - "PIN hier oben" instruction text with downward-pointing arrow
    - Arrow positioned independently and aligned with expected PIN location (left for REWE, right for ALDI/Lidl)
    - Market-specific horizontal alignment for PIN instruction and arrow
    - Footer with fixed height (25% of screen) that doesn't change with content
    - Side-by-side buttons: "Manuelle Eingabe" (left, smaller) and "Daten überprüfen" (right, larger)
    - Both buttons now have the same height for consistent appearance
    - Footer extends to screen edges with no side margins
    - Automatic font size adjustment if content doesn't fit
- **2.4**:
  - **Fixed Live Visualization of PIN Search Regions**:
    - Completely rewrote coordinate transformation system for overlay positioning
    - Replaced CameraX CoordinateTransform approach with direct manual transformation
    - ML Kit coordinates are display-oriented, so direct scaling and offset calculation is used
    - Properly handles PreviewView ScaleType (FILL_CENTER/FIT_CENTER) for accurate positioning
    - Overlays now correctly show where barcode and PIN detection is happening
    - Fixed 90° rotation issues and overlay visibility problems
    - Improved logging for debugging coordinate transformations
- **2.3**:
  - **Fixed Coordinate Transformation for Preview Overlays**:
    - Corrected coordinate transformation logic to use FILL_CENTER scaling (PreviewView default)
    - Previous implementation incorrectly assumed FIT_CENTER scaling with letterboxing
    - FILL_CENTER crops the image to fill the preview, requiring different offset calculations
    - Improved debug logging to show aspect ratios for better troubleshooting
    - More accurate positioning of barcode and PIN highlight overlays
- **2.2**:
  - **ALDI PIN Detection Position Fix**:
    - Changed ALDI PIN detection region from upper-right to upper-left corner
    - PIN search region now positioned to the left of barcode (mirrored from Lidl's upper-right positioning)
    - Improved PIN detection accuracy for ALDI cards by scanning correct corner location
- **2.1**:
  - **German Localization**:
    - Translated all user-facing text to German
    - Updated all string resources in `strings.xml` to German
    - Replaced hardcoded strings in activities with string resources
    - Modified `BalanceResult.getDisplayMessage()` to accept Context for localization
    - All UI text, error messages, and instructions now in German
    - Improved maintainability by using string resources throughout the app
- **2.0**:
  - **Dependency Injection with Hilt**:
    - Integrated Hilt dependency injection framework for better code organization
    - Created `GiftCardCheckerApplication` with `@HiltAndroidApp` annotation
    - Added `AppModule` for application-level dependencies (JsAssetLoader)
    - Added `OcrModule` for ML Kit dependencies (BarcodeScanner, TextRecognizer)
    - Improved testability and maintainability through dependency injection
  - **Intent API Compatibility**:
    - Added `IntentExtensions` utility for handling deprecated Intent APIs
    - Provides version-compatible methods for `getParcelableExtra`, `getSerializableExtra`, etc.
    - Ensures compatibility across all Android versions (API 24+)
  - **Build Configuration Improvements**:
    - Added debug and release build variants with different configurations
    - Enabled ProGuard for release builds with resource shrinking
    - Added build config fields for logging and debug overlay control
    - Debug builds use `.debug` application ID suffix
  - **ProGuard Rules**:
    - Added comprehensive ProGuard rules for Hilt dependency injection
    - Preserved ML Kit, WebView, and data model classes
    - Optimized release build size and obfuscation
- **1.9**:
  - **Fixed ALDI PIN Detection Box Positioning**:
    - Enhanced coordinate transformation in `updateHighlights()` function
    - Improved logging for debugging overlay positioning issues
    - Fixed PIN detection box appearing below barcode when it should be above
    - Better handling of coordinate transformation from ML Kit space to preview view space
    - Added comprehensive debug logging to track coordinate transformations
- **1.8**:
  - **Simplified Main Screen UI**:
    - Redesigned main screen to show three large logos (REWE, ALDI, Lidl) without additional explanations
    - Each logo uses brand colors: REWE (red), ALDI (blue), Lidl (yellow)
    - Clean, minimalist interface with evenly distributed logo cards
    - Removed header, subtitle, and instructions section for cleaner design
- **1.7**:
  - **REWE Performance Optimization**:
    - Image blocking for REWE pages to reduce loading time
    - Only decorative images are blocked (logos, banners, etc.)
    - Captcha images and form-related images are always allowed
    - Increased wait times for REWE form detection (3 seconds)
    - More conservative image blocking to prevent interfering with form rendering
- **1.6**: 
  - **PIN Detection Region Improvements**:
    - REWE TYPE_2: Added 2x PIN area gap between barcode and PIN search region for Aztec barcodes
    - ALDI: Corner-focused PIN detection with no barcode overlap, similar to Lidl
    - Improved PIN detection accuracy by better positioning search regions
  - **Fixed REWE PIN detection**: Rotation-aware PIN region calculation
  - Works correctly when phone is portrait but card is held landscape
  - PIN search region now relative to barcode position, not fixed image quarters
  - Improved coordinate transformation for cropping rotated images
  - Better fallback strategy with rotation-aware wider search region
  - Multi-orientation OCR tries 4 rotations (270°, 0°, 90°, 180°)
- **1.5**: 
  - Fixed Lidl card number extraction: now uses last 20 digits (same as ALDI)
  - Added REWE TYPE_2 support: detects Aztec barcodes and extracts first 13 digits
  - Updated card number extraction logic for all markets
- **1.4**: 
  - Added Lidl gift card support
  - Fixed long error messages in WebView (truncated to prevent UI issues)
  - Optimized debug data collection (filters out navigation/cookie checkboxes)
  - Improved JSON parsing with safety checks for large responses
  - Lidl uses same form provider as ALDI (tx-gate.com) with direct iframe loading
- **1.3**: 
  - Enhanced barcode scanning distance (2-3x improvement)
  - High-resolution image analysis (1920x1080) for better detection
  - Continuous autofocus and optimized camera settings
  - Visual highlighting of detected barcodes (green) and PINs (blue)
  - Region-of-interest PIN detection in upper-right corner of barcode
  - Pinch-to-zoom support for manual distance adjustment
  - User confirmation required before navigation (no auto-navigate)
  - Improved coordinate conversion for accurate highlight positioning
- **1.2**: 
  - Implemented native Android touch simulation for CAPTCHA field focus
  - Uses MotionEvent to simulate touch at CAPTCHA coordinates for reliable keyboard opening
  - Added JavaScript interface for coordinate-based touch simulation
  - Improved keyboard opening reliability on mobile devices
- **1.1**: 
  - Added ALDI page preloading to reduce blank page issues
  - Improved CAPTCHA field focus with retry mechanism and keyboard opening
  - Enhanced form auto-fill reliability with better field detection
- **1.0**: Initial release with REWE and ALDI Nord support
