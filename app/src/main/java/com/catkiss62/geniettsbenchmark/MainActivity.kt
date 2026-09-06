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
    companion object { private const val REQUEST_ROBERTA_MODEL = 3303 }

    private data class TargetState(
        val id: String,
        val title: String,
        val text: String,
        val preset: TextPreset? = null,
        val prepared: PreparedText? = null,
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
            text = "Genie-TTS v2.0.2\n恬豆 V2 性能收尾测试 v0.3.3"
            textSize = 22f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "完整 RoBERTa · 5 个入选音色 · CPU 8 线程 · 模型常驻复用"
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
            hint = "自由输入中文台词（最多 80 字）"
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
            buttons.addView(freeInput, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            })
            addButton("导入自由输入 RoBERTa 模型") { chooseFrontendModel() }
            addButton("重新生成并播放") { runCurrent() }
            addButton("播放上次合成结果") { playLastGenerated() }
            addButton("运行自动诊断（不播放）") { runDiagnostic() }
            addButton("复制诊断报告") { copyDiagnosticReport() }
            stopButton = addButton("停止当前任务") {
                cancelRequested = true
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
            appendLine("\n预设使用 FP32 RoBERTa；自由输入使用导入的 INT8 RoBERTa。")
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
            appendLine("===== Genie-TTS v0.3.3 自动诊断 · ${timeStamp()} =====")
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS diagnostic v0.3.3", diagnosticReport))
        showCurrent("自动诊断报告已复制。")
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
        worker.shutdownNow()
        frontend.close()
        engine.close()
        super.onDestroy()
    }
}
