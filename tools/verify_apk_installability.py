#!/usr/bin/env python3
"""Reject APK ZIP layouts that Android's package installer will not accept."""

from __future__ import annotations

import argparse
import struct
import zipfile
from pathlib import Path


REQUIRED_ENTRIES = ("AndroidManifest.xml", "resources.arsc", "classes.dex")


def verify(apk_path: Path) -> None:
    with zipfile.ZipFile(apk_path) as apk:
        entries = apk.infolist()
        names = [entry.filename for entry in entries]

        duplicates = sorted({name for name in names if names.count(name) > 1})
        if duplicates:
            raise ValueError(f"duplicate APK entries: {duplicates}")

        missing = [name for name in REQUIRED_ENTRIES if name not in names]
        if missing:
            raise ValueError(f"missing required APK entries: {missing}")

        resources = apk.getinfo("resources.arsc")
        if resources.compress_type != zipfile.ZIP_STORED:
            raise ValueError(
                "resources.arsc is compressed; Android 11+ rejects APKs targeting API 30+ "
                "with INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED"
            )

        temp_assets = sorted(
            name
            for name in names
            if name.startswith("assets/") and Path(name).name.startswith(".")
        )
        if temp_assets:
            raise ValueError(f"temporary hidden assets leaked into APK: {temp_assets}")

        native_entries = [entry for entry in entries if entry.filename.endswith(".so")]
        for entry in native_entries:
            if not entry.filename.startswith("lib/arm64-v8a/"):
                raise ValueError(f"unexpected native ABI entry: {entry.filename}")
            header = apk.read(entry)[:20]
            if len(header) < 20 or header[:4] != b"\x7fELF":
                raise ValueError(f"invalid ELF header: {entry.filename}")
            endian = "<" if header[5] == 1 else ">"
            machine = struct.unpack_from(f"{endian}H", header, 18)[0]
            if machine != 183:
                raise ValueError(f"native library is not AArch64: {entry.filename}")

    print(
        f"APK installability invariants passed: {apk_path} "
        f"({len(entries)} entries, resources.arsc stored, arm64 native libraries valid)"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    verify(args.apk)


if __name__ == "__main__":
    main()
