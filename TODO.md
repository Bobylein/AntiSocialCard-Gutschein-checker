# TODO - AntiSocialCard-Checker

## Planned Features

### High Priority

- [ ] **Add third market support** - Extend the app to support additional retailers as needed
- [ ] **Improve CAPTCHA handling** - Better user guidance when CAPTCHA is required
- [ ] **Offline mode indicator** - Show clear message when no network available

### Medium Priority

- [ ] **Card history** - Save recently checked cards (encrypted) for quick re-checking
- [ ] **Multiple card management** - Allow saving multiple cards per market
- [ ] **Export functionality** - Export balance history to CSV

### Low Priority

- [ ] **Widget support** - Home screen widget for quick balance check
- [ ] **Batch checking** - Check multiple cards at once
- [ ] **Dark/Light theme toggle** - Allow users to choose theme
- [ ] **Localization** - Add German language support (currently English UI)

## Known Issues

- [ ] **ALDI CAPTCHA** - ALDI balance check requires manual CAPTCHA solving
- [ ] **Website changes** - JavaScript selectors may break if retailers update their websites
- [ ] **OCR accuracy** - PIN OCR may not work well with unusual fonts or low-quality images

## Technical Debt

- [ ] Add unit tests for Market implementations
- [ ] Add UI tests for main flows
- [ ] Implement ViewModel + LiveData for better lifecycle handling
- [ ] Add Hilt/Dagger for dependency injection
- [ ] Create custom scanner overlay drawable

## Completed

- [x] Project setup with Kotlin and required dependencies
- [x] Data models (GiftCard, BalanceResult, MarketType)
- [x] Market abstraction layer
- [x] REWE market implementation
- [x] ALDI Nord market implementation
- [x] Barcode scanning with CameraX + ML Kit
- [x] PIN entry with manual and OCR options
- [x] WebView balance checking
- [x] Main UI with market selection
- [x] Error handling for common scenarios

