# Genie-TTS → AI Companion 接入指南

状态：v0.6.4 是模型与播放的收口基线；v0.7.0 增加 LLM 真流式联调；v0.7.1 增加后续积压短句拼合与单 AudioTrack 连续播放；v0.7.2 增加乐奈 V2.1 三语试听，但其交付 APK 的恬豆 T2S 权重被截断，不得作为移植来源；v0.7.3 已从原始配对权重重建恬豆，并加入完整性与全链推理门禁。测试引擎基于 Genie-TTS v2.0.2、GPT-SoVITS V2 权重和 Android ONNX Runtime；目标设备已完成恬豆三语与约 500 字符分段播放真机验证。

本文件面向后续接手 AI 伴侣项目的开发者或 AI。详细实验历史、失败路线和性能数据见 [PROJECT_LEDGER.md](PROJECT_LEDGER.md)。

## 一句话接入原则

保留 AI 伴侣现有的 Dart `TtsPlaybackQueue` 和 19 种 `CompanionEmotion`，把本项目的前端、ONNX 推理与 AudioTrack 播放封装成新的本地 `TtsProvider`；语言和音色在每次回复开始时确定一次，整条回复的所有分段保持一致。

## 需要移植的源码

| 文件 | 职责 | 接入处理 |
|---|---|---|
| `GenieBenchmarkEngine.kt` | 四个 ONNX 会话、逐步 Decoder、VITS、资源读取 | 去掉 benchmark 报告字段后作为 `GenieTtsEngine` 核心 |
| `ChineseFrontend.kt` | 中文规范化、G2P、INT8 Chinese RoBERTa、BERT 对齐 | 保留；中文模式专用 |
| `EnglishFrontend.kt` | CMUdict/ARPAbet、热词、未知词逐字母回退 | 保留；英文模式按需创建 |
| `NativeJapaneseFrontend.kt` | OpenJTalk 词典释放与 JNI 调用 | 保留；日文模式按需创建 |
| `native_frontend_jni.cpp` | OpenJTalk JNI 桥 | 随 Android 模块移植 |
| `GenieSymbolsV2.kt` | 三语音素到 Genie V2 ID | 原样保留 |
| `StreamingAudioPlayer.kt` | PCM AudioTrack 播放参考实现 | 可复用底层写入逻辑；上层队列仍由 AI 伴侣管理 |
| `StreamingDialogue.kt` | 三语增量切句、长度策略、模拟流夹具 | 迁移切句规则；正式项目用真实 LLM delta 替换夹具 |
| `DeepSeekStreamClient.kt` | 最小 DeepSeek Chat Completions SSE 客户端 | 仅作联调参考；正式项目优先复用已有 API 层 |
| `SecureApiKeyStore.kt` | Android Keystore 加密测试 Key | 正式项目若已有密钥存储则复用，不要保存第二份 |
| `SystemAudioPolicy.kt` | 普通媒体语音属性、系统静音/振动拦截 | 原样迁移；禁止退回无障碍辅助音频用途 |
| `VoiceProfileCatalog.kt` | 稳定音色名称、用途与移植边界 | 迁移前四项；排除 `test_backup` |
| `VoicePackageCatalog.kt` | 多套 V2 模型包的资源命名空间与能力边界 | 若正式产品允许换模型，保留同类 package 层；切换时必须卸载旧会话 |
| `MainActivity.kt` | 测试 UI 与诊断编排 | 不移植，只作调用顺序参考 |

## AI 伴侣现有接口

AI 伴侣已经具备真正的文本流式入口：`TtsPlaybackQueue.beginStream()`、`addDelta()`、`endStream()`；也已将情绪以 `TtsEmotionCue` 传给每个分段。不要再造第二套流式队列或情绪分类器。

接入时让新的本地 Provider 实现现有 `TtsQueueService`：

1. `prepareText`：只清理可朗读正文，并按用户选择的 TTS 语言处理固定词。
2. `generatePrepared`：把一个完整句子交给单一后台推理线程，返回 WAV/PCM 标识。
3. `playPrepared`：按顺序播放已完成的音频，完成时再结束 Future。
4. `stop`：取消未开始的队列、停止 AudioTrack；当前 ONNX 算子不能安全强杀时，在算子结束后丢弃结果。

播放前必须读取系统 `AudioManager.ringerMode`。只在 `RINGER_MODE_NORMAL` 下播放；静音和振动模式都直接阻止 TTS，不提供额外开关。AudioTrack 使用 `USAGE_MEDIA + CONTENT_TYPE_SPEECH`，不得使用 `USAGE_ASSISTANCE_ACCESSIBILITY`。手机在正常响铃模式但媒体音量为零时，由系统自然保持无声。

重要：Dart 层可能提前发起多个 `generatePrepared`。本地 Genie ONNX 会话必须由单一串行 worker 复用，不能让多个句子并发运行同一组会话，也不能为每段重新加载模型。

## 语言路由

AI 伴侣的三个设置必须独立：

- 生成语言：决定 DeepSeek 输出什么语言；
- 翻译显示：只供阅读，不进入 TTS；
- TTS 语言：明确选择 `zh`、`en` 或 `ja`。

三种语言共用 Genie 声学 ONNX 模型，但前端隔离：

| TTS 语言 | 前端 | BERT | 生命周期 |
|---|---|---|---|
| 中文 | 中文 G2P + Chinese RoBERTa | 非零 INT8 RoBERTa | 首次中文朗读时加载并复用 |
| 英文 | CMUdict/ARPAbet | 全零 | 首次英文朗读时创建 |
| 日文 | OpenJTalk | 全零 | 首次日文朗读时创建并释放词典 |

禁止应用启动时同时初始化三套前端或后台预热全部 TTS 会话。v0.6.1 已证明这种启动环境可能造成严重长文本性能回退。

正式中文模式不启用真正的中英混合前端。少量固定英文词在中文 TTS 的文本准备阶段使用中文谐音白名单：`token` → “拖肯”，`DeepSeek` → “地铺C咳”。纯英文内容切换到英文 TTS 模式。

## 音色模式

设置页提供五种正式选项：

| 设置值 | 显示名称 | 行为 |
|---|---|---|
| `auto` | 自动匹配情绪 | 根据本轮 `TtsEmotionCue` 选择下列四种音色 |
| `daily` | 日常认真 | 无论情绪如何都固定使用日常主音色 |
| `gentle` | 温柔轻声 | 无论情绪如何都固定使用温柔音色 |
| `lively` | 活泼可爱 | 无论情绪如何都固定使用活泼音色 |
| `cute` | 日常可爱 | 无论情绪如何都固定使用可爱音色 |

固定模式的优先级高于情绪：用户选择单一音色后，整条回复只使用这一种音色。`test_backup`（备选音色）只存在于测试项目，不加入正式设置、资源清单或自动映射代码。

### 自动情绪映射

19 种情绪仍是唯一情绪真源；本地 TTS 只做提供商边界映射，不额外请求 LLM，也不运行第二个情绪分类器。

| 音色 | 情绪键 |
|---|---|
| `daily` | `normal`、`serious`、`confident`、`angry`、`disgust`、`confused` |
| `gentle` | `calm`、`worried`、`crying`、`afraid`、`nervous`、`shy`、`embarrassed`、`affection` |
| `lively` | `happy`、`excited`、`surprised` |
| `cute` | `playful`、`helpless`、`flustered` |

情绪缺失、无效、来源为低可信启发式且置信度不足时回退 `daily`。音色在一次 `beginStream` 时确定并锁定到会话结束；不要随句切换，否则会产生像换人的听感。

## 流式与分段

本项目的 500 字测试已经验证“播放上一段时串行生成下一段”。v0.7.0 又把模拟 delta 和真实 DeepSeek SSE 接到同一管线；v0.7.1 保留首段立即生成，并把 TTS 忙碌时已经积压的短自然句拼到目标长度，从而减少短段固定开销。正式 AI 伴侣仍应复用已有 API 层与队列：

1. 收到流式 delta；
2. 情绪标签由现有隐藏首行解析，不进入朗读文本；
3. 累积到完整自然句或达到安全最大长度后入队；
4. 本地 worker 串行生成，AudioTrack 按原顺序播放；
5. 用户打断时同时停止 LLM 流、待生成段和播放。

播放端固定复用一个 `AudioTrack.MODE_STREAM`，直接连续写入各段 PCM；不得在段间主动 `sleep(200ms)`，也不得为每段重建 AudioTrack。拼合器不能为了凑长度等待尚未到达的网络文字：只有队列中已经存在的自然单元才可合并，首段始终优先降低开播等待。

多模型包切换与同一模型内切换参考音色是两件事：参考音色只替换参考张量；切换到另一套 GPT/SoVITS 权重时必须关闭四个旧 ONNX 会话、释放内存，再加载新模型。不要同时常驻两套约数百 MB 的声学模型。

不要仅凭 Genie 转换器打印“Conversion successful”就判定新语音包可用。早期 V2 可能采用 322 音素和 1025 个频谱输入，而 Genie 2.0.2 模板采用扩展后的 732 音素和 704 个频谱输入；转换器会写入正确 BIN 但不一定同步模板维度。应运行 `tools/prepare_mobile_v2_voice_assets.py` 的兼容修复，并至少实际创建四个 ORT 会话、完成一次 Encoder→自回归 Decoder→VITS 推理后再打 APK。日文参考必须使用其原文标注、Japanese OpenJTalk 音素和零 BERT；不要把日文参考当中文处理。原 322 音素表是扩展表的前缀，因此中文、英文和日文旧 ID 保持兼容，但前端兼容仍不能替代实际三语听感验证。

RTF 略大于 1 时，任意长度都不能数学上保证完全无停顿。首版保持 v0.5/v0.6.2 已验证的固定 1 秒首段预填充，不要先做自适应缓冲；真实 API 接入后再依据真机队列数据调整。

联调版的 DeepSeek 下拉包含 `deepseek-v4-flash` 和临时的 `deepseek-v4.1-flash-expires-on-0910`，并统一显式关闭思考模式。临时模型失效属于 API 配置变化，不是 TTS 故障。测试 Key 由 Android Keystore 加密且不进入报告；正式移植应交给 AI 伴侣已有凭据管理。

## 已验证边界

- CPU 8 线程是目标设备上最可靠配置；XNNPACK 曾生成异常短音频，NNAPI VITS 未获得收益。
- 中文必须使用非零 Chinese RoBERTa；全零 BERT 虽不报错，但会明显损害声调和问句语气。
- v0.6.2 日文 523 字符测试：80.36 秒音频、聚合 RTF 1.063、温控始终正常；用户只感知到一次较长等待，整体听感通过。
- 峰值 PSS 已观测约 2.5 GB。正式应用必须处理后台、内存压力、音频焦点和来电中断。
- 候选音色来自同一对 GPT/SoVITS 权重的不同参考音频，不是四套声学模型；切换音色应切换对应参考 SSL/BERT/参考音频特征。

## 禁止重踩

- 不要恢复 v0.6.1 的“启动后台全量预热 + 三语启动初始化 + 最高 2.5 秒自适应预填充”组合。
- 不要为每个句子重新创建 ONNX 会话。
- 不要同时并发运行多个句子的 Genie 推理。
- 不要将 19 种情绪复制成第二套 TTS 情绪系统。
- 不要在一条回复中随机切换参考音色。
- 不要把训练数据集打进 APK；运行时只需要转换后的模型、前端资源和参考特征。
- 不要使用 `USAGE_ASSISTANCE_ACCESSIBILITY` 播放陪伴语音；它可能绕过用户对普通声音的静音预期。

## 最小验收顺序

1. 固定 `daily`，分别验证中文、英文、日文短句。
2. 固定 `daily`，验证中文约 500 字分段播放与打断。
3. 依次固定 `gentle`、`lively`、`cute`，确认设置覆盖情绪映射。
4. 开启 `auto`，用 19 种情绪标签做映射单元测试；无需为每种情绪都跑完整长音频。
5. 用真实 DeepSeek 流式回复测试普通对话和约 1000 字长对话，记录首句等待、队列深度、累计 underrun 与峰值内存。
