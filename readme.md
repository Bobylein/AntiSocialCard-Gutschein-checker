# AntiSocialCard-Checker

An Android application for checking gift card balances by scanning barcodes and PINs. Currently supports REWE and ALDI Nord gift cards.

## Features

- **Barcode Scanning**: Use ML Kit to scan gift card barcodes with the camera
- **OCR PIN Capture**: Optionally scan the PIN using OCR, or enter it manually
- **WebView Balance Check**: Automatically fill and submit balance check forms
- **Multi-Market Support**: Modular architecture for easy addition of new retailers
- **Dark Theme UI**: Modern, dark-themed interface

## Supported Markets

| Market | Website | Card Format | Mode |
|--------|---------|-------------|------|
| REWE | kartenwelt.rewe.de | Variable length barcode, PIN | Automatic |
| ALDI Nord | helaba.com/de/aldi | 20-digit number, 4-digit PIN | Auto-fill* |

*ALDI form fields are auto-filled. User must solve CAPTCHA and submit manually.

## Requirements

- Android 7.0 (API 24) or higher
- Camera permission for barcode/PIN scanning
- Internet permission for balance checking

## Building

1. Open the project in Android Studio
2. Sync Gradle files
3. Build and run on a device or emulator

```bash
./gradlew assembleDebug
```

## Architecture

```
app/
├── src/main/java/com/antisocial/giftcardchecker/
│   ├── MainActivity.kt          # Market selection
│   ├── ScannerActivity.kt       # Barcode scanning
│   ├── PinEntryActivity.kt      # PIN entry/OCR
│   ├── BalanceCheckActivity.kt  # WebView balance check
│   ├── model/
│   │   ├── GiftCard.kt          # Card data model
│   │   ├── BalanceResult.kt     # Result data model
│   │   └── MarketType.kt        # Market enum
│   └── markets/
│       ├── Market.kt            # Abstract market class
│       ├── ReweMarket.kt        # REWE implementation
│       └── AldiMarket.kt        # ALDI implementation
```

## Libraries Used

- **CameraX**: Camera preview and image capture
- **ML Kit Barcode Scanning**: Barcode detection
- **ML Kit Text Recognition**: OCR for PIN scanning
- **WebKit**: WebView for balance checking
- **Material Components**: UI components

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

- CAPTCHA: ALDI requires manual CAPTCHA solving and form submission
- Website Changes: If retailers update their websites, the JavaScript selectors may need updating
- Network Required: Balance checking requires an internet connection

## License

This project is for educational purposes. Use responsibly and respect the terms of service of the respective retailers.

## Version History

- **1.1**: 
  - Added ALDI page preloading to reduce blank page issues
  - Improved CAPTCHA field focus with retry mechanism and keyboard opening
  - Enhanced form auto-fill reliability with better field detection
- **1.0**: Initial release with REWE and ALDI Nord support

