#!/bin/bash
# Run captcha OCR tests in the virtual environment

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check if venv exists
if [ ! -d "venv" ]; then
    echo "Virtual environment not found. Running setup..."
    bash setup_test_env.sh
fi

# Run the test script with all arguments passed through using venv Python
venv/bin/python3 test_captcha_models.py "$@"

