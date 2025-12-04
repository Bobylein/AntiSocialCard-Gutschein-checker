#!/bin/bash
# Setup script for Python test environment

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Setting up Python test environment..."
echo ""

# Find system Python 3 (avoid Cursor's Python)
PYTHON3=""
for py in /usr/bin/python3 /usr/bin/python3.13 /usr/bin/python3.12 /usr/bin/python3.11 /usr/bin/python3.10; do
    if [ -x "$py" ] && "$py" --version &>/dev/null; then
        PYTHON3="$py"
        break
    fi
done

# Fallback to python3 in PATH if system Python not found
if [ -z "$PYTHON3" ]; then
    if command -v python3 &> /dev/null; then
        PYTHON3="python3"
    else
        echo "Error: python3 not found. Please install Python 3."
        exit 1
    fi
fi

echo "Using Python: $PYTHON3"
"$PYTHON3" --version

# Create virtual environment if it doesn't exist
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    "$PYTHON3" -m venv venv
    echo "✓ Virtual environment created"
    
    # Ensure pip is installed
    if [ ! -f "venv/bin/pip" ] && [ ! -f "venv/bin/pip3" ]; then
        echo "Installing pip..."
        venv/bin/python3 -m ensurepip --upgrade 2>/dev/null || {
            echo "Warning: ensurepip failed, trying to install pip manually..."
            curl -sS https://bootstrap.pypa.io/get-pip.py | venv/bin/python3
        }
    fi
else
    echo "✓ Virtual environment already exists"
fi

# Activate virtual environment
echo ""
echo "Activating virtual environment..."
source venv/bin/activate

# Upgrade pip (try both pip and pip3)
PIP_CMD=""
if [ -f "$SCRIPT_DIR/venv/bin/pip" ]; then
    PIP_CMD="$SCRIPT_DIR/venv/bin/pip"
elif [ -f "$SCRIPT_DIR/venv/bin/pip3" ]; then
    PIP_CMD="$SCRIPT_DIR/venv/bin/pip3"
else
    # Use python -m pip as fallback
    PIP_CMD="$SCRIPT_DIR/venv/bin/python3 -m pip"
fi

echo "Upgrading pip..."
$PIP_CMD install --upgrade pip > /dev/null 2>&1

# Install dependencies
echo "Installing dependencies..."
$PIP_CMD install -r requirements_test.txt

echo ""
echo "✓ Setup complete!"
echo ""
echo "To activate the environment manually, run:"
echo "  source venv/bin/activate"
echo ""
echo "To run the tests:"
echo "  python test_captcha_models.py"

