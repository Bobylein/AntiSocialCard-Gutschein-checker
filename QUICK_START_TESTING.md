# Quick Start: Python Test Environment

## Setup (One-time)

```bash
./setup_test_env.sh
```

This will:
- Create a Python virtual environment (`venv/`)
- Install all required dependencies
- Set everything up for testing

## Running Tests

```bash
# Run all tests
./run_tests.sh

# Test specific model
./run_tests.sh --model model_v1.onnx

# Skip downloading (test only local models)
./run_tests.sh --no-download
```

## Manual Usage

If you prefer to activate the environment manually:

```bash
# Activate virtual environment
source venv/bin/activate

# Run tests
python test_captcha_models.py

# Deactivate when done
deactivate
```

## Files Created

- `venv/` - Python virtual environment (gitignored)
- `models/` - Downloaded ONNX models (gitignored)
- `setup_test_env.sh` - Setup script
- `run_tests.sh` - Test runner script

## Troubleshooting

If setup fails:
```bash
# Remove and recreate
rm -rf venv
./setup_test_env.sh
```

If dependencies are missing:
```bash
source venv/bin/activate
pip install -r requirements_test.txt
```




