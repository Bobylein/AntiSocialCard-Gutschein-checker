# Technical Documentation - AntiSocialCard-Checker

## Overview

AntiSocialCard-Checker is an Android application built with Kotlin that allows users to check gift card balances by scanning barcodes and entering PINs. The app uses WebView to interact with retailer balance check websites.

## Technology Stack

- **Language**: Kotlin 1.9.20
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Build System**: Gradle 8.2 with Kotlin DSL

## Dependencies

### Core Android
- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.11.0`
- `androidx.constraintlayout:constraintlayout:2.1.4`

### Camera & ML Kit
- `androidx.camera:camera-camera2:1.3.1`
- `androidx.camera:camera-lifecycle:1.3.1`
- `androidx.camera:camera-view:1.3.1`
- `com.google.mlkit:barcode-scanning:17.2.0`
- `com.google.mlkit:text-recognition:16.0.0`

### Dependency Injection
- `com.google.dagger:hilt-android:2.48`
- `com.google.dagger:hilt-compiler:2.48` (KSP)

### Async & Lifecycle
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.7.0`
- `androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0`

## Architecture

### Dependency Injection (Hilt)

The app uses Hilt for dependency injection, providing better testability and code organization:

1. **Application Class**: `GiftCardCheckerApplication` annotated with `@HiltAndroidApp`
2. **Modules**:
   - `AppModule`: Provides application-level singletons (JsAssetLoader)
   - `OcrModule`: Provides ML Kit instances (BarcodeScanner, TextRecognizer)
3. **Benefits**:
   - Centralized dependency management
   - Easier testing with mock dependencies
   - Better separation of concerns

### Activity Flow

```
MainActivity (Market Selection)
    ↓
ScannerActivity (Barcode Scanning)
    ↓
PinEntryActivity (PIN Entry/OCR)
    ↓
BalanceCheckActivity (WebView Balance Check)
```

### Data Models

#### GiftCard
```kotlin
data class GiftCard(
    val cardNumber: String,
    val pin: String,
    val marketType: MarketType,
    val timestamp: Long
)
```

#### BalanceResult
```kotlin
data class BalanceResult(
    val status: BalanceStatus,
    val balance: String?,
    val currency: String,
    val errorMessage: String?,
    val rawResponse: String?,
    val timestamp: Long
)
```

### Market Abstraction

The `Market` abstract class provides a template for implementing balance checks for different retailers:

```kotlin
abstract class Market {
    abstract val marketType: MarketType
    abstract val displayName: String
    abstract val balanceCheckUrl: String
    abstract val brandColor: Int
    
    abstract fun getFormFillScript(card: GiftCard): String
    abstract fun getFormSubmitScript(): String
    abstract fun getBalanceExtractionScript(): String
    abstract fun parseBalanceResponse(response: String): BalanceResult
    abstract fun isBalancePageLoaded(html: String): Boolean
    abstract fun isErrorPageLoaded(html: String): Boolean
}
```

## Implementation Details

### Barcode Scanning (ScannerActivity)

Uses CameraX with ML Kit Barcode Scanner with enhanced distance scanning:

1. **Camera Configuration**:
   - High-resolution ImageAnalysis (1920x1080 target)
   - YUV_420_888 output format for better quality
   - Continuous autofocus via Camera2Interop
   - Optimized exposure settings

2. **ML Kit Configuration**:
   - BarcodeScannerOptions with all formats enabled
   - Optimized for distance scanning

3. **Processing Flow**:
   - Initialize camera provider
   - Bind Preview and ImageAnalysis use cases
   - Process each frame with ML Kit BarcodeScanner
   - Extract bounding boxes for visual highlighting
   - Filter valid barcodes (4+ digits)
   - Display detected barcode with visual highlight

4. **Visual Feedback**:
   - RED highlight overlay shows expected barcode search region
   - BLUE highlight overlay shows expected PIN search region
   - Coordinate conversion from ML Kit coordinate system to preview view coordinates
   - Accounts for display rotation, aspect ratio differences, and letterboxing/pillarboxing
   - Highlights positioned relative to preview view using accurate transformation
   - Enhanced debug logging tracks coordinate transformations for troubleshooting
   - Helps users position the gift card correctly

5. **User Interaction**:
   - Pinch-to-zoom gesture support
   - User must confirm detection before navigation
   - Manual entry option always available

### OCR PIN Capture

#### ScannerActivity (Automatic PIN Detection)
Uses rotation-aware region-of-interest (ROI) detection for improved accuracy:

1. **Barcode Detection**: First detects barcode and extracts bounding box
2. **Rotation Detection**: Determines phone orientation from `imageProxy.imageInfo.rotationDegrees`
3. **PIN Region Calculation** (rotation-aware):
   - For **REWE cards**: PIN is physically to the LEFT of barcode on the card
     - **TYPE_1**: Standard positioning adjacent to barcode
     - **TYPE_2** (Aztec): Gap of 2x PIN area height between barcode and PIN search region
   - In **portrait mode** (90°/270° rotation): "left of barcode" maps to ABOVE barcode in ML Kit coords
   - In **landscape mode** (0°/180° rotation): "left of barcode" maps to left of barcode in ML Kit coords
   - For **LIDL cards**: PIN is in upper-right corner, calculated relative to barcode position
   - For **ALDI cards**: PIN is in upper-right corner, positioned towards corners with no barcode overlap
4. **Coordinate Transformation**: 
   - ML Kit provides coordinates in display-corrected space (rotation applied)
   - Native bitmap from camera is in sensor orientation (not rotated)
   - `transformCoordinatesForRotation()` converts ML Kit coords to bitmap coords for cropping
5. **Image Cropping**: Crops native bitmap to transformed PIN region
6. **Multi-Orientation OCR**: Tries 4 rotations (270°, 0°, 90°, 180°) on cropped region
7. **Fallback Strategy**: If initial region fails, tries wider rotation-aware region
8. **Visual Highlight**: Blue overlay shows detected PIN region

#### PinEntryActivity (Manual PIN Capture)
Uses CameraX with ML Kit Text Recognition:

1. Capture image on button press
2. Process with TextRecognizer
3. Extract potential PIN (4-8 digit sequences)
4. Show detected text for user confirmation

### WebView Balance Check (BalanceCheckActivity)

1. Load retailer's balance check URL (with headers for ALDI)
2. Wait for page load
3. Inject JavaScript to fill form fields automatically
4. Focus CAPTCHA field to open keyboard (for ALDI)
5. User manually solves CAPTCHA and submits form (for ALDI)
6. Monitor page changes to detect form submission
7. Extract balance using JavaScript or HTML parsing
8. Display result to user

**Page Preloading (MainActivity):**
- When user clicks ALDI card, hidden WebView starts loading ALDI page in background
- Page loads while user goes through scanner flow
- When BalanceCheckActivity loads same URL, it benefits from cache/preload
- Reduces blank page issues and improves perceived performance

**ALDI-specific implementation:**
- Loads iframe URL directly with referrer header to prevent blank page
- Uses enhanced field selectors with table structure fallback
- Detects form submission via iframe navigation monitoring
- **Page preloading**: Hidden WebView in MainActivity preloads ALDI page when user clicks ALDI card
- **CAPTCHA focus**: Native Android touch simulation for reliable keyboard opening
  - JavaScript calculates CAPTCHA field coordinates using getBoundingClientRect()
  - Calls Android.simulateTouch(x, y) via JavaScript interface
  - Android simulates native MotionEvent touch at calculated coordinates
  - More reliable than JavaScript focus() for opening keyboard on WebView
  - Includes fallback JavaScript click method if touch simulation fails
  - Handles both direct form and iframe scenarios with proper coordinate calculation

### JavaScript Injection

Each market implementation provides JavaScript for:

1. **Form Fill**: Locates input fields by various selectors (name, placeholder, label text)
2. **Form Submit**: Finds and clicks submit button
3. **Balance Extraction**: Parses page text for balance patterns

Example pattern matching:
```javascript
var balancePatterns = [
    /Guthaben[:\s]*([0-9]+[,\.][0-9]{2})\s*€/i,
    /([0-9]+[,\.][0-9]{2})\s*€/,
    /€\s*([0-9]+[,\.][0-9]{2})/
];
```

## Market Implementations

### REWE (ReweMarket)

- **URL**: `https://kartenwelt.rewe.de/rewe-geschenkkarte.html`
- **Fields**: Kartennummer (card number), PIN
- **Brand Color**: #CC071E (Red)
- **Card Types**:
  - **TYPE_1**: Standard barcode - uses last 13 digits of scanned code
  - **TYPE_2**: Aztec barcode - uses first 13 digits of scanned code
- **Performance Optimization**:
  - Image blocking: All images are blocked except captcha images to reduce page load time
  - Captcha images are identified by URL patterns (captcha, security, verify, challenge, recaptcha, hcaptcha, turnstile)
  - Blocked images return empty responses to prevent unnecessary network requests

### ALDI Nord (AldiMarket)

- **URL**: `https://balancechecks.tx-gate.com/balance.php?cid=59` (direct iframe URL)
- **Fields**: Gutschein (20-digit), PIN (4-digit), CAPTCHA
- **Brand Color**: #00529B (Blue)
- **PIN Detection**: 
  - PIN search area positioned towards upper-right corner of card
  - Uses extended width/height (2x region size) similar to Lidl
  - No overlap with barcode area - starts from barcode right edge, ends at barcode top
  - Corner-focused detection for improved accuracy
- **Implementation**: 
  - Navigates directly to iframe URL with referrer header to prevent blank page
  - Auto-fills card number and PIN fields using enhanced selectors
  - User manually solves CAPTCHA and submits form
  - Form submission detection extracts balance from result page

## Error Handling

### Error Types

- `BalanceStatus.SUCCESS`: Balance retrieved successfully
- `BalanceStatus.INVALID_CARD`: Card number/PIN invalid
- `BalanceStatus.NETWORK_ERROR`: Network connectivity issues
- `BalanceStatus.PARSING_ERROR`: Failed to extract balance
- `BalanceStatus.WEBSITE_CHANGED`: Website structure changed
- `BalanceStatus.UNKNOWN_ERROR`: Unexpected error

### Retry Logic

- Form fill: Up to 5 attempts with 1-second delay
- Balance extraction: 5-second timeout before error

## Utility Classes

### IntentExtensions

Provides version-compatible methods for deprecated Intent APIs:

- `getParcelableExtraCompat<T>()`: Handles `getParcelableExtra()` deprecation
- `getSerializableExtraCompat<T>()`: Handles `getSerializableExtra()` deprecation
- `getParcelableArrayListExtraCompat<T>()`: Handles ArrayList variants
- `getParcelableArrayExtraCompat<T>()`: Handles array variants

These extensions automatically use the correct API based on Android version (API 33+ uses typed methods, older versions use deprecated methods with proper casting).

## Security Considerations

- No card data stored locally
- HTTPS enforced for WebView
- Card numbers displayed masked (first/last 4 digits only)
- ProGuard rules to keep sensitive classes
- Release builds use ProGuard obfuscation and resource shrinking

## Testing

### Manual Testing Checklist

1. Camera permission handling
2. Barcode scanning with various formats
3. OCR accuracy for different PIN fonts
4. WebView form filling
5. Balance extraction accuracy
6. Error state handling
7. Network error recovery

## Recent Improvements

### v1.3 - Enhanced Barcode Scanning and Visual Feedback

**Improved Scanning Distance:**
- High-resolution ImageAnalysis (1920x1080) for better detail capture
- YUV_420_888 output format for superior image quality
- Continuous autofocus via Camera2Interop for sharp images at all distances
- Optimized camera exposure settings

**Visual Highlighting:**
- Green overlay highlights detected barcode region
- Blue overlay highlights detected PIN region
- Accurate coordinate conversion from image space to screen space
- Highlights positioned dynamically based on detection bounding boxes

**PIN Detection Improvements:**
- Region-of-interest (ROI) detection focusing on upper-right corner
- Image cropping to PIN region before OCR processing
- Improved accuracy by reducing noise from other text
- Fallback to full-image OCR if ROI detection fails

**User Experience:**
- Pinch-to-zoom gesture support for manual distance adjustment
- User confirmation required before navigation (no auto-navigate)
- Visual feedback shows exactly what was detected
- Better control over scanning process

**Technical Details:**
- Stores actual image dimensions from ImageProxy for accurate coordinate conversion
- Calculates scale factors based on aspect ratio differences
- Handles letterboxing and pillarboxing correctly
- Proper elevation and z-ordering for highlight overlays

### v1.5 - Rotation-Aware PIN Detection for REWE

**Problem Solved:**
REWE gift cards require landscape card orientation to scan the barcode, but the app runs in portrait mode. The PIN field is physically to the LEFT of the barcode on the card, but due to rotation transformations, this appears in different positions in the ML Kit coordinate system.

**Solution:**
1. **Rotation Detection**: Check `rotationDegrees` from `imageProxy.imageInfo` to determine phone orientation
2. **Rotation-Aware Region Calculation**:
   - Portrait mode (90°/270°): Search ABOVE barcode in ML Kit coords (maps to left side of physical card)
   - Landscape mode (0°/180°): Search LEFT of barcode in ML Kit coords
3. **Fixed Coordinate Transformation**: `transformCoordinatesForRotation()` now correctly handles:
   - 90° clockwise: ML Kit Y → Bitmap X, ML Kit X → Bitmap Y (inverted)
   - 270° clockwise: ML Kit Y → Bitmap X (inverted), ML Kit X → Bitmap Y
   - Proper dimension swapping for cropped regions
4. **Rotation-Aware Fallback**: Wider search region also uses rotation-aware positioning

**Coordinate System Reference:**
- Camera sensor captures in landscape (e.g., 1920x1080)
- For portrait mode, CameraX rotates preview to match display
- ML Kit receives rotation info and provides coordinates in display space
- Native bitmap from `mediaImage` is still in sensor orientation
- Must transform ML Kit coords back to bitmap coords for cropping

### v1.2 - Native Touch Simulation for CAPTCHA Focus
- **Native Android Touch Events**: Uses MotionEvent.obtain() to simulate touch at CAPTCHA coordinates
- **Coordinate-Based Approach**: JavaScript calculates field center coordinates, Android simulates touch
- **JavaScript Interface**: Added simulateTouch() method to WebAppInterface for coordinate-based touch
- **Reliable Keyboard Opening**: Native touch simulation is more reliable than JavaScript focus() on WebView
- **Fallback Mechanism**: Includes JavaScript click fallback if touch simulation fails

### v1.1 - Page Preloading and Enhanced Focus
- **ALDI Page Preloading**: Hidden WebView preloads ALDI page when user selects ALDI market
- **CAPTCHA Focus Enhancement**: 
  - Multi-attempt focus with retry mechanism (up to 5 attempts)
  - Field readiness checks (visibility, enabled state)
  - Scroll into view before focusing
  - Multiple event dispatching (focus, click, mouse events)
  - Android-side backup focus method

### v1.4 - Lidl Support and Error Message Fixes
- **Lidl Market Implementation**: 
  - Added LidlMarket class using same form provider as ALDI (tx-gate.com)
  - Uses direct iframe URL loading: `balancechecks.tx-gate.com/balance.php?cid=79`
  - Same form field selectors as ALDI (cardnumberfield, pin, input for CAPTCHA)
  - 20-digit card number (last 20 digits of scanned barcode), 4-digit PIN format
- **Error Message Handling**:
  - Truncated console messages to 500 characters in logcat
  - Truncated user-facing error messages to 200 characters
  - Added safety checks for very long JSON responses (>50KB)
  - Filters out navigation/cookie checkboxes from debug data collection
  - Prevents huge JSON responses that cause parsing errors
- **WebView Error Suppression**:
  - Hides long error messages displayed in DOM for Lidl pages
  - Uses MutationObserver to catch dynamically added error elements
  - Detects and hides elements with error-like text (>500 chars)

### v1.6 - PIN Detection Region Improvements
- **REWE TYPE_2 Gap Enhancement**:
  - Added 2x PIN area gap between barcode and PIN search region for Aztec barcodes
  - In portrait mode: gap applied vertically (between barcode top and PIN region bottom)
  - In landscape mode: gap applied horizontally (between barcode left and PIN region right)
  - Improves PIN detection accuracy by avoiding barcode interference
- **ALDI Corner-Focused Detection**:
  - Dedicated ALDI case with Lidl-style corner positioning (mirrored for left side)
  - Extended search region (2x width and height)
  - PIN region positioned in upper-left corner - starts from left of barcode and extends leftward
  - No overlap with barcode area - PIN region ends at barcode left edge and top
  - Improved PIN detection by focusing on actual corner location (upper-left instead of upper-right)

### v1.8 - Simplified Main Screen UI
- **Redesigned Main Screen**:
  - Removed header, subtitle, and instructions section
  - Three large logo cards evenly distributed vertically
  - Each card uses brand colors: REWE (red #CC071E), ALDI (blue #00529B), Lidl (yellow #FFD100)
  - Large, bold text logos (48sp) centered on each card
  - Clean, minimalist interface focusing on market selection
  - Cards maintain click handlers and navigation functionality

### v1.9 - Fixed ALDI PIN Detection Box Positioning
- **Coordinate Transformation Improvements**:
  - Enhanced `updateHighlights()` function with better coordinate transformation logic
  - Improved handling of ML Kit coordinate system to preview view coordinate system mapping
  - Fixed issue where PIN detection box appeared below barcode when it should be above
  - Better accounting for display rotation and aspect ratio differences
  - Refactored coordinate transformation into helper function for maintainability
- **Debug Logging**:
  - Added comprehensive debug logging for PIN overlay positioning
  - Logs show barcode and PIN overlay positions in both ML Kit and preview coordinates
  - Tracks transformation parameters (scale, offset, rotation) for debugging
  - Helps identify coordinate system mismatches between detection and display
- **Technical Details**:
  - ML Kit coordinates are in display-corrected space (rotation applied)
  - PreviewView automatically handles rotation but uses FIT_CENTER scaling
  - Coordinate transformation accounts for letterboxing/pillarboxing
  - Scale factors calculated based on aspect ratio differences
  - Proper offset calculation for centered image display

### v2.0 - Dependency Injection and Build Improvements

**Hilt Integration:**
- Added Hilt dependency injection framework for better code organization
- Created `GiftCardCheckerApplication` with `@HiltAndroidApp` annotation
- Implemented `AppModule` for application-level dependencies
- Implemented `OcrModule` for ML Kit dependencies (BarcodeScanner, TextRecognizer)
- Improved testability through dependency injection

**Intent API Compatibility:**
- Created `IntentExtensions` utility for handling deprecated Intent APIs
- Provides version-compatible methods that work across all Android versions
- Eliminates deprecation warnings while maintaining compatibility

**Build Configuration:**
- Added debug and release build variants with different configurations
- Enabled ProGuard for release builds with resource shrinking
- Added build config fields for conditional logging and debug features
- Debug builds use `.debug` application ID suffix for side-by-side installation

**ProGuard Rules:**
- Comprehensive rules for Hilt dependency injection
- Preserved ML Kit, WebView, and data model classes
- Optimized release build size and obfuscation

### v2.3 - Fixed Coordinate Transformation for Preview Overlays

**Problem:**
The coordinate transformation logic in `ScannerActivity.updateHighlights()` was incorrectly assuming PreviewView uses FIT_CENTER scaling with letterboxing. However, PreviewView uses FILL_CENTER by default, which crops the image to fill the entire view.

**Solution:**
1. **Updated Scaling Logic**:
   - Changed from separate `scaleX`/`scaleY` to unified `scale` factor
   - Implemented proper FILL_CENTER scaling calculations
   - When image is wider: scale to fill height, crop left/right (negative offset)
   - When image is taller: scale to fill width, crop top/bottom (negative offset)
2. **Improved Debug Logging**:
   - Added aspect ratio logging for both image and preview
   - Simplified scale logging to show single unified scale factor
   - Better visibility into coordinate transformation parameters

**Technical Details:**
- FILL_CENTER scales the image to fill the entire preview view, cropping parts that don't fit
- Negative offsets indicate the image extends beyond the preview boundaries
- Coordinate transformation now correctly accounts for cropping behavior
- More accurate positioning of visual highlight overlays (RED for barcode, BLUE for PIN)

### v2.1 - German Localization

**Complete UI Translation:**
- Translated all user-facing text to German
- Updated all string resources in `strings.xml` to German
- App name changed to "Gutschein-Checker"
- All activity titles, instructions, button labels, and error messages in German

**Code Improvements:**
- Replaced all hardcoded strings in activities with string resources
- Modified `BalanceResult.getDisplayMessage()` to accept Context parameter for localization
- Updated `BalanceCheckActivity`, `ScannerActivity`, and `PinEntryActivity` to use string resources
- Improved maintainability by centralizing all text in string resources

**New String Resources Added:**
- Form filling status messages
- CAPTCHA instructions (full and short versions)
- Balance check button labels
- Error messages (network, validation, etc.)
- PIN detection messages
- Card number validation messages

**Benefits:**
- Easier to maintain and update text
- Ready for future multi-language support
- Consistent German terminology throughout the app
- Better user experience for German-speaking users

## Future Improvements

- Add more market implementations
- Local card history (encrypted)
- Batch balance checking
- Widget for quick balance check
- Accessibility improvements

