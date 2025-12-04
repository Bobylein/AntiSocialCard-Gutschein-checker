#!/usr/bin/env python3
"""Debug script to figure out the correct character mapping for the model."""

import sys
sys.path.insert(0, '.')

from test_captcha_models import CaptchaOcrTester
from pathlib import Path

# Expected outputs
expected = {
    'aldi_captcha1.png': 'VXrS4i',
    'aldi_captcha2.png': 'ADRjxP'
}

tester = CaptchaOcrTester()
tester.debug_mode = True

# Test first captcha
result = tester.test_model('captcha_model_v1.onnx', download=False)

if result.get('success'):
    print("\n" + "="*60)
    print("CHARACTER MAPPING ANALYSIS")
    print("="*60)
    
    for img_result in result['results']:
        img_name = img_result['image']
        actual = img_result['text']
        expected_text = expected.get(img_name, '?')
        
        print(f"\n{img_name}:")
        print(f"  Expected: {expected_text}")
        print(f"  Actual:   {actual}")
        
        if actual != expected_text:
            print(f"  Mismatch! Need to find correct character mapping.")
            print(f"  Expected chars: {list(expected_text)}")
            print(f"  Actual chars:   {list(actual)}")



