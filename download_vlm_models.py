#!/usr/bin/env python3
import os
import sys
import urllib.request
import time

MODELS = {
    "Qwen2-VL-2B-Instruct-Q4_K_M.gguf": "https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF/resolve/main/Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
    "mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf": "https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
    "moondream2-text-model-f16.gguf": "https://huggingface.co/moondream/moondream2-gguf/resolve/main/moondream2-text-model-f16.gguf",
    "moondream2-mmproj-f16.gguf": "https://huggingface.co/moondream/moondream2-gguf/resolve/main/moondream2-mmproj-f16.gguf"
}

DEST_DIR = "/sdcard/AstitvaModels"

def download_file(url, filepath):
    temp_filepath = filepath + ".tmp"
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36'
    }
    
    # Check if download is already complete
    if os.path.exists(filepath):
        print(f"[✓] {os.path.basename(filepath)} already exists. Skipping.")
        return

    # Check for resume support
    start_pos = 0
    mode = 'wb'
    if os.path.exists(temp_filepath):
        start_pos = os.path.getsize(temp_filepath)
        mode = 'ab'
        print(f"[*] Resuming download of {os.path.basename(filepath)} from {start_pos / (1024*1024):.2f} MB...")
    else:
        print(f"[*] Starting download of {os.path.basename(filepath)}...")

    req = urllib.request.Request(url, headers=headers)
    if start_pos > 0:
        req.add_header('Range', f'bytes={start_pos}-')

    try:
        with urllib.request.urlopen(req) as response:
            meta = response.info()
            content_length = meta.get('Content-Length')
            
            total_size = int(content_length) if content_length else 0
            if start_pos > 0:
                # Content-Length is the remaining size, add start_pos to get total size
                total_size += start_pos
                
            bytes_downloaded = start_pos
            
            last_print_time = time.time()
            with open(temp_filepath, mode) as f:
                while True:
                    buffer = response.read(8192 * 8)
                    if not buffer:
                        break
                    f.write(buffer)
                    bytes_downloaded += len(buffer)
                    
                    # Show progress every 1.5 seconds
                    current_time = time.time()
                    if current_time - last_print_time > 1.5:
                        percent = (bytes_downloaded / total_size) * 100 if total_size > 0 else 0
                        speed = len(buffer) / (current_time - last_print_time) / 1024 # KB/s
                        print(f"    Progress: {percent:.2f}% | {bytes_downloaded / (1024*1024):.1f}/{total_size / (1024*1024):.1f} MB | {speed:.1f} KB/s", end='\r')
                        last_print_time = current_time
                        
            print(f"\n[✓] Download of {os.path.basename(filepath)} complete.")
            os.rename(temp_filepath, filepath)
            
    except Exception as e:
        print(f"\n[✗] Error downloading {os.path.basename(filepath)}: {e}")
        if 'HTTP Error 416' in str(e):
            print("    Attempting to overwrite the corrupt temp file...")
            if os.path.exists(temp_filepath):
                os.remove(temp_filepath)
            download_file(url, filepath)

def main():
    if not os.path.exists(DEST_DIR):
        os.makedirs(DEST_DIR)
        
    print(f"==================================================")
    print(f"     Astitva OS - VLM Offline Model Downloader    ")
    print(f"==================================================")
    print(f"Target Directory: {DEST_DIR}\n")
    
    for filename, url in MODELS.items():
        filepath = os.path.join(DEST_DIR, filename)
        download_file(url, filepath)
        print("-" * 50)
        
    print("\n[🎉] All VLM Models downloaded successfully to SD card!")

if __name__ == "__main__":
    main()
