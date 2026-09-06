#!/usr/bin/env python3
"""Build a fixed-text Android audition bundle from a converted Genie V2 voice."""

from __future__ import annotations

import argparse
import json
import os
import shutil
from pathlib import Path

import numpy as np
import onnx


TARGET_TEXT = "你好呀，今天过得怎么样？如果有什么想说的，我会认真听你慢慢讲。"


def require_chinese_bert(label: str, array: np.ndarray) -> None:
    """Reject the silent zero fallback used when Chinese RoBERTa is unavailable."""
    nonzero = int(np.count_nonzero(array))
    if nonzero == 0:
        raise RuntimeError(
            f"{label} 的 Chinese RoBERTa 特征全为 0。"
            "请确认 GenieData/Chinese-RoBERTa-wwm-ext-large 的 tokenizer 与 model.onnx 可用；"
            "为避免把缺失的韵律特征静默打入 APK，已停止资源制作。"
        )


def write_tensor(output: Path, relative: str, name: str, array: np.ndarray) -> dict:
    array = np.ascontiguousarray(array)
    if array.dtype == np.float64:
        array = array.astype(np.float32)
    path = output / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    array.tofile(path)
    dtype = {np.dtype("float32"): "float32", np.dtype("int64"): "int64"}[array.dtype]
    return {"name": name, "file": relative, "dtype": dtype, "shape": list(array.shape)}


def copy_models(source: Path, output: Path) -> dict:
    target = output / "models"
    target.mkdir(parents=True, exist_ok=True)
    names = [
        "t2s_encoder_fp32.onnx",
        "t2s_encoder_fp32.bin",
        "t2s_first_stage_decoder_fp32.onnx",
        "t2s_stage_decoder_fp32.onnx",
        "vits_fp32.onnx",
    ]
    for name in names:
        shutil.copyfile(source / name, target / name)
    np.fromfile(source / "t2s_shared_fp16.bin", dtype=np.float16).astype(np.float32).tofile(
        target / "t2s_shared_fp32.bin"
    )
    np.fromfile(source / "vits_fp16.bin", dtype=np.float16).astype(np.float32).tofile(
        target / "vits_fp32.bin"
    )
    return {
        "encoder": "models/t2s_encoder_fp32.onnx",
        "first_decoder": "models/t2s_first_stage_decoder_fp32.onnx",
        "stage_decoder": "models/t2s_stage_decoder_fp32.onnx",
        "vocoder": "models/vits_fp32.onnx",
    }


def input_names(model_path: Path) -> list[str]:
    return [item.name for item in onnx.load(model_path, load_external_data=False).graph.input]


def build(args: argparse.Namespace) -> None:
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    genie_data = args.genie_data.resolve()
    # Genie V2 does not use the speaker encoder. Genie 2.0.2 nevertheless checks
    # for this path during package import, so an empty local placeholder is enough.
    speaker_encoder = genie_data / "speaker_encoder.onnx"
    if not speaker_encoder.exists():
        speaker_encoder.touch()
    os.environ["GENIE_DATA_DIR"] = str(genie_data)

    from genie_tts.Audio.ReferenceAudio import ReferenceAudio
    from genie_tts.GetPhonesAndBert import get_phones_and_bert

    refs = json.loads(args.references_json.read_text(encoding="utf-8"))
    target_seq, target_bert = get_phones_and_bert("。" + TARGET_TEXT, language="Chinese")
    require_chinese_bert("目标文本", target_bert)
    shared = [
        write_tensor(output, "tensors/target_text_seq.tensor", "text_seq", target_seq),
        write_tensor(output, "tensors/target_text_bert.tensor", "text_bert", target_bert),
    ]

    cases = []
    for item in refs:
        ref_path = args.references_dir / item["audio"]
        prompt = ReferenceAudio(str(ref_path), item["transcript"], "Chinese")
        require_chinese_bert(f'{item["title"]} 参考文本', prompt.text_bert)
        audio_target = output / "references" / f'{item["id"]}.wav'
        audio_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ref_path, audio_target)
        cases.append({
            "id": item["id"],
            "title": item["title"],
            "text": TARGET_TEXT,
            "reference_text": item["transcript"],
            "reference_audio": f'references/{item["id"]}.wav',
            "tensors": [
                write_tensor(output, f'tensors/{item["id"]}_ref_seq.tensor', "ref_seq", prompt.phonemes_seq),
                write_tensor(output, f'tensors/{item["id"]}_ref_bert.tensor', "ref_bert", prompt.text_bert),
                write_tensor(output, f'tensors/{item["id"]}_ssl_content.tensor', "ssl_content", prompt.ssl_content),
                write_tensor(output, f'tensors/{item["id"]}_ref_audio.tensor', "ref_audio", prompt.audio_32k),
            ],
        })

    models = copy_models(args.converted_model_dir.resolve(), output)
    manifest = {
        "version": "genie-tts-v2.0.2-tiandou-v2-audition-v0.3.0",
        "character": "tiandou",
        "sample_rate": 32000,
        "models": models,
        "shared_tensors": shared,
        "cases": cases,
        "encoder_input_names": input_names(output / models["encoder"]),
        "first_stage_input_names": input_names(output / models["first_decoder"]),
        "stage_input_names": input_names(output / models["stage_decoder"]),
        "vocoder_input_names": input_names(output / models["vocoder"]),
    }
    manifest["asset_files"] = sorted(
        str(path.relative_to(output)).replace(os.sep, "/")
        for path in output.rglob("*") if path.is_file()
    )
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--converted-model-dir", type=Path, required=True)
    parser.add_argument("--genie-data", type=Path, required=True)
    parser.add_argument("--references-dir", type=Path, required=True)
    parser.add_argument("--references-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/benchmark"))
    build(parser.parse_args())


if __name__ == "__main__":
    main()
