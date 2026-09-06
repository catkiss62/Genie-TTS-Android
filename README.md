# Genie-TTS Android Benchmark

这是一个面向 Android ARM64 手机的 Genie-TTS v2.0.2 真机性能验证项目。

第一阶段只回答一个问题：GPT-SoVITS 经 Genie 转换后的 ONNX 推理，在手机上能否达到实时速度。

## v0.2.0 范围

- 官方 Genie-TTS `v2.0.2`
- 官方预定义中文角色“菲比” (`V2ProPlus`)
- Android 原生 ONNX Runtime `1.22.0`
- CPU 2/4/6/8 线程与 XNNPACK 2/4/6/8 线程性能矩阵
- 实验性 NNAPI FP16 VITS（其余模型仍走 4 线程 CPU；不支持时自动跳过）
- 固定短句、中句、长句三组基准
- 每项自动测试三次，以第 2、3 次平均 RTF 排名并选择本机最快配置
- 记录模型加载、Encoder、逐 Token Decoder、VITS、音频时长、RTF、PSS 与电池温度
- 保留每个成功配置的音频样本，便于逐项检查听感
- 使用最快配置自动复测短/中/长句，并模拟三段连续生成与播放的断流时间线
- 完整测试历史可一次复制，运行中可以安全请求停止
- 修复 Stage Decoder 第二轮把布尔停止标记误传给浮点缓存输入的问题
- 单个后端加载或推理失败不会中断整个性能矩阵
- GitHub Actions 构建时完整运行一次短句推理冒烟测试

参考音频特征、G2P 与中文 BERT 特征由 GitHub Actions 在构建时预计算。手机执行的 Encoder、首步 Decoder、自回归 Decoder 和 VITS 均为真实 Genie 模型，并非录音回放。

`RTF = 核心推理耗时 / 生成音频时长`。RTF 小于 1 表示生成速度快于播放速度。

完整中文输入还需要移植文本归一化、分词、G2pM、多音字修正、变调、儿化和 RoBERTa。当前版本继续隔离这些前处理变量，先确定目标手机上最合适的执行后端与线程数，再加入任意文本和自定义音色导入。

“分句流式模拟”基于每段真实核心推理耗时和音频时长计算生产/播放时间线，用来判断首段完成后是否会断流；它不是任意文本前处理，也不代表 VITS 已经支持单句内部的边合成边播放。

## 来源与许可

- [High-Logic/Genie-TTS](https://github.com/High-Logic/Genie-TTS), MIT
- [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)
- [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime), MIT

模型和角色语音的权利归各自权利人所有，本项目仅用于本地技术测试。
