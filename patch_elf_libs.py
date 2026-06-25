#!/usr/bin/env python3
import os

LIBS_DIR = "libs/lib/arm64-v8a"
SO_IMPL_PATH = os.path.join(LIBS_DIR, "libllama-server-impl.so")

def patch_binary():
    print("[*] Renaming OpenSSL versioned libraries...")
    ssl3_path = os.path.join(LIBS_DIR, "libssl.so.3")
    ssl_new_path = os.path.join(LIBS_DIR, "libssl.so")
    if os.path.exists(ssl3_path):
        if os.path.exists(ssl_new_path):
            os.remove(ssl_new_path)
        os.rename(ssl3_path, ssl_new_path)
        print(f"    Renamed libssl.so.3 -> libssl.so")
    
    crypto3_path = os.path.join(LIBS_DIR, "libcrypto.so.3")
    crypto_new_path = os.path.join(LIBS_DIR, "libcrypto.so")
    if os.path.exists(crypto3_path):
        if os.path.exists(crypto_new_path):
            os.remove(crypto_new_path)
        os.rename(crypto3_path, crypto_new_path)
        print(f"    Renamed libcrypto.so.3 -> libcrypto.so")

    print("[*] Patching libllama-server-impl.so dynamic linkage...")
    if not os.path.exists(SO_IMPL_PATH):
        print(f"[✗] Error: {SO_IMPL_PATH} not found!")
        return

    with open(SO_IMPL_PATH, "rb") as f:
        data = f.read()

    # Replacements (must maintain exact string length using null bytes padding)
    patched_data = data.replace(b"libssl.so.3", b"libssl.so\x00\x00")
    patched_data = patched_data.replace(b"libcrypto.so.3", b"libcrypto.so\x00\x00")

    with open(SO_IMPL_PATH, "wb") as f:
        f.write(patched_data)
        
    print("[✓] Patching complete! Dynamic links updated successfully.")

if __name__ == "__main__":
    patch_binary()
