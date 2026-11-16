#!/usr/bin/env python3
"""Inspect native ELF alignment; APKs also check uncompressed ZIP offsets.

This does not replace a device test. AAB ZIP offsets are not delivery APK offsets.
"""
import argparse
import json
import struct
import zipfile
from pathlib import Path


def verify(path):
    records = []
    with zipfile.ZipFile(path) as bundle, path.open('rb') as stream:
        for entry in bundle.infolist():
            if not entry.filename.endswith('.so'):
                continue
            data = bundle.read(entry)
            if data[:4] != b'\x7fELF':
                raise ValueError(f'Not an ELF library: {entry.filename}')
            endian = '<' if data[5] == 1 else '>'
            bits = 64 if data[4] == 2 else 32
            if bits == 64:
                offset = struct.unpack_from(endian + 'Q', data, 32)[0]
                size, count = struct.unpack_from(endian + 'HH', data, 54)
                align_offset, align_type = 48, 'Q'
            else:
                offset = struct.unpack_from(endian + 'I', data, 28)[0]
                size, count = struct.unpack_from(endian + 'HH', data, 42)
                align_offset, align_type = 28, 'I'
            loads = []
            for index in range(count):
                start = offset + index * size
                if struct.unpack_from(endian + 'I', data, start)[0] == 1:
                    loads.append(struct.unpack_from(endian + align_type, data, start + align_offset)[0])
            if not loads:
                raise ValueError(f'No ELF LOAD segments: {entry.filename}')
            stream.seek(entry.header_offset + 26)
            name_length, extra_length = struct.unpack('<HH', stream.read(4))
            data_offset = entry.header_offset + 30 + name_length + extra_length
            elf_ok = bits != 64 or min(loads) >= 16384
            check_zip = path.suffix == '.apk' and entry.compress_type == zipfile.ZIP_STORED
            zip_ok = not check_zip or data_offset % 16384 == 0
            records.append({
                'library': entry.filename, 'bits': bits,
                'minimum_load_alignment': min(loads), 'elf_16kb_ok': elf_ok,
                'apk_zip_offset_checked': check_zip, 'apk_zip_16kb_ok': zip_ok,
            })
    return {'artifact': path.name, 'native_libraries': records,
            'pass': all(r['elf_16kb_ok'] and r['apk_zip_16kb_ok'] for r in records),
            'device_16kb_test': 'NOT_RUN'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    args = parser.parse_args()
    result = verify(args.artifact)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['pass'] else 1)
