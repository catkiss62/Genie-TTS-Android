#!/usr/bin/env python3
"""Build an Android audition bundle for one converted Genie V2 or V2Pro voice.

Chinese references use the same character-tokenized INT8 RoBERTa path as ChineseFrontend.kt;
English and Japanese references use their native phonemes with zero BERT, matching Genie.
Original checkpoints and recordings stay private; only this reproducible builder is public.
V2Pro prompt/speaker encoders run only while packaging; Android receives per-reference
ge/ge_advanced tensors and keeps the same four-session runtime as V2.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path

import numpy as np
import onnx
import onnxruntime
from onnx import numpy_helper


PRESETS = [
    ("neutral", "普通闲聊", "我刚才看到一件很有意思的事，等一下慢慢讲给你听。"),
    ("question", "疑问", "你今天过得怎么样？有没有什么特别想和我说的？"),
    ("comfort", "安慰", "没关系，你不用急着回答，我会在这里陪着你。"),
    ("lively", "活泼", "快看，快看，我发现了一个超级有趣的东西！"),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_tensor(root: Path, relative: str, name: str, array: np.ndarray) -> dict:
    array = np.ascontiguousarray(array)
    if array.dtype == np.float64:
        array = array.astype(np.float32)
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    array.tofile(path)
    dtype = {np.dtype("float32"): "float32", np.dtype("int64"): "int64"}[array.dtype]
    return {"name": name, "file": relative, "dtype": dtype, "shape": list(array.shape)}


def copy_seed_presets(seed_root: Path, output: Path) -> tuple[list[dict], dict, list[dict]]:
    """Reuse target-text tensors from a validated voice bundle.

    Chinese target features are independent of the speaker checkpoint and reference audio. This
    lets a new Japanese-reference voice reuse the already verified Android Chinese frontend
    without needing the large RoBERTa model during private packaging.
    """
    manifest = json.loads((seed_root / "manifest.json").read_text(encoding="utf-8"))
    presets = manifest["presets"]
    for preset in presets:
        for tensor in preset["tensors"]:
            source = seed_root / tensor["file"]
            target = output / tensor["file"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    return presets, manifest["frontend"], manifest["feature_modes"]


def _external_length(tensor: onnx.TensorProto) -> int | None:
    values = {item.key: item.value for item in tensor.external_data}
    length = values.get("length")
    return int(length) if length is not None else None


def _adapt_external_matrix(
    model: onnx.ModelProto,
    tensor_name: str,
    fixed_dimension: int,
) -> int | None:
    """Make a two-dimensional template initializer match its converted external weight.

    Genie 2.0.2's V2 templates use the current 732-symbol frontend and a 704-bin
    reference spectrum. Older, still-valid GPT-SoVITS V2 checkpoints commonly use the
    original 322-symbol frontend and a 1025-bin spectrum. The upstream converter writes
    the correct checkpoint bytes but leaves those three template dimensions unchanged.
    """
    tensor = next((item for item in model.graph.initializer if item.name == tensor_name), None)
    if tensor is None or tensor.data_type != onnx.TensorProto.FLOAT:
        return None
    length = _external_length(tensor)
    if length is None or length % (4 * fixed_dimension) != 0:
        raise RuntimeError(f"无法根据外部权重长度修复 {tensor_name}: length={length}")
    inferred_dimension = length // (4 * fixed_dimension)
    expected_bytes = int(np.prod(tensor.dims)) * 4
    if expected_bytes != length:
        del tensor.dims[:]
        tensor.dims.extend([inferred_dimension, fixed_dimension])
    return inferred_dimension


def adapt_legacy_v2_templates(models_dir: Path) -> None:
    """Repair the shape metadata that Genie 2.0.2 leaves stale for legacy V2 models."""
    encoder_path = models_dir / "t2s_encoder_fp32.onnx"
    encoder = onnx.load(encoder_path, load_external_data=False)
    _adapt_external_matrix(
        encoder,
        "encoder.ar_text_embedding.word_embeddings.weight",
        fixed_dimension=512,
    )
    onnx.save(encoder, encoder_path)

    vits_path = models_dir / "vits_fp32.onnx"
    vits = onnx.load(vits_path, load_external_data=False)
    _adapt_external_matrix(
        vits,
        "vq_model.enc_p.text_embedding.weight",
        fixed_dimension=192,
    )
    # The spectral projection is [128, bins], so infer the second dimension separately.
    spectral = next((
        item for item in vits.graph.initializer
        if item.name == "vq_model.ref_enc.spectral.0.fc.weight"
    ), None)
    # V2Pro/V2ProPlus moves the reference encoder into a separate prompt graph and
    # feeds precomputed ge/ge_advanced tensors to VITS. There is no spectral layer
    # to patch in that graph.
    if spectral is None:
        onnx.save(vits, vits_path)
        return
    spectral_length = _external_length(spectral)
    if spectral_length is None or spectral_length % (4 * 128) != 0:
        raise RuntimeError(f"无法识别 VITS 参考频谱宽度: length={spectral_length}")
    spectral_bins = spectral_length // (4 * 128)
    if list(spectral.dims) != [128, spectral_bins]:
        del spectral.dims[:]
        spectral.dims.extend([128, spectral_bins])
        slice_end = next(
            node for node in vits.graph.node
            if node.name == "/vq_model/Constant_10" and node.op_type == "Constant"
        )
        value = next(attr for attr in slice_end.attribute if attr.name == "value")
        value.t.CopyFrom(numpy_helper.from_array(np.asarray(spectral_bins, dtype=np.int64)))
    onnx.save(vits, vits_path)


def validate_model_sessions(models_dir: Path) -> None:
    """Catch missing external data and stale shapes before a several-hundred-MB APK is built."""
    for name in (
        "t2s_encoder_fp32.onnx",
        "t2s_first_stage_decoder_fp32.onnx",
        "t2s_stage_decoder_fp32.onnx",
        "vits_fp32.onnx",
    ):
        session = onnxruntime.InferenceSession(
            str(models_dir / name),
            providers=["CPUExecutionProvider"],
        )
        del session


def adapt_source_weight_shapes(
    models_dir: Path,
    gpt_checkpoint: Path,
    sovits_checkpoint: Path,
) -> int:
    """Align every external initializer with the paired source checkpoint.

    GPT-SoVITS v2Pro uses the same four-session Android graph as V2, but some
    speaker-conditioning layers are 1024-wide instead of the 512-wide shapes in
    Genie's bundled V2 template. The converter writes the correct bytes and
    lengths while retaining those stale template dimensions. Reading the paired
    checkpoints lets us correct every initializer deterministically instead of
    guessing from a file size or accidentally borrowing another voice's shapes.
    """
    from genie_tts.Converter.load_state_dict import load_gpt_model, load_sovits_model

    gpt_weights = load_gpt_model(str(gpt_checkpoint))["weight"]
    sovits_weights = load_sovits_model(str(sovits_checkpoint))["weight"]

    def gpt_tensor(name: str):
        return gpt_weights.get("model." + name.replace("transformer_encoder", "h"))

    def sovits_tensor(name: str):
        key = name[len("vq_model."):] if name.startswith("vq_model.") else name
        return sovits_weights.get(key)

    def encoder_tensor(name: str):
        if name.startswith("encoder."):
            return gpt_weights.get("model." + name[len("encoder."):])
        if name.startswith("vits."):
            return sovits_weights.get(name[len("vits."):])
        return None

    sources = {
        "t2s_encoder_fp32.onnx": encoder_tensor,
        "t2s_first_stage_decoder_fp32.onnx": gpt_tensor,
        "t2s_stage_decoder_fp32.onnx": gpt_tensor,
        "vits_fp32.onnx": sovits_tensor,
    }
    changed = 0
    for filename, resolve in sources.items():
        path = models_dir / filename
        model = onnx.load(path, load_external_data=False)
        model_changed = False
        for initializer in model.graph.initializer:
            if not initializer.external_data:
                continue
            source_tensor = resolve(initializer.name)
            if source_tensor is None:
                raise RuntimeError(f"源权重缺少 ONNX 初始化器：{filename}:{initializer.name}")
            external_length = _external_length(initializer)
            expected_length = source_tensor.numel() * 4
            if external_length != expected_length:
                raise RuntimeError(
                    f"外部权重长度不一致：{filename}:{initializer.name} · "
                    f"{external_length} / {expected_length}"
                )
            source_shape = list(source_tensor.shape)
            if list(initializer.dims) != source_shape:
                del initializer.dims[:]
                initializer.dims.extend(source_shape)
                changed += 1
                model_changed = True
        if model_changed:
            onnx.save(model, path)
    return changed


def route_v2pro_prompt_features(models_dir: Path) -> None:
    """Feed offline V2Pro speaker embeddings into Genie's otherwise-compatible V2 graph.

    V2Pro keeps V2's decoder and waveform generator but widens the global speaker
    embedding to 1024 channels. Its prompt encoder also derives a separate 512-channel
    advanced embedding for MRTE. Genie's V2 graph originally derives one embedding from
    ref_audio and therefore cannot represent the split. Replace only those six consumers;
    the rest of the proven Android V2 graph, including its upsampling configuration, stays
    untouched.
    """
    path = models_dir / "vits_fp32.onnx"
    model = onnx.load(path, load_external_data=False)
    input_names = {item.name for item in model.graph.input}
    if {"ge", "ge_advanced"}.issubset(input_names):
        return
    reference_output = "/vq_model/ref_enc/Unsqueeze_6_output_0"
    advanced_consumers = 0
    global_consumers = 0
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            if name != reference_output:
                continue
            if node.name == "/vq_model/enc_p/mrte/Add_1":
                node.input[index] = "ge_advanced"
                advanced_consumers += 1
            else:
                node.input[index] = "ge"
                global_consumers += 1
    if advanced_consumers != 1 or global_consumers != 5:
        raise RuntimeError(
            "V2Pro 提示路由结构不符合预期："
            f"advanced={advanced_consumers}, global={global_consumers}"
        )
    model.graph.input.extend([
        onnx.helper.make_tensor_value_info("ge", onnx.TensorProto.FLOAT, [1, 1024, 1]),
        onnx.helper.make_tensor_value_info("ge_advanced", onnx.TensorProto.FLOAT, [1, 512, 1]),
    ])
    onnx.save(model, path)


def copy_models(
    source: Path,
    output: Path,
    gpt_checkpoint: Path | None = None,
    sovits_checkpoint: Path | None = None,
    use_prompt_features: bool = False,
) -> dict:
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
    adapt_legacy_v2_templates(target)
    if (gpt_checkpoint is None) != (sovits_checkpoint is None):
        raise RuntimeError("--gpt-checkpoint 与 --sovits-checkpoint 必须成对提供。")
    if gpt_checkpoint is not None and sovits_checkpoint is not None:
        changed = adapt_source_weight_shapes(target, gpt_checkpoint, sovits_checkpoint)
        print(f"source-shape validation: PASS · patched={changed}")
    if use_prompt_features:
        route_v2pro_prompt_features(target)
    validate_model_sessions(target)
    return {
        "encoder": "models/t2s_encoder_fp32.onnx",
        "first_decoder": "models/t2s_first_stage_decoder_fp32.onnx",
        "stage_decoder": "models/t2s_stage_decoder_fp32.onnx",
        "vocoder": "models/vits_fp32.onnx",
    }


def input_names(path: Path) -> list[str]:
    return [item.name for item in onnx.load(path, load_external_data=False).graph.input]


class MobileChineseFeatures:
    def __init__(self, model: Path, vocab_tsv: Path):
        self.session = onnxruntime.InferenceSession(str(model), providers=["CPUExecutionProvider"])
        self.vocab = {}
        with vocab_tsv.open(encoding="utf-8") as stream:
            for line in stream:
                token, value = line.rstrip("\n").split("\t", 1)
                self.vocab[token] = int(value)

    def prepare(self, text: str, leading_period: bool) -> tuple[str, np.ndarray, np.ndarray]:
        from genie_tts.G2P.Chinese.ChineseG2P import chinese_to_phones

        normalized, _, phones, word2ph = chinese_to_phones(("。" if leading_period else "") + text)
        token_ids = np.array(
            [[101, *(self.vocab.get(char, 100) for char in normalized), 102]], dtype=np.int64
        )
        raw = self.session.run(None, {
            "input_ids": token_ids,
            "token_type_ids": np.zeros_like(token_ids),
            "attention_mask": np.ones_like(token_ids),
        })[0]
        expanded = np.repeat(raw[1:1 + len(word2ph)], np.asarray(word2ph), axis=0).astype(np.float32)
        sequence = np.asarray([phones], dtype=np.int64)
        if expanded.shape != (sequence.shape[1], 1024) or not np.any(expanded):
            raise RuntimeError(
                f"中文特征尺寸异常：text={normalized!r}, phones={sequence.shape}, bert={expanded.shape}"
            )
        return normalized, sequence, expanded


def build(args: argparse.Namespace) -> None:
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    os.environ["GENIE_DATA_DIR"] = str(args.genie_data.resolve())

    from genie_tts.Audio.ReferenceAudio import ReferenceAudio
    from genie_tts.ModelManager import load_session_with_fp16_conversion

    converted_model_dir = args.converted_model_dir.resolve()
    converted_vocoder_inputs = input_names(converted_model_dir / "vits_fp32.onnx")
    needs_prompt_features = "ge" in converted_vocoder_inputs or "ge_advanced" in converted_vocoder_inputs
    prompt_model_dir = args.prompt_encoder_model_dir.resolve() if args.prompt_encoder_model_dir else None
    if needs_prompt_features and prompt_model_dir is None:
        raise RuntimeError("该 V2Pro VITS 需要 ge/ge_advanced；请提供 --prompt-encoder-model-dir。")

    speaker_encoder = None
    prompt_encoder = None
    if prompt_model_dir is not None:
        speaker_encoder = onnxruntime.InferenceSession(
            str(args.genie_data.resolve() / "speaker_encoder.onnx"),
            providers=["CPUExecutionProvider"],
        )
        prompt_encoder = load_session_with_fp16_conversion(
            str(prompt_model_dir / "prompt_encoder_fp32.onnx"),
            str(prompt_model_dir / "prompt_encoder_fp16.bin"),
            providers=["CPUExecutionProvider"],
        )

    shared_frontend = args.shared_frontend.resolve()
    frontend_target = output / "frontend"
    frontend_target.mkdir(parents=True, exist_ok=True)
    for name in ("bert_vocab.tsv", "char_phones.tsv", "phrase_phones.tsv", "punctuation_ids.tsv"):
        shutil.copyfile(shared_frontend / name, frontend_target / name)

    feature_builder = None
    if args.roberta is not None:
        feature_builder = MobileChineseFeatures(
            args.roberta.resolve(), shared_frontend / "bert_vocab.tsv"
        )
    references = json.loads(args.references_json.read_text(encoding="utf-8"))
    cases = []
    for item in references:
        audio_path = args.references_dir / item["audio"]
        reference_language = item.get("language", "Chinese")
        prompt = ReferenceAudio(str(audio_path), item["transcript"], reference_language)
        if reference_language.lower() == "chinese":
            if feature_builder is None:
                raise RuntimeError("中文参考音频必须提供 --roberta 以生成非零参考 BERT。")
            _, ref_seq, ref_bert = feature_builder.prepare(
                item["transcript"], leading_period=False
            )
        else:
            ref_seq = prompt.phonemes_seq
            ref_bert = prompt.text_bert
        audio_relative = f"references/{item['id']}.wav"
        audio_target = output / audio_relative
        audio_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(audio_path, audio_target)
        reference_tensors = [
            write_tensor(output, f"tensors/{item['id']}_ref_seq.tensor", "ref_seq", ref_seq),
            write_tensor(output, f"tensors/{item['id']}_ref_bert.tensor", "ref_bert", ref_bert),
            write_tensor(output, f"tensors/{item['id']}_ssl_content.tensor", "ssl_content", prompt.ssl_content),
            write_tensor(
                output,
                f"tensors/{item['id']}_ref_audio.tensor",
                "ref_audio",
                prompt.audio_32k,
            ),
        ]
        if prompt_encoder is not None and speaker_encoder is not None:
            sv_input = speaker_encoder.get_inputs()[0].name
            sv_emb = speaker_encoder.run(None, {sv_input: prompt.audio_16k})[0]
            ge, ge_advanced = prompt_encoder.run(None, {
                "ref_audio": prompt.audio_32k,
                "sv_emb": sv_emb,
            })
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

    if args.preset_source is not None:
        presets, frontend, feature_modes = copy_seed_presets(
            args.preset_source.resolve(), output
        )
    else:
        if feature_builder is None:
            raise RuntimeError("必须提供 --roberta，或用 --preset-source 复用已验证预设。")
        presets = []
        for preset_id, title, text in PRESETS:
            _, sequence, bert = feature_builder.prepare(text, leading_period=True)
            presets.append({
                "id": preset_id,
                "title": title,
                "text": text,
                "tensors": [
                    write_tensor(output, f"tensors/presets/{preset_id}_text_seq.tensor", "text_seq", sequence),
                    write_tensor(output, f"tensors/presets/{preset_id}_text_bert.tensor", "text_bert", bert),
                ],
                "bert_nonzero": int(np.count_nonzero(bert)),
                "bert_elements": int(bert.size),
            })
        roberta = args.roberta.resolve()
        frontend = {
            "roberta": "frontend/chinese_roberta_int8.onnx",
            "vocab": "frontend/bert_vocab.tsv",
            "char_phones": "frontend/char_phones.tsv",
            "phrase_phones": "frontend/phrase_phones.tsv",
            "punctuation_ids": "frontend/punctuation_ids.tsv",
            "max_phrase_chars": 9,
            "bert_dim": 1024,
            "quantization": "dynamic-int8-per-channel",
            "roberta_bytes": roberta.stat().st_size,
            "roberta_sha256": sha256(roberta),
            "roberta_external": True,
        }
        feature_modes = [{
            "id": "mobile_int8",
            "title": "完整 Chinese RoBERTa",
            "description": "预设与自由输入均使用本地 INT8 Chinese RoBERTa 非零特征。",
            "tensors": [],
            "bert_nonzero": presets[0]["bert_nonzero"],
            "bert_elements": presets[0]["bert_elements"],
            "tone_diagnostic": "手机端同构中文 G2P 与 INT8 RoBERTa",
        }]

    models = copy_models(
        converted_model_dir,
        output,
        args.gpt_checkpoint.resolve() if args.gpt_checkpoint else None,
        args.sovits_checkpoint.resolve() if args.sovits_checkpoint else None,
        use_prompt_features=prompt_model_dir is not None,
    )
    manifest = {
        "version": args.version,
        "character": args.character,
        "sample_rate": 32000,
        "models": models,
        "shared_tensors": [],
        "feature_modes": feature_modes,
        "preset_feature_title": "完整 Chinese RoBERTa",
        "preset_feature_description": "预计算 INT8；非零中文特征",
        "presets": presets,
        "frontend": frontend,
        "cases": cases,
        "encoder_input_names": input_names(output / models["encoder"]),
        "first_stage_input_names": input_names(output / models["first_decoder"]),
        "stage_input_names": input_names(output / models["stage_decoder"]),
        "vocoder_input_names": input_names(output / models["vocoder"]),
    }
    manifest["asset_files"] = sorted(
        str(path.relative_to(output)).replace(os.sep, "/")
        for path in output.rglob("*") if path.is_file() and path.name != "manifest.json"
    ) + ["manifest.json"]
    manifest["asset_integrity"] = {
        relative: {
            "bytes": (output / relative).stat().st_size,
            "sha256": sha256(output / relative),
        }
        for relative in manifest["asset_files"]
        if relative != "manifest.json"
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"voice={args.character}, candidates={len(cases)}, presets={len(presets)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--converted-model-dir", type=Path, required=True)
    parser.add_argument(
        "--prompt-encoder-model-dir",
        type=Path,
        help=(
            "V2Pro/V2ProPlus 转换目录，包含 prompt_encoder_fp32.onnx 与 "
            "prompt_encoder_fp16.bin；只离线生成 ge/ge_advanced，不打入 APK。"
        ),
    )
    parser.add_argument(
        "--gpt-checkpoint",
        type=Path,
        help="可选的配对 GPT checkpoint；用于逐项校正 v2Pro 外部权重形状。",
    )
    parser.add_argument(
        "--sovits-checkpoint",
        type=Path,
        help="可选的配对 SoVITS checkpoint；必须与 --gpt-checkpoint 同时提供。",
    )
    parser.add_argument("--genie-data", type=Path, required=True)
    parser.add_argument("--references-dir", type=Path, required=True)
    parser.add_argument("--references-json", type=Path, required=True)
    parser.add_argument("--shared-frontend", type=Path, required=True)
    parser.add_argument("--roberta", type=Path)
    parser.add_argument(
        "--preset-source",
        type=Path,
        help="已验证资源根目录；复用其中的中文预设张量和 RoBERTa 元数据。",
    )
    parser.add_argument("--character", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())


if __name__ == "__main__":
    main()
