# WebView Fixes Applied - 2025-12-04

## Problem Summary
ALDI gift card balance checking was not working properly in the WebView. The scanning worked well, but the WebView automation failed.

## Root Cause
- ALDI uses a cross-origin iframe from `balancechecks.tx-gate.com` for the balance check form
- `requiresManualEntry` was set to `false` in `AldiMarket.kt`
- JavaScript could not access the cross-origin iframe due to browser security policies
- After 5 failed attempts, the app showed "Form fields not found" error
- User never saw the WebView with the form to manually enter data

## Fixes Applied

### 1. Enable Manual Entry Mode for ALDI (AldiMarket.kt)
**File**: `app/src/main/java/com/antisocial/giftcardchecker/markets/AldiMarket.kt`

**Changes**:
- Set `requiresManualEntry = true` (line 38)
- Updated all comments from 19-digit to 20-digit card numbers
- Added explanatory comments about cross-origin iframe restrictions

**Result**:
- WebView now displays immediately when ALDI is selected
- Shows card number and PIN to user for manual entry
- User can manually enter data, solve CAPTCHA, and submit
- Much better UX than error screen

### 2. Add Error Detection for Blocked Cards
**File**: `app/src/main/java/com/antisocial/giftcardchecker/markets/AldiMarket.kt`

**Changes**:
- Added "gesperrt" (blocked) to error detection in JavaScript (line 510)
- Added "gesperrt" to `parseBalanceResponse()` (line 581)
- Added "gesperrt" to `isErrorPageLoaded()` (line 638)
- Updated error message to include "oder Gutschein gesperrt" (line 582)

**Result**: App now properly detects and reports blocked/locked gift cards

### 3. Add Network Connectivity Check
**File**: `app/src/main/java/com/antisocial/giftcardchecker/BalanceCheckActivity.kt`

**Changes**:
- Added imports for `ConnectivityManager` and `NetworkCapabilities` (lines 4-7)
- Added `isNetworkAvailable()` helper method (lines 634-639)
- Check network before loading WebView (lines 63-67)
- Show error if no network available

**Result**: Better error handling when device is offline

### 4. Add WebView Cache Clearing for Privacy
**File**: `app/src/main/java/com/antisocial/giftcardchecker/BalanceCheckActivity.kt`

**Changes**:
- Clear WebView cache, history, and cookies in `onDestroy()` (lines 623-626)

**Result**: Enhanced privacy - no card data persists in WebView after activity closes

### 5. Update Documentation
**Files Updated**:
- `readme.md` - Updated ALDI card format from 19 to 20 digits (line 18)
- `documentation/technical-documentation.md` - Updated ALDI card format (line 150) and note (line 152)
- `AldiMarket.kt` - Updated all code comments

**Result**: Consistent documentation across all files

## Testing Instructions

1. **Build the app**:
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on device**:
   ```bash
   ./gradlew installDebug
   ```

3. **Test ALDI flow**:
   - Select ALDI from main screen
   - Scan barcode (20 digits)
   - Scan or enter PIN (4 digits)
   - Confirm data
   - WebView should load immediately showing the Helaba page
   - Card number and PIN will be displayed in instructions
   - Manually enter the data in the form
   - Solve CAPTCHA
   - Click "Guthabenabfrage" button
   - View balance result

## Expected Behavior

### Before Fix
- User scans card successfully
- WebView loads but shows loading spinner
- After ~10 seconds, error appears: "Form fields not found"
- User cannot proceed

### After Fix
- User scans card successfully
- WebView loads and displays immediately
- Instructions show: "Bitte geben Sie die Daten manuell ein:"
- Card number and PIN are displayed
- User manually enters data and solves CAPTCHA
- User clicks submit button
- Balance is displayed (if valid)

## Additional Improvements Made

1. **Scanner already extracts 20 digits** (ScannerActivity.kt:162, 219-227) - no changes needed
2. **Confirmation screen validates minimum 8 digits** - appropriate for both 19 and 20 digit cards
3. **Network check prevents wasted time** when offline
4. **Cache clearing improves security** - no sensitive data left in WebView

## Files Modified

1. `app/src/main/java/com/antisocial/giftcardchecker/markets/AldiMarket.kt`
2. `app/src/main/java/com/antisocial/giftcardchecker/BalanceCheckActivity.kt`
3. `readme.md`
4. `documentation/technical-documentation.md`

## Build Status

✅ Build successful with no errors
⚠️ 5 warnings (pre-existing, not related to changes):
- Deprecated `getParcelableExtra()` method
- Deprecated `onBackPressed()` method
- Type inference warnings

## Next Steps

1. Test on physical device with real ALDI cards
2. Verify CAPTCHA can be solved manually
3. Test network error handling by toggling airplane mode
4. Consider adding retry logic if CAPTCHA fails
5. Monitor for any changes to Helaba website structure
