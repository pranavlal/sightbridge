#!/usr/bin/env python3
"""Check 64-bit ELF LOAD segments and stored APK libraries for 16 KB alignment.

Usage: python3 scripts/check_native_alignment.py path/to/app.apk
This is a static build gate; a 16 KB Android runtime test is still required.
"""
import argparse
import struct
import sys
import zipfile


def check(apk_path):
    failures = []
    count = 0
    with zipfile.ZipFile(apk_path) as apk, open(apk_path, "rb") as raw:
        for entry in apk.infolist():
            if not entry.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not entry.filename.endswith(".so"):
                continue
            count += 1
            data = apk.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                failures.append(f"{entry.filename}: expected little-endian ELF64")
                continue
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, number = struct.unpack_from("<HH", data, 54)
            for i in range(number):
                kind, _, file_offset, address, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, offset + i * size)
                if kind == 1 and (alignment < 16384 or file_offset % 16384 != address % 16384):
                    failures.append(f"{entry.filename}: LOAD segment {i} alignment={alignment}")
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                data_offset = entry.header_offset + 30 + name_size + extra_size
                if data_offset % 16384:
                    failures.append(f"{entry.filename}: APK data offset is not 16 KB aligned")
    print(f"Checked {count} 64-bit native libraries in {apk_path}")
    for failure in failures:
        print(f"FAIL: {failure}")
    if not count:
        print("No 64-bit native libraries found; verify the intended release ABIs.")
        return 1
    print("Static 16 KB alignment: " + ("FAIL" if failures else "PASS"))
    return bool(failures)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    sys.exit(check(parser.parse_args().apk))
