# Captcha OCR Model Setup

This project uses the `techietrader/captcha_ocr` ONNX model from Hugging Face for captcha recognition.

## Model Information

- **Model**: [techietrader/captcha_ocr](https://huggingface.co/techietrader/captcha_ocr)
- **Type**: Image-to-Text ONNX model
- **License**: Apache 2.0
- **Accuracy**: > 99.1%

## Setup Instructions

### 1. Download the Model

You need to download the ONNX model file from Hugging Face and place it in the assets folder.

#### Option A: Using Hugging Face CLI (Recommended)

```bash
# Install huggingface-cli if not already installed
pip install huggingface_hub

# Download the model
huggingface-cli download techietrader/captcha_ocr --local-dir ./model_temp

# Copy the ONNX model file to assets
cp ./model_temp/*.onnx app/src/main/assets/captcha_ocr.onnx

# Clean up
rm -rf ./model_temp
```

#### Option B: Manual Download

1. Visit https://huggingface.co/techietrader/captcha_ocr
2. Log in or sign up to Hugging Face (required to access the model)
3. Navigate to the "Files" tab
4. Download the ONNX model file (usually named something like `model.onnx` or `captcha_ocr.onnx`)
5. Rename it to `captcha_ocr.onnx` and place it in `app/src/main/assets/`

#### Option C: Using Python Script

Create a script `download_model.py`:

```python
from huggingface_hub import hf_hub_download
import shutil
import os

# Download the model
model_path = hf_hub_download(
    repo_id="techietrader/captcha_ocr",
    filename="model.onnx",  # Adjust filename based on actual model file
    local_dir="./model_temp"
)

# Copy to assets directory
assets_dir = "app/src/main/assets"
os.makedirs(assets_dir, exist_ok=True)
shutil.copy(model_path, os.path.join(assets_dir, "captcha_ocr.onnx"))

print(f"Model downloaded to {assets_dir}/captcha_ocr.onnx")
```

### 2. Verify Model File

After downloading, verify that the file exists:

```bash
ls -lh app/src/main/assets/captcha_ocr.onnx
```

The file should be several MB in size (typically 1-10 MB depending on the model variant).

### 3. Run Tests

Once the model is in place, you can run the tests:

```bash
# Run all Android instrumentation tests (includes CaptchaOcrTest)
./gradlew connectedAndroidTest

# Or use ADB to run specific tests (see TEST_SETUP.md for details)
adb shell am instrument -w -e class com.antisocial.giftcardchecker.CaptchaOcrTest com.antisocial.giftcardchecker.test/androidx.test.runner.AndroidJUnitRunner
```

## Model Variants

The model has multiple variants (v1-v8) with different accuracies. You can download any variant, but the code expects a single ONNX file named `captcha_ocr.onnx` in the assets folder.

## Troubleshooting

### Model Not Found Error

If you see "Model file not found in assets", ensure:
1. The file `captcha_ocr.onnx` exists in `app/src/main/assets/`
2. The file is not corrupted
3. You've synced Gradle after adding the file

### Model Input/Output Mismatch

The model's input/output format may vary. Check the logs for:
- Model input names and shapes
- Model output names and shapes

You may need to adjust:
- `INPUT_WIDTH` and `INPUT_HEIGHT` in `OnnxCaptchaOcr.kt`
- `modelInputName` and `modelOutputName` in `OnnxCaptchaOcr.kt`
- The `postprocessOutput()` method based on actual output format

### Access Denied

If you get an access denied error when downloading from Hugging Face:
1. Make sure you're logged in: `huggingface-cli login`
2. Accept the model's terms of service on the Hugging Face website
3. Some models require you to request access first

## Testing

The test suite includes:
- `testAldiCaptcha1()` - Tests recognition on `TestCaptchas/aldi_captcha1.png`
- `testAldiCaptcha2()` - Tests recognition on `TestCaptchas/aldi_captcha2.png`
- `testAllCaptchas()` - Tests all captcha images in the TestCaptchas folder
- `testModelInitialization()` - Verifies model loads correctly

## Notes

- The model expects images to be preprocessed (resized, normalized)
- Input size may need adjustment based on the specific model variant
- Output format may vary - check model card on Hugging Face for details
- The postprocessing logic may need tuning based on the actual model output

