#!/usr/bin/env python3
"""Overlay CI-verified code entries onto a previously verified full APK."""

from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path


REQUIRED_ENTRIES = {"AndroidManifest.xml", "resources.arsc", "classes.dex"}
PRIVATE_ASSET_PREFIX = "assets/benchmark_jiuhu/"
EXPECTED_PRIVATE_ASSETS = 49


def copy_info(source: zipfile.ZipInfo) -> zipfile.ZipInfo:
    target = zipfile.ZipInfo(source.filename, source.date_time)
    target.compress_type = source.compress_type
    target.comment = source.comment
    target.extra = source.extra
    target.internal_attr = source.internal_attr
    target.external_attr = source.external_attr
    target.create_system = source.create_system
    return target


def checked_entries(apk: zipfile.ZipFile, label: str) -> dict[str, zipfile.ZipInfo]:
    entries = apk.infolist()
    by_name = {entry.filename: entry for entry in entries}
    if len(by_name) != len(entries):
        raise ValueError(f"{label} contains duplicate ZIP entries")
    missing = REQUIRED_ENTRIES.difference(by_name)
    if missing:
        raise ValueError(f"{label} is missing required entries: {sorted(missing)}")
    return by_name


def write_entry(
    source_apk: zipfile.ZipFile,
    source_info: zipfile.ZipInfo,
    output_apk: zipfile.ZipFile,
) -> None:
    with source_apk.open(source_info, "r") as source:
        with output_apk.open(copy_info(source_info), "w", force_zip64=True) as target:
            shutil.copyfileobj(source, target, length=1024 * 1024)


def repackage(base_path: Path, thin_path: Path, output_path: Path) -> None:
    with zipfile.ZipFile(base_path, "r") as base_apk:
        with zipfile.ZipFile(thin_path, "r") as thin_apk:
            base = checked_entries(base_apk, "base APK")
            thin = checked_entries(thin_apk, "thin APK")
            thin_overlay = {
                name: info for name, info in thin.items() if not name.startswith("META-INF/")
            }
            if any(name.startswith(PRIVATE_ASSET_PREFIX) for name in thin_overlay):
                raise ValueError("thin APK unexpectedly contains private Jiuhu assets")

            private_assets = [name for name in base if name.startswith(PRIVATE_ASSET_PREFIX)]
            if len(private_assets) != EXPECTED_PRIVATE_ASSETS:
                raise ValueError(
                    f"base APK has {len(private_assets)} Jiuhu assets; "
                    f"expected {EXPECTED_PRIVATE_ASSETS}"
                )

            output_path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(output_path, "w", allowZip64=True) as output_apk:
                for name, info in base.items():
                    if name.startswith("META-INF/") or name in thin_overlay:
                        continue
                    write_entry(base_apk, info, output_apk)
                for info in thin_overlay.values():
                    write_entry(thin_apk, info, output_apk)

    with zipfile.ZipFile(output_path, "r") as output_apk:
        output = checked_entries(output_apk, "repackaged APK")
        private_assets = [name for name in output if name.startswith(PRIVATE_ASSET_PREFIX)]
        if len(private_assets) != EXPECTED_PRIVATE_ASSETS:
            raise ValueError("repackaged APK did not preserve all Jiuhu assets")
        retired = [
            name for name in output
            if name.startswith("assets/benchmark/")
            or "tiandou" in name.lower()
            or "lenai" in name.lower()
            or "/mao" in name.lower()
        ]
        if retired:
            raise ValueError(f"retired voice assets found: {retired[:5]}")
        if any(name.startswith("META-INF/") for name in output):
            raise ValueError("old signing metadata survived repackaging")
        for name, source_info in thin_overlay.items():
            target_info = output[name]
            if target_info.CRC != source_info.CRC or target_info.file_size != source_info.file_size:
                raise ValueError(f"CI overlay mismatch: {name}")

    print(
        f"Repackaged {output_path}: {len(output)} entries, "
        f"{len(thin_overlay)} CI entries, {len(private_assets)} Jiuhu assets"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("base_apk", type=Path)
    parser.add_argument("thin_apk", type=Path)
    parser.add_argument("output_apk", type=Path)
    args = parser.parse_args()
    repackage(args.base_apk, args.thin_apk, args.output_apk)


if __name__ == "__main__":
    main()
