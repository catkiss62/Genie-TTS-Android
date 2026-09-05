package com.catkiss62.geniettsbenchmark

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var engine: GenieBenchmarkEngine
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var lastResult: BenchmarkResult? = null
    private var latestReport = ""

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
            text = "Genie-TTS v2.0.2\n手机性能测试"
            textSize = 26f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "菲比 · GPT-SoVITS V2ProPlus · CPU / ARM64\n固定测试句用于验证真实核心推理速度"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(10), 0, dp(16))
        })
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)
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
            manifest.cases.forEach { testCase -> addButton("运行：${testCase.title}") { runCase(testCase) } }
            addButton("播放上一次结果") {
                lastResult?.let { engine.play(it.audio, manifest.sampleRate) } ?: updateStatus("还没有生成语音。")
            }
            addButton("复制测试报告") {
                if (latestReport.isBlank()) return@addButton updateStatus("还没有测试报告。")
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS benchmark", latestReport))
                updateStatus("测试报告已复制。\n\n$latestReport")
            }
            status.text = buildString {
                appendLine(deviceLine())
                appendLine("测试包：${manifest.version} / ${manifest.character}")
                appendLine()
                appendLine("第一次点击会释放并加载大型模型，耗时明显更长。")
                appendLine("第二次开始才是温启动速度，建议同一句至少测试三次。")
                appendLine("RTF < 1 表示生成速度快于播放速度。")
            }
        } catch (error: Throwable) {
            status.text = "基准资源未打入 APK。请安装 GitHub Actions 生成的 full APK。\n\n${error.stackTraceToString()}"
        }
    }

    private fun addButton(label: String, action: () -> Unit) {
        val density = resources.displayMetrics.density
        val button = Button(this).apply { text = label; isAllCaps = false; gravity = Gravity.CENTER; setOnClickListener { action() } }
        buttons.addView(button, LinearLayout.LayoutParams(-1, (52 * density).toInt()).apply { bottomMargin = (8 * density).toInt() })
    }

    private fun runCase(testCase: BenchmarkCase) {
        setBusy(true)
        worker.execute {
            try {
                postStatus("准备基准资源……")
                val root = engine.prepareAssets(::postStatus)
                postStatus("正在加载四个 Genie ONNX 模型……")
                engine.loadModels(root)
                postStatus("正在生成“${testCase.title}”……")
                val result = engine.run(root, testCase)
                lastResult = result
                latestReport = result.report(deviceLine())
                engine.play(result.audio, engine.readManifest().sampleRate)
                postStatus(latestReport)
            } catch (error: Throwable) {
                latestReport = buildString {
                    appendLine("Genie-TTS Android Benchmark v0.1.1")
                    appendLine(deviceLine())
                    appendLine("测试：${testCase.title}")
                    appendLine("文本：${testCase.text}")
                    appendLine("结果：失败")
                    append(error.stackTraceToString())
                }
                postStatus(latestReport)
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun deviceLine() = "设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}"
    private fun postStatus(text: String) = runOnUiThread { updateStatus(text) }
    private fun updateStatus(text: String) { status.text = text }
    private fun setBusy(value: Boolean) {
        progress.visibility = if (value) View.VISIBLE else View.GONE
        for (index in 0 until buttons.childCount) buttons.getChildAt(index).isEnabled = !value
    }

    override fun onDestroy() {
        worker.shutdownNow()
        engine.close()
        super.onDestroy()
    }
}
