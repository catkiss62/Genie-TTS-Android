#!/usr/bin/env python3
"""Prepare a fixed-input Genie-TTS v2.0.2 Android benchmark bundle."""

from __future__ import annotations

import argparse
import gc
import json
import os
from pathlib import Path

import numpy as np


CASES = [
    ("short", "短句", "你好，很高兴见到你。"),
    ("medium", "中句", "今天的天气很好，我们一起出去走走吧。"),
    ("long", "长句", "如果你愿意，我可以把刚才发生的事情慢慢说给你听，然后我们再决定接下来做什么。"),
]


def download(cache: Path) -> None:
    from huggingface_hub import snapshot_download
    cache.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id="High-Logic/Genie",
        repo_type="model",
        allow_patterns=["GenieData/*", "CharacterModels/v2ProPlus/feibi/*"],
        local_dir=cache,
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


def fixtures(cache: Path, output: Path) -> None:
    os.chdir(cache)
    os.environ["GENIE_DATA_DIR"] = str((cache / "GenieData").resolve())

    import genie_tts as genie
    from genie_tts.GetPhonesAndBert import get_phones_and_bert
    from genie_tts.ModelManager import model_manager
    from genie_tts.Utils.Shared import context

    genie.load_predefined_character("feibi")
    model = model_manager.get("feibi")
    if model is None or model.PROMPT_ENCODER is None:
        raise RuntimeError("The bundled Feibi v2ProPlus model was not loaded")
    prompt = context.current_prompt_audio
    prompt.update_global_emb(model.PROMPT_ENCODER)

    shared = [
        write_tensor(output, "tensors/ref_seq.tensor", "ref_seq", prompt.phonemes_seq),
        write_tensor(output, "tensors/ref_bert.tensor", "ref_bert", prompt.text_bert),
        write_tensor(output, "tensors/ssl_content.tensor", "ssl_content", prompt.ssl_content),
        write_tensor(output, "tensors/ge.tensor", "ge", prompt.global_emb),
        write_tensor(output, "tensors/ge_advanced.tensor", "ge_advanced", prompt.global_emb_advanced),
    ]

    cases = []
    for case_id, title, text in CASES:
        text_seq, text_bert = get_phones_and_bert("。" + text, language="Chinese")
        cases.append({
            "id": case_id,
            "title": title,
            "text": text,
            "tensors": [
                write_tensor(output, f"tensors/{case_id}_text_seq.tensor", "text_seq", text_seq),
                write_tensor(output, f"tensors/{case_id}_text_bert.tensor", "text_bert", text_bert),
            ],
        })

    metadata = {
        "encoder_input_names": [item.name for item in model.T2S_ENCODER.get_inputs()],
        "first_stage_input_names": [item.name for item in model.T2S_FIRST_STAGE_DECODER.get_inputs()],
        "stage_input_names": [item.name for item in model.T2S_STAGE_DECODER.get_inputs()],
        "vocoder_input_names": [item.name for item in model.VITS.get_inputs()],
        "shared_tensors": shared,
        "cases": cases,
    }
    (output / "fixture_metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")


def inject_fp16_weights(model_path: Path, weights_path: Path):
    import onnx
    model = onnx.load(model_path, load_external_data=False)
    fp32_bytes = np.fromfile(weights_path, dtype=np.float16).astype(np.float32).tobytes()
    for tensor in model.graph.initializer:
        if tensor.data_location != onnx.TensorProto.EXTERNAL:
            continue
        entries = {entry.key: entry.value for entry in tensor.external_data}
        offset = int(entries.get("offset", 0))
        length = int(entries.get("length", 0))
        if offset + length > len(fp32_bytes):
            raise ValueError(f"External tensor {tensor.name} exceeds {weights_path.name}")
        tensor.raw_data = fp32_bytes[offset:offset + length]
        del tensor.external_data[:]
        tensor.data_location = onnx.TensorProto.DEFAULT
    return model


def models(cache: Path, output: Path) -> None:
    import onnx
    source = cache / "CharacterModels/v2ProPlus/feibi/tts_models"
    target = output / "models"
    target.mkdir(parents=True, exist_ok=True)

    encoder = onnx.load(source / "t2s_encoder_fp32.onnx", load_external_data=True)
    onnx.save_model(encoder, target / "t2s_encoder.onnx")
    del encoder
    gc.collect()

    conversions = [
        ("t2s_first_stage_decoder_fp32.onnx", "t2s_shared_fp16.bin", "t2s_first_stage_decoder.onnx"),
        ("t2s_stage_decoder_fp32.onnx", "t2s_shared_fp16.bin", "t2s_stage_decoder.onnx"),
        ("vits_fp32.onnx", "vits_fp16.bin", "vits.onnx"),
    ]
    for model_name, weight_name, output_name in conversions:
        model = inject_fp16_weights(source / model_name, source / weight_name)
        onnx.save_model(model, target / output_name)
        del model
        gc.collect()


def finish(output: Path) -> None:
    metadata_path = output / "fixture_metadata.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata_path.unlink()
    asset_files = sorted(
        str(path.relative_to(output)).replace(os.sep, "/")
        for path in output.rglob("*") if path.is_file()
    )
    manifest = {
        "version": "genie-tts-v2.0.2-feibi-v2pp",
        "character": "feibi",
        "sample_rate": 32000,
        "models": {
            "encoder": "models/t2s_encoder.onnx",
            "first_decoder": "models/t2s_first_stage_decoder.onnx",
            "stage_decoder": "models/t2s_stage_decoder.onnx",
            "vocoder": "models/vits.onnx",
        },
        **metadata,
        "asset_files": asset_files,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["download", "fixtures", "models", "finish"])
    parser.add_argument("--cache", type=Path, default=Path("tools/.cache/genie"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/benchmark"))
    args = parser.parse_args()
    cache = args.cache.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.phase == "download":
        download(cache)
    elif args.phase == "fixtures":
        fixtures(cache, output)
    elif args.phase == "models":
        models(cache, output)
    else:
        finish(output)


if __name__ == "__main__":
    main()
