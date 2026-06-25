import os
import zipfile
import sys
import stat

if len(sys.argv) < 3:
    print("Usage: python extract_zip_with_symlinks.py <zip_path> <dest_dir>")
    sys.exit(1)

zip_path = sys.argv[1]
dest_dir = sys.argv[2]

print(f"Extracting {zip_path} to {dest_dir}...")

with zipfile.ZipFile(zip_path, "r") as zip_ref:
    for info in zip_ref.infolist():
        out_path = os.path.join(dest_dir, info.filename)
        
        if info.filename.endswith("/"):
            os.makedirs(out_path, exist_ok=True)
            continue
            
        parent_dir = os.path.dirname(out_path)
        os.makedirs(parent_dir, exist_ok=True)
        
        mode = info.external_attr >> 16
        is_symlink = stat.S_ISLNK(mode)
        
        if is_symlink:
            target = zip_ref.read(info).decode("utf-8").strip()
            if os.path.lexists(out_path):
                try:
                    os.remove(out_path)
                except OSError:
                    pass
            try:
                os.symlink(target, out_path)
            except Exception as e:
                print(f"Failed to create symlink {out_path} -> {target}: {e}")
        else:
            if os.path.lexists(out_path):
                try:
                    os.remove(out_path)
                except OSError:
                    pass
            try:
                with open(out_path, "wb") as f:
                    f.write(zip_ref.read(info))
                # Set permissions if valid
                if mode & 0o777:
                    os.chmod(out_path, mode & 0o777)
            except Exception as e:
                print(f"Failed to extract {out_path}: {e}")

print("Extraction completed successfully!")
