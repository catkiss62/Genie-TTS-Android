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
    private lateinit var engine: GenieBenchmarkEngine
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private lateinit var selector: Spinner
    private lateinit var stopButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val history = StringBuilder()
    private val generated = mutableMapOf<String, BenchmarkResult>()
    private val ratings = mutableMapOf<String, String>()
    private val config = EngineConfig(BackendMode.CPU, 8)
    private var preparedRoot: File? = null
    @Volatile private var cancelRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = GenieBenchmarkEngine(this)
        setContentView(buildUi())
        showInitialState()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            setBackgroundColor(Color.rgb(247, 243, 255))
        }
        root.addView(TextView(this).apply {
            text = "Genie-TTS v2.0.2\n恬豆 V2 音色盲测 v0.3.0"
            textSize = 24f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "同一套 GPT + SoVITS 权重 · 9 段参考音频 · 固定测试台词 · CPU 8 线程"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(10))
        })
        selector = Spinner(this)
        root.addView(selector, LinearLayout.LayoutParams(-1, dp(50)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttons)
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(35, 29, 48))
            setTextIsSelectable(true)
            setPadding(0, dp(14), 0, dp(24))
        }
        root.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun showInitialState() {
        try {
            val manifest = engine.readManifest()
            manifest.cases.forEach { item ->
                getPreferences(MODE_PRIVATE).getString("rating_${item.id}", null)?.let { ratings[item.id] = it }
            }
            selector.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                manifest.cases.map { "${it.title}${if (it.id == "ref03") "（短参考）" else ""}" },
            )
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showCandidate()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            addButton("播放当前候选的原始录音") { playOriginal() }
            addButton("生成并播放当前候选") { runCurrent() }
            addButton("播放当前候选的合成结果") { playGenerated() }
            addButton("依次生成全部 9 个候选") { runAll() }
            addRatingRow()
            addButton("复制完整音色测试报告") { copyReport() }
            stopButton = addButton("停止当前生成") {
                cancelRequested = true
                updateStatus("已请求停止；当前 ONNX 算子结束后会退出。")
            }.apply { isEnabled = false }
            showCandidate()
        } catch (error: Throwable) {
            status.text = "音色测试资源未正确打入 APK。\n\n${error.stackTraceToString()}"
        }
    }

    private fun addButton(label: String, action: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(-1, (48 * density).toInt()).apply {
                bottomMargin = (6 * density).toInt()
            })
        }
    }

    private fun addRatingRow() {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("喜欢", "一般", "淘汰").forEach { rating ->
            row.addView(Button(this).apply {
                text = "标记$rating"
                isAllCaps = false
                setOnClickListener { rateCurrent(rating) }
            }, LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f))
        }
        buttons.addView(row, LinearLayout.LayoutParams(-1, (52 * density).toInt()))
    }

    private fun currentCase(): BenchmarkCase {
        val cases = engine.readManifest().cases
        return cases[selector.selectedItemPosition.coerceIn(cases.indices)]
    }

    private fun showCandidate(extra: String? = null) {
        val item = currentCase()
        val result = generated[item.id]
        status.text = buildString {
            appendLine(deviceLine())
            appendLine("当前：${item.title}${if (item.id == "ref03") "（2.70 秒短参考）" else ""}")
            appendLine("参考台词：${item.referenceText}")
            appendLine("固定生成台词：${item.text}")
            appendLine("标记：${ratings[item.id] ?: "未标记"}")
            appendLine("合成：${result?.let { "已生成 · ${"%.3f".format(it.audioSeconds)} 秒 · RTF ${"%.3f".format(it.coreRtf)}" } ?: "尚未生成"}")
            if (extra != null) appendLine("\n$extra")
            appendLine("\n建议先听原音，再生成并听合成结果。情绪文件夹名称不参与推理。")
        }
    }

    private fun playOriginal() {
        val item = currentCase()
        val path = item.referenceAudio ?: return showCandidate("此候选没有原始录音。")
        runCatching { engine.playReferenceAsset(path) }
            .onSuccess { showCandidate("正在播放原始录音。") }
            .onFailure { showCandidate("原始录音播放失败：${it.message}") }
    }

    private fun playGenerated() {
        val item = currentCase()
        val result = generated[item.id] ?: return showCandidate("请先生成当前候选。")
        engine.play(result.audio, engine.readManifest().sampleRate)
        showCandidate("正在播放合成结果。")
    }

    private fun runCurrent() = runTask("生成 ${currentCase().title}") {
        val item = currentCase()
        val result = generate(item)
        engine.play(result.audio, engine.readManifest().sampleRate)
        runOnUiThread { showCandidate("生成完成，正在自动播放合成结果。") }
    }

    private fun runAll() = runTask("生成全部候选") {
        val manifest = engine.readManifest()
        appendHistory("===== 全部候选音色测试 · ${timeStamp()} =====\n${deviceLine()}\n固定台词：${manifest.cases.first().text}\n")
        ensureReady()
        manifest.cases.forEachIndexed { index, item ->
            checkCancelled()
            postProgress(index, manifest.cases.size, "正在生成 ${item.title}……")
            generate(item)
            postProgress(index + 1, manifest.cases.size, "${item.title} 已完成")
        }
        runOnUiThread { showCandidate("九个候选均已生成。请在下拉框切换，逐个播放并标记。") }
    }

    private fun generate(item: BenchmarkCase): BenchmarkResult {
        val root = ensureReady()
        postStatus("${item.title}：正在生成固定台词……")
        val result = engine.run(root, item) { cancelRequested }
        generated[item.id] = result
        appendHistory(buildString {
            appendLine("参考：${item.title}${if (item.id == "ref03") "（短参考）" else ""}")
            appendLine("参考台词：${item.referenceText}")
            append(result.report(deviceLine()))
        })
        return result
    }

    private fun ensureReady(): File {
        val root = preparedRoot ?: engine.prepareAssets(::postStatus).also { preparedRoot = it }
        postStatus("正在加载恬豆 V2 模型（首次会较慢）……")
        engine.loadModels(root, config)
        return root
    }

    private fun rateCurrent(rating: String) {
        val item = currentCase()
        ratings[item.id] = rating
        getPreferences(MODE_PRIVATE).edit().putString("rating_${item.id}", rating).apply()
        appendHistory("评分：${item.title} = $rating\n")
        showCandidate("已标记为“$rating”。")
    }

    private fun copyReport() {
        val manifest = engine.readManifest()
        val report = buildString {
            append(history)
            appendLine("===== 当前评分汇总 =====")
            manifest.cases.forEach { appendLine("${it.title}：${ratings[it.id] ?: "未标记"}") }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS Tiandou audition v0.3.0", report))
        showCandidate("测试报告和评分汇总已复制。")
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
        fun update(view: View) {
            if (view is Button) view.isEnabled = if (::stopButton.isInitialized && view === stopButton) value else !value
            if (view is LinearLayout) for (i in 0 until view.childCount) update(view.getChildAt(i))
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
