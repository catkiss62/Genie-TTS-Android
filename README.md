# Genie-TTS Android

这是一个面向 Android ARM64 手机的 Genie-TTS v2.0.2 真机验证项目。

## v0.3.1：Chinese RoBERTa 声调对照

代码核查发现，v0.3.0 的中文音素序列是正确的（目标字“样”为 `y + ang4`），但目标文本与九段参考文本的 `text_bert/ref_bert` 全部为零。原因是资源制作环境缺少可用的 Chinese RoBERTa 时，Genie 会回退到零张量，而旧制作脚本没有拒绝这个回退。

v0.3.1 保留全部九个候选，并提供两种可切换模式：

- **完整 Chinese RoBERTa（默认）**：目标与参考文本都使用已验证的非零特征；
- **旧版全零特征（对照）**：保留 v0.3.0 的张量，只用于同候选 A/B 听感比较。

界面会直接显示 BERT 非零元素数和“样”的音素诊断。可以生成当前候选的旧/新两版，再切换模式分别播放。RoBERTa 在制作 APK 时预计算，不把约 1.2 GB 的 RoBERTa 模型放进 APK，因此此次 A/B 的核心推理 RTF仍可直接比较。

`tools/prepare_custom_v2_assets.py` 也已加入强校验：任何中文 BERT 张量全零都会中止打包，不再静默生成缺失韵律特征的 APK。

## v0.3.0：自定义 V2 音色盲测

当前版本使用一套配对的 GPT-SoVITS V2 权重，在相同目标文本下比较九段参考音频对音色、语气和稳定性的影响。

- Android 原生 ONNX Runtime 1.22.0
- 固定使用已在目标手机上验证最稳的 CPU 8 线程
- 可播放每个候选的原始参考录音
- 可单独生成当前候选，或一次生成全部候选
- 可回放合成结果并标记“喜欢 / 一般 / 淘汰”
- 可复制推理数据和评分汇总
- 候选 3 只有 2.70 秒，低于 Genie 建议的 3–10 秒，保留作短参考对照

目标生成文本固定为：

> 你好呀，今天过得怎么样？如果有什么想说的，我会认真听你慢慢讲。

固定文本可以避免把文本差异误判为参考音频差异。候选原来的情绪文件夹名不会送入模型，也不会影响推理。

## 模型与数据说明

一个可用的 GPT-SoVITS V2 角色需要一对权重：

- GPT `.ckpt`：文本到语义；
- SoVITS `.pth`：语义到音频。

这两个文件是一套完整模型，而不是两个独立音色。训练数据集不需要整包放进 APK；运行时只需要转换后的 ONNX 权重和预先计算好的参考特征。

用户提供的原始权重、数据集和参考录音不提交到公开仓库。`tools/prepare_custom_v2_assets.py` 接收已转换的 Genie V2 模型目录、GenieData 和参考音频清单，在本地生成 Android 资源。

示例：

```bash
python tools/prepare_custom_v2_assets.py \
  --converted-model-dir /private/voice/tts_models \
  --genie-data /private/GenieData \
  --references-dir /private/references \
  --references-json /private/references.json
gradle --no-daemon :app:assembleDebug
```

生成的模型资源目录已被 `.gitignore` 排除。

## 旧版性能结论

v0.2.0 使用官方“菲比”V2ProPlus 模型测试过 CPU、XNNPACK 和 NNAPI。目标 Xiaomi 设备的持续 CPU 8 线程 RTF 约为 1.35–1.47；XNNPACK 出现提前停止的异常短音频，NNAPI 没有收益。因此 v0.3.0 的音质比较只使用 CPU 8 线程。

`RTF = 核心推理耗时 / 生成音频时长`。RTF 小于 1 表示生成速度快于播放速度；略大于 1 时仍可以通过分句和首段预缓冲实现连续播放体验。

## 来源与许可

- [High-Logic/Genie-TTS](https://github.com/High-Logic/Genie-TTS)，MIT
- [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)
- [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime)，MIT

模型、角色语音和数据集的权利归各自权利人所有；本项目仅用于获得授权的本地技术测试。
