#!/usr/bin/env python3
"""Add legacy-zero and verified Chinese-RoBERTa feature modes to v0.3 assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path

import numpy as np


def write_tensor(root: Path, relative: str, name: str, array: np.ndarray) -> dict:
    array = np.ascontiguousarray(array)
    if array.dtype == np.float64:
        array = array.astype(np.float32)
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    array.tofile(path)
    dtype = {np.dtype("float32"): "float32", np.dtype("int64"): "int64"}[array.dtype]
    return {"name": name, "file": relative, "dtype": dtype, "shape": list(array.shape)}


def read_tensor(root: Path, spec: dict) -> np.ndarray:
    dtype = {"float32": np.float32, "int64": np.int64}[spec["dtype"]]
    return np.fromfile(root / spec["file"], dtype=dtype).reshape(spec["shape"])


def bert_stats(array: np.ndarray) -> tuple[int, int, str]:
    return int(np.count_nonzero(array)), int(array.size), hashlib.sha256(array.tobytes()).hexdigest()[:16]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--genie-data", type=Path, required=True)
    args = parser.parse_args()
    root = args.assets.resolve()
    os.environ["GENIE_DATA_DIR"] = str(args.genie_data.resolve())

    from genie_tts.GetPhonesAndBert import get_phones_and_bert
    from genie_tts.G2P.Chinese.ChineseG2P import chinese_to_phones

    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    legacy_target = manifest["shared_tensors"]
    legacy_target_bert_spec = next(item for item in legacy_target if item["name"] == "text_bert")
    legacy_target_bert = read_tensor(root, legacy_target_bert_spec)

    target_text = "。" + manifest["cases"][0]["text"]
    target_seq, target_bert = get_phones_and_bert(target_text, language="Chinese")
    clean, phones, phone_ids, word2ph = chinese_to_phones(target_text)
    tone_parts = []
    for index, char in enumerate(clean):
        if char == "样":
            start = sum(word2ph[:index])
            count = word2ph[index]
            tone_parts.append(
                f"样={'+'.join(phones[start:start + count])} / IDs {phone_ids[start:start + count]}"
            )
    tone_diagnostic = "；".join(tone_parts) or "目标文本不含‘样’"

    legacy_seq_spec = next(item for item in legacy_target if item["name"] == "text_seq")
    legacy_seq = read_tensor(root, legacy_seq_spec)
    if not np.array_equal(legacy_seq, target_seq):
        raise RuntimeError("新版中文 G2P 与旧版目标音素不一致，拒绝混入 RoBERTa 对照")

    roberta_target = [
        write_tensor(root, "tensors/roberta/target_text_seq.tensor", "text_seq", target_seq),
        write_tensor(root, "tensors/roberta/target_text_bert.tensor", "text_bert", target_bert),
    ]
    legacy_nz, legacy_total, legacy_hash = bert_stats(legacy_target_bert)
    roberta_nz, roberta_total, roberta_hash = bert_stats(target_bert)

    for case in manifest["cases"]:
        legacy_features = [item for item in case["tensors"] if item["name"] in {"ref_seq", "ref_bert"}]
        case["tensors"] = [item for item in case["tensors"] if item["name"] not in {"ref_seq", "ref_bert"}]
        ref_seq, ref_bert = get_phones_and_bert(case["reference_text"], language="Chinese")
        roberta_features = [
            write_tensor(root, f"tensors/roberta/{case['id']}_ref_seq.tensor", "ref_seq", ref_seq),
            write_tensor(root, f"tensors/roberta/{case['id']}_ref_bert.tensor", "ref_bert", ref_bert),
        ]
        case["feature_tensors"] = {
            "legacy_zero": legacy_features,
            "roberta_verified": roberta_features,
        }

    manifest["version"] = "genie-tts-v2.0.2-tiandou-v2-tone-v0.3.1"
    manifest["shared_tensors"] = []
    manifest["feature_modes"] = [
        {
            "id": "legacy_zero",
            "title": "旧版全零特征（对照）",
            "description": f"Chinese BERT 全零：{legacy_nz}/{legacy_total}，SHA {legacy_hash}",
            "tensors": legacy_target,
            "bert_nonzero": legacy_nz,
            "bert_elements": legacy_total,
            "tone_diagnostic": tone_diagnostic,
        },
        {
            "id": "roberta_verified",
            "title": "完整 Chinese RoBERTa（推荐）",
            "description": f"Chinese BERT 非零：{roberta_nz}/{roberta_total}，SHA {roberta_hash}",
            "tensors": roberta_target,
            "bert_nonzero": roberta_nz,
            "bert_elements": roberta_total,
            "tone_diagnostic": tone_diagnostic,
        },
    ]
    manifest["asset_files"] = sorted(
        str(path.relative_to(root)).replace(os.sep, "/")
        for path in root.rglob("*")
        if path.is_file() and path.name != "manifest.json"
    )
    manifest["asset_files"].append("manifest.json")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(tone_diagnostic)
    print(manifest["feature_modes"][0]["description"])
    print(manifest["feature_modes"][1]["description"])


if __name__ == "__main__":
    main()
