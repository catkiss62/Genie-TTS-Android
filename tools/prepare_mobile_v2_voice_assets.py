#!/usr/bin/env python3
"""Build an Android audition bundle for one converted Genie V2 voice.

The reference and preset BERT tensors are produced with the same character-tokenized INT8
RoBERTa path used by ChineseFrontend.kt. Original checkpoints and reference recordings stay in
the private build directory; only this reproducible builder belongs in the public repository.
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
    spectral = next(
        item for item in vits.graph.initializer
        if item.name == "vq_model.ref_enc.spectral.0.fc.weight"
    )
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
    adapt_legacy_v2_templates(target)
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

    shared_frontend = args.shared_frontend.resolve()
    frontend_target = output / "frontend"
    frontend_target.mkdir(parents=True, exist_ok=True)
    for name in ("bert_vocab.tsv", "char_phones.tsv", "phrase_phones.tsv", "punctuation_ids.tsv"):
        shutil.copyfile(shared_frontend / name, frontend_target / name)

    feature_builder = MobileChineseFeatures(
        args.roberta.resolve(), shared_frontend / "bert_vocab.tsv"
    )
    references = json.loads(args.references_json.read_text(encoding="utf-8"))
    cases = []
    for item in references:
        audio_path = args.references_dir / item["audio"]
        prompt = ReferenceAudio(str(audio_path), item["transcript"], "Chinese")
        _, ref_seq, ref_bert = feature_builder.prepare(item["transcript"], leading_period=False)
        audio_relative = f"references/{item['id']}.wav"
        audio_target = output / audio_relative
        audio_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(audio_path, audio_target)
        cases.append({
            "id": item["id"],
            "title": item["title"],
            "text": PRESETS[0][2],
            "reference_text": item["transcript"],
            "reference_audio": audio_relative,
            "tensors": [
                write_tensor(output, f"tensors/{item['id']}_ref_seq.tensor", "ref_seq", ref_seq),
                write_tensor(output, f"tensors/{item['id']}_ref_bert.tensor", "ref_bert", ref_bert),
                write_tensor(output, f"tensors/{item['id']}_ssl_content.tensor", "ssl_content", prompt.ssl_content),
                write_tensor(output, f"tensors/{item['id']}_ref_audio.tensor", "ref_audio", prompt.audio_32k),
            ],
        })

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

    models = copy_models(args.converted_model_dir.resolve(), output)
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
    manifest = {
        "version": args.version,
        "character": args.character,
        "sample_rate": 32000,
        "models": models,
        "shared_tensors": [],
        "feature_modes": [{
            "id": "mobile_int8",
            "title": "完整 Chinese RoBERTa",
            "description": "预设与自由输入均使用本地 INT8 Chinese RoBERTa 非零特征。",
            "tensors": [],
            "bert_nonzero": presets[0]["bert_nonzero"],
            "bert_elements": presets[0]["bert_elements"],
            "tone_diagnostic": "手机端同构中文 G2P 与 INT8 RoBERTa",
        }],
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
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"voice={args.character}, candidates={len(cases)}, presets={len(presets)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--converted-model-dir", type=Path, required=True)
    parser.add_argument("--genie-data", type=Path, required=True)
    parser.add_argument("--references-dir", type=Path, required=True)
    parser.add_argument("--references-json", type=Path, required=True)
    parser.add_argument("--shared-frontend", type=Path, required=True)
    parser.add_argument("--roberta", type=Path, required=True)
    parser.add_argument("--character", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    build(parser.parse_args())


if __name__ == "__main__":
    main()
