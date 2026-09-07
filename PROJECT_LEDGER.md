# Genie-TTS Android 项目总账

最后更新：2026-09-07 · 当前测试版：v0.6.1 · 仓库：`catkiss62/Genie-TTS-Android`

## 当前接班区

项目已经证明 Genie-TTS v2.0.2 的 GPT-SoVITS V2 权重可以在 Android ARM64 上使用 ONNX Runtime 完整推理，中文音质可用，恬豆模型也已确认具备中、英、日三语能力。v0.6.0 的动态中英混合、纯英文和纯日语已由用户真机确认听感无明显问题。当前主音色为候选 1，推理固定 CPU 8 线程。

v0.6.1 不再优化模型本身，只修复整页滚动与虚拟导航栏避让，并加入启动后台预热和不改音频的自适应首段缓冲。完成真机回归后即可进入 AI 伴侣工程接入。

公开开发分支为 `agent/v001-genie-benchmark`，草稿 PR 为 [#1](https://github.com/catkiss62/Genie-TTS-Android/pull/1)。原始角色权重、参考录音、转换后的 ONNX 模型和可识别角色身份的数据均不得提交到公开仓库。

## 已确认的产品决策

| 项目 | 结论 |
|---|---|
| 模型版本 | 用户提供的 `恬豆GPT.ckpt` + `恬豆SoVITS.pth` 是一套 GPT-SoVITS V2 配对权重，不是两个音色 |
| 训练数据集 | 只用于训练/重训，不是 Android 运行时依赖，不放入 APK |
| 主音色 | 候选 1：认真、自然，作为日常主音色 |
| 保留备选 | 候选 2：安静温柔，播放端 +4 dB；候选 4：活泼；候选 6/7：稍可爱的日常 |
| 已淘汰 | 候选 3 偏尖且参考太短；5 音量/音色不稳定；8/9 完整 RoBERTa 后播音腔明显 |
| 后端 | CPU 8 线程。XNNPACK 曾产生异常短音频；NNAPI VITS 没有收益 |
| 中文语调 | 必须使用完整、非零 Chinese RoBERTa；零 BERT 是早期声调和语气异常的主因 |
| “快看”瑕疵 | 激动语境下偶发破音/异常，用户已决定接受，不以牺牲整体自然度为代价专项修复 |
| 长文本 | 采用自然标点分段、首段生成后开播、播放期间生成后续段；RTF 略大于 1 时需预缓冲 |
| 多语言 | 中文模式可夹英文；纯英文、纯日语各自独立。日语不与中文自动混排，避免汉字语言歧义 |

## 版本演进

| 版本 | 关键内容与结论 |
|---|---|
| v0.1.1 | 首个 Android 原生基准。修正 ONNX 布尔/浮点输入类型错误；确认完整链路可运行 |
| v0.2.0 | 对比 CPU/XNNPACK/NNAPI；CPU 8 线程最可靠 |
| v0.3.0 | 导入用户 V2 权重并用 9 段参考录音盲测；确认参考音频决定音色和语气方向 |
| v0.3.1 | 找到目标/参考 BERT 全零问题；加入完整 Chinese RoBERTa 后语调、问句和整体自然度大幅提升 |
| v0.3.2 | 四类预设、自由输入 INT8 RoBERTa、动态中文前端和特征缓存 |
| v0.3.3 | 音色收敛至 1/2/4/6/7；模型会话常驻；自动诊断与波形指标；“快看快看”只规范为“快看，快看” |
| v0.4.0 | 约 500 字真分段流式播放与逐段缓冲报告 |
| v0.4.1 | 中文前端中拉丁字母不再静默跳过；`token` 暂按中文近似“拖肯” |
| v0.5.0 | 用桌面端预计算的固定 ARPAbet/OpenJTalk 音素验证恬豆权重；英日听感均被用户评价为非常好 |
| v0.6.0 | CMUdict 动态英文、Android OpenJTalk 动态日语、中文＋英文混合单次 RoBERTa/单次 TTS；12 条预设与黄金音素校验 |
| v0.6.1 | 整页滚动与系统栏避让；TTS 会话启动后台预热；长文本按首段实测 RTF 使用 0.75–2.5 秒自适应预填充；模型和音质链不变 |

## 真机测试基线

目标设备：Xiaomi 25060RK16C · Android 15 / API 35 · arm64-v8a。

| 测试 | 已观测结果 |
|---|---|
| 早期官方中文长句 | RTF 约 1.44，PSS 约 1.78 GB |
| 完整 RoBERTa 中文热推理 | 温度较低时多在 0.93–1.10 左右；持续运行和发热后可升到 1.3–1.9 |
| 505 字分段流式 | 16 段，80.16 秒音频，聚合 RTF 1.230，首段等待 4.728 秒，PSS 峰值约 2.39 GB |
| v0.5.0 英语固定句 | 3.52 秒音频，核心 6.556 秒，RTF 1.863，PSS 约 1.56 GB |
| v0.5.0 日语固定句 | 4.04 秒音频，核心 7.187 秒，RTF 1.779，PSS 约 1.58 GB |

RTF 波动与手机温度、CPU 调度和自回归生成 token 数有关。报告中的“模型加载”只在冷启动出现；热推理慢不代表每次重新加载。实际陪伴应用应在模型常驻后按句生成，并根据近期 RTF 动态预缓冲。

## 当前运行时架构

### 中文与中英混合

- 中文动态输入：INT8 Chinese RoBERTa + Genie 中文 G2P 词典；预设基准仍可使用离线 FP32 特征。
- 中英混合：先识别英文片段，为所有中文片段构造一次 RoBERTa 输入；中文音素复制对应非零 BERT 行，英文 ARPAbet 音素填零 BERT；最终拼成一个 `PreparedText`，只调用一次 TTS。
- 英文热词：`DeepSeek`、`token`、`AI`、`API`、`GPT`、`CPU`、`GPU`、`ONNX`。
- 普通英文优先查 CMUdict，未知词按字母名回退，因此至少不会被跳过。

### 纯英文

- CMUdict/ARPAbet，零 BERT；不运行 Chinese RoBERTa。
- 数字目前按单个数字英文读法展开；未知词逐字母读出。
- 动态预设与桌面端黄金音素逐项比较，报告必须说明是否完全一致。

### 纯日语

- Android ARM64 原生 OpenJTalk 1.11，零 BERT；不运行 Chinese RoBERTa。
- JNI 读取音素和 `a1/a2/a3` 韵律数组，再转换到 Genie V2 的日语符号 ID。
- OpenJTalk 词典首次从 APK 释放到应用私有目录，后续常驻复用。
- 动态预设与桌面官方前端黄金音素对比；若不一致，先检查韵律标记转换，不能只凭听感改表。

## 资源边界与复现

### 不进入公开仓库的资源

- 用户提供的 GPT `.ckpt`、SoVITS `.pth`、数据集和原始参考录音；
- 转换后的 Genie ONNX 模型、参考 SSL/BERT 特征和完整 Android `assets/genie`；
- OpenJTalk Android `.so` 与 103 MB 解压词典（由工作流复现）；
- 外置约 352 MB INT8 Chinese RoBERTa 文件。

### 可公开提交的资源

- Android/Kotlin/C++ 源码、转换脚本、清单结构和测试报告逻辑；
- 压缩后的 CMUdict 及其许可证说明；
- 固定版本的 OpenJTalk 资源准备工作流与校验 SHA-256。

模型资源使用 `tools/prepare_custom_v2_assets.py` 本地制作。OpenJTalk 使用 `.github/workflows/prepare-openjtalk-assets.yml`，当前固定：

- `ayutaz/openjtalk-native` v1.0.1 Android ARM64 包，SHA-256 `7805ac88c8cf3a2aa55fa9b32ce1008b53e24dea9188c6cc32dbbe87be292d1f`；
- Open JTalk dictionary 1.11，SHA-256 `fe6ba0e43542cef98339abdffd903e062008ea170b04e7e2a35da805902f382a`。

## v0.6.1 真机验收顺序

1. 安装后确认页面可以一直滑到“停止当前任务”和完整状态报告，虚拟导航键不遮挡按钮。
2. 等顶部状态显示“TTS 后台预热：已完成”，再生成任意中文与动态三语预设；报告应显示复用已加载模型。
3. 运行一次约 500 字流式测试，确认第一段后的停顿没有比 v0.6.0 更严重，并记录报告中的“自适应首段预填充”。
4. 本版没有改变声音链，若中英日听感与 v0.6.0 一致即可通过；无需重新完成全部 12 条语音盲测。

## AI 伴侣接入待办

在 v0.6.1 真机确认后按以下顺序继续：

1. 抽出不依赖测试 UI 的 `GenieTtsService`，保留模型会话与 RoBERTa 会话常驻。
2. DeepSeek API 返回文本后，按用户选择的中文/英文/日语模式路由；中文允许热词英文，日语不自动猜测汉字语言。
3. 按自然标点切段，维护生成队列和 AudioTrack 播放队列；根据设备近期 RTF 决定首段预缓冲，而不是只按字符数写死。
4. 为取消、抢话、来电/音频焦点、应用后台和内存压力增加生命周期处理。
5. 内置候选 1；可选候选 2/4/6/7。后续导入新音色必须导入成对 GPT/SoVITS 权重并在桌面端转换，不能只替换一个文件。

## 易踩坑

- 中文 BERT 全零不会直接报错，却会明显损害声调与语气；打包脚本必须拒绝全零中文特征。
- 一段参考音频对应一个生成方向；多个候选不是同时送入模型混合。
- 参考音频建议 3–10 秒、干净、文本准确、语气一致。简单拼接多段音频通常会引入停顿和风格冲突。
- 自回归采样有随机性，同一句的 token 数、音频长度和 RTF 会变化；判断问题应多生成几次。
- PSS 峰值超过 2 GB，正式接入前必须验证目标手机的内存回收与后台行为。
- 首次日语测试包含 103 MB 词典释放，不应拿它评估热运行前端速度。

## 上游与许可

- [High-Logic/Genie-TTS v2.0.2](https://github.com/High-Logic/Genie-TTS/tree/v2.0.2)，MIT；
- [RVC-Boss/GPT-SoVITS](https://github.com/RVC-Boss/GPT-SoVITS)；
- [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime)，MIT；
- [CMUdict](https://github.com/cmusphinx/cmudict)，BSD-style；
- [ayutaz/openjtalk-native](https://github.com/ayutaz/openjtalk-native)，BSD-3-Clause；
- Open JTalk/NAIST-jdic 词典，BSD-style。

模型、角色语音和数据集仍受各自许可与权利约束。本项目仅使用获得授权的资源做本地技术测试。
