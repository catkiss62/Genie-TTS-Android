package com.catkiss62.geniettsbenchmark

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
    private val history = StringBuilder()
    private val generated = mutableMapOf<String, BenchmarkResult>()
    private val ratings = mutableMapOf<String, String>()
    private val config = EngineConfig(BackendMode.CPU, 8)
    private var preparedRoot: File? = null
    private lateinit var activeTarget: TargetState
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
            text = "Genie-TTS v2.0.2\n恬豆 V2 多台词筛选 v0.3.2"
            textSize = 22f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "完整 RoBERTa · 8 个候选 · 4 类预设 · 本地自由输入 · CPU 8 线程"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(6))
        })
        selector = Spinner(this)
        root.addView(selector, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(TextView(this).apply {
            text = "快捷预设"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttons)
        freeInput = EditText(this).apply {
            hint = "输入中文台词（建议单句 10–60 字，当前最多 80 字）"
            minLines = 2
            maxLines = 4
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
            activeTarget = manifest.presets.first().toState()
            freeInput.setText(activeTarget.text)
            manifest.presets.forEach { preset ->
                manifest.cases.forEach { item ->
                    val key = resultKey(preset.id, item)
                    getPreferences(MODE_PRIVATE).getString("rating_$key", null)?.let { ratings[key] = it }
                }
            }
            selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, manifest.cases.map { it.title })
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showCurrent()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            addPresetRows(manifest.presets)
            buttons.addView(freeInput, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            })
            addButton("生成上方自由输入并播放") { runCustom() }
            addButton("生成并播放当前候选（当前台词）") { runCurrent() }
            addButton("同一候选连续生成 3 次") { runCurrentThreeTimes() }
            addButton("播放当前候选的合成结果") { playGenerated() }
            addButton("播放当前候选的原始录音") { playOriginal() }
            addButton("用当前台词生成全部 8 个候选") { runAll() }
            addRatingRow()
            addButton("复制完整筛选报告") { copyReport() }
            stopButton = addButton("停止当前生成") {
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
                        activeTarget = preset.toState()
                        freeInput.setText(preset.text)
                        showCurrent("已选择“${preset.title}”预设。")
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            buttons.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun TextPreset.toState() = TargetState(id, title, text, preset = this)
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

    private fun addRatingRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("喜欢", "一般", "淘汰").forEach { rating ->
            row.addView(Button(this).apply {
                text = "标记$rating"
                isAllCaps = false
                setOnClickListener { rateCurrent(rating) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        buttons.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun currentCase(): BenchmarkCase {
        val cases = engine.readManifest().cases
        return cases[selector.selectedItemPosition.coerceIn(cases.indices)]
    }

    private fun resultKey(targetId: String, item: BenchmarkCase) = "$targetId:${item.id}"

    private fun showCurrent(extra: String? = null) {
        val item = currentCase()
        val result = generated[resultKey(activeTarget.id, item)]
        status.text = buildString {
            appendLine(deviceLine())
            appendLine("当前候选：${item.title}")
            appendLine("参考台词：${item.referenceText}")
            appendLine("当前台词：${activeTarget.title}")
            appendLine(activeTarget.text)
            appendLine("标记：${ratings[resultKey(activeTarget.id, item)] ?: "未标记"}")
            appendLine("合成：${result?.let { "已生成 · ${"%.3f".format(it.audioSeconds)} 秒 · RTF ${"%.3f".format(it.coreRtf)}" } ?: "尚未生成"}")
            if (extra != null) appendLine("\n$extra")
            appendLine("\n四类预设使用精确 FP32 RoBERTa；自由输入使用手机端 INT8 RoBERTa。")
        }
    }

    private fun playOriginal() {
        val item = currentCase()
        val path = item.referenceAudio ?: return showCurrent("此候选没有原始录音。")
        runCatching { engine.playReferenceAsset(path) }
            .onSuccess { showCurrent("正在播放原始录音。") }
            .onFailure { showCurrent("原始录音播放失败：${it.message}") }
    }

    private fun playGenerated() {
        val item = currentCase()
        val result = generated[resultKey(activeTarget.id, item)] ?: return showCurrent("请先生成当前候选和当前台词。")
        engine.play(result.audio, engine.readManifest().sampleRate)
        showCurrent("正在播放合成结果。")
    }

    private fun runCurrent() = runTask("生成 ${currentCase().title}") {
        val target = activeTarget
        val result = generate(currentCase(), target)
        engine.play(result.audio, engine.readManifest().sampleRate)
        runOnUiThread { showCurrent("生成完成，正在自动播放。") }
    }

    private fun runCurrentThreeTimes() = runTask("连续生成 3 次") {
        val item = currentCase()
        val target = activeTarget
        repeat(3) { index ->
            checkCancelled()
            postProgress(index, 3, "正在生成第 ${index + 1} 次……")
            generate(item, target, index + 1)
            postProgress(index + 1, 3, "第 ${index + 1} 次完成")
        }
        runOnUiThread { showCurrent("连续三次已完成；当前播放记录为第三次，报告保留三次数据。") }
    }

    private fun runAll() = runTask("生成全部候选") {
        val manifest = engine.readManifest()
        val target = activeTarget
        appendHistory("===== 全部候选 · ${target.title} · ${timeStamp()} =====\n${deviceLine()}\n文本：${target.text}\n")
        ensureReady()
        manifest.cases.forEachIndexed { index, item ->
            checkCancelled()
            postProgress(index, manifest.cases.size, "正在生成 ${item.title}……")
            generate(item, target)
            postProgress(index + 1, manifest.cases.size, "${item.title} 已完成")
        }
        runOnUiThread { showCurrent("八个候选均已生成。切换候选即可分别播放。") }
    }

    private fun runCustom() = runTask("生成自由输入") {
        val input = freeInput.text.toString()
        val root = ensureAssets()
        engine.releaseModelsForFrontend()
        engine.prepareFrontendAssets(root, ::postStatus)
        val prepared = frontend.prepare(root, input, ::postStatus)
        checkCancelled()
        val target = TargetState(
            id = "custom_${prepared.normalizedText.hashCode().toUInt().toString(16)}",
            title = "自由输入",
            text = prepared.text,
            prepared = prepared,
        )
        activeTarget = target
        val result = generate(currentCase(), target)
        engine.play(result.audio, engine.readManifest().sampleRate)
        runOnUiThread { showCurrent("自由输入生成完成，正在自动播放。") }
    }

    private fun generate(item: BenchmarkCase, target: TargetState, runNumber: Int? = null): BenchmarkResult {
        val root = ensureReady()
        postStatus("${item.title} · ${target.title}：正在生成……")
        val result = when {
            target.preset != null -> engine.runPreset(root, item, target.preset) { cancelRequested }
            target.prepared != null -> engine.runPrepared(root, item, target.prepared) { cancelRequested }
            else -> error("缺少目标台词特征")
        }
        generated[resultKey(target.id, item)] = result
        appendHistory(buildString {
            appendLine("参考：${item.title}")
            appendLine("参考台词：${item.referenceText}")
            append(result.report(deviceLine(), runNumber))
        })
        return result
    }

    private fun ensureAssets(): File = preparedRoot ?: engine.prepareAssets(::postStatus).also { preparedRoot = it }

    private fun ensureReady(): File {
        val root = ensureAssets()
        postStatus("正在加载恬豆 V2 模型（首次会较慢）……")
        engine.loadModels(root, config)
        return root
    }

    private fun rateCurrent(rating: String) {
        val item = currentCase()
        val key = resultKey(activeTarget.id, item)
        ratings[key] = rating
        getPreferences(MODE_PRIVATE).edit().putString("rating_$key", rating).apply()
        appendHistory("评分：${activeTarget.title} · ${item.title} = $rating\n")
        showCurrent("已标记为“$rating”。")
    }

    private fun copyReport() {
        val manifest = engine.readManifest()
        val report = buildString {
            append(history)
            appendLine("===== 当前预设评分汇总 =====")
            manifest.presets.forEach { preset ->
                appendLine(preset.title)
                manifest.cases.forEach { item ->
                    appendLine("${item.title}：${ratings[resultKey(preset.id, item)] ?: "未标记"}")
                }
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS selection v0.3.2", report))
        showCurrent("测试报告和评分汇总已复制。")
    }

    private fun runTask(name: String, task: () -> Unit) {
        cancelRequested = false
        setBusy(true)
        worker.execute {
            try {
                task()
            } catch (cancelled: CancellationException) {
                appendHistory("===== $name 已停止 · ${timeStamp()} =====\n")
                postStatus("测试已停止；已经生成的结果仍然保留。")
            } catch (error: Throwable) {
                appendHistory("===== $name 失败 · ${timeStamp()} =====\n${error.stackTraceToString()}\n")
                postStatus("$name 失败：${error.message}\n\n${error.stackTraceToString()}")
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun appendHistory(text: String) {
        if (history.isNotEmpty() && !history.endsWith("\n\n")) history.appendLine()
        history.append(text.trimEnd()).appendLine().appendLine()
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
        updateStatus("进度：$done / $total\n$text\n\n已完成结果会保留在报告中。")
    }
    private fun updateStatus(text: String) { status.text = text }
    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (value) progress.isIndeterminate = true
        selector.isEnabled = !value
        freeInput.isEnabled = !value
        fun update(view: View) {
            if (view is Button) view.isEnabled = if (::stopButton.isInitialized && view === stopButton) value else !value
            if (view is LinearLayout) for (index in 0 until view.childCount) update(view.getChildAt(index))
        }
        update(buttons)
    }

    override fun onDestroy() {
        cancelRequested = true
        worker.shutdownNow()
        engine.close()
        super.onDestroy()
    }
}
