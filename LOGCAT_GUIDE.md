# Logcat Guide for AntiSocialCard-Checker

This guide explains how to view logs from the app to debug PIN detection issues.

## Prerequisites

1. **Android device or emulator** connected and running
2. **ADB (Android Debug Bridge)** installed
   - Usually comes with Android Studio
   - Located in: `~/Android/Sdk/platform-tools/adb` (Linux/Mac)
   - Or: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (Windows)

## Quick Start

### Option 1: Use the provided scripts

```bash
# View filtered logs (recommended - shows only relevant PIN/barcode detection logs)
./view_logs.sh

# View all detailed logs from ScannerActivity
./view_all_logs.sh
```

### Option 2: Manual logcat commands

```bash
# Basic logcat - filter by app package
adb logcat | grep "com.antisocial.giftcardchecker"

# Filter by ScannerActivity tag (recommended)
adb logcat ScannerActivity:D *:S

# View logs with color highlighting for key terms
adb logcat ScannerActivity:D *:S | grep --color=always -E "PIN|REWE|Lidl|barcode|region|OCR|Extracted|detected|TYPE_1"

# Save logs to file
adb logcat ScannerActivity:D *:S > scanner_logs.txt
```

## Key Log Tags to Look For

When debugging PIN detection, look for these log entries:

### PIN Region Detection
- `=== PIN REGION DEBUG ===` - Shows region calculations
- `REWE TYPE_1: PIN in separate field to the LEFT of barcode`
- `Search region: LEFT side only`
- `Region coordinates: left=... right=...`

### OCR Results
- `=== OCR RESULTS FOR PIN REGION ===`
- `Full recognized text: '...'` - Shows all text OCR found
- `Extracted PIN: ...` - The PIN that was extracted
- `Block X: '...'` - Individual text blocks found

### Detection Results
- `PIN detected (region-of-interest): ...` - Successfully found PIN
- `No PIN found in region` - PIN not found in search region
- `REWE card: No PIN found in left region, not searching full image` - Prevents false positives

## Using Android Studio

1. Open Android Studio
2. Connect your device/emulator
3. Go to **View → Tool Windows → Logcat**
4. Filter by:
   - **Package Name**: `com.antisocial.giftcardchecker`
   - **Tag**: `ScannerActivity`
   - **Log Level**: `Debug` or `Verbose`

## Troubleshooting

### "No devices found"
- Make sure USB debugging is enabled on your device
- Run `adb devices` to verify connection
- Try `adb kill-server && adb start-server`

### "ADB not found"
- Add Android SDK platform-tools to your PATH
- Or use full path: `~/Android/Sdk/platform-tools/adb logcat`

### Logs are too verbose
- Use the filtered script: `./view_logs.sh`
- Or filter manually: `adb logcat ScannerActivity:D *:S`

## Example Log Output

When scanning a REWE Type 1 card, you should see:

```
D/ScannerActivity: === PIN REGION DEBUG ===
D/ScannerActivity: Market type: REWE
D/ScannerActivity: REWE card type: TYPE_1
D/ScannerActivity: Barcode box: left=500, top=300, right=800, bottom=400
D/ScannerActivity: Search region: LEFT side only (right edge at barcode left: 500)
D/ScannerActivity: Region coordinates: left=200, right=500 (must be < barcode left)
D/ScannerActivity: === OCR RESULTS FOR PIN REGION ===
D/ScannerActivity: Full recognized text: '7080'
D/ScannerActivity: Extracted PIN: 7080
D/ScannerActivity: PIN detected (region-of-interest): 7080
```

## Saving Logs for Analysis

To save logs for later analysis:

```bash
# Save to file with timestamp
adb logcat ScannerActivity:D *:S > logs_$(date +%Y%m%d_%H%M%S).txt

# Or use Android Studio's logcat export feature
# Right-click in Logcat → Export to File
```


