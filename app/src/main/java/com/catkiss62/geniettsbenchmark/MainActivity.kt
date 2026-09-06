package com.catkiss62.geniettsbenchmark

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class MatrixScore(
        val config: EngineConfig,
        val warmRtf: Double,
        val warmCoreMs: Double,
        val warmVocoderMs: Double,
        val maxPssMb: Int,
    )

    private lateinit var engine: GenieBenchmarkEngine
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private lateinit var stopButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private val history = StringBuilder()
    private val qualityAudios = mutableListOf<Pair<String, FloatArray>>()
    private var qualityIndex = 0
    private var lastResult: BenchmarkResult? = null
    private var bestConfig: EngineConfig? = null
    @Volatile private var cancelRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = GenieBenchmarkEngine(this)
        setContentView(buildUi())
        showInitialState()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(247, 243, 255))
        }
        root.addView(TextView(this).apply {
            text = "Genie-TTS v2.0.2\nAndroid 性能矩阵 v0.2.0"
            textSize = 25f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "菲比 · GPT-SoVITS V2ProPlus · ARM64\n自动比较 CPU / XNNPACK / NNAPI，温启动结果用于排名"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(10), 0, dp(16))
        })
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
            setPadding(0, dp(18), 0, dp(30))
        }
        val scroll = ScrollView(this).apply { addView(status) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun showInitialState() {
        try {
            val manifest = engine.readManifest()
            addButton("1. 运行完整性能矩阵（中句，每项 3 次）") { runMatrix() }
            addButton("2. 最快配置测试短/中/长（各 3 次）") {
                val config = bestConfig ?: return@addButton updateStatus("请先运行完整性能矩阵，让 APK 选出本机最快配置。")
                runAllCases(config)
            }
            addButton("3. 最快配置分句流式模拟") {
                val config = bestConfig ?: return@addButton updateStatus("请先运行完整性能矩阵，让 APK 选出本机最快配置。")
                runStreamingSimulation(config)
            }
            addButton("播放下一组音质样本") { playNextQualitySample(manifest.sampleRate) }
            addButton("复制完整测试报告") { copyReport() }
            addButton("清空报告") {
                history.setLength(0)
                updateStatus("测试报告已清空；已选出的最快配置仍然保留。")
            }
            stopButton = addButton("停止当前测试") {
                cancelRequested = true
                updateStatus("已请求停止。当前 ONNX 算子结束后会安全退出……")
            }.apply { isEnabled = false }
            status.text = buildString {
                appendLine(deviceLine())
                appendLine("测试包：${manifest.version} / ${manifest.character}")
                appendLine()
                appendLine("建议先关闭省电模式并等待手机降温，再运行第 1 项。")
                appendLine("完整矩阵共 9 个配置 × 3 次，预计需要数分钟；第 1 次视为冷启动，按第 2、3 次平均 RTF 排名。")
                appendLine("切换配置时会卸载旧模型，单项失败不会中断整个矩阵。")
                appendLine("RTF < 1 表示生成速度快于播放速度。NNAPI 为实验项，仅加速 VITS，并允许失败。")
            }
        } catch (error: Throwable) {
            status.text = "基准资源未打入 APK。请安装 GitHub Actions 生成的 full APK。\n\n${error.stackTraceToString()}"
        }
    }

    private fun addButton(label: String, action: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(-1, (52 * density).toInt()).apply {
                bottomMargin = (8 * density).toInt()
            })
        }
    }

    private fun runMatrix() = runTask("完整性能矩阵") {
        history.setLength(0)
        qualityAudios.clear()
        qualityIndex = 0
        bestConfig = null
        val manifest = engine.readManifest()
        val medium = manifest.cases.firstOrNull { it.id == "medium" } ?: manifest.cases[manifest.cases.size / 2]
        val configs = listOf(
            EngineConfig(BackendMode.CPU, 2),
            EngineConfig(BackendMode.CPU, 4),
            EngineConfig(BackendMode.CPU, 6),
            EngineConfig(BackendMode.CPU, 8),
            EngineConfig(BackendMode.XNNPACK, 2),
            EngineConfig(BackendMode.XNNPACK, 4),
            EngineConfig(BackendMode.XNNPACK, 6),
            EngineConfig(BackendMode.XNNPACK, 8),
            EngineConfig(BackendMode.NNAPI_VITS, 4),
        )
        appendHistory(buildString {
            appendLine("===== v0.2.0 完整性能矩阵 · ${timeStamp()} =====")
            appendLine(deviceLine())
            appendLine("句子：${medium.text}")
            appendLine("起始电池温度：${temperatureText(batteryTemperatureC())}")
            appendLine("排名规则：每项第 2、3 轮的平均 RTF（第 1 轮保留但不参与排名）")
        })
        postStatus("准备基准资源……")
        val root = engine.prepareAssets(::postStatus)
        val scores = mutableListOf<MatrixScore>()
        val totalRuns = configs.size * 3
        var completedRuns = 0
        configs.forEachIndexed { configIndex, config ->
            checkCancelled()
            postProgress(completedRuns, totalRuns, "配置 ${configIndex + 1}/${configs.size}：正在加载 ${config.label}……")
            try {
                engine.loadModels(root, config)
                val results = mutableListOf<BenchmarkResult>()
                repeat(3) { runIndex ->
                    checkCancelled()
                    postProgress(completedRuns, totalRuns, "${config.label} · 第 ${runIndex + 1}/3 轮正在生成……")
                    val result = engine.run(root, medium) { cancelRequested }
                    results += result
                    lastResult = result
                    completedRuns += 1
                    appendHistory(result.report(deviceLine(), runIndex + 1))
                    postProgress(completedRuns, totalRuns, "${config.label} · 第 ${runIndex + 1}/3 轮完成 · RTF ${"%.3f".format(result.coreRtf)}")
                }
                val warm = results.drop(1)
                scores += MatrixScore(
                    config,
                    warm.map { it.coreRtf }.average(),
                    warm.map { it.totalInferenceMs.toDouble() }.average(),
                    warm.map { it.vocoderMs.toDouble() }.average(),
                    results.maxOf { it.pssMb },
                )
                qualityAudios += config.label to results.last().audio
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                completedRuns = (configIndex + 1) * 3
                appendHistory(buildString {
                    appendLine("配置失败：${config.label}")
                    appendLine("原因：${error.javaClass.simpleName}: ${error.message}")
                    appendLine("此配置已跳过，矩阵继续。")
                    appendLine(error.stackTraceToString())
                })
                postProgress(completedRuns, totalRuns, "${config.label} 失败并已跳过，继续下一个配置。")
            } finally {
                engine.unloadModels()
                System.gc()
            }
        }
        check(scores.isNotEmpty()) { "所有配置均测试失败，请复制完整报告发给我。" }
        val ranked = scores.sortedBy { it.warmRtf }
        bestConfig = ranked.first().config
        appendHistory(buildString {
            appendLine("===== 性能矩阵排名（温启动平均） =====")
            ranked.forEachIndexed { index, score ->
                appendLine(
                    "${index + 1}. ${score.config.label} · RTF ${"%.3f".format(score.warmRtf)}" +
                        " · 核心 ${"%.0f".format(score.warmCoreMs)} ms · VITS ${"%.0f".format(score.warmVocoderMs)} ms" +
                        " · 峰值 PSS 约 ${score.maxPssMb} MB"
                )
            }
            appendLine("本机推荐：${ranked.first().config.label}")
            appendLine("结束电池温度：${temperatureText(batteryTemperatureC())}")
            appendLine("提示：请用“播放下一组音质样本”逐项确认后端没有引入听感异常。")
        })
        postStatus(history.toString())
    }

    private fun runAllCases(config: EngineConfig) = runTask("最快配置短中长测试") {
        val manifest = engine.readManifest()
        postStatus("准备基准资源……")
        val root = engine.prepareAssets(::postStatus)
        postStatus("正在加载 ${config.label}……")
        engine.loadModels(root, config)
        appendHistory("===== 最快配置短/中/长各 3 次 · ${timeStamp()} =====\n配置：${config.label}\n")
        val total = manifest.cases.size * 3
        var done = 0
        manifest.cases.forEach { testCase ->
            repeat(3) { index ->
                checkCancelled()
                postProgress(done, total, "${testCase.title} · 第 ${index + 1}/3 轮……")
                val result = engine.run(root, testCase) { cancelRequested }
                lastResult = result
                appendHistory(result.report(deviceLine(), index + 1))
                done += 1
                postProgress(done, total, "${testCase.title} · 第 ${index + 1}/3 轮完成 · RTF ${"%.3f".format(result.coreRtf)}")
            }
        }
        appendHistory("结束电池温度：${temperatureText(batteryTemperatureC())}\n")
        postStatus(history.toString())
    }

    private fun runStreamingSimulation(config: EngineConfig) = runTask("分句流式模拟") {
        val manifest = engine.readManifest()
        postStatus("准备基准资源……")
        val root = engine.prepareAssets(::postStatus)
        engine.loadModels(root, config)
        appendHistory("===== 分句流式模拟 · ${timeStamp()} =====\n配置：${config.label}\n说明：用固定短/中/长句模拟连续三段；生成线程与播放时间线并行计算。\n")
        var generationReadyMs = 0L
        var playbackCursorMs = 0L
        var totalAudioMs = 0L
        var totalGapMs = 0L
        var firstAudioDelayMs = 0L
        manifest.cases.forEachIndexed { index, testCase ->
            checkCancelled()
            postProgress(index, manifest.cases.size, "生成模拟分段 ${index + 1}/${manifest.cases.size}：${testCase.title}……")
            val result = engine.run(root, testCase) { cancelRequested }
            lastResult = result
            generationReadyMs += result.totalInferenceMs
            val playbackStartMs = maxOf(generationReadyMs, playbackCursorMs)
            val gapMs = if (index == 0) 0L else maxOf(0L, playbackStartMs - playbackCursorMs)
            if (index == 0) firstAudioDelayMs = playbackStartMs
            val audioMs = (result.audioSeconds * 1000.0).toLong()
            playbackCursorMs = playbackStartMs + audioMs
            totalAudioMs += audioMs
            totalGapMs += gapMs
            appendHistory("分段 ${index + 1}（${testCase.title}）：生成 ${result.totalInferenceMs} ms · 音频 $audioMs ms · 等待/断流 $gapMs ms\n")
            postProgress(index + 1, manifest.cases.size, "模拟分段 ${index + 1}/${manifest.cases.size} 完成")
        }
        val uninterrupted = totalGapMs == 0L
        appendHistory(buildString {
            appendLine("首段可播放延迟：$firstAudioDelayMs ms")
            appendLine("三段总音频：$totalAudioMs ms")
            appendLine("首段之后累计断流：$totalGapMs ms")
            appendLine("模拟结论：${if (uninterrupted) "首段生成后可连续播放" else "仍会发生等待，需要更短分句或进一步优化"}")
            appendLine("注意：这是调度时间线模拟，不是任意文本前处理或真正边合成边输出的实现。")
        })
        postStatus(history.toString())
    }

    private fun playNextQualitySample(sampleRate: Int) {
        if (qualityAudios.isEmpty()) return updateStatus("还没有音质样本。请先完成性能矩阵。")
        val (label, audio) = qualityAudios[qualityIndex % qualityAudios.size]
        qualityIndex = (qualityIndex + 1) % qualityAudios.size
        engine.play(audio, sampleRate)
        updateStatus("正在播放：$label\n这是该配置第 3 轮的中句结果。下一次点击会播放下一个配置。")
    }

    private fun copyReport() {
        if (history.isBlank()) return updateStatus("还没有测试报告。")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS benchmark v0.2.0", history.toString()))
        updateStatus("完整测试报告已复制，共 ${history.lines().size} 行。")
    }

    private fun runTask(name: String, task: () -> Unit) {
        cancelRequested = false
        setBusy(true)
        worker.execute {
            try {
                task()
            } catch (cancelled: CancellationException) {
                appendHistory("===== $name 已由用户停止 · ${timeStamp()} =====\n")
                postStatus("测试已安全停止。已完成的结果仍保留，可复制完整报告。\n\n${history}")
            } catch (error: Throwable) {
                appendHistory(buildString {
                    appendLine("===== $name 失败 · ${timeStamp()} =====")
                    appendLine("${error.javaClass.name}: ${error.message}")
                    appendLine(error.stackTraceToString())
                })
                postStatus(history.toString())
            } finally {
                engine.unloadModels()
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

    private fun batteryTemperatureC(): Double? {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE || tenths <= 0) null else tenths / 10.0
    }

    private fun temperatureText(value: Double?) = value?.let { "${"%.1f".format(it)} °C" } ?: "设备未提供"
    private fun timeStamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    private fun deviceLine() = "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}"
    private fun postStatus(text: String) = runOnUiThread { updateStatus(text) }
    private fun postProgress(done: Int, total: Int, text: String) = runOnUiThread {
        progress.isIndeterminate = false
        progress.max = total
        progress.progress = done
        updateStatus("进度：$done / $total\n$text\n\n已完成结果会持续写入完整报告。")
    }
    private fun updateStatus(text: String) { status.text = text }
    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        if (value) progress.isIndeterminate = true
        for (index in 0 until buttons.childCount) {
            val child = buttons.getChildAt(index)
            child.isEnabled = if (::stopButton.isInitialized && child === stopButton) value else !value
        }
    }

    override fun onDestroy() {
        cancelRequested = true
        worker.shutdownNow()
        engine.close()
        super.onDestroy()
    }
}
