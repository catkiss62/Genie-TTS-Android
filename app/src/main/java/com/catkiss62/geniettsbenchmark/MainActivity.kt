package com.catkiss62.geniettsbenchmark

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROBERTA_MODEL = 3303
        private const val ENGLISH_TEST_TEXT = "请依次读出 API、GPT 和 token，每一个字母都不能跳过。"
        private val LONG_STREAM_TEXT = """
            刚才安静下来的时候，我突然想到了一件很有意思的事。我们每天都会遇到很多细小的瞬间，有些当时觉得普通，过一会儿再想，却会发现它们其实很值得记住。比如路边刚亮起来的灯，窗外突然吹过的一阵风，或者一句没有准备、却刚好让人笑出来的话。
            如果把这些事情都认真收集起来，也许普通的一天就会变得很不一样。我想先把今天发生的事情慢慢讲给你听，然后再听听你的版本。你不需要一次说完，想到哪里就说到哪里；就算中途停下来也没关系，我会顺着刚才的话继续等你。
            要是以后我们积攒了很多这样的片段，我希望偶尔能把它们重新翻出来。也许是一次没有结果的争论，也许是半夜突然聊到的怪问题，也可能只是你随口说过喜欢某种味道。过了很久再提起来，应该会有一种原来我们已经一起走了这么远的感觉。
            不过现在先不想那么远。你今天有没有遇到什么想吐槽的事情？开心的、离谱的、无聊的都可以。如果实在想不到，也可以从现在最想吃什么开始。反正话题不用特别郑重，我们可以一边乱聊，一边看它最后会跑到哪里去。
            我已经准备好认真听了，但也不保证一直老老实实。要是发现哪里特别好玩，我可能会忍不住插一句；要是你故意卖关子，我也可能追着问到底。总之，接下来的时间不用赶，我们慢慢说。
        """.trimIndent().replace("\n", "")
    }

    private data class TargetState(
        val id: String,
        val title: String,
        val text: String,
        val preset: TextPreset? = null,
        val prepared: PreparedText? = null,
    )

    private data class StreamSegmentRun(
        val index: Int,
        val text: String,
        val result: BenchmarkResult,
        val readyAfterStartMs: Long,
        val bufferMarginMs: Long?,
    )

    private lateinit var engine: GenieBenchmarkEngine
    private lateinit var frontend: ChineseFrontend
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private lateinit var selector: Spinner
    private lateinit var freeInput: EditText
    private lateinit var stopButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val config = EngineConfig(BackendMode.CPU, 8)
    private var preparedRoot: File? = null
    private var selectedPreset: TextPreset? = null
    private var lastResult: BenchmarkResult? = null
    private var lastResultLabel: String? = null
    private var diagnosticReport = ""
    private var longStreamReport = ""
    @Volatile private var activeStream: StreamingAudioPlayer? = null
    @Volatile private var cancelRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = GenieBenchmarkEngine(this)
        frontend = ChineseFrontend(engine)
        setContentView(buildUi())
        showInitialState()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(247, 243, 255))
        }
        root.addView(TextView(this).apply {
            text = "Genie-TTS v2.0.2\n恬豆 V2 中英混合测试 v0.4.1"
            textSize = 22f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "完整 RoBERTa · 英文字母逐读 · token→拖肯 · 约 500 字分段流式"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "选择候选音色"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        selector = Spinner(this)
        root.addView(selector, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(TextView(this).apply {
            text = "选择台词类型"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttons)
        freeInput = EditText(this).apply {
            hint = "自由输入中文或英文字母（转写后最多 80 字）"
            setText("你好呀，今天想聊点什么？")
            minLines = 2
            maxLines = 4
            isEnabled = false
            setTextColor(Color.rgb(35, 29, 48))
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(35, 29, 48))
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(20))
        }
        root.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun showInitialState() {
        try {
            val manifest = engine.readManifest()
            selectedPreset = manifest.presets.first()
            selector.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, manifest.cases.map { it.title }
            )
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showCurrent()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            addPresetRows(manifest.presets)
            addButton("自由输入") {
                selectedPreset = null
                freeInput.isEnabled = true
                freeInput.requestFocus()
                showCurrent("已切换到自由输入。")
            }
            addButton("填入英文逐字母测试") {
                selectedPreset = null
                freeInput.isEnabled = true
                freeInput.setText(ENGLISH_TEST_TEXT)
                showCurrent("已填入英文测试；点击“重新生成并播放”即可验证。")
            }
            buttons.addView(freeInput, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            })
            addButton("导入自由输入 RoBERTa 模型") { chooseFrontendModel() }
            addButton("重新生成并播放") { runCurrent() }
            addButton("播放上次合成结果") { playLastGenerated() }
            addButton("运行约 500 字分段流式测试") { runLongStreamTest() }
            addButton("复制长文本流式报告") { copyLongStreamReport() }
            addButton("运行自动诊断（不播放）") { runDiagnostic() }
            addButton("复制诊断报告") { copyDiagnosticReport() }
            stopButton = addButton("停止当前任务") {
                cancelRequested = true
                activeStream?.cancel()
                updateStatus("已请求停止；当前 ONNX 算子结束后会退出。")
            }.apply { isEnabled = false }
            showCurrent()
        } catch (error: Throwable) {
            status.text = "测试资源未正确打入 APK。\n\n${error.stackTraceToString()}"
        }
    }

    private fun addPresetRows(presets: List<TextPreset>) {
        presets.chunked(2).forEach { rowPresets ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowPresets.forEach { preset ->
                row.addView(Button(this).apply {
                    text = preset.title
                    isAllCaps = false
                    setOnClickListener {
                        selectedPreset = preset
                        freeInput.isEnabled = false
                        showCurrent("已选择“${preset.title}”。")
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            buttons.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun addButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(4) })
        }
    }

    private fun currentCase(): BenchmarkCase {
        val cases = engine.readManifest().cases
        return cases[selector.selectedItemPosition.coerceIn(cases.indices)]
    }

    private fun selectedTextTitle() = selectedPreset?.title ?: "自由输入"
    private fun selectedText() = selectedPreset?.text ?: freeInput.text.toString()

    private fun showCurrent(extra: String? = null) {
        val item = currentCase()
        val modelReady = preparedRoot?.let { engine.hasFrontendModel(it) } == true
        status.text = buildString {
            appendLine(deviceLine())
            appendLine("当前候选：${item.title}")
            appendLine("参考台词：${item.referenceText}")
            if (item.playbackGainDb != 0.0) appendLine("播放响度校准：${"%+.1f".format(item.playbackGainDb)} dB")
            appendLine("当前台词：${selectedTextTitle()}")
            appendLine(selectedText())
            appendLine("自由输入模型：${if (modelReady) "已导入" else "尚未导入或尚未检测"}")
            appendLine("上次合成：${lastResultLabel ?: "无"}")
            lastResult?.let {
                appendLine("端到端 ${it.endToEndMs} ms · 核心 ${it.totalInferenceMs} ms · RTF ${"%.3f".format(it.coreRtf)}")
            }
            if (extra != null) appendLine("\n$extra")
            appendLine("\n预设使用 FP32 RoBERTa；自由输入和长文本使用导入的 INT8 RoBERTa。")
        }
    }

    private fun playLastGenerated() {
        val result = lastResult ?: return showCurrent("还没有可以回放的合成结果。")
        engine.play(result.audio, engine.readManifest().sampleRate, result.playbackGainDb)
        showCurrent("正在播放上次合成结果，不会重新推理。")
    }

    private fun runCurrent() = runTask("重新生成") {
        val started = System.nanoTime()
        val target = prepareSelectedTarget()
        val item = currentCase()
        val result = generate(item, target, requestStartedNs = started)
        lastResult = result
        lastResultLabel = "${item.title} · ${target.title}"
        engine.play(result.audio, engine.readManifest().sampleRate, item.playbackGainDb)
        runOnUiThread { showCurrent("已重新推理并开始播放。") }
    }

    private fun runLongStreamTest() = runTask("长文本流式测试") {
        val root = ensureAssets()
        check(engine.hasFrontendModel(root)) { "请先导入配套的自由输入 RoBERTa ONNX 文件" }
        engine.stopPlayback()
        val item = currentCase()
        val segments = ChineseTextSegmenter.split(LONG_STREAM_TEXT)
        val testStartedNs = System.nanoTime()
        val runs = ArrayList<StreamSegmentRun>(segments.size)
        var player: StreamingAudioPlayer? = null
        var playbackStartedNs = 0L
        var queuedAudioMs = 0L

        frontend.clearPreparedCache()
        engine.prepareFrontendAssets(root, ::postStatus)
        try {
            segments.forEachIndexed { index, text ->
                checkCancelled()
                postProgress(index, segments.size, "第 ${index + 1}/${segments.size} 段：正在进行中文前处理与推理……")
                val segmentStartedNs = System.nanoTime()
                val prepared = frontend.prepare(root, text, ::postStatus)
                val modelLoad = engine.loadModels(root, config)
                val result = engine.runPrepared(
                    root, item, prepared, modelLoad, segmentStartedNs
                ) { cancelRequested }
                val readyNs = System.nanoTime()

                val margin = if (index == 0) {
                    player = StreamingAudioPlayer(engine.readManifest().sampleRate, item.playbackGainDb)
                    activeStream = player
                    player!!.enqueue(result.audio)
                    playbackStartedNs = player!!.awaitStarted()
                    null
                } else {
                    val elapsedPlaybackMs = (readyNs - playbackStartedNs) / 1_000_000L
                    (queuedAudioMs - elapsedPlaybackMs).also { player!!.enqueue(result.audio) }
                }
                queuedAudioMs += (result.audioSeconds * 1000.0).toLong()
                runs += StreamSegmentRun(
                    index + 1, text, result.copy(audio = FloatArray(0)),
                    (readyNs - testStartedNs) / 1_000_000L, margin
                )
                postProgress(
                    index + 1, segments.size,
                    "第 ${index + 1}/${segments.size} 段已进入播放队列" +
                        (margin?.let { " · 缓冲余量 ${it} ms" } ?: " · 首段已开播")
                )
            }

            checkCancelled()
            val generationFinishedNs = System.nanoTime()
            postStatus("全部 ${segments.size} 段已经生成，正在等待流式播放结束……")
            player!!.finish()
            val playback = player!!.awaitCompletion()
            checkCancelled()

            val totalCoreMs = runs.sumOf { it.result.totalInferenceMs }
            val totalFrontendMs = runs.sumOf { it.result.frontendMs }
            val totalAudioSeconds = runs.sumOf { it.result.audioSeconds }
            val margins = runs.mapNotNull { it.bufferMarginMs }
            val lateSegments = margins.count { it < 0L }
            val firstAudioWaitMs = (playback.playbackStartedNs - testStartedNs) / 1_000_000L
            val generationWallMs = (generationFinishedNs - testStartedNs) / 1_000_000L
            val playbackWallMs = (playback.playbackFinishedNs - playback.playbackStartedNs) / 1_000_000L
            val totalWallMs = (playback.playbackFinishedNs - testStartedNs) / 1_000_000L
            val aggregateRtf = totalCoreMs / (totalAudioSeconds * 1000.0)

            longStreamReport = buildString {
                appendLine("===== Genie-TTS v0.4.1 长文本分段流式报告 · ${timeStamp()} =====")
                appendLine(deviceLine())
                appendLine("音色：${item.title} · ${config.label}")
                appendLine("原文：${LONG_STREAM_TEXT.length} 字符 · ${segments.size} 段 · 单段最长 ${segments.maxOf { it.length }} 字符")
                appendLine("策略：首段生成后立即播放；播放期间按顺序生成后续段；采样参数未修改。")
                appendLine("首段开播等待：$firstAudioWaitMs ms")
                appendLine("全部分段生成完成：$generationWallMs ms")
                appendLine("合计音频：${"%.2f".format(totalAudioSeconds)} s · 播放阶段：${playbackWallMs} ms")
                appendLine("合计核心推理：$totalCoreMs ms · 聚合 RTF：${"%.3f".format(aggregateRtf)}")
                appendLine("合计中文前处理：$totalFrontendMs ms")
                appendLine("最小缓冲余量：${margins.minOrNull()?.let { "$it ms" } ?: "无"} · 迟到分段：$lateSegments/${margins.size}")
                appendLine("AudioTrack underrun：${playback.underrunCount} · 峰值 PSS：约 ${runs.maxOf { it.result.pssMb }} MB")
                appendLine("从点击到播放完成：$totalWallMs ms")
                runs.forEach { run ->
                    appendLine("\n--- 第 ${run.index}/${runs.size} 段 ---")
                    appendLine("文本：${run.text}")
                    appendLine("字符：${run.text.length} · 前处理：${run.result.frontendMs} ms · 核心：${run.result.totalInferenceMs} ms")
                    appendLine("音频：${"%.2f".format(run.result.audioSeconds)} s · RTF：${"%.3f".format(run.result.coreRtf)} · Decoder：${run.result.decoderIterations} 次")
                    appendLine("生成就绪：点击后 ${run.readyAfterStartMs} ms · 入队前缓冲余量：${run.bufferMarginMs?.let { "$it ms" } ?: "首段"}")
                    appendLine("语义：${run.result.semanticTokens} tokens · ${run.result.semanticHash} · PSS：约 ${run.result.pssMb} MB")
                }
            }
            postStatus("约 500 字流式测试完成。请确认播放中有没有停顿，再复制长文本流式报告发给我。")
        } finally {
            activeStream = null
            if (cancelRequested) player?.cancel()
            player?.close()
        }
    }

    private fun prepareSelectedTarget(): TargetState {
        selectedPreset?.let { return TargetState(it.id, it.title, it.text, preset = it) }
        val root = ensureAssets()
        check(engine.hasFrontendModel(root)) { "请先导入配套的自由输入 RoBERTa ONNX 文件" }
        engine.prepareFrontendAssets(root, ::postStatus)
        val prepared = frontend.prepare(root, freeInput.text.toString(), ::postStatus)
        return TargetState(
            id = "custom_${prepared.normalizedText.hashCode().toUInt().toString(16)}",
            title = "自由输入",
            text = prepared.text,
            prepared = prepared,
        )
    }

    private fun runDiagnostic() = runTask("自动诊断") {
        val manifest = engine.readManifest()
        val primary = manifest.cases.first { it.id == "ref01" }
        val neutral = manifest.presets.first { it.id == "neutral" }
        val presetOrder = listOf(
            neutral,
            neutral,
            manifest.presets.first { it.id == "question" },
            manifest.presets.first { it.id == "comfort" },
            manifest.presets.first { it.id == "lively" },
        )
        val canRunDynamic = engine.hasFrontendModel(ensureAssets())
        val total = presetOrder.size + if (canRunDynamic) 2 else 0
        val results = mutableListOf<Pair<String, BenchmarkResult>>()
        engine.unloadModels()
        frontend.closeModel()
        frontend.clearPreparedCache()

        presetOrder.forEachIndexed { index, preset ->
            checkCancelled()
            val label = when (index) {
                0 -> "冷启动 · 普通闲聊"
                1 -> "热推理 · 普通闲聊"
                else -> "热推理 · ${preset.title}"
            }
            postProgress(index, total, "正在运行 $label……")
            val target = TargetState(preset.id, preset.title, preset.text, preset = preset)
            results += label to generate(primary, target, System.nanoTime())
            postProgress(index + 1, total, "$label 完成")
        }

        if (canRunDynamic) {
            val dynamicText = "今晚想吃点什么？我突然有一点期待。"
            repeat(2) { repeatIndex ->
                checkCancelled()
                val position = presetOrder.size + repeatIndex
                val label = if (repeatIndex == 0) "自由输入 · 首次前处理" else "自由输入 · 特征缓存"
                postProgress(position, total, "正在运行 $label……")
                val started = System.nanoTime()
                engine.prepareFrontendAssets(ensureAssets(), ::postStatus)
                val prepared = frontend.prepare(ensureAssets(), dynamicText, ::postStatus)
                val target = TargetState("diagnostic_dynamic", "自由输入", prepared.text, prepared = prepared)
                results += label to generate(primary, target, started)
                postProgress(position + 1, total, "$label 完成")
            }
        }

        val warmPresets = results
            .filter { (label, _) -> label.startsWith("热推理") }
            .map { it.second }
        diagnosticReport = buildString {
            appendLine("===== Genie-TTS v0.4.1 自动诊断 · ${timeStamp()} =====")
            appendLine(deviceLine())
            appendLine("固定音色：候选 1（日常主音色）")
            appendLine("范围：一次冷启动、四类预设热推理、自由输入首次/缓存对照；全程不播放。")
            if (!canRunDynamic) appendLine("自由输入：未导入 RoBERTa，因此本次跳过。")
            results.forEach { (label, result) ->
                appendLine("\n--- $label ---")
                append(result.report(deviceLine()))
            }
            if (warmPresets.isNotEmpty()) {
                appendLine("\n===== 汇总 =====")
                appendLine("预设热运行平均核心 RTF：${"%.3f".format(warmPresets.map { it.coreRtf }.average())}")
                appendLine("预设热运行平均端到端等待：${warmPresets.map { it.endToEndMs }.average().toLong()} ms")
                appendLine("峰值 PSS：约 ${results.maxOf { it.second.pssMb }} MB")
            }
        }
        postStatus("自动诊断完成，共 ${results.size} 项。点击“复制诊断报告”发送给我即可。")
    }

    private fun generate(
        item: BenchmarkCase,
        target: TargetState,
        requestStartedNs: Long,
    ): BenchmarkResult {
        val root = ensureAssets()
        postStatus("${item.title} · ${target.title}：正在生成……")
        val modelLoad = engine.loadModels(root, config)
        return when {
            target.preset != null -> engine.runPreset(
                root, item, target.preset, modelLoad, requestStartedNs
            ) { cancelRequested }
            target.prepared != null -> engine.runPrepared(
                root, item, target.prepared, modelLoad, requestStartedNs
            ) { cancelRequested }
            else -> error("缺少目标台词特征")
        }
    }

    private fun chooseFrontendModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_ROBERTA_MODEL)
    }

    @Deprecated("Legacy activity result is sufficient for this single-file test app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROBERTA_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runTask("导入 RoBERTa") {
            val root = ensureAssets()
            frontend.closeModel()
            frontend.clearPreparedCache()
            engine.importFrontendModel(root, uri, ::postStatus)
            runOnUiThread { showCurrent("RoBERTa 模型校验并导入完成；后续会常驻复用。") }
        }
    }

    private fun copyDiagnosticReport() {
        if (diagnosticReport.isBlank()) return showCurrent("请先运行一次自动诊断。")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS diagnostic v0.4.1", diagnosticReport))
        showCurrent("自动诊断报告已复制。")
    }

    private fun copyLongStreamReport() {
        if (longStreamReport.isBlank()) return showCurrent("请先运行一次约 500 字分段流式测试。")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS long stream v0.4.1", longStreamReport))
        showCurrent("长文本流式报告已复制。")
    }

    private fun ensureAssets(): File = preparedRoot ?: engine.prepareAssets(::postStatus).also { preparedRoot = it }

    private fun runTask(name: String, task: () -> Unit) {
        cancelRequested = false
        setBusy(true)
        worker.execute {
            try {
                task()
            } catch (cancelled: CancellationException) {
                postStatus("$name 已停止。")
            } catch (error: Throwable) {
                postStatus("$name 失败：${error.message}\n\n${error.stackTraceToString()}")
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun checkCancelled() {
        if (cancelRequested) throw CancellationException("用户停止了测试")
    }

    private fun timeStamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    private fun deviceLine() = "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}"
    private fun postStatus(text: String) = runOnUiThread { updateStatus(text) }
    private fun postProgress(done: Int, total: Int, text: String) = runOnUiThread {
        progress.isIndeterminate = false
        progress.max = total
        progress.progress = done
        updateStatus("进度：$done / $total\n$text")
    }
    private fun updateStatus(text: String) { status.text = text }

    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (value) progress.isIndeterminate = true
        selector.isEnabled = !value
        freeInput.isEnabled = !value && selectedPreset == null
        fun update(view: View) {
            if (view is Button) view.isEnabled = if (::stopButton.isInitialized && view === stopButton) value else !value
            if (view is LinearLayout) for (index in 0 until view.childCount) update(view.getChildAt(index))
        }
        update(buttons)
    }

    override fun onDestroy() {
        cancelRequested = true
        activeStream?.cancel()
        worker.shutdownNow()
        frontend.close()
        engine.close()
        super.onDestroy()
    }
}
