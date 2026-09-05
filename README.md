# Genie-TTS Android Benchmark

这是一个面向 Android ARM64 手机的 Genie-TTS v2.0.2 真机性能验证项目。

第一阶段只回答一个问题：GPT-SoVITS 经 Genie 转换后的 ONNX 推理，在手机 CPU 上能否达到实时速度。

## v0.1.0 范围

- 官方 Genie-TTS `v2.0.2`
- 官方预定义中文角色“菲比” (`V2ProPlus`)
- Android 原生 ONNX Runtime `1.22.0`
- 固定短句、中句、长句三组基准
- 记录模型加载、Encoder、逐 Token Decoder、VITS、音频时长、RTF 与 PSS
- 生成完成后直接播放结果

参考音频特征、G2P 与中文 BERT 特征由 GitHub Actions 在构建时预计算。手机执行的 Encoder、首步 Decoder、自回归 Decoder 和 VITS 均为真实 Genie 模型，并非录音回放。

`RTF = 核心推理耗时 / 生成音频时长`。RTF 小于 1 表示生成速度快于播放速度。

完整中文输入还需要移植文本归一化、分词、G2pM、多音字修正、变调、儿化和 RoBERTa。第一版先隔离这些前处理变量，确认核心推理速度合格后，再加入任意文本与自定义音色导入。

## 来源与许可

- [High-Logic/Genie-TTS](https://github.com/High-Logic/Genie-TTS), MIT
- [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)
- [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime), MIT

模型和角色语音的权利归各自权利人所有，本项目仅用于本地技术测试。
