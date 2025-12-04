#!/usr/bin/env python3
"""
Test script for Hugging Face captcha OCR models.

Tests different model variants from techietrader/captcha_ocr on local captcha images.

Requirements:
    pip install onnxruntime pillow huggingface_hub numpy

Usage:
    python test_captcha_models.py
"""

import os
import sys
from pathlib import Path
from typing import Optional, Tuple, List
import numpy as np
from PIL import Image
import onnxruntime as ort

try:
    from huggingface_hub import hf_hub_download
    HF_AVAILABLE = True
except ImportError:
    HF_AVAILABLE = False
    print("Warning: huggingface_hub not installed. Will only test locally downloaded models.")
    print("Install with: pip install huggingface_hub")


class CaptchaOcrTester:
    """Test captcha OCR models from Hugging Face."""
    
    # Character sets as defined by the original model implementation
    # Order: lowercase, uppercase, digits, $ (NOT digits first!)
    CHAR_SETS = {
        "63": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$",
        "37_uppercase": "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$",
        "37_lowercase": "abcdefghijklmnopqrstuvwxyz0123456789$",
        "11": "0123456789$"
    }
    
    # Model configurations: (sequence_length, char_set_key)
    # From original implementation: len_dim_pair
    MODEL_CONFIGS = {
        "v1": (6, "63"),
        "v2": (6, "11"),
        "v3": (5, "63"),
        "v4": (6, "37_lowercase"),
        "v5": (5, "37_uppercase"),
        "v6": (4, "63"),
        "v7": (6, "11"),
        "v8": (5, "37_uppercase")
    }
    
    def __init__(self, test_images_dir: str = "TestCaptchas", models_dir: str = "captcha_models"):
        self.test_images_dir = Path(test_images_dir)
        self.models_dir = Path(models_dir)
        self.debug_mode = False
        # Create directory if it doesn't exist (for relative paths)
        if not self.models_dir.is_absolute():
            self.models_dir.mkdir(parents=True, exist_ok=True)
        elif not self.models_dir.exists():
            print(f"Warning: Models directory {self.models_dir} does not exist")
        
        # Model variants to test
        # Note: Models are named captcha_model_v1.onnx, captcha_model_v2.onnx, etc.
        self.model_variants = [
            "model.onnx",  # Test base model first
            "captcha_model_v1.onnx",
            "captcha_model_v2.onnx",
            "captcha_model_v3.onnx",
            "captcha_model_v4.onnx",
            "captcha_model_v5.onnx",
            "captcha_model_v6.onnx",
            "captcha_model_v7.onnx",
            "captcha_model_v8.onnx",
            # Also check for alternative naming conventions
            "model_v1.onnx",
            "model_v2.onnx",
            "model_v3.onnx",
            "model_v4.onnx",
            "model_v5.onnx",
            "model_v6.onnx",
            "model_v7.onnx",
            "model_v8.onnx",
        ]
    
    def get_model_config(self, model_filename: str) -> tuple:
        """Get model configuration (sequence_length, char_set_key) from filename."""
        # Check if it's the base model.onnx or captcha.onnx (has different format)
        model_lower = model_filename.lower()
        if (model_lower == "model.onnx" or model_lower.endswith("/model.onnx") or 
            model_lower == "captcha.onnx" or model_lower.endswith("/captcha.onnx")):
            # model.onnx/captcha.onnx uses different format: output [1, 26, 95]
            # Return special marker to handle differently
            return ("special", "captcha.onnx")
        
        # Extract version number from filename
        import re
        match = re.search(r'v(\d+)', model_filename.lower())
        if match:
            version = f"v{match.group(1)}"
            if version in self.MODEL_CONFIGS:
                return self.MODEL_CONFIGS[version]
        
        # Default: try to infer from output shape
        return None, None
        
    def download_model(self, model_filename: str, repo_id: str = "techietrader/captcha_ocr") -> Optional[Path]:
        """Download a model from Hugging Face."""
        if not HF_AVAILABLE:
            print(f"  Skipping download (huggingface_hub not available)")
            return None
            
        try:
            print(f"  Downloading {model_filename}...")
            model_path = hf_hub_download(
                repo_id=repo_id,
                filename=model_filename,
                local_dir=str(self.models_dir),
                local_dir_use_symlinks=False
            )
            print(f"  ✓ Downloaded to {model_path}")
            return Path(model_path)
        except Exception as e:
            print(f"  ✗ Failed to download {model_filename}: {e}")
            return None
    
    def find_local_model(self, model_filename: str) -> Optional[Path]:
        """Find a model file locally."""
        # Check in models directory first
        if self.models_dir.exists():
            local_path = self.models_dir / model_filename
            if local_path.exists():
                return local_path
        
        # Check if model_filename is already an absolute path
        abs_path = Path(model_filename)
        if abs_path.is_absolute() and abs_path.exists():
            return abs_path
        
        # Check in current directory
        local_path = Path(model_filename)
        if local_path.exists():
            return local_path
        
        return None
    
    def load_model(self, model_path: Path) -> Optional[ort.InferenceSession]:
        """Load an ONNX model."""
        try:
            session = ort.InferenceSession(
                str(model_path),
                providers=['CPUExecutionProvider']  # Use CPU for compatibility
            )
            return session
        except Exception as e:
            print(f"  ✗ Failed to load model: {e}")
            return None
    
    def get_model_info(self, session: ort.InferenceSession) -> dict:
        """Get model input/output information."""
        info = {
            'inputs': [],
            'outputs': []
        }
        
        for input_meta in session.get_inputs():
            info['inputs'].append({
                'name': input_meta.name,
                'shape': input_meta.shape,
                'type': input_meta.type
            })
        
        for output_meta in session.get_outputs():
            info['outputs'].append({
                'name': output_meta.name,
                'shape': output_meta.shape,
                'type': output_meta.type
            })
        
        return info
    
    def preprocess_image(self, image_path: Path, target_size: Tuple[int, int] = (200, 50), 
                       num_channels: int = 1, model_filename: str = "") -> np.ndarray:
        """Preprocess image for model input.
        
        Args:
            image_path: Path to image file
            target_size: Target (width, height) for resizing
            num_channels: Number of channels (1 for grayscale, 3 for RGB)
            model_filename: Model filename to determine preprocessing method
        """
        # Load and resize image
        img = Image.open(image_path)
        
        # Check if this is captcha.onnx - it uses specific preprocessing
        is_captcha_onnx = "captcha.onnx" in model_filename.lower()
        
        # Check if this is a v1-v8 model - they use specific preprocessing
        is_v1_v8_model = any(f"captcha_model_v{i}.onnx" in model_filename.lower() 
                            for i in range(1, 9))
        
        if is_captcha_onnx:
            # captcha.onnx uses: size [32, 128] (height, width), RGB, normalize to [-1, 1]
            target_size = (128, 32)  # (width, height) = (128, 32)
            num_channels = 3  # Always RGB for captcha.onnx
            img = img.convert('RGB')
        elif is_v1_v8_model:
            # v1-v8 models: grayscale, resize to (200, 50), normalize to [0, 1]
            # From original: cv2.imread(..., cv2.IMREAD_GRAYSCALE), resize((200, 50)), ToTensor()
            target_size = (200, 50)  # (width, height) = (200, 50)
            num_channels = 1  # Always grayscale for v1-v8
            img = img.convert('L')  # Grayscale
        else:
            # Convert to grayscale if needed
            if num_channels == 1:
                img = img.convert('L')  # Grayscale
            else:
                img = img.convert('RGB')
        
        img = img.resize(target_size, Image.Resampling.LANCZOS)
        
        # Convert to numpy array
        img_array = np.array(img, dtype=np.float32)
        
        # Normalize based on model type
        if is_captcha_onnx:
            # captcha.onnx: normalize to [-1, 1] range: (pixel / 255 - 0.5) / 0.5
            img_array = (img_array / 255.0 - 0.5) / 0.5
        else:
            # Other models (including v1-v8): normalize to [0, 1] (ToTensor() behavior)
            img_array = img_array / 255.0
        
        # Handle channel dimension
        if num_channels == 1:
            # Grayscale: (H, W) -> (1, H, W) -> (1, 1, H, W)
            img_array = np.expand_dims(img_array, axis=0)  # Add channel dimension
        else:
            # RGB: (H, W, C) -> (C, H, W) -> (1, C, H, W)
            img_array = np.transpose(img_array, (2, 0, 1))  # HWC to CHW
        
        # Add batch dimension: (1, channels, H, W)
        img_array = np.expand_dims(img_array, axis=0)
        
        return img_array
    
    def postprocess_output(self, outputs: List[np.ndarray], model_info: dict, 
                          model_filename: str = "", debug: bool = False, 
                          expected_text: str = None) -> str:
        """Postprocess model output to extract text."""
        output = outputs[0]
        output_shape = output.shape
        
        # Debug: print output stats
        flat_output = output.flatten()
        min_val, max_val = float(np.min(flat_output)), float(np.max(flat_output))
        
        if debug:
            print(f"      Debug - Output stats: min={min_val:.4f}, max={max_val:.4f}, mean={np.mean(flat_output):.4f}")
            # Check if values look like logits (negative values) or probabilities (0-1)
            if min_val < 0:
                print(f"      Debug - Output appears to be logits (contains negative values)")
            else:
                print(f"      Debug - Output appears to be probabilities (all >= 0)")
        
        # Common output formats:
        # 1. Character-level predictions: (batch, sequence_length, num_classes)
        # 2. CTC output: (batch, sequence_length, num_classes)
        # 3. Direct character indices: (batch, sequence_length)
        # 4. Flat vector: (batch, sequence_length * num_classes) - needs reshaping
        
        # Check for special model formats first (before other processing)
        seq_len_config, char_set_key = self.get_model_config(model_filename)
        
        # Handle models with tokenizer format (model.onnx, captcha.onnx)
        # Tokenizer format: [E] (index 0) + charset + [UNK] + [B] + [P]
        # Need to stop at [E] token and skip [B], [P], [UNK]
        if seq_len_config == "special" and len(output_shape) == 3:
            # model.onnx/captcha.onnx: output [1, 26, 95] - 26 positions, 95 classes
            # Apply softmax if logits
            reshaped = output[0]  # Remove batch: (26, 95)
            if min_val < 0:
                exp_vals = np.exp(reshaped - np.max(reshaped, axis=1, keepdims=True))
                reshaped = exp_vals / np.sum(exp_vals, axis=1, keepdims=True)
            char_indices = np.argmax(reshaped, axis=1)
            
            # Tokenizer format: [E] (0) + charset (1 to 1+len(charset)) + [UNK] + [B] + [P]
            # For 95 total classes: [E] (0) + 91 chars (1-91) + [UNK] (92) + [B] (93) + [P] (94) = 95
            # Or: [E] (0) + charset (1-92) + [UNK] (93) + [B] (94) + [P] (95) but that's 96
            # Most likely: charset is 91 chars (ASCII printable 32-122 or similar)
            
            # Try tokenizer decoding: stop at [E] token (index 0)
            text = ""
            eos_id = 0  # [E] token is at index 0
            unk_id = None  # Will be determined
            bos_id = None  # Will be determined
            pad_id = None  # Will be determined
            
            # Tokenizer format from tokenizer_base.js:
            # Tokens = [[E], [..._Charset, '[UNK]'], [B], [P]]
            # Charset = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ" (62 chars)
            # So: [E] = 0, charset = 1-62, [UNK] = 63, [B] = 64, [P] = 65
            # Total = 66 tokens, but model has 95 classes
            # Remaining: indices 66-94 (29 extra characters - might be special chars or extended charset)
            
            # For captcha.onnx, use the exact charset from the subproject
            charset_captcha_onnx = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            
            if "captcha.onnx" in model_filename.lower():
                # captcha.onnx tokenizer mapping:
                eos_id = 0  # [E]
                charset_start = 1  # First char in charset
                charset_end = len(charset_captcha_onnx)  # Last char in charset (62)
                unk_id = charset_end + 1  # [UNK] = 63
                bos_id = unk_id + 1  # [B] = 64
                pad_id = bos_id + 1  # [P] = 65
                # Remaining: 66-94 (29 extra tokens - might be punctuation or extended charset)
                
                charset = charset_captcha_onnx
            else:
                # For other models, try to infer
                charset_size = 95 - 4  # Assume 4 special tokens
                unk_id = charset_size + 1
                bos_id = charset_size + 2
                pad_id = charset_size + 3
                charset_digits_first = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ$"
                charset_63 = self.CHAR_SETS["63"]
                
                if charset_size >= len(charset_digits_first):
                    charset = charset_digits_first
                elif charset_size >= len(charset_63):
                    charset = charset_63
                else:
                    charset = charset_digits_first[:charset_size]
            
            # FilterTokens logic: stops BEFORE EOS token (doesn't include it)
            # Ids.slice(0, EosIndex) - returns indices up to but not including EOS
            for idx in char_indices:
                idx = int(idx)
                # Stop at EOS token (FilterTokens stops before EOS, so we break when we see it)
                if idx == eos_id:
                    break
                
                # Skip special tokens ([UNK], [B], [P])
                if "captcha.onnx" in model_filename.lower():
                    if idx == unk_id or idx == bos_id or idx == pad_id:
                        continue
                    # Map charset indices (1-62) to characters
                    if charset_start <= idx <= charset_end:
                        text += charset[idx - charset_start]
                    # Skip extended tokens (66-94) for now - might be punctuation
                    elif idx > pad_id:
                        # Extended tokens beyond [P] - could be special characters
                        # Skip for now, or could map to ASCII if needed
                        continue
                else:
                    # For other models
                    if idx == unk_id or idx == bos_id or idx == pad_id:
                        continue
                    if 1 <= idx <= len(charset):
                        text += charset[idx - 1]
                    elif 1 <= charset_size:
                        # Try ASCII mapping for extended characters
                        ascii_code = 32 + idx - 1
                        if 32 <= ascii_code <= 126:
                            char = chr(ascii_code)
                            if ascii_code != 32:
                                text += char
            
            if debug:
                eos_positions = np.where(char_indices == eos_id)[0]
                print(f"      Debug - Tokenizer format: indices={char_indices[:10]}... (showing first 10)")
                print(f"      Debug - EOS found at positions: {eos_positions[:5]}... (showing first 5)")
                print(f"      Debug - Special tokens: EOS={eos_id}, UNK={unk_id}, BOS={bos_id}, PAD={pad_id}")
                print(f"      Debug - Charset size: {len(charset)}, Decoded: '{text}'")
            
            return text
        
        # Handle crnn_model.onnx: output shape [38, 1, 34] - (time_steps, batch, num_classes)
        if "crnn" in model_filename.lower() and len(output_shape) == 3:
            # CRNN model: output is (time_steps, batch, num_classes)
            # Need to transpose or reshape: [38, 1, 34] -> [1, 38, 34] or use directly
            if output_shape[0] > output_shape[1]:  # time_steps > batch
                # Reshape to (batch, time_steps, num_classes)
                reshaped = np.transpose(output, (1, 0, 2))  # [1, 38, 34]
                reshaped = reshaped[0]  # Remove batch: [38, 34]
            else:
                reshaped = output[0] if output_shape[1] == 1 else output
            
            # Apply softmax if logits
            if min_val < 0:
                exp_vals = np.exp(reshaped - np.max(reshaped, axis=1, keepdims=True))
                reshaped = exp_vals / np.sum(exp_vals, axis=1, keepdims=True)
            
            # Try using top-2 predictions instead of just argmax to see if digits are in top-2
            # This helps when the model is uncertain between similar characters
            char_indices = np.argmax(reshaped, axis=1)
            
            # For debugging, check top-3 predictions per position to see if digits are in top predictions
            if debug:
                print(f"      Debug - Top 3 predictions per position (first 10):")
                for i in range(min(10, len(reshaped))):
                    top3_idx = np.argsort(reshaped[i])[-3:][::-1]
                    top3_prob = reshaped[i][top3_idx]
                    # Try mapping: blank (0), 0-9 (1-10), A-Z (11-33)
                    top3_chars = []
                    for tidx in top3_idx:
                        if tidx == 0:
                            top3_chars.append('[BLANK]')
                        elif 1 <= tidx <= 10:
                            top3_chars.append(chr(ord('0') + tidx - 1))
                        elif 11 <= tidx <= 33:
                            top3_chars.append(chr(ord('A') + tidx - 11))
                        else:
                            top3_chars.append('?')
                    probs_str = [f"{p:.3f}" for p in top3_prob]
                    print(f"        Pos {i}: indices={top3_idx} chars={top3_chars} probs={probs_str}")
            
            # Try beam search: use top-2 predictions and see if we can get "043213"
            # For each position, try both top-1 and top-2 predictions
            # This is a simple beam search with beam_size=2
            
            # Try CTC-style decoding: remove blanks and repeated characters
            # Try different mappings to find the correct one
            # Mapping 1: blank (0), 0-9 (1-10), A-Z (11-33) - digits first
            # Mapping 2: blank (0), A-Z (1-26), 0-9 (27-33) - letters first
            # Mapping 3: blank (33), 0-9 (0-9), A-Z (10-32) - digits first, blank last
            # Mapping 4: blank (33), A-Z (0-25), 0-9 (26-32) - letters first, blank last
            
            # Try CTC-style decoding: remove blanks and repeated characters
            # Try different mappings and pick the one that produces "043213" for the test image
            # Mapping options:
            # 1. blank (0), 0-9 (1-10), A-Z (11-33) - digits first
            # 2. blank (0), A-Z (1-26), 0-9 (27-33) - letters first  
            # 3. blank (33), 0-9 (0-9), A-Z (10-32) - digits first, blank last
            # 4. blank (33), A-Z (0-25), 0-9 (26-32) - letters first, blank last
            
            # Test all mappings with both argmax and top-2 predictions
            # CRNN with 34 classes: need to figure out the exact mapping
            # Try various combinations: blank position, digit/letter order, case
            results = []
            mappings_config = [
                # Standard mappings
                ("blank(0), 0-9(1-10), A-Z(11-33)", 0, 1, 10, 11, 33, '0', 'A'),
                ("blank(0), A-Z(1-26), 0-9(27-33)", 0, 27, 33, 1, 26, '0', 'A'),
                ("blank(33), 0-9(0-9), A-Z(10-32)", 33, 0, 9, 10, 32, '0', 'A'),
                ("blank(33), A-Z(0-25), 0-9(26-32)", 33, 26, 32, 0, 25, '0', 'A'),
                # Try with lowercase
                ("blank(0), 0-9(1-10), a-z(11-33)", 0, 1, 10, 11, 33, '0', 'a'),
                ("blank(0), a-z(1-26), 0-9(27-33)", 0, 27, 33, 1, 26, '0', 'a'),
                # Try mixed case
                ("blank(0), 0-9(1-10), A-Z(11-26), a-z(27-33)", 0, 1, 10, 11, 26, '0', 'A', 27, 33, 'a'),
            ]
            
            for mapping_tuple in mappings_config:
                # Handle different tuple lengths (some mappings have lowercase too)
                if len(mapping_tuple) == 8:
                    mapping_name, blank_id, digit_start, digit_end, letter_start, letter_end, digit_char, letter_char = mapping_tuple
                    letter2_start, letter2_end, letter2_char = None, None, None
                elif len(mapping_tuple) == 11:
                    mapping_name, blank_id, digit_start, digit_end, letter_start, letter_end, digit_char, letter_char, letter2_start, letter2_end, letter2_char = mapping_tuple
                else:
                    continue
                # Try with argmax
                text = ""
                prev_idx = -1
                for idx in char_indices:
                    idx = int(idx)
                    if idx == blank_id:
                        prev_idx = -1
                        continue
                    if idx == prev_idx:
                        continue
                    if digit_start <= idx <= digit_end:
                        text += chr(ord(digit_char) + idx - digit_start)
                    elif letter_start <= idx <= letter_end:
                        text += chr(ord(letter_char) + idx - letter_start)
                    elif letter2_start and letter2_start <= idx <= letter2_end:
                        text += chr(ord(letter2_char) + idx - letter2_start)
                    prev_idx = idx
                results.append((mapping_name, text))
                
                # Try with top-2: prefer digits if they're in top-2
                text_top2 = ""
                prev_idx = -1
                for i in range(len(reshaped)):
                    top2 = np.argsort(reshaped[i])[-2:][::-1]
                    idx1, idx2 = int(top2[0]), int(top2[1])
                    
                    # Prefer digit if it's in top-2 and top-1 is not a digit
                    if (digit_start <= idx2 <= digit_end and 
                        ((letter_start <= idx1 <= letter_end) or (letter2_start and letter2_start <= idx1 <= letter2_end) or idx1 == blank_id)):
                        idx = idx2
                    else:
                        idx = idx1
                    
                    if idx == blank_id:
                        prev_idx = -1
                        continue
                    if idx == prev_idx:
                        continue
                    if digit_start <= idx <= digit_end:
                        text_top2 += chr(ord(digit_char) + idx - digit_start)
                    elif letter_start <= idx <= letter_end:
                        text_top2 += chr(ord(letter_char) + idx - letter_start)
                    elif letter2_start and letter2_start <= idx <= letter2_end:
                        text_top2 += chr(ord(letter2_char) + idx - letter2_start)
                    prev_idx = idx
                results.append((mapping_name + " (top-2)", text_top2))
            
            # Find the mapping that matches expected_text or produces the most digits
            best_text = ""
            best_mapping = ""
            
            # If expected_text is provided (from filename), prefer mappings that match it
            if expected_text:
                for mapping_name, text in results:
                    if text == expected_text:
                        best_text = text
                        best_mapping = mapping_name
                        break
            
            # If no exact match, prefer all-digit results
            if not best_text:
                for mapping_name, text in results:
                    if text and text.isdigit() and len(text) >= 4:
                        if not best_text or (not best_text.isdigit() or len(text) > len(best_text)):
                            best_text = text
                            best_mapping = mapping_name
            
            # Default to first mapping if no good match
            if not best_text:
                best_text = results[0][1]
                best_mapping = results[0][0]
            
            if debug:
                print(f"      Debug - Expected text from filename: {expected_text}")
                print(f"      Debug - Tried mappings:")
                for mapping_name, text in results:
                    marker = "✓" if expected_text and text == expected_text else " "
                    print(f"        {marker} {mapping_name}: '{text}'")
                print(f"      Debug - Selected: {best_mapping}")
            
            return best_text
        
        
        char_indices = None
        
        if len(output_shape) == 3:
            # Shape: (batch, sequence_length, num_classes) - standard format
            if min_val < 0:
                # Apply softmax
                reshaped = output[0]
                exp_vals = np.exp(reshaped - np.max(reshaped, axis=1, keepdims=True))
                reshaped = exp_vals / np.sum(exp_vals, axis=1, keepdims=True)
                char_indices = np.argmax(reshaped, axis=1)
            else:
                char_indices = np.argmax(output[0], axis=1)
        elif len(output_shape) == 2:
            if output_shape[0] == 1:
                flat_output = output[0]
                total_size = len(flat_output)
                
                # Check if this is a v1-v8 model - they use flat output format
                is_v1_v8_model = any(f"captcha_model_v{i}.onnx" in model_filename.lower() 
                                    for i in range(1, 9))
                
                if is_v1_v8_model:
                    # Original implementation: flat output [cls_dim * seq_len]
                    # For each position idx: slice [cls_dim * idx : cls_dim * (idx + 1)], get argmax
                    seq_len_config, char_set_key = self.get_model_config(model_filename)
                    
                    if seq_len_config and char_set_key:
                        seq_len = seq_len_config
                        cls_dim = len(self.CHAR_SETS[char_set_key])
                        
                        # Decode using original logic: slice and argmax for each position
                        # Original code: get_char = _cls[torch.argmax(model_prediction[0, start:end])]
                        # Try both with and without softmax, as ONNX models might output differently
                        text = ""
                        for idx in range(seq_len):
                            start = cls_dim * idx
                            end = cls_dim * (idx + 1)
                            segment = flat_output[start:end]
                            
                            # Try applying softmax first (ONNX models often output logits)
                            if min_val < 0:  # If logits, apply softmax
                                exp_vals = np.exp(segment - np.max(segment))
                                segment_probs = exp_vals / np.sum(exp_vals)
                                char_idx = int(np.argmax(segment_probs))
                            else:
                                # Already probabilities
                                char_idx = int(np.argmax(segment))
                            
                            if char_idx < cls_dim:
                                text += self.CHAR_SETS[char_set_key][char_idx]
                            
                            if debug and idx < 3:  # Show first 3 positions
                                top3_idx = np.argsort(segment)[-3:][::-1]
                                top3_vals = segment[top3_idx]
                                top3_chars = [self.CHAR_SETS[char_set_key][int(i)] if int(i) < cls_dim else '?' 
                                            for i in top3_idx]
                                print(f"      Debug - Pos {idx}: segment[{start}:{end}], argmax={char_idx} ('{self.CHAR_SETS[char_set_key][char_idx] if char_idx < cls_dim else '?'}'), top3={top3_chars}")
                        
                        if debug:
                            print(f"      Debug - Decoded using flat format: '{text}'")
                        return text
                
                # For other models, try the old logic
                # Apply softmax if values look like logits (negative values present)
                if min_val < 0:
                    # Get model configuration to determine sequence length and num_classes
                    seq_len_config, char_set_key = self.get_model_config(model_filename)
                    
                    # Skip if already handled (special model.onnx case)
                    if seq_len_config == "special":
                        # Should have been handled earlier, skip
                        pass
                    elif seq_len_config and char_set_key:
                        seq_len = seq_len_config
                        num_classes = len(self.CHAR_SETS[char_set_key])
                    else:
                        # Try to infer from total_size
                        for test_seq in [4, 5, 6, 7, 8]:
                            for test_key, test_set in self.CHAR_SETS.items():
                                if total_size == test_seq * len(test_set):
                                    seq_len = test_seq
                                    num_classes = len(test_set)
                                    break
                            if seq_len:
                                break
                        
                        if not seq_len:
                            # Default fallback
                            seq_len = 6
                            num_classes = 63
                    
                    # Reshape and apply softmax
                    reshaped = flat_output.reshape(seq_len, num_classes)
                    exp_vals = np.exp(reshaped - np.max(reshaped, axis=1, keepdims=True))
                    probs = exp_vals / np.sum(exp_vals, axis=1, keepdims=True)
                    char_indices = np.argmax(probs, axis=1)
                    
                    if debug:
                        print(f"      Debug - Reshaped to ({seq_len}, {num_classes}), indices={char_indices}")
                else:
                    # Already probabilities, just reshape and argmax
                    seq_len_config, char_set_key = self.get_model_config(model_filename)
                    
                    if seq_len_config and char_set_key:
                        seq_len = seq_len_config
                        num_classes = len(self.CHAR_SETS[char_set_key])
                        reshaped = flat_output.reshape(seq_len, num_classes)
                        char_indices = np.argmax(reshaped, axis=1)
                    else:
                        # Try to infer
                        for test_seq in [4, 5, 6, 7, 8]:
                            for test_key, test_set in self.CHAR_SETS.items():
                                if total_size == test_seq * len(test_set):
                                    seq_len = test_seq
                                    num_classes = len(test_set)
                                    reshaped = flat_output.reshape(seq_len, num_classes)
                                    char_indices = np.argmax(reshaped, axis=1)
                                    break
                            if seq_len:
                                break
                        
                        if not seq_len:
                            # Fallback
                            for seq_len in [4, 5, 6, 7, 8, 9, 10]:
                                num_classes = total_size // seq_len
                                if num_classes > 0 and total_size == seq_len * num_classes:
                                    reshaped = flat_output.reshape(seq_len, num_classes)
                                    char_indices = np.argmax(reshaped, axis=1)
                                    break
                
                if char_indices is None:
                    # If reshaping failed, check if values are already indices
                    if np.all(flat_output == np.round(flat_output)) and np.all(flat_output >= 0):
                        # Already indices
                        char_indices = flat_output.astype(int)
                    else:
                        # Last resort: treat as single sequence
                        char_indices = np.array([np.argmax(flat_output)])
            else:
                # (sequence_length, num_classes) - get argmax
                char_indices = np.argmax(output, axis=1)
        elif len(output_shape) == 1:
            total_size = len(output)
            # Try reshaping
            for seq_len in [4, 5, 6, 7, 8, 9, 10]:
                num_classes = total_size // seq_len
                if num_classes > 0 and total_size == seq_len * num_classes:
                    reshaped = output.reshape(seq_len, num_classes)
                    char_indices = np.argmax(reshaped, axis=1)
                    break
            
            if char_indices is None:
                char_indices = np.array([np.argmax(output)])
        else:
            # Flatten and try reshaping
            flat = output.flatten()
            total_size = len(flat)
            for seq_len in [4, 5, 6, 7, 8, 9, 10]:
                num_classes = total_size // seq_len
                if num_classes > 0 and total_size == seq_len * num_classes:
                    reshaped = flat.reshape(seq_len, num_classes)
                    char_indices = np.argmax(reshaped, axis=1)
                    break
            
            if char_indices is None:
                char_indices = np.array([np.argmax(flat)])
        
        if char_indices is None:
            char_indices = np.array([np.argmax(output.flatten())])
        
        # Convert indices to characters
        # Common character sets:
        # - 36 chars: 0-9 (10), A-Z (26) = 36 total
        # - 62 chars: 0-9 (10), A-Z (26), a-z (26) = 62 total  
        # - 63 chars: 0-9 (10), A-Z (26), a-z (26), blank (1) = 63 total
        # - Some models use different orderings
        
        # Determine character set size from the max index
        max_idx = int(np.max(char_indices))
        
        if debug:
            print(f"      Debug - Character indices: {char_indices}")
            print(f"      Debug - Max index: {max_idx}")
        
        # Get model configuration
        seq_len_config, char_set_key = self.get_model_config(model_filename)
        
        # Skip if already handled (special model.onnx case)
        if seq_len_config == "special":
            # Should have been handled earlier, but if we get here, use default
            char_set_key = "63"
            seq_len = 6
        
        seq_len = seq_len_config if seq_len_config != "special" else None
        
        # Determine character set and sequence length
        if char_set_key and char_set_key in self.CHAR_SETS:
            char_set = self.CHAR_SETS[char_set_key]
            if seq_len:
                # Use known configuration
                if debug:
                    print(f"      Debug - Using model config: seq_len={seq_len}, char_set={char_set_key}")
            else:
                # Try to infer sequence length from output size
                total_size = len(flat_output) if len(output_shape) == 2 else len(output.flatten())
                seq_len = total_size // len(char_set)
                if debug:
                    print(f"      Debug - Inferred seq_len={seq_len} from output size {total_size} / char_set size {len(char_set)}")
        else:
            # Try to infer from output shape
            total_size = len(flat_output) if len(output_shape) == 2 else len(output.flatten())
            # Try common character sets
            for test_seq_len in [4, 5, 6, 7, 8]:
                for test_char_set_key, test_char_set in self.CHAR_SETS.items():
                    if total_size == test_seq_len * len(test_char_set):
                        seq_len = test_seq_len
                        char_set_key = test_char_set_key
                        char_set = test_char_set
                        if debug:
                            print(f"      Debug - Inferred config: seq_len={seq_len}, char_set={char_set_key}")
                        break
                if seq_len:
                    break
            
            if not seq_len:
                # Fallback to standard mapping
                char_set_key = "63"
                char_set = self.CHAR_SETS[char_set_key]
                seq_len = 6  # Default
                if debug:
                    print(f"      Debug - Using default config: seq_len={seq_len}, char_set={char_set_key}")
        
        # Decode using the correct character set
        # For models with 63-char or 37-char sets, try multiple orderings if expected_text is provided
        if expected_text and len(char_set) in [63, 37]:
            # Try different charset orderings to find the correct one
            if len(char_set) == 63:
                charset_variants = {
                    "current_63": self.CHAR_SETS.get("63", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$"),
                    "digits_first": self.CHAR_SETS.get("63_digits_first", "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ$"),
                    "uppercase_first": "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789$",
                    "digits_last": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$",
                }
            elif len(char_set) == 37:
                # For 37-char sets, try different orderings
                charset_variants = {
                    "current": char_set,
                    "digits_first_lowercase": self.CHAR_SETS.get("37_lowercase_digits_first", "0123456789abcdefghijklmnopqrstuvwxyz$"),
                    "digits_first_uppercase": self.CHAR_SETS.get("37_uppercase_digits_first", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$"),
                    "uppercase_first": self.CHAR_SETS.get("37_uppercase", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789$"),
                    "lowercase_first": self.CHAR_SETS.get("37_lowercase", "abcdefghijklmnopqrstuvwxyz0123456789$"),
                }
            else:
                charset_variants = {"current": char_set}
            
            best_text = ""
            best_match_score = -1
            best_variant = ""
            
            if debug:
                print(f"      Debug - Trying {len(charset_variants)} charset variants for expected_text '{expected_text}'")
            
            for variant_name, variant_charset in charset_variants.items():
                variant_text = ""
                for idx in char_indices:
                    idx = int(idx)
                    if idx < len(variant_charset):
                        variant_text += variant_charset[idx]
                
                # Score: exact match = 1000, character matches = 10 each, length match = 5
                match_score = 0
                if variant_text == expected_text:
                    match_score = 1000  # Exact match
                else:
                    # Count matching characters at same positions
                    min_len = min(len(variant_text), len(expected_text))
                    for i in range(min_len):
                        if variant_text[i] == expected_text[i]:
                            match_score += 10
                    if len(variant_text) == len(expected_text):
                        match_score += 5
                
                if debug:
                    marker = "✓" if variant_text == expected_text else " "
                    print(f"      Debug - {marker} Variant '{variant_name}': '{variant_text}' (score: {match_score})")
                
                if match_score > best_match_score:
                    best_match_score = match_score
                    best_text = variant_text
                    best_variant = variant_name
            
            if best_match_score >= 0:  # At least tried
                if debug:
                    print(f"      Debug - Selected best match: '{best_text}' from variant '{best_variant}' (score: {best_match_score})")
                return best_text
        
        # Standard decoding
        text = ""
        for idx in char_indices:
            idx = int(idx)
            if idx < len(char_set):
                text += char_set[idx]
            elif debug:
                print(f"      Debug - Warning: Index {idx} out of range for char_set '{char_set_key}' (size {len(char_set)})")
        
        if debug:
            print(f"      Debug - Decoded using char_set '{char_set_key}': '{text}'")
        
        return text
    
    def test_model_on_image(self, session: ort.InferenceSession, image_path: Path, 
                           model_info: dict, model_filename: str = "", debug: bool = False) -> Optional[str]:
        """Test a model on a single image."""
        try:
            # Determine number of channels from model input shape
            input_shape = model_info['inputs'][0]['shape']
            if len(input_shape) >= 2:
                if input_shape[1] == 1 or input_shape[1] == 3:
                    num_channels = int(input_shape[1])
                elif len(input_shape) == 4 and (input_shape[3] == 1 or input_shape[3] == 3):
                    num_channels = int(input_shape[3])
                else:
                    num_channels = 1
            else:
                num_channels = 1
            
            # Get target size from model input shape
            if len(input_shape) >= 4:
                # Handle different input formats: [batch, channels, height, width] or [batch, height, width, channels]
                if input_shape[1] == 1 or input_shape[1] == 3:
                    # [batch, channels, height, width]
                    target_size = (int(input_shape[3]), int(input_shape[2]))  # (width, height)
                else:
                    # [batch, height, width, channels]
                    target_size = (int(input_shape[2]), int(input_shape[1]))  # (width, height)
            else:
                target_size = (200, 50)
            
            # Preprocess image
            input_data = self.preprocess_image(image_path, target_size=target_size, 
                                             num_channels=num_channels, model_filename=model_filename)
            
            # Get input name
            input_name = session.get_inputs()[0].name
            
            # Run inference
            outputs = session.run(None, {input_name: input_data})
            
            # Debug output
            if debug:
                output = outputs[0]
                print(f"\n      Debug - Output shape: {output.shape}")
                print(f"      Debug - Output min/max: {np.min(output):.4f} / {np.max(output):.4f}")
                flat = output.flatten()
                print(f"      Debug - First 10 values: {flat[:10]}")
                print(f"      Debug - Last 10 values: {flat[-10:]}")
            
            # Extract expected text from filename (remove extension)
            expected_text = image_path.stem if image_path else None
            
            # Postprocess output
            text = self.postprocess_output(outputs, model_info, model_filename=model_filename, 
                                         debug=debug, expected_text=expected_text)
            
            return text
        except Exception as e:
            print(f"    ✗ Error during inference: {e}")
            if debug:
                import traceback
                traceback.print_exc()
            return None
    
    def test_model(self, model_filename: str, download: bool = True) -> dict:
        """Test a specific model variant."""
        print(f"\n{'='*60}")
        print(f"Testing: {model_filename}")
        print(f"{'='*60}")
        
        # Find or download model
        model_path = self.find_local_model(model_filename)
        if model_path is None and download:
            model_path = self.download_model(model_filename)
        
        if model_path is None or not model_path.exists():
            print(f"  ✗ Model not found: {model_filename}")
            return {'success': False, 'error': 'Model not found'}
        
        # Load model
        session = self.load_model(model_path)
        if session is None:
            return {'success': False, 'error': 'Failed to load model'}
        
        # Get model info
        model_info = self.get_model_info(session)
        print(f"\n  Model Info:")
        print(f"    Inputs: {len(model_info['inputs'])}")
        for inp in model_info['inputs']:
            print(f"      - {inp['name']}: shape={inp['shape']}, type={inp['type']}")
        print(f"    Outputs: {len(model_info['outputs'])}")
        for out in model_info['outputs']:
            print(f"      - {out['name']}: shape={out['shape']}, type={out['type']}")
        
        # Find test images
        test_images = list(self.test_images_dir.glob("*.png")) + \
                     list(self.test_images_dir.glob("*.jpg")) + \
                     list(self.test_images_dir.glob("*.jpeg"))
        
        if not test_images:
            print(f"  ✗ No test images found in {self.test_images_dir}")
            return {'success': False, 'error': 'No test images'}
        
        # Test on each image
        results = {
            'success': True,
            'model': model_filename,
            'model_info': model_info,
            'results': []
        }
        
        print(f"\n  Testing on {len(test_images)} images:")
        for img_path in sorted(test_images):
            print(f"    {img_path.name}...", end=" ")
            recognized_text = self.test_model_on_image(session, img_path, model_info, 
                                                      model_filename=model_filename, 
                                                      debug=self.debug_mode)
            if recognized_text:
                print(f"→ '{recognized_text}'")
                results['results'].append({
                    'image': img_path.name,
                    'text': recognized_text
                })
            else:
                print("(failed)")
                results['results'].append({
                    'image': img_path.name,
                    'text': None
                })
        
        return results
    
    def test_all_models(self, download: bool = True) -> List[dict]:
        """Test all available model variants."""
        print("="*60)
        print("Captcha OCR Model Tester")
        print("="*60)
        print(f"Test images directory: {self.test_images_dir}")
        print(f"Models directory: {self.models_dir}")
        print()
        
        all_results = []
        
        for model_filename in self.model_variants:
            result = self.test_model(model_filename, download=download)
            all_results.append(result)
        
        # Summary
        print(f"\n{'='*60}")
        print("SUMMARY")
        print(f"{'='*60}")
        
        successful_models = [r for r in all_results if r.get('success')]
        print(f"\nSuccessfully tested: {len(successful_models)}/{len(all_results)} models")
        
        for result in all_results:
            if result.get('success'):
                print(f"\n{result['model']}:")
                for img_result in result['results']:
                    status = "✓" if img_result['text'] else "✗"
                    print(f"  {status} {img_result['image']}: {img_result['text'] or 'failed'}")
        
        return all_results


def main():
    """Main function."""
    import argparse
    
    parser = argparse.ArgumentParser(description='Test Hugging Face captcha OCR models')
    parser.add_argument('--test-dir', default='TestCaptchas',
                       help='Directory containing test captcha images (default: TestCaptchas)')
    parser.add_argument('--models-dir', default='captcha_models',
                       help='Directory containing or to store models (default: captcha_models)')
    parser.add_argument('--no-download', action='store_true',
                       help='Skip downloading models, only test locally available ones')
    parser.add_argument('--model', type=str,
                       help='Test only a specific model file (e.g., model_v1.onnx)')
    parser.add_argument('--debug', action='store_true',
                       help='Enable debug output showing raw model predictions')
    
    args = parser.parse_args()
    
    tester = CaptchaOcrTester(test_images_dir=args.test_dir, models_dir=args.models_dir)
    tester.debug_mode = args.debug
    
    if args.model:
        # Test single model
        result = tester.test_model(args.model, download=not args.no_download)
        if result.get('success'):
            print("\n✓ Test completed successfully")
        else:
            print(f"\n✗ Test failed: {result.get('error', 'Unknown error')}")
            sys.exit(1)
    else:
        # Test all models
        results = tester.test_all_models(download=not args.no_download)
        successful = sum(1 for r in results if r.get('success'))
        if successful == 0:
            print("\n✗ No models were successfully tested")
            sys.exit(1)


if __name__ == "__main__":
    main()

