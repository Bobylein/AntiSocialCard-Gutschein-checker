# Testing Captcha OCR Models

This directory contains Python scripts to test the Hugging Face captcha OCR models on your PC before integrating them into the Android app.

## Quick Start

### Option 1: Using Setup Script (Recommended)

```bash
# Setup virtual environment and install dependencies
./setup_test_env.sh

# Run tests
./run_tests.sh

# Or run with options
./run_tests.sh --model model_v1.onnx
./run_tests.sh --no-download
```

### Option 2: Manual Setup

#### 1. Create Virtual Environment

```bash
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
```

#### 2. Install Dependencies

```bash
pip install -r requirements_test.txt
```

Or install manually:
```bash
pip install onnxruntime pillow numpy huggingface_hub
```

#### 3. Run Tests

```bash
# Test all model variants
python test_captcha_models.py

# Test only locally available models (skip download)
python test_captcha_models.py --no-download

# Test a specific model
python test_captcha_models.py --model model_v1.onnx

# Use custom test images directory
python test_captcha_models.py --test-dir /path/to/captchas

# Use custom models directory (default: captcha_models)
python test_captcha_models.py --models-dir /path/to/models
```

**Note**: Remember to activate the virtual environment before running tests manually:
```bash
source venv/bin/activate  # On Windows: venv\Scripts\activate
```

## What It Does

The test script (`test_captcha_models.py`):

1. **Loads models** from `captcha_models/` directory (or downloads from Hugging Face)
   - Tests model variants: model_v1.onnx through model_v8.onnx
   - Looks for models in `captcha_models/` by default
   - Can download to that directory if models are missing

2. **Tests each model** on your captcha images:
   - Loads images from `TestCaptchas/` folder
   - Preprocesses images (resize, normalize)
   - Runs inference
   - Extracts recognized text

3. **Shows results** for each model and image combination

## Example Output

```
============================================================
Captcha OCR Model Tester
============================================================
Test images directory: TestCaptchas
Models directory: models

============================================================
Testing: model_v1.onnx
============================================================
  Downloading model_v1.onnx...
  ✓ Downloaded to models/model_v1.onnx

  Model Info:
    Inputs: 1
      - image: shape=[1, 3, 50, 200], type=tensor(float)
    Outputs: 1
      - text: shape=[1, 10], type=tensor(int64)

  Testing on 2 images:
    aldi_captcha1.png... → 'ABC123'
    aldi_captcha2.png... → 'XYZ789'

============================================================
SUMMARY
============================================================

Successfully tested: 3/9 models

model_v1.onnx:
  ✓ aldi_captcha1.png: ABC123
  ✓ aldi_captcha2.png: XYZ789
```

## Model Location

Models are expected to be in `captcha_models/` directory (relative to project root). The script will:
- Look for models in `captcha_models/` first
- Download missing models to that directory if `--no-download` is not used
- You can specify a different directory with `--models-dir`

## Model Variants

The script tests these model variants (if available in `captcha_models/`):
- `model.onnx` (default)
- `model_v1.onnx` through `model_v8.onnx`

Each variant may have different:
- Input/output shapes
- Accuracy
- Performance characteristics

## Troubleshooting

### Model Download Fails

If you get authentication errors:
```bash
# Login to Hugging Face
huggingface-cli login

# Or set token as environment variable
export HF_TOKEN=your_token_here
```

### Model Not Found

Some model variants may not exist. The script will skip them and continue testing others.

### Wrong Output Format

If the recognized text looks wrong, the model's output format may differ. Check the model info in the output and adjust the `postprocess_output()` method in the script if needed.

### Image Preprocessing

The script uses default image size (200x50). If a model expects different dimensions, you may need to adjust the `preprocess_image()` method or check the model's expected input shape.

## Integration with Android

After testing on PC and finding the best model:

1. Download the best-performing model
2. Rename it to `captcha_ocr.onnx`
3. Place it in `app/src/main/assets/`
4. Update `OnnxCaptchaOcr.kt` if needed based on the model's input/output format
5. Run Android tests: `./gradlew connectedAndroidTest`

## Notes

- Models are downloaded to `models/` directory (can be customized with `--models-dir`)
- Test images are read from `TestCaptchas/` (can be customized with `--test-dir`)
- The script uses CPU execution provider for maximum compatibility
- Output format interpretation may need adjustment based on actual model architecture

