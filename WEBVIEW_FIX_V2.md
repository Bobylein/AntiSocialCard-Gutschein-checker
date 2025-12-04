# ALDI WebView Fix V2 - Hybrid Auto-Fill Approach

## Problems Identified

### Problem 1: Infinite Loading Spinner
- `loadBalanceCheckPage()` always called `showLoading(true)`
- Even in manual entry mode, the loading overlay would immediately hide the WebView
- User saw loading spinner forever and couldn't interact with the page

### Problem 2: User Wants Auto-Fill
- Previous fix (V1) set `requiresManualEntry = true`
- This disabled auto-fill completely
- User wants the form to be pre-filled automatically if possible

### Problem 3: Page Takes Too Long to Become Interactive
- Auto-fill tries 5 times (10+ seconds total)
- During this time, WebView is hidden behind loading overlay
- Even if page loads successfully, user can't see or interact with it

## Solution: Hybrid Auto-Fill with Fast Fallback

### Approach
1. **Try auto-fill first** (requiresManualEntry = false)
2. **Show WebView after 2 attempts** (~4-6 seconds) so page becomes interactive
3. **Continue auto-fill in background** - if it succeeds, show success message
4. **User can manually enter** if auto-fill fails, since WebView is already visible

### Changes Made

#### 1. AldiMarket.kt - Re-enable Auto-Fill
```kotlin
// Before:
override val requiresManualEntry: Boolean = true

// After:
override val requiresManualEntry: Boolean = false
```

#### 2. BalanceCheckActivity.kt - Fix Loading Overlay
```kotlin
private fun loadBalanceCheckPage() {
    // Only show loading overlay if not in manual entry mode
    if (!market.requiresManualEntry) {
        showLoading(true)
    }
    // ... rest of code
}
```

#### 3. BalanceCheckActivity.kt - Show WebView After 2 Attempts
```kotlin
if (pageLoadAttempts == 2 && market.marketType == ALDI) {
    Log.d(TAG, "Showing WebView after 2 attempts")
    showLoading(false)
    binding.webView.visibility = View.VISIBLE
    // Continue retrying auto-fill in background
}
```

#### 4. BalanceCheckActivity.kt - Manual Fallback for ALDI
```kotlin
if (pageLoadAttempts >= MAX_ATTEMPTS) {
    if (market.marketType == ALDI) {
        // Show WebView with instructions for manual entry
        formFilled = true
        showLoading(false)
        binding.webView.visibility = View.VISIBLE
        binding.tvCaptchaInstruction.text =
            "Automatisches Ausfüllen fehlgeschlagen.\n\n" +
            "Bitte geben Sie die Daten manuell ein:\n\n" +
            "Gutscheinnummer: ${giftCard.cardNumber}\n" +
            "PIN: ${giftCard.pin}"
        // ... show buttons
    } else {
        showError(BalanceResult.websiteChanged(errorMsg))
    }
}
```

## User Experience Flow

### Best Case: Auto-Fill Succeeds
1. User scans ALDI card (20 digits + PIN)
2. WebView loads Helaba page
3. After ~4-6 seconds, WebView becomes visible
4. Form fields are automatically filled
5. Success message: "Die Gutscheinnummer und PIN wurden automatisch ausgefüllt"
6. User solves CAPTCHA and clicks submit
7. Balance displayed

### Fallback Case: Auto-Fill Fails
1. User scans ALDI card (20 digits + PIN)
2. WebView loads Helaba page
3. After ~4-6 seconds, WebView becomes visible (even though auto-fill hasn't worked yet)
4. Auto-fill continues trying in background
5. If still no success after 10 seconds, shows instructions:
   - "Automatisches Ausfüllen fehlgeschlagen"
   - Displays card number and PIN
   - User can manually enter and proceed
6. User solves CAPTCHA and clicks submit
7. Balance displayed

## Key Improvements

### ✅ No More Infinite Loading
- WebView shows after maximum 6 seconds
- User can always interact with the page

### ✅ Auto-Fill Still Attempted
- JavaScript tries to fill form automatically
- If successful, user only needs to solve CAPTCHA

### ✅ Smooth Fallback
- If auto-fill fails, user can manually enter
- Card number and PIN are displayed for easy copying

### ✅ Better UX
- User always sees progress (WebView loading)
- Can interact with page even if auto-fill hasn't completed
- Clear instructions at each stage

## Technical Details

### Timing
- **Attempt 1**: ~0-3 seconds - Try auto-fill
- **Attempt 2**: ~3-6 seconds - Try auto-fill, **show WebView**
- **Attempts 3-5**: ~6-12 seconds - Continue auto-fill in background
- **After 5 attempts**: Show manual entry instructions

### JavaScript Strategy (AldiMarket.kt)
The JavaScript tries multiple approaches:
1. Accept cookie banner
2. Click "Guthaben abfragen" tab if not active
3. Wait for iframe to load
4. Try to access iframe content
5. If cross-origin blocked, try direct form access
6. Try both mobile and desktop form layouts

### Logging
All attempts are logged with tag `BalanceCheckActivity`:
- Page load events
- Form field detection
- Auto-fill success/failure
- Iframe accessibility
- URL processing

## Testing Checklist

- [ ] Build succeeds: `./gradlew assembleDebug`
- [ ] Install on device: `./gradlew installDebug`
- [ ] Test ALDI flow with real card
- [ ] Verify WebView shows within 6 seconds
- [ ] Check if auto-fill works (if form fields accessible)
- [ ] Verify manual entry works if auto-fill fails
- [ ] Test CAPTCHA solving
- [ ] Verify balance displays correctly
- [ ] Check logcat for errors/warnings

## Monitoring

Check logcat with:
```bash
adb logcat | grep -E "(BalanceCheckActivity|Form fill)"
```

Look for:
- "Showing WebView after 2 attempts" - WebView displayed early
- "Form fields filled successfully" - Auto-fill worked
- "Showing WebView for manual entry after auto-fill failed" - Fallback mode
- "Form fields not found, attempt X/5" - Retry attempts

## Files Modified

1. `app/src/main/java/com/antisocial/giftcardchecker/markets/AldiMarket.kt`
   - Line 38: Changed `requiresManualEntry` to `false`

2. `app/src/main/java/com/antisocial/giftcardchecker/BalanceCheckActivity.kt`
   - Lines 259-270: Added check for manual entry mode in `loadBalanceCheckPage()`
   - Lines 426-432: Show WebView after 2 attempts for ALDI
   - Lines 434-457: Manual fallback with instructions for ALDI

## Build Status

✅ **BUILD SUCCESSFUL** - Ready for testing
