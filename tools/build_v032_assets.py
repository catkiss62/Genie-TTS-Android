#!/usr/bin/env python3
"""Build the v0.3.3 final audition bundle and Android Chinese frontend data."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pickle
import shutil
from pathlib import Path

import numpy as np


PRESETS = [
    ("neutral", "普通闲聊", "我刚才看到一件很有意思的事，等一下慢慢讲给你听。"),
    ("question", "疑问", "你今天过得怎么样？有没有什么特别想和我说的？"),
    ("comfort", "安慰", "没关系，你不用急着回答，我会在这里陪着你。"),
    ("lively", "活泼", "快看，快看，我发现了一个超级有趣的东西！"),
]

KEPT_CASE_IDS = {"ref01", "ref02", "ref04", "ref06", "ref07"}


def write_tensor(root: Path, relative: str, name: str, array: np.ndarray) -> dict:
    array = np.ascontiguousarray(array)
    if array.dtype == np.float64:
        array = array.astype(np.float32)
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    array.tofile(path)
    dtype = {np.dtype("float32"): "float32", np.dtype("int64"): "int64"}[array.dtype]
    return {"name": name, "file": relative, "dtype": dtype, "shape": list(array.shape)}


def write_tsv(path: Path, rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write("\t".join(str(value) for value in row) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def remove_unreferenced_generated_assets(root: Path, manifest: dict) -> None:
    referenced = {"manifest.json"}
    referenced.update(manifest["models"].values())
    referenced.update(manifest["frontend"][key] for key in (
        "roberta", "vocab", "char_phones", "phrase_phones", "punctuation_ids"
    ))
    for preset in manifest["presets"]:
        referenced.update(item["file"] for item in preset["tensors"])
    for case in manifest["cases"]:
        referenced.update(item["file"] for item in case["tensors"])
        if case.get("reference_audio"):
            referenced.add(case["reference_audio"])
    for folder_name in ("frontend", "references", "tensors"):
        folder = root / folder_name
        for path in folder.rglob("*"):
            if path.is_file() and str(path.relative_to(root)).replace(os.sep, "/") not in referenced:
                path.unlink()


def make_frontend_data(root: Path, genie_data: Path, g2pm_dir: Path, quantized_roberta: Path) -> dict:
    from genie_tts.G2P.Chinese.ChineseG2P import chinese_to_phones
    from genie_tts.G2P.SymbolsV2 import symbol_to_id_v2

    frontend = root / "frontend"
    frontend.mkdir(parents=True, exist_ok=True)

    with (g2pm_dir / "digest_cedict.pkl").open("rb") as stream:
        cedict = pickle.load(stream)
    with (genie_data / "G2P/ChineseG2P/polyphonic.pickle").open("rb") as stream:
        phrases = pickle.load(stream)
    tokenizer = json.loads((genie_data / "RoBERTa/tokenizer.json").read_text(encoding="utf-8"))
    vocab = tokenizer["model"]["vocab"]

    char_rows = []
    for char, values in sorted(cedict.items()):
        if len(char) == 1 and values:
            char_rows.append((char, values[0]))

    phrase_rows = []
    max_phrase_chars = 1
    for phrase, values in sorted(phrases.items()):
        if not phrase or not all("\u4e00" <= char <= "\u9fff" for char in phrase):
            continue
        if not isinstance(values, (list, tuple)) or len(values) != len(phrase):
            continue
        phrase_rows.append((phrase, " ".join(values)))
        max_phrase_chars = max(max_phrase_chars, len(phrase))

    punctuation_rows = []
    for token in ["!", "?", "…", ",", ".", "-"]:
        punctuation_rows.append((token, symbol_to_id_v2[token]))

    write_tsv(frontend / "punctuation_ids.tsv", punctuation_rows)
    write_tsv(frontend / "bert_vocab.tsv", sorted(vocab.items(), key=lambda item: item[1]))

    def grouped_phone_ids(text: str):
        try:
            clean, _, ids, word2ph = chinese_to_phones(text)
        except (IndexError, KeyError, ValueError):
            return None
        if clean != text or len(word2ph) != len(text) or sum(word2ph) != len(ids):
            return None
        grouped = []
        offset = 0
        for count in word2ph:
            grouped.append(",".join(str(value) for value in ids[offset:offset + count]))
            offset += count
        return "|".join(grouped)

    # Direct phone fixtures preserve Genie's word segmentation, neutral tones and tone sandhi
    # for common words. Android uses the pinyin tables only as a fallback for unseen spans.
    direct_char_rows = []
    for char, _ in char_rows:
        grouped = grouped_phone_ids(char)
        if grouped:
            direct_char_rows.append((char, grouped))
    # Add common Jieba words as well as the polyphonic correction dictionary. The
    # frequency floor keeps the mobile lexicon compact while covering everyday chat.
    import jieba_fast
    direct_words = {phrase for phrase, _ in phrase_rows}
    with open(jieba_fast.get_dict_file().name, encoding="utf-8") as stream:
        for line in stream:
            parts = line.rstrip().split()
            if len(parts) < 2:
                continue
            word, frequency = parts[0], int(parts[1])
            if frequency >= 50 and 2 <= len(word) <= 9 and all("\u4e00" <= char <= "\u9fff" for char in word):
                direct_words.add(word)
                max_phrase_chars = max(max_phrase_chars, len(word))
    direct_phrase_rows = []
    for index, phrase in enumerate(sorted(direct_words)):
        grouped = grouped_phone_ids(phrase)
        if grouped:
            direct_phrase_rows.append((phrase, grouped))
        if index and index % 10000 == 0:
            print(f"mobile lexicon: {index}/{len(direct_words)}")
    write_tsv(frontend / "char_phones.tsv", direct_char_rows)
    write_tsv(frontend / "phrase_phones.tsv", direct_phrase_rows)
    roberta_sha256 = sha256_file(quantized_roberta)

    return {
        "roberta": "frontend/chinese_roberta_int8.onnx",
        "vocab": "frontend/bert_vocab.tsv",
        "char_phones": "frontend/char_phones.tsv",
        "phrase_phones": "frontend/phrase_phones.tsv",
        "punctuation_ids": "frontend/punctuation_ids.tsv",
        "max_phrase_chars": max_phrase_chars,
        "bert_dim": 1024,
        "quantization": "dynamic-int8-per-channel",
        "roberta_bytes": quantized_roberta.stat().st_size,
        "roberta_sha256": roberta_sha256,
        "roberta_external": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--genie-data", type=Path, required=True)
    parser.add_argument("--g2pm-dir", type=Path, required=True)
    parser.add_argument("--quantized-roberta", type=Path, required=True)
    parser.add_argument("--keep-all-cases", action="store_true")
    parser.add_argument("--no-gain-calibration", action="store_true")
    parser.add_argument(
        "--bundle-version",
        default="genie-tts-v2.0.2-tiandou-v2-final-v0.3.3",
    )
    args = parser.parse_args()

    root = args.assets.resolve()
    genie_data = args.genie_data.resolve()
    os.environ["GENIE_DATA_DIR"] = str(genie_data)
    from genie_tts.GetPhonesAndBert import get_phones_and_bert

    manifest_path = root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    verified = next(item for item in manifest["feature_modes"] if item["id"] == "roberta_verified")

    presets = []
    for preset_id, title, text in PRESETS:
        seq, bert = get_phones_and_bert("。" + text, language="Chinese")
        nonzero = int(np.count_nonzero(bert))
        if nonzero != int(bert.size):
            raise RuntimeError(f"{title} 的 RoBERTa 特征异常：{nonzero}/{bert.size}")
        presets.append({
            "id": preset_id,
            "title": title,
            "text": text,
            "tensors": [
                write_tensor(root, f"tensors/presets/{preset_id}_text_seq.tensor", "text_seq", seq),
                write_tensor(root, f"tensors/presets/{preset_id}_text_bert.tensor", "text_bert", bert),
            ],
            "bert_nonzero": nonzero,
            "bert_elements": int(bert.size),
        })

    kept_cases = []
    for case in manifest["cases"]:
        if not args.keep_all_cases and case["id"] not in KEPT_CASE_IDS:
            continue
        feature_tensors = case.get("feature_tensors", {})
        if "roberta_verified" in feature_tensors:
            case["tensors"] = case["tensors"] + feature_tensors["roberta_verified"]
        case.pop("feature_tensors", None)
        case["playback_gain_db"] = (
            0.0 if args.no_gain_calibration else (4.0 if case["id"] == "ref02" else 0.0)
        )
        kept_cases.append(case)

    manifest["version"] = args.bundle_version
    manifest["cases"] = kept_cases
    manifest["presets"] = presets
    manifest["frontend"] = make_frontend_data(
        root, genie_data, args.g2pm_dir.resolve(), args.quantized_roberta.resolve()
    )
    manifest["shared_tensors"] = []
    manifest["feature_modes"] = [{
        "id": "roberta_verified",
        "title": "完整 Chinese RoBERTa",
        "description": "预设使用 FP32 精确特征；自由输入使用本地 INT8 RoBERTa。",
        "tensors": [],
        "bert_nonzero": verified["bert_nonzero"],
        "bert_elements": verified["bert_elements"],
        "tone_diagnostic": verified["tone_diagnostic"],
    }]
    remove_unreferenced_generated_assets(root, manifest)
    manifest["asset_files"] = sorted(
        str(path.relative_to(root)).replace(os.sep, "/")
        for path in root.rglob("*")
        if path.is_file() and path.name != "manifest.json"
    )
    manifest["asset_files"].append("manifest.json")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"presets={len(presets)}, candidates={len(kept_cases)}")
    print(json.dumps(manifest["frontend"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
