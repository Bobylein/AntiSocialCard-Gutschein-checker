# Captcha OCR Test Setup

This document explains how to set up and run tests for the Hugging Face captcha OCR model.

## Quick Start

### 1. Download the Model

Follow the instructions in `MODEL_SETUP.md` to download the ONNX model file and place it in:
```
app/src/main/assets/captcha_ocr.onnx
```

### 2. Test Images

Test captcha images are already set up:
- Source: `TestCaptchas/` folder (aldi_captcha1.png, aldi_captcha2.png)
- Copied to: `app/src/androidTest/assets/` (for device testing)

### 3. Run Tests

**Note**: The `--tests` flag doesn't work with `connectedAndroidTest` (Android instrumentation tests). Use one of these methods:

#### Option A: Run All Tests (Recommended)
```bash
# Run all Android instrumentation tests (includes CaptchaOcrTest)
./gradlew connectedAndroidTest
```

#### Option B: Run Specific Test via ADB
First, ensure a device/emulator is connected:
```bash
# Check connected devices
adb devices

# Run all tests in CaptchaOcrTest class
adb shell am instrument -w -e class com.antisocial.giftcardchecker.CaptchaOcrTest com.antisocial.giftcardchecker.test/androidx.test.runner.AndroidJUnitRunner

# Run a specific test method
adb shell am instrument -w -e class com.antisocial.giftcardchecker.CaptchaOcrTest#testAldiCaptcha1 com.antisocial.giftcardchecker.test/androidx.test.runner.AndroidJUnitRunner
```

#### Option C: Use Android Studio
1. Right-click on `CaptchaOcrTest.kt` or a specific test method
2. Select "Run 'CaptchaOcrTest'" or "Run 'testAldiCaptcha1()'"

## Test Structure

### Test Class: `CaptchaOcrTest`

Located at: `app/src/androidTest/java/com/antisocial/giftcardchecker/CaptchaOcrTest.kt`

**Test Methods:**
- `testAldiCaptcha1()` - Tests recognition on aldi_captcha1.png
- `testAldiCaptcha2()` - Tests recognition on aldi_captcha2.png  
- `testAllCaptchas()` - Tests all available captcha images
- `testModelInitialization()` - Verifies model loads correctly

### OCR Class: `OnnxCaptchaOcr`

Located at: `app/src/main/java/com/antisocial/giftcardchecker/ocr/OnnxCaptchaOcr.kt`

This class handles:
- Loading the ONNX model from assets
- Preprocessing images (resize, normalize)
- Running inference
- Postprocessing output to extract text

## Dependencies

The following dependency has been added to `app/build.gradle.kts`:
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
```

## Troubleshooting

### Model Not Found

**Error**: "Model file not found in assets"

**Solution**: 
1. Download the model following `MODEL_SETUP.md`
2. Ensure the file is named exactly `captcha_ocr.onnx`
3. Place it in `app/src/main/assets/`
4. Sync Gradle

### Test Images Not Found

**Error**: "Test captcha file not found"

**Solution**:
- Test images are automatically loaded from `androidTest/assets/`
- They have been copied from `TestCaptchas/` folder
- If missing, copy them manually:
  ```bash
  cp TestCaptchas/*.png app/src/androidTest/assets/
  ```

### Model Input/Output Mismatch

**Error**: Model inference fails or returns unexpected results

**Solution**:
1. Check the model's input/output format in the logs
2. Adjust `INPUT_WIDTH` and `INPUT_HEIGHT` in `OnnxCaptchaOcr.kt` if needed
3. Verify `modelInputName` and `modelOutputName` match the actual model
4. Adjust `postprocessOutput()` method based on actual output format

### ONNX Runtime Errors

**Error**: ClassNotFoundException or API errors

**Solution**:
1. Ensure ONNX Runtime dependency is synced: `./gradlew build --refresh-dependencies`
2. Check that the correct version is used (1.18.0)
3. Clean and rebuild: `./gradlew clean build`

## Expected Output

When tests run successfully, you should see output like:
```
aldi_captcha1.png recognized as: 'ABC123'
aldi_captcha2.png recognized as: 'XYZ789'

=== Testing all captchas ===
aldi_captcha1.png: 'ABC123'
aldi_captcha2.png: 'XYZ789'
Successfully recognized: 2/2 captchas
```

The actual recognized text will depend on the captcha images and model accuracy.

## Notes

- The model input size (200x50) may need adjustment based on the specific model variant
- The postprocessing logic assumes character-level output - may need tuning for different model architectures
- Model accuracy is reported as >99.1% on the Hugging Face page
- Test results are logged to Android Logcat with tag "CaptchaOcrTest"

## Next Steps

After successful testing, you can integrate the `OnnxCaptchaOcr` class into your main application for automatic captcha solving in the ALDI balance check flow.

