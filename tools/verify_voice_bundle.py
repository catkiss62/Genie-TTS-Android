#!/usr/bin/env python3
"""Verify one private Android voice bundle before APK packaging.

This intentionally does more than open the ZIP: it checks every declared resource,
loads all four ONNX sessions with their external weights, and completes one
Encoder -> autoregressive Decoder -> VITS inference.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort


DTYPES = {"float32": np.float32, "int64": np.int64}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_tensor(root: Path, spec: dict) -> np.ndarray:
    return np.fromfile(root / spec["file"], dtype=DTYPES[spec["dtype"]]).reshape(spec["shape"])


def verify(root: Path) -> None:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    integrity = manifest.get("asset_integrity")
    if not integrity:
        raise RuntimeError(f"{root}: manifest 缺少 asset_integrity")
    for relative in manifest["asset_files"]:
        if relative == "manifest.json":
            continue
        path = root / relative
        expected = integrity.get(relative)
        if expected is None:
            raise RuntimeError(f"{root}: 缺少完整性记录 {relative}")
        if not path.is_file() or path.stat().st_size != expected["bytes"]:
            actual = path.stat().st_size if path.exists() else -1
            raise RuntimeError(f"{root}: 文件大小错误 {relative}: {actual} / {expected['bytes']}")
        if sha256(path) != expected["sha256"]:
            raise RuntimeError(f"{root}: SHA-256 错误 {relative}")

    case = manifest["cases"][0]
    target = manifest.get("presets", [None])[0]
    specs = [*manifest.get("shared_tensors", []), *case["tensors"]]
    if target is not None:
        specs.extend(target["tensors"])
    tensors = {spec["name"]: read_tensor(root, spec) for spec in specs}
    models = manifest["models"]
    sessions = {
        name: ort.InferenceSession(str(root / relative), providers=["CPUExecutionProvider"])
        for name, relative in models.items()
    }

    encoder_inputs = {name: tensors[name] for name in manifest["encoder_input_names"]}
    x, prompts = sessions["encoder"].run(None, encoder_inputs)
    y, y_emb, *cache = sessions["first_decoder"].run(None, {"x": x, "prompts": prompts})
    for index in range(500):
        y, y_emb, stop_condition, *cache = sessions["stage_decoder"].run(
            None,
            dict(zip(manifest["stage_input_names"], [y, y_emb, *cache])),
        )
        if bool(np.asarray(stop_condition).any()):
            break
    else:
        raise RuntimeError(f"{root}: Decoder 500 步内未停止")

    y[0, -1] = 0
    requested = y.size if index == 0 else index
    semantic = y.reshape(-1)[-max(1, min(requested, y.size)):]
    invalid = np.flatnonzero(semantic >= 1024)
    if invalid.size:
        semantic = semantic[: max(1, int(invalid[0]))]
    semantic = semantic.reshape(1, 1, -1)
    audio = sessions["vocoder"].run(None, {
        name: semantic if name == "pred_semantic" else tensors[name]
        for name in manifest["vocoder_input_names"]
    })[0]
    if audio.size == 0 or not np.isfinite(audio).all():
        raise RuntimeError(f"{root}: VITS 输出无效")
    print(
        f"{root.name}: PASS · {index + 1} decoder steps · "
        f"{audio.size} samples · {audio.size / manifest['sample_rate']:.2f}s"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundles", type=Path, nargs="+")
    args = parser.parse_args()
    for bundle in args.bundles:
        verify(bundle.resolve())


if __name__ == "__main__":
    main()
