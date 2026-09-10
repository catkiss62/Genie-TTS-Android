#!/usr/bin/env python3
"""Replace reference candidates in a validated V2Pro Android voice bundle.

The large GPT/SoVITS graphs are copied byte-for-byte from ``--base-bundle``.
Only reference audio, Japanese phonemes, SSL features, VITS reference audio,
and the two V2Pro speaker-prompt tensors are regenerated. Raw recordings,
character checkpoints, and offline encoder models remain private.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
from pathlib import Path

import numpy as np
import onnxruntime
import soundfile

from prepare_mobile_v2_voice_assets import PRESETS, sha256, write_tensor


def clear_reference_assets(output: Path) -> None:
    references = output / "references"
    if references.exists():
        shutil.rmtree(references)
    tensors = output / "tensors"
    for path in tensors.iterdir():
        if path.name == "presets":
            continue
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()


def validate_reference(path: Path) -> None:
    info = soundfile.info(path)
    if info.format != "WAV" or info.subtype != "PCM_16":
        raise RuntimeError(f"参考音频必须是 16-bit PCM WAV：{path} · {info.format}/{info.subtype}")
    if info.channels != 1 or info.samplerate != 44100:
        raise RuntimeError(
            f"参考音频必须是 44.1 kHz 单声道：{path} · "
            f"{info.samplerate} Hz/{info.channels} ch"
        )
    duration = info.frames / info.samplerate
    if not 3.0 <= duration <= 10.0:
        raise RuntimeError(f"参考音频时长必须在 3–10 秒内：{path} · {duration:.3f}s")


def build(args: argparse.Namespace) -> None:
    base = args.base_bundle.resolve()
    output = args.output.resolve()
    if not (base / "manifest.json").is_file():
        raise RuntimeError(f"基础资源目录缺少 manifest.json：{base}")
    if output == base or base in output.parents or output in base.parents:
        raise RuntimeError("输出目录不能等于、包含或位于基础资源目录内。")
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(base, output)
    clear_reference_assets(output)

    os.environ["GENIE_DATA_DIR"] = str(args.genie_data.resolve())
    from genie_tts.Audio.ReferenceAudio import ReferenceAudio
    from genie_tts.ModelManager import load_session_with_fp16_conversion

    speaker_encoder = onnxruntime.InferenceSession(
        str(args.genie_data.resolve() / "speaker_encoder.onnx"),
        providers=["CPUExecutionProvider"],
    )
    prompt_model_dir = args.prompt_encoder_model_dir.resolve()
    prompt_encoder = load_session_with_fp16_conversion(
        str(prompt_model_dir / "prompt_encoder_fp32.onnx"),
        str(prompt_model_dir / "prompt_encoder_fp16.bin"),
        providers=["CPUExecutionProvider"],
    )

    references = json.loads(args.references_json.read_text(encoding="utf-8"))
    ids = [item["id"] for item in references]
    if len(ids) != len(set(ids)):
        raise RuntimeError("候选 id 不得重复。")

    cases = []
    for item in references:
        audio_path = args.references_dir.resolve() / item["audio"]
        validate_reference(audio_path)
        reference_language = item.get("language", "Japanese")
        prompt = ReferenceAudio(str(audio_path), item["transcript"], reference_language)

        audio_relative = f"references/{item['id']}.wav"
        audio_target = output / audio_relative
        audio_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(audio_path, audio_target)

        reference_tensors = [
            write_tensor(output, f"tensors/{item['id']}_ref_seq.tensor", "ref_seq", prompt.phonemes_seq),
            write_tensor(output, f"tensors/{item['id']}_ref_bert.tensor", "ref_bert", prompt.text_bert),
            write_tensor(output, f"tensors/{item['id']}_ssl_content.tensor", "ssl_content", prompt.ssl_content),
            write_tensor(output, f"tensors/{item['id']}_ref_audio.tensor", "ref_audio", prompt.audio_32k),
        ]
        sv_input = speaker_encoder.get_inputs()[0].name
        sv_emb = speaker_encoder.run(None, {sv_input: prompt.audio_16k})[0]
        ge, ge_advanced = prompt_encoder.run(None, {
            "ref_audio": prompt.audio_32k,
            "sv_emb": sv_emb,
        })
        ge = np.asarray(ge, dtype=np.float32)
        ge_advanced = np.asarray(ge_advanced, dtype=np.float32)
        if ge.shape != (1, 1024, 1) or ge_advanced.shape != (1, 512, 1):
            raise RuntimeError(
                f"V2Pro 提示维度异常：{item['id']} · ge={ge.shape}, "
                f"ge_advanced={ge_advanced.shape}"
            )
        reference_tensors.extend([
            write_tensor(output, f"tensors/{item['id']}_ge.tensor", "ge", ge),
            write_tensor(
                output,
                f"tensors/{item['id']}_ge_advanced.tensor",
                "ge_advanced",
                ge_advanced,
            ),
        ])
        cases.append({
            "id": item["id"],
            "title": item["title"],
            "text": PRESETS[0][2],
            "reference_text": item["transcript"],
            "reference_language": reference_language,
            "reference_audio": audio_relative,
            "tensors": reference_tensors,
        })

    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["version"] = args.version
    manifest["character"] = args.character
    manifest["cases"] = cases
    manifest["asset_files"] = sorted(
        str(path.relative_to(output)).replace(os.sep, "/")
        for path in output.rglob("*")
        if path.is_file() and path.name != "manifest.json"
    ) + ["manifest.json"]
    manifest["asset_integrity"] = {
        relative: {
            "bytes": (output / relative).stat().st_size,
            "sha256": sha256(output / relative),
        }
        for relative in manifest["asset_files"]
        if relative != "manifest.json"
    }
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"voice={args.character}, candidates={len(cases)}, version={args.version}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-bundle", type=Path, required=True)
    parser.add_argument("--prompt-encoder-model-dir", type=Path, required=True)
    parser.add_argument("--genie-data", type=Path, required=True)
    parser.add_argument("--references-dir", type=Path, required=True)
    parser.add_argument("--references-json", type=Path, required=True)
    parser.add_argument("--character", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())


if __name__ == "__main__":
    main()
