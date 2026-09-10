package com.catkiss62.geniettsbenchmark

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROBERTA_MODEL = 3303
        private const val ENGLISH_TEST_TEXT = "请依次读出 API、GPT 和 token，每一个字母都不能跳过。"
        private const val NATIVE_ENGLISH_TEXT = "DeepSeek uses a token. I am happy to hear your voice."
        private const val NATIVE_JAPANESE_TEXT = "こんにちは。今日は一緒に、ゆっくりお話ししましょう。"
        private val NATIVE_ENGLISH_PHONES = longArrayOf(
            3, 26, 58, 73, 75, 58, 61, 92, 88, 93, 12, 93, 12, 80, 68, 61, 12, 64, 3,
            22, 10, 63, 51, 10, 73, 57, 80, 88, 51, 58, 74, 92, 16, 74, 90, 71, 75, 3,
        )
        private val NATIVE_JAPANESE_PHONES = longArrayOf(
            3, 222, 229, 322, 64, 227, 160, 125, 160, 316, 96, 3,
            223, 229, 323, 229, 316, 96, 160, 322, 126, 251, 229, 227, 160, 1,
            318, 254, 322, 126, 222, 254, 323, 248, 160, 229, 322, 158, 96, 227, 96,
            251, 160, 251, 160, 225, 96, 251, 229, 323, 229, 3,
        )
        private val LONG_STREAM_TEXT_ZH = """
            刚才安静下来的时候，我突然想到了一件很有意思的事。我们每天都会遇到很多细小的瞬间，有些当时觉得普通，过一会儿再想，却会发现它们其实很值得记住。比如路边刚亮起来的灯，窗外突然吹过的一阵风，或者一句没有准备、却刚好让人笑出来的话。
            如果把这些事情都认真收集起来，也许普通的一天就会变得很不一样。我想先把今天发生的事情慢慢讲给你听，然后再听听你的版本。你不需要一次说完，想到哪里就说到哪里；就算中途停下来也没关系，我会顺着刚才的话继续等你。
            要是以后我们积攒了很多这样的片段，我希望偶尔能把它们重新翻出来。也许是一次没有结果的争论，也许是半夜突然聊到的怪问题，也可能只是你随口说过喜欢某种味道。过了很久再提起来，应该会有一种原来我们已经一起走了这么远的感觉。
            不过现在先不想那么远。你今天有没有遇到什么想吐槽的事情？开心的、离谱的、无聊的都可以。如果实在想不到，也可以从现在最想吃什么开始。反正话题不用特别郑重，我们可以一边乱聊，一边看它最后会跑到哪里去。
            我已经准备好认真听了，但也不保证一直老老实实。要是发现哪里特别好玩，我可能会忍不住插一句；要是你故意卖关子，我也可能追着问到底。总之，接下来的时间不用赶，我们慢慢说。
        """.trimIndent().replace("\n", "")
        private val LONG_STREAM_TEXT_EN = """
            When the room became quiet, I remembered a small moment from earlier today. Nothing dramatic happened, but afternoon light reached the window at just the right angle, and everything looked calm and warm. I wanted to tell you before the memory faded. We often forget ordinary details, even though they can make a difficult day feel softer: a distant song, a warm cup of tea, or a message arriving at the perfect time. If you want, tell me one small thing you noticed today. It need not be important. We can begin there and take our time.
        """.trimIndent().replace("\n", " ")
        private val LONG_STREAM_TEXT_JA = """
            部屋が静かになったとき、今日あった小さな出来事を思い出しました。特別な事件ではないけれど、午後の光がちょうど窓から差し込んで、ほんの少しだけ部屋が暖かく見えたんです。その瞬間を忘れる前に、あなたに話しておきたいと思いました。毎日の中には、気づいてもすぐに忘れてしまうことがたくさんあります。でも、遠くから聞こえた音楽や、まだ温かかったお茶や、ちょうどいいタイミングで届いた言葉みたいに、小さなものが一日を優しくしてくれることもあります。もしよかったら、あなたが今日見つけた小さなことも教えてください。面白い話でなくても、立派な話でなくても大丈夫です。思いついたところから始めて、途中で話題が変わっても気にしないでください。急いで結論を出す必要はありません。私はここで、あなたの言葉をゆっくり聞いています。そして、話し終わったあとに少し笑えたなら、それだけで今日は十分にいい時間だったと思います。明日の予定がまだ決まっていなくても、心配はいりません。今は目の前の時間を大切にして、気になったことを一つずつ話していきましょう。言葉がすぐに見つからないときは、少し黙って考えても大丈夫です。静かな時間も会話の一部です。あなたのペースで進めばいいんです。
        """.trimIndent().replace("\n", "")
    }

    private enum class LongStreamLanguage(val title: String) {
        CHINESE("中文"),
        ENGLISH("英文"),
        JAPANESE("日文"),
    }

    private data class LongStreamTest(
        val language: LongStreamLanguage,
        val text: String,
        val targetChars: Int,
        val maxChars: Int,
    )

    private data class TargetState(
        val id: String,
        val title: String,
        val text: String,
        val preset: TextPreset? = null,
        val prepared: PreparedText? = null,
    )

    private data class FixedLanguageTest(
        val id: String,
        val title: String,
        val text: String,
        val featureTitle: String,
        val phones: LongArray,
    )

    private data class StreamSegmentRun(
        val index: Int,
        val text: String,
        val result: BenchmarkResult,
        val readyAfterStartMs: Long,
        val bufferMarginMs: Long?,
    )

    private sealed interface DialogueEvent {
        data class Segment(
            val index: Int,
            val text: String,
            val closedAfterStartMs: Long,
            val textQueueDepth: Int,
            val sourceUnitCount: Int = 1,
        ) : DialogueEvent

        data class Complete(
            val fullText: String,
            val finishedAfterStartMs: Long,
            val apiResult: DeepSeekStreamResult?,
        ) : DialogueEvent

        data class Failure(val error: Throwable) : DialogueEvent
    }

    private data class DialogueSegmentRun(
        val index: Int,
        val text: String,
        val closedAfterStartMs: Long,
        val textQueueDepth: Int,
        val audioReadyAfterStartMs: Long,
        val bufferMarginMs: Long?,
        val sourceUnitCount: Int,
        val result: BenchmarkResult,
    )

    private lateinit var engine: GenieBenchmarkEngine
    private lateinit var frontend: ChineseFrontend
    private var englishFrontend: EnglishFrontend? = null
    private var japaneseFrontend: NativeJapaneseFrontend? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: LinearLayout
    private lateinit var voicePackageSelector: Spinner
    private lateinit var playbackTuningPanel: LinearLayout
    private lateinit var playbackSpeedLabel: TextView
    private lateinit var playbackPitchLabel: TextView
    private lateinit var highFrequencySofteningLabel: TextView
    private lateinit var playbackSpeedSlider: SeekBar
    private lateinit var playbackPitchSlider: SeekBar
    private lateinit var highFrequencySofteningSlider: SeekBar
    private lateinit var selector: Spinner
    private lateinit var runtimePresetSelector: Spinner
    private lateinit var freeInput: EditText
    private lateinit var dialogueLanguageSelector: Spinner
    private lateinit var dialogueLengthSelector: Spinner
    private lateinit var dialoguePromptInput: EditText
    private lateinit var deepSeekModelSelector: Spinner
    private lateinit var deepSeekApiKeyInput: EditText
    private lateinit var dialogueOutput: TextView
    private lateinit var stopButton: Button
    private lateinit var pageScroll: ScrollView
    private val worker = Executors.newSingleThreadExecutor()
    private val config = EngineConfig(BackendMode.CPU, 8)
    private var currentVoicePackage = VoicePackageCatalog.all.first()
    private var jiuhuPlaybackSpeed = 1.0f
    private var jiuhuPitchSemitones = 0
    private var jiuhuHighFrequencySofteningDb = 0.0f
    private var preparedRoot: File? = null
    private var selectedPreset: TextPreset? = null
    private var lastResult: BenchmarkResult? = null
    private var lastResultLabel: String? = null
    private var lastResultReport = ""
    private var diagnosticReport = ""
    private var longStreamReport = ""
    private var longStreamReportLabel = "无"
    private var dialogueReport = ""
    private var dialogueReportLabel = "无"
    private lateinit var apiKeyStore: SecureApiKeyStore
    @Volatile private var activeStream: StreamingAudioPlayer? = null
    @Volatile private var activeDeepSeekClient: DeepSeekStreamClient? = null
    @Volatile private var cancelRequested = false
    @Volatile private var cancelRequestedNs = 0L
    @Volatile private var cancelAppliedNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = GenieBenchmarkEngine(
            this,
            currentVoicePackage.assetNamespace,
            currentVoicePackage.id,
        )
        frontend = ChineseFrontend(engine)
        apiKeyStore = SecureApiKeyStore(this)
        setContentView(buildUi())
        showInitialState()
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(247, 243, 255))
        }
        content.addView(TextView(this).apply {
            text = "Genie-TTS v2.0.2\n双音色 Android 三语联调 v0.7.5"
            textSize = 22f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "恬豆 V2 + 小酒狐 V2Pro · 中英日三语 · CPU 8线程"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(6))
        })
        content.addView(TextView(this).apply {
            text = "选择语音包"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        voicePackageSelector = Spinner(this)
        content.addView(voicePackageSelector, LinearLayout.LayoutParams(-1, dp(48)))
        playbackTuningPanel = buildPlaybackTuningPanel()
        content.addView(playbackTuningPanel, LinearLayout.LayoutParams(-1, -2))
        updatePlaybackTuningUi(false)
        content.addView(TextView(this).apply {
            text = "选择参考音频"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        selector = Spinner(this)
        content.addView(selector, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(TextView(this).apply {
            text = "动态三语前端预设"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        runtimePresetSelector = Spinner(this)
        content.addView(runtimePresetSelector, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(TextView(this).apply {
            text = "中文基准与诊断"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(buttons)
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
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(8)))
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(35, 29, 48))
            setTextIsSelectable(true)
            setPadding(0, dp(10), 0, dp(20))
        }
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.rgb(247, 243, 255))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    0,
                    insets.systemWindowInsetTop,
                    0,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        return pageScroll
    }

    private fun buildPlaybackTuningPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(8))
        visibility = View.GONE
        addView(TextView(this@MainActivity).apply {
            text = "小酒狐专属播放调节"
            textSize = 13f
            setTextColor(Color.rgb(50, 37, 86))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        playbackSpeedLabel = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        addView(playbackSpeedLabel)
        playbackSpeedSlider = SeekBar(this@MainActivity).apply {
            max = 12
            progress = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    jiuhuPlaybackSpeed = 0.70f + progress * 0.05f
                    updatePlaybackTuningUi(fromUser)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        addView(playbackSpeedSlider, LinearLayout.LayoutParams(-1, dp(40)))
        playbackPitchLabel = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        addView(playbackPitchLabel)
        playbackPitchSlider = SeekBar(this@MainActivity).apply {
            max = 12
            progress = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    jiuhuPitchSemitones = progress - 6
                    updatePlaybackTuningUi(fromUser)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        addView(playbackPitchSlider, LinearLayout.LayoutParams(-1, dp(40)))
        highFrequencySofteningLabel = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        addView(highFrequencySofteningLabel)
        highFrequencySofteningSlider = SeekBar(this@MainActivity).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    jiuhuHighFrequencySofteningDb = if (progress == 0) 0.0f else -progress / 10.0f
                    updatePlaybackTuningUi(fromUser)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        addView(highFrequencySofteningSlider, LinearLayout.LayoutParams(-1, dp(40)))
        addView(TextView(this@MainActivity).apply {
            text = "语速使用系统 time-stretch 并保持音调；音调独立调整；高频柔化从约 4 kHz 起逐渐衰减，不改变音高和低音音域。"
            textSize = 11f
            setTextColor(Color.GRAY)
        })
        updatePlaybackTuningUi(false)
    }

    private fun currentPlaybackTuning(): PlaybackTuning =
        if (currentVoicePackage.supportsPlaybackTuning) {
            PlaybackTuning(
                jiuhuPlaybackSpeed,
                jiuhuPitchSemitones,
                jiuhuHighFrequencySofteningDb,
            )
        } else {
            PlaybackTuning.NEUTRAL
        }

    private fun updatePlaybackTuningUi(showStatus: Boolean) {
        if (!::playbackTuningPanel.isInitialized) return
        playbackTuningPanel.visibility =
            if (currentVoicePackage.supportsPlaybackTuning) View.VISIBLE else View.GONE
        playbackSpeedLabel.text = "语速：${"%.2f".format(jiuhuPlaybackSpeed)}×（音调保持不变）"
        playbackPitchLabel.text = "音调：${"%+d".format(jiuhuPitchSemitones)} 半音"
        highFrequencySofteningLabel.text =
            "高频柔化：${"%.1f".format(jiuhuHighFrequencySofteningDb)} dB（0.0 为关闭）"
        if (showStatus && currentVoicePackage.supportsPlaybackTuning) {
            showCurrent("播放调节已更新；可直接点击“播放上次合成结果”试听，不需要重新推理。")
        }
    }

    private fun playbackTuningReportLine(tuning: PlaybackTuning = currentPlaybackTuning()): String =
        if (currentVoicePackage.supportsPlaybackTuning) "播放调节：${tuning.reportLabel}\n" else ""

    private fun showInitialState() {
        try {
            val manifest = engine.readManifest()
            selectedPreset = manifest.presets.first()
            selector.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, manifest.cases.map { it.displayTitle }
            )
            selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showCurrent()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            runtimePresetSelector.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                RuntimeLanguagePresets.all.map { it.displayLabel },
            )
            runtimePresetSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
            addButton("运行所选动态三语预设并播放") { runRuntimePreset() }
            addButton("英语固定音素基准") {
                runLanguageTest(
                    FixedLanguageTest(
                        "native_english", "英语固定测试", NATIVE_ENGLISH_TEXT,
                        "English ARPAbet", NATIVE_ENGLISH_PHONES,
                    )
                )
            }
            addButton("日语固定音素基准") {
                runLanguageTest(
                    FixedLanguageTest(
                        "native_japanese", "日语固定测试", NATIVE_JAPANESE_TEXT,
                        "Japanese OpenJTalk", NATIVE_JAPANESE_PHONES,
                    )
                )
            }
            addButton("播放上次合成结果") { playLastGenerated() }
            addButton("复制上次合成报告") { copyLastResultReport() }
            addButton("中文约 500 字长文本试听") {
                runLongStreamTest(LongStreamTest(LongStreamLanguage.CHINESE, LONG_STREAM_TEXT_ZH, 42, 54))
            }
            addButton("英文约 500 字符长文本试听") {
                runLongStreamTest(LongStreamTest(LongStreamLanguage.ENGLISH, LONG_STREAM_TEXT_EN, 88, 110))
            }
            addButton("日文约 500 字符长文本试听") {
                runLongStreamTest(LongStreamTest(LongStreamLanguage.JAPANESE, LONG_STREAM_TEXT_JA, 42, 54))
            }
            addButton("复制上一次长文本报告") { copyLongStreamReport() }

            buttons.addView(TextView(this).apply {
                text = "DeepSeek / 模拟 LLM 真流式联调"
                textSize = 17f
                setTextColor(Color.rgb(50, 37, 86))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(6))
            })
            buttons.addView(TextView(this).apply {
                text = "选择 TTS 语言；普通与约 1000 字共用同一流水线，不创建重复聊天页面。"
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, dp(4))
            })
            dialogueLanguageSelector = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    DialogueLanguage.entries.map { it.title },
                )
            }
            buttons.addView(dialogueLanguageSelector, LinearLayout.LayoutParams(-1, dp(48)))
            dialogueLengthSelector = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    DialogueLengthMode.entries.map { it.title },
                )
            }
            buttons.addView(dialogueLengthSelector, LinearLayout.LayoutParams(-1, dp(48)))
            dialoguePromptInput = EditText(this).apply {
                hint = "发给 DeepSeek 的测试消息"
                setText("说说你今天想到的一件有趣小事。")
                minLines = 2
                maxLines = 4
                setTextColor(Color.rgb(35, 29, 48))
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            buttons.addView(dialoguePromptInput, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
            deepSeekModelSelector = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    DeepSeekModelCatalog.all.map { it.title },
                )
                val savedModel = apiKeyStore.loadModel()
                setSelection(DeepSeekModelCatalog.all.indexOfFirst { it.id == savedModel }.coerceAtLeast(0))
            }
            buttons.addView(deepSeekModelSelector, LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(6)
            })
            deepSeekApiKeyInput = EditText(this).apply {
                hint = if (apiKeyStore.loadApiKey() == null) {
                    "输入 DeepSeek API Key（不会写入仓库或报告）"
                } else {
                    "API Key 已加密保存；留空表示继续使用"
                }
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
                setTextColor(Color.rgb(35, 29, 48))
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            buttons.addView(deepSeekApiKeyInput, LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(6)
            })
            val keyButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            keyButtons.addView(Button(this).apply {
                text = "保存 API Key"
                isAllCaps = false
                setOnClickListener { saveDeepSeekKey() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            keyButtons.addView(Button(this).apply {
                text = "清除 API Key"
                isAllCaps = false
                setOnClickListener { clearDeepSeekKey() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            buttons.addView(keyButtons, LinearLayout.LayoutParams(-1, dp(48)))
            addButton("运行模拟流式联调并播放") { runDialogueStream(DialogueStreamSource.SIMULATED) }
            addButton("运行 DeepSeek API 真流式并播放") { runDialogueStream(DialogueStreamSource.DEEPSEEK) }
            addButton("复制上一次流式联调报告") { copyDialogueReport() }
            dialogueOutput = TextView(this).apply {
                text = "流式回复会显示在这里。"
                textSize = 13f
                setTextColor(Color.rgb(35, 29, 48))
                setTextIsSelectable(true)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            buttons.addView(dialogueOutput, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })

            addButton("运行自动诊断（不播放）") { runDiagnostic() }
            addButton("复制诊断报告") { copyDiagnosticReport() }
            stopButton = addButton("停止当前任务") {
                cancelRequestedNs = System.nanoTime()
                cancelRequested = true
                activeDeepSeekClient?.cancel()
                activeStream?.cancel()
                cancelAppliedNs = System.nanoTime()
                updateStatus("已请求停止；当前 ONNX 算子结束后会退出。")
            }.apply { isEnabled = false }
            voicePackageSelector.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                VoicePackageCatalog.all.map { it.title },
            )
            voicePackageSelector.setSelection(VoicePackageCatalog.all.indexOf(currentVoicePackage), false)
            voicePackageSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = VoicePackageCatalog.all[position.coerceIn(VoicePackageCatalog.all.indices)]
                    if (selected != currentVoicePackage) switchVoicePackage(selected)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
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
                        selectedPreset = engine.readManifest().presets.first { it.id == preset.id }
                        freeInput.isEnabled = false
                        showCurrent("已选择“${selectedPreset!!.title}”。")
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

    private fun switchVoicePackage(selected: VoicePackageSpec) {
        engine.stopPlayback()
        frontend.close()
        japaneseFrontend?.close()
        englishFrontend = null
        japaneseFrontend = null
        engine.close()
        currentVoicePackage = selected
        updatePlaybackTuningUi(false)
        engine = GenieBenchmarkEngine(this, selected.assetNamespace, selected.id)
        frontend = ChineseFrontend(engine)
        preparedRoot = null
        selectedPreset = engine.readManifest().presets.first()
        selector.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            engine.readManifest().cases.map { it.displayTitle },
        )
        selector.setSelection(0, false)
        lastResult = null
        lastResultLabel = null
        lastResultReport = ""
        longStreamReport = ""
        longStreamReportLabel = "无"
        dialogueReport = ""
        dialogueReportLabel = "无"
        freeInput.isEnabled = false
        showCurrent("已切换语音包；模型会在首次生成时按需加载。")
    }

    private fun selectedTextTitle() = selectedPreset?.title ?: "自由输入"
    private fun selectedText() = selectedPreset?.text ?: freeInput.text.toString()
    private fun selectedRuntimePreset(): RuntimeLanguagePreset =
        RuntimeLanguagePresets.all[runtimePresetSelector.selectedItemPosition.coerceIn(RuntimeLanguagePresets.all.indices)]

    private fun showCurrent(extra: String? = null) {
        val item = currentCase()
        val modelReady = preparedRoot?.let { engine.hasFrontendModel(it) } == true
        status.text = buildString {
            val runtimePreset = selectedRuntimePreset()
            appendLine(deviceLine())
            appendLine("当前语音包：${currentVoicePackage.title}")
            val voiceProfile = VoiceProfileCatalog.resolve(item.id)
            appendLine("当前音色：${item.displayTitle}")
            if (currentVoicePackage.supportsPlaybackTuning) {
                appendLine(playbackTuningReportLine().trimEnd())
            }
            voiceProfile?.let {
                appendLine("稳定键：${it.key} · ${if (it.includeInCompanion) "列入正式移植" else "仅保留在测试项目"}")
                appendLine("建议用途：${it.intendedUse}")
            }
            appendLine("参考台词：${item.referenceText}")
            if (item.playbackGainDb != 0.0) appendLine("播放响度校准：${"%+.1f".format(item.playbackGainDb)} dB")
            appendLine("当前台词：${selectedTextTitle()}")
            appendLine(selectedText())
            appendLine("动态三语预设：${runtimePreset.displayLabel}")
            appendLine(runtimePreset.text)
            appendLine("含义/目的：${runtimePreset.translation}")
            appendLine("自由输入模型：${if (modelReady) "已导入" else "尚未导入或尚未检测"}")
            appendLine("启动后台预热：已禁用（恢复 v0.5.0 中文路径）")
            appendLine("英文前端：${if (englishFrontend == null) "未加载" else "已按需加载"} · 日文前端：${if (japaneseFrontend == null) "未加载" else "已按需加载"}")
            appendLine("上次合成：${lastResultLabel ?: "无"}")
            appendLine("上一次长文本报告：$longStreamReportLabel")
            appendLine("上一次流式联调报告：$dialogueReportLabel")
            appendLine("DeepSeek API Key：${if (apiKeyStore.loadApiKey() == null) "未保存" else "已在本机加密保存"}")
            lastResult?.let {
                appendLine("端到端 ${it.endToEndMs} ms · 核心 ${it.totalInferenceMs} ms · RTF ${"%.3f".format(it.coreRtf)}")
            }
            if (extra != null) appendLine("\n$extra")
            appendLine("\n中文预设使用 FP32 RoBERTa；动态中英混合仅运行一次导入的 INT8 Chinese RoBERTa。")
            appendLine("动态英文使用 CMUdict/ARPAbet，动态日语使用 Android OpenJTalk；两者按官方方案使用零 BERT。")
            appendLine("英日只在点击对应测试后加载；最终接入的中文英文词优先使用中文谐音白名单。")
        }
    }

    private fun playLastGenerated() {
        val result = lastResult ?: return showCurrent("还没有可以回放的合成结果。")
        val started = engine.play(
            result.audio,
            engine.readManifest().sampleRate,
            result.playbackGainDb,
            currentPlaybackTuning(),
        )
        showCurrent(if (started) "正在播放上次合成结果，不会重新推理。" else mutedPlaybackMessage())
    }

    private fun runCurrent() = runTask("重新生成") {
        val started = System.nanoTime()
        val target = prepareSelectedTarget()
        val item = currentCase()
        val result = generate(item, target, requestStartedNs = started)
        lastResult = result
        lastResultLabel = "${item.displayTitle} · ${target.title}"
        lastResultReport = result.report(deviceLine()) + playbackTuningReportLine()
        val playbackStarted = engine.play(
            result.audio,
            engine.readManifest().sampleRate,
            item.playbackGainDb,
            currentPlaybackTuning(),
        )
        runOnUiThread {
            showCurrent(if (playbackStarted) "已重新推理并开始播放。" else mutedPlaybackMessage())
        }
    }

    private fun runLanguageTest(test: FixedLanguageTest) {
        if (!currentVoicePackage.supportsMultilingual) {
            return showCurrent("当前语音包没有开放英日测试。")
        }
        runTask(test.title) {
        val started = System.nanoTime()
        val root = ensureAssets()
        val item = currentCase()
        postStatus("${item.displayTitle} · ${test.title}：正在使用官方音素生成……")
        val bertDim = engine.readManifest().frontend.bertDim
        val prepared = PreparedText(
            text = test.text,
            normalizedText = test.text,
            sequence = test.phones,
            bert = FloatArray(test.phones.size * bertDim),
            bertDim = bertDim,
            frontendMs = 0L,
            diagnostic = "官方固定音素 · ${test.phones.size}音素 · BERT 全零",
        )
        val modelLoad = engine.loadModels(root, config)
        val result = engine.runPrepared(
            root = root,
            case = item,
            prepared = prepared,
            modelLoad = modelLoad,
            requestStartedNs = started,
            targetTitle = test.title,
            featureModeTitle = test.featureTitle,
            featureDescription = "Genie 官方原生音素 · 零 BERT",
            shouldCancel = { cancelRequested },
        )
        lastResult = result
        lastResultLabel = "${item.displayTitle} · ${test.title}"
        lastResultReport = result.report(deviceLine()) + playbackTuningReportLine()
        val playbackStarted = engine.play(
            result.audio,
            engine.readManifest().sampleRate,
            item.playbackGainDb,
            currentPlaybackTuning(),
        )
        postStatus(
            lastResultReport +
                "\n请重点判断：是否像目标语言、角色音色是否保持、发音和停顿是否自然。\n" +
                if (playbackStarted) "已开始播放；可点击“复制上次合成报告”发给我。" else mutedPlaybackMessage()
        )
        }
    }

    private fun runRuntimePreset() {
        if (!currentVoicePackage.supportsMultilingual) {
            return showCurrent("当前语音包没有开放动态三语前端。")
        }
        runTask("动态三语前端") {
        val started = System.nanoTime()
        val root = ensureAssets()
        val item = currentCase()
        val preset = selectedRuntimePreset()
        val bertDim = engine.readManifest().frontend.bertDim
        postStatus("${item.displayTitle} · ${preset.displayLabel}：正在运行手机端语言前端……")
        val prepared = when (preset.mode) {
            RuntimeLanguageMode.HYBRID -> {
                check(engine.hasFrontendModel(root)) { "中英混合模式需要先导入配套的自由输入 RoBERTa ONNX 文件" }
                engine.prepareFrontendAssets(root, ::postStatus)
                frontend.prepareHybrid(root, preset.text, requireEnglishFrontend(), ::postStatus)
            }
            RuntimeLanguageMode.ENGLISH -> requireEnglishFrontend().prepare(preset.text, bertDim, ::postStatus)
            RuntimeLanguageMode.JAPANESE -> requireJapaneseFrontend().prepare(preset.text, bertDim, ::postStatus)
        }
        val goldenDiagnostic = goldenPhoneDiagnostic(prepared.sequence, preset.expectedPhones)
        val featureTitle = when (preset.mode) {
            RuntimeLanguageMode.HYBRID -> "Chinese RoBERTa + English ARPAbet"
            RuntimeLanguageMode.ENGLISH -> "English CMUdict/ARPAbet"
            RuntimeLanguageMode.JAPANESE -> "Android OpenJTalk 1.11"
        }
        val featureDescription = when (preset.mode) {
            RuntimeLanguageMode.HYBRID -> "中文非零 BERT + 英文零 BERT · 单次 TTS 推理"
            RuntimeLanguageMode.ENGLISH -> "运行时 CMUdict/热词/逐字母回退 · 零 BERT"
            RuntimeLanguageMode.JAPANESE -> "运行时 OpenJTalk 音素与韵律 · 零 BERT"
        }
        val verified = prepared.copy(diagnostic = prepared.diagnostic + " · " + goldenDiagnostic)
        val modelLoad = engine.loadModels(root, config)
        val result = engine.runPrepared(
            root = root,
            case = item,
            prepared = verified,
            modelLoad = modelLoad,
            requestStartedNs = started,
            targetTitle = preset.displayLabel,
            featureModeTitle = featureTitle,
            featureDescription = featureDescription,
            shouldCancel = { cancelRequested },
        )
        lastResult = result
        lastResultLabel = "${item.displayTitle} · ${preset.displayLabel}"
        lastResultReport = buildString {
            append(result.report(deviceLine()))
            append(playbackTuningReportLine())
            appendLine("含义/测试目的：${preset.translation}")
            appendLine("动态前端校验：$goldenDiagnostic")
        }
        val playbackStarted = engine.play(
            result.audio,
            engine.readManifest().sampleRate,
            item.playbackGainDb,
            currentPlaybackTuning(),
        )
        postStatus(
            lastResultReport + if (playbackStarted) {
                "\n已开始播放；请重点判断跨语言衔接、发音和音色是否稳定。"
            } else {
                "\n${mutedPlaybackMessage()}"
            }
        )
        }
    }

    private fun goldenPhoneDiagnostic(actual: LongArray, expected: LongArray?): String {
        if (expected == null) return "中英混合结构检查（无固定黄金序列）"
        if (actual.contentEquals(expected)) return "与官方桌面前端黄金音素完全一致（${actual.size}/${expected.size}）"
        val mismatch = (0 until minOf(actual.size, expected.size)).firstOrNull { actual[it] != expected[it] }
        return if (mismatch != null) {
            "未匹配黄金音素：第 ${mismatch + 1} 项 ${actual[mismatch]} != ${expected[mismatch]}（${actual.size}/${expected.size}）"
        } else {
            "未匹配黄金音素：长度 ${actual.size} != ${expected.size}"
        }
    }

    private fun runLongStreamTest(test: LongStreamTest) {
        if (test.language != LongStreamLanguage.CHINESE && !currentVoicePackage.supportsMultilingual) {
            return showCurrent("当前语音包没有开放英日长文本测试。")
        }
        runTask("${test.language.title}长文本试听") {
        check(!SystemAudioPolicy.isSilentOrVibrate(this)) {
            "手机处于静音或振动模式，长文本 TTS 播放已阻止"
        }
        val root = ensureAssets()
        if (test.language == LongStreamLanguage.CHINESE) {
            check(engine.hasFrontendModel(root)) { "中文长文本需要先导入配套的自由输入 RoBERTa ONNX 文件" }
        }
        engine.stopPlayback()
        val item = currentCase()
        val playbackTuning = currentPlaybackTuning()
        val segments = ChineseTextSegmenter.split(test.text, test.targetChars, test.maxChars)
        val testStartedNs = System.nanoTime()
        val thermalAtStart = thermalStatus()
        val runs = ArrayList<StreamSegmentRun>(segments.size)
        var player: StreamingAudioPlayer? = null
        var playbackStartedNs = 0L
        var queuedAudioMs = 0L

        if (test.language == LongStreamLanguage.CHINESE) {
            frontend.clearPreparedCache()
            engine.prepareFrontendAssets(root, ::postStatus)
        }
        try {
            segments.forEachIndexed { index, text ->
                checkCancelled()
                postProgress(index, segments.size, "第 ${index + 1}/${segments.size} 段：正在进行${test.language.title}前处理与推理……")
                val segmentStartedNs = System.nanoTime()
                val bertDim = engine.readManifest().frontend.bertDim
                val prepared = when (test.language) {
                    LongStreamLanguage.CHINESE -> frontend.prepare(root, text, ::postStatus)
                    LongStreamLanguage.ENGLISH -> requireEnglishFrontend().prepare(text, bertDim, ::postStatus)
                    LongStreamLanguage.JAPANESE -> requireJapaneseFrontend().prepare(text, bertDim, ::postStatus)
                }
                val modelLoad = engine.loadModels(root, config)
                val featureModeTitle = when (test.language) {
                    LongStreamLanguage.CHINESE -> "完整 Chinese RoBERTa"
                    LongStreamLanguage.ENGLISH -> "English CMUdict/ARPAbet"
                    LongStreamLanguage.JAPANESE -> "Android OpenJTalk 1.11"
                }
                val featureDescription = when (test.language) {
                    LongStreamLanguage.CHINESE -> "本地 INT8；非零中文特征"
                    LongStreamLanguage.ENGLISH -> "运行时 CMUdict；零 BERT"
                    LongStreamLanguage.JAPANESE -> "运行时 OpenJTalk 音素与韵律；零 BERT"
                }
                val result = engine.runPrepared(
                    root = root,
                    case = item,
                    prepared = prepared,
                    modelLoad = modelLoad,
                    requestStartedNs = segmentStartedNs,
                    targetTitle = "${test.language.title}长文本第 ${index + 1} 段",
                    featureModeTitle = featureModeTitle,
                    featureDescription = featureDescription,
                    shouldCancel = { cancelRequested },
                )
                val readyNs = System.nanoTime()

                val margin = if (index == 0) {
                    player = StreamingAudioPlayer(
                        this,
                        engine.readManifest().sampleRate,
                        item.playbackGainDb,
                        playbackTuning,
                    )
                    activeStream = player
                    player!!.enqueue(result.audio)
                    playbackStartedNs = player!!.awaitStarted()
                    null
                } else {
                    val elapsedPlaybackMs = (readyNs - playbackStartedNs) / 1_000_000L
                    (queuedAudioMs - elapsedPlaybackMs).also { player!!.enqueue(result.audio) }
                }
                queuedAudioMs += (result.audioSeconds * 1000.0 / playbackTuning.speed).toLong()
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
            val thermalAtEnd = thermalStatus()

            longStreamReport = buildString {
                appendLine("===== Genie-TTS v0.7.5 ${test.language.title}长文本分段流式报告 · ${timeStamp()} =====")
                appendLine(deviceLine())
                appendLine("音色：${item.displayTitle} · ${config.label}")
                if (currentVoicePackage.supportsPlaybackTuning) {
                    append(playbackTuningReportLine(playbackTuning))
                }
                appendLine("语言：${test.language.title} · 原文：${test.text.length} 字符 · ${segments.size} 段 · 单段最长 ${segments.maxOf { it.length }} 字符")
                appendLine("策略：首段固定预填充 1000 ms；单 AudioTrack 连续 PCM、无固定段间等待；播放前段时按顺序生成后段；采样参数未修改。")
                appendLine("温控状态：开始 $thermalAtStart · 结束 $thermalAtEnd")
                appendLine("首段开播等待：$firstAudioWaitMs ms")
                appendLine("全部分段生成完成：$generationWallMs ms")
                appendLine("合计音频：${"%.2f".format(totalAudioSeconds)} s · 播放阶段：${playbackWallMs} ms")
                appendLine("合计核心推理：$totalCoreMs ms · 聚合 RTF：${"%.3f".format(aggregateRtf)}")
                appendLine("合计${test.language.title}前处理：$totalFrontendMs ms")
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
            longStreamReportLabel = "${test.language.title} · ${test.text.length} 字符 · RTF ${"%.3f".format(aggregateRtf)}"
            postStatus(
                "${test.language.title}长文本试听完成。上一次长文本报告已替换为本次结果。" +
                    "如果听感或停顿正常，无需复制；异常时再发送这一语言的报告。"
            )
        } finally {
            activeStream = null
            if (cancelRequested) player?.cancel()
            player?.close()
        }
        }
    }

    private fun saveDeepSeekKey() {
        try {
            val entered = deepSeekApiKeyInput.text.toString().trim()
            if (entered.isNotEmpty()) apiKeyStore.saveApiKey(entered)
            check(apiKeyStore.loadApiKey() != null) { "请先输入 DeepSeek API Key" }
            apiKeyStore.saveModel(selectedDeepSeekModel())
            deepSeekApiKeyInput.setText("")
            deepSeekApiKeyInput.hint = "API Key 已加密保存；留空表示继续使用"
            showCurrent("DeepSeek API Key 已使用 Android Keystore 加密保存，只存在于本应用私有数据中。")
        } catch (error: Throwable) {
            showCurrent("保存 API Key 失败：${error.message}")
        }
    }

    private fun clearDeepSeekKey() {
        apiKeyStore.clearApiKey()
        deepSeekApiKeyInput.setText("")
        deepSeekApiKeyInput.hint = "输入 DeepSeek API Key（不会写入仓库或报告）"
        showCurrent("已经清除本机保存的 DeepSeek API Key。")
    }

    private fun copyDialogueReport() {
        if (dialogueReport.isBlank()) return showCurrent("请先完成或中断一次流式联调测试。")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS dialogue stream v0.7.5", dialogueReport))
        showCurrent("上一次流式联调报告已复制：$dialogueReportLabel")
    }

    private fun runDialogueStream(source: DialogueStreamSource) {
        if (!currentVoicePackage.supportsDialogueStreaming) {
            return showCurrent("DeepSeek / 模拟真流式本轮只用于恬豆；小酒狐用于三语短句和固定长文本分段试听。")
        }
        val language = DialogueLanguage.entries[
            dialogueLanguageSelector.selectedItemPosition.coerceIn(DialogueLanguage.entries.indices)
        ]
        val lengthMode = DialogueLengthMode.entries[
            dialogueLengthSelector.selectedItemPosition.coerceIn(DialogueLengthMode.entries.indices)
        ]
        val userPrompt = dialoguePromptInput.text.toString().trim().ifBlank { "说说你今天想到的一件小事。" }
        val model = selectedDeepSeekModel()
        val enteredKey = deepSeekApiKeyInput.text.toString().trim()
        val apiKey = if (source == DialogueStreamSource.DEEPSEEK) {
            enteredKey.ifBlank { apiKeyStore.loadApiKey().orEmpty() }.also {
                if (it.isBlank()) return showCurrent("请先输入并保存 DeepSeek API Key。")
            }
        } else {
            ""
        }
        apiKeyStore.saveModel(model)
        dialogueOutput.text = ""
        runTask("${source.title} · ${lengthMode.title}") {
            runDialoguePipeline(source, language, lengthMode, userPrompt, model, apiKey)
        }
    }

    private fun runDialoguePipeline(
        source: DialogueStreamSource,
        language: DialogueLanguage,
        lengthMode: DialogueLengthMode,
        userPrompt: String,
        model: String,
        apiKey: String,
    ) {
        check(!SystemAudioPolicy.isSilentOrVibrate(this)) {
            "手机处于静音或振动模式，流式对话 TTS 播放已阻止"
        }
        val root = ensureAssets()
        if (language == DialogueLanguage.CHINESE) {
            check(engine.hasFrontendModel(root)) { "中文流式联调需要先导入配套的自由输入 RoBERTa ONNX 文件" }
            engine.prepareFrontendAssets(root, ::postStatus)
            frontend.clearPreparedCache()
        }
        engine.stopPlayback()
        val item = currentCase()
        val playbackTuning = currentPlaybackTuning()
        val testStartedNs = System.nanoTime()
        cancelRequestedNs = 0L
        cancelAppliedNs = 0L
        val firstDeltaNs = AtomicLong(0L)
        val firstSegmentClosedNs = AtomicLong(0L)
        val maximumTextQueueDepth = AtomicInteger(0)
        // A normal 800–1200 character answer is only a few dozen segments. Keeping the full
        // text stream buffered lets the report distinguish API speed from the slower TTS worker,
        // while the fixed token limit still prevents unbounded memory growth.
        val events = ArrayBlockingQueue<DialogueEvent>(64)
        val deepSeekClient = if (source == DialogueStreamSource.DEEPSEEK) DeepSeekStreamClient() else null
        activeDeepSeekClient = deepSeekClient

        fun offerEvent(event: DialogueEvent) {
            while (!cancelRequested) {
                if (events.offer(event, 100L, TimeUnit.MILLISECONDS)) {
                    val segmentDepth = events.count { it is DialogueEvent.Segment }
                    maximumTextQueueDepth.accumulateAndGet(segmentDepth, ::maxOf)
                    return
                }
            }
            throw CancellationException("用户停止了流式联调")
        }

        val producer = Thread({
            val segmenter = StreamingDialogueSegmenter(language)
            val fullText = StringBuilder()
            var segmentIndex = 0
            try {
                fun acceptDelta(delta: String) {
                    if (delta.isEmpty()) return
                    val now = System.nanoTime()
                    firstDeltaNs.compareAndSet(0L, now)
                    fullText.append(delta)
                    postDialogueDelta(delta)
                    segmenter.addDelta(delta).forEach { closed ->
                        val closedNs = System.nanoTime()
                        firstSegmentClosedNs.compareAndSet(0L, closedNs)
                        segmentIndex += 1
                        offerEvent(
                            DialogueEvent.Segment(
                                index = segmentIndex,
                                text = closed.text,
                                closedAfterStartMs = (closedNs - testStartedNs) / 1_000_000L,
                                textQueueDepth = events.size + 1,
                            )
                        )
                    }
                }

                val apiResult = when (source) {
                    DialogueStreamSource.SIMULATED -> {
                        val fixture = DialogueFixtures.text(language, lengthMode)
                        var offset = 0
                        val chunkSizes = intArrayOf(3, 5, 2, 7, 4, 6)
                        var chunkIndex = 0
                        while (offset < fixture.length) {
                            if (cancelRequested || Thread.currentThread().isInterrupted) {
                                throw CancellationException("用户停止了模拟流")
                            }
                            val end = minOf(fixture.length, offset + chunkSizes[chunkIndex % chunkSizes.size])
                            Thread.sleep(55L)
                            acceptDelta(fixture.substring(offset, end))
                            offset = end
                            chunkIndex += 1
                        }
                        null
                    }
                    DialogueStreamSource.DEEPSEEK -> deepSeekClient!!.stream(
                        apiKey = apiKey,
                        model = model,
                        systemPrompt = DialogueFixtures.systemPrompt(language, lengthMode),
                        userPrompt = userPrompt,
                        maxTokens = lengthMode.maxTokens,
                        onDelta = ::acceptDelta,
                    )
                }
                segmenter.finish().forEach { closed ->
                    val closedNs = System.nanoTime()
                    firstSegmentClosedNs.compareAndSet(0L, closedNs)
                    segmentIndex += 1
                    offerEvent(
                        DialogueEvent.Segment(
                            index = segmentIndex,
                            text = closed.text,
                            closedAfterStartMs = (closedNs - testStartedNs) / 1_000_000L,
                            textQueueDepth = events.size + 1,
                        )
                    )
                }
                offerEvent(
                    DialogueEvent.Complete(
                        fullText = fullText.toString(),
                        finishedAfterStartMs = (System.nanoTime() - testStartedNs) / 1_000_000L,
                        apiResult = apiResult,
                    )
                )
            } catch (error: Throwable) {
                events.offer(DialogueEvent.Failure(error), 100L, TimeUnit.MILLISECONDS)
            }
        }, "Genie-TTS-dialogue-source").apply {
            isDaemon = true
            start()
        }

        val runs = ArrayList<DialogueSegmentRun>()
        val deferredEvents = ArrayDeque<DialogueEvent>()
        var player: StreamingAudioPlayer? = null
        var playbackStartedNs = 0L
        var queuedAudioMs = 0L
        var sourceComplete: DialogueEvent.Complete? = null
        var generationFinishedNs = 0L
        var modelReadyAfterStartMs = 0L
        var inFlight = false
        val thermalAtStart = thermalStatus()

        fun nextEvent(): DialogueEvent? = if (deferredEvents.isNotEmpty()) {
            deferredEvents.removeFirst()
        } else {
            events.poll(250L, TimeUnit.MILLISECONDS)
        }

        fun coalesceQueuedUnits(first: DialogueEvent.Segment): DialogueEvent.Segment {
            if (runs.isEmpty()) return first
            val available = arrayListOf(first)
            while (true) {
                val next = if (deferredEvents.isNotEmpty()) deferredEvents.removeFirst() else events.poll()
                when (next) {
                    is DialogueEvent.Segment -> available += next
                    null -> break
                    else -> {
                        deferredEvents.addFirst(next)
                        break
                    }
                }
            }
            val packed = DialogueSegmentPacker.packPrefix(available.map { it.text }, language)
            for (index in available.lastIndex downTo packed.sourceUnits) {
                deferredEvents.addFirst(available[index])
            }
            val used = available.take(packed.sourceUnits)
            return first.copy(
                index = runs.size + 1,
                text = packed.text,
                closedAfterStartMs = used.maxOf { it.closedAfterStartMs },
                textQueueDepth = used.maxOf { it.textQueueDepth },
                sourceUnitCount = used.sumOf { it.sourceUnitCount },
            )
        }

        try {
            val initialModelLoad = engine.loadModels(root, config)
            modelReadyAfterStartMs = (System.nanoTime() - testStartedNs) / 1_000_000L
            while (sourceComplete == null) {
                checkCancelled()
                val event = nextEvent()
                if (event == null) {
                    if (!producer.isAlive && events.isEmpty() && deferredEvents.isEmpty()) {
                        error("文字流意外结束，未收到完成事件")
                    }
                    continue
                }
                when (event) {
                    is DialogueEvent.Failure -> throw event.error
                    is DialogueEvent.Complete -> sourceComplete = event
                    is DialogueEvent.Segment -> {
                        val packedEvent = coalesceQueuedUnits(event)
                        inFlight = true
                        postStatus(
                            "${source.title} · ${lengthMode.title}\n" +
                                "收到第 ${runs.size + 1} 个 TTS 段（拼合 ${packedEvent.sourceUnitCount} 个自然单元），正在进行${language.title}前处理与 TTS……\n" +
                                "文本队列：${events.count { it is DialogueEvent.Segment }} 段"
                        )
                        val segmentStartedNs = System.nanoTime()
                        val prepared = prepareDialogueSegment(root, language, packedEvent.text)
                        val modelLoad = if (runs.isEmpty()) initialModelLoad else engine.loadModels(root, config)
                        val result = engine.runPrepared(
                            root = root,
                            case = item,
                            prepared = prepared,
                            modelLoad = modelLoad,
                            requestStartedNs = segmentStartedNs,
                            targetTitle = "${lengthMode.title}第 ${runs.size + 1} 段",
                            featureModeTitle = dialogueFeatureTitle(language),
                            featureDescription = dialogueFeatureDescription(language),
                            shouldCancel = { cancelRequested },
                        )
                        checkCancelled()
                        val readyNs = System.nanoTime()
                        val margin = if (player == null) {
                            player = StreamingAudioPlayer(
                                this,
                                engine.readManifest().sampleRate,
                                item.playbackGainDb,
                                playbackTuning,
                            )
                            activeStream = player
                            player!!.enqueue(result.audio)
                            playbackStartedNs = player!!.awaitStarted(20L)
                            null
                        } else {
                            val elapsedPlaybackMs = (readyNs - playbackStartedNs) / 1_000_000L
                            (queuedAudioMs - elapsedPlaybackMs).also { player!!.enqueue(result.audio) }
                        }
                        queuedAudioMs += (result.audioSeconds * 1000.0 / playbackTuning.speed).toLong()
                        runs += DialogueSegmentRun(
                            index = runs.size + 1,
                            text = packedEvent.text,
                            closedAfterStartMs = packedEvent.closedAfterStartMs,
                            textQueueDepth = packedEvent.textQueueDepth,
                            audioReadyAfterStartMs = (readyNs - testStartedNs) / 1_000_000L,
                            bufferMarginMs = margin,
                            sourceUnitCount = packedEvent.sourceUnitCount,
                            result = result.copy(audio = FloatArray(0)),
                        )
                        generationFinishedNs = readyNs
                        inFlight = false
                    }
                }
            }

            check(runs.isNotEmpty()) { "DeepSeek 没有返回可以朗读的正文" }
            checkCancelled()
            player!!.finish()
            val playback = player!!.awaitCompletion()
            checkCancelled()
            val thermalAtEnd = thermalStatus()
            val totalCoreMs = runs.sumOf { it.result.totalInferenceMs }
            val totalFrontendMs = runs.sumOf { it.result.frontendMs }
            val totalAudioSeconds = runs.sumOf { it.result.audioSeconds }
            val margins = runs.mapNotNull { it.bufferMarginMs }
            val lateSegments = margins.count { it < 0L }
            val firstDeltaAfterMs = firstDeltaNs.get().takeIf { it > 0L }?.let { (it - testStartedNs) / 1_000_000L }
            val firstClosedAfterMs = firstSegmentClosedNs.get().takeIf { it > 0L }?.let { (it - testStartedNs) / 1_000_000L }
            val firstAudioReadyMs = runs.first().audioReadyAfterStartMs
            val firstPlaybackMs = (playback.playbackStartedNs - testStartedNs) / 1_000_000L
            val playbackWallMs = (playback.playbackFinishedNs - playback.playbackStartedNs) / 1_000_000L
            val totalWallMs = (playback.playbackFinishedNs - testStartedNs) / 1_000_000L
            val aggregateRtf = totalCoreMs / (totalAudioSeconds * 1000.0)

            dialogueReport = buildString {
                appendLine("===== Genie-TTS v0.7.5 流式对话联调报告 · ${timeStamp()} =====")
                appendLine(deviceLine())
                appendLine("来源：${source.title} · 模式：${lengthMode.title} · TTS：${language.title}")
                appendLine("音色：${item.displayTitle} · ${config.label}")
                if (currentVoicePackage.supportsPlaybackTuning) {
                    append(playbackTuningReportLine(playbackTuning))
                }
                if (source == DialogueStreamSource.DEEPSEEK) appendLine("DeepSeek 模型：$model · 思考模式：关闭")
                appendLine("测试输入：$userPrompt")
                appendLine("输出：${sourceComplete!!.fullText.length} 字符 · ${runs.sumOf { it.sourceUnitCount }} 个自然单元 → ${runs.size} 个 TTS 段 · 单段最长 ${runs.maxOf { it.text.length }} 字符")
                appendLine("切句：首段即时提交；后续仅拼合已积压文本，目标 ${language.targetChars} · 硬上限 ${language.maxChars} 字符")
                appendLine("播放：单 AudioTrack 连续 PCM；无固定段间等待；播放前段时生成后段")
                appendLine("温控状态：开始 $thermalAtStart · 结束 $thermalAtEnd")
                appendLine("首个文字块：${firstDeltaAfterMs?.let { "$it ms" } ?: "无"}")
                appendLine("第一段文字闭合：${firstClosedAfterMs?.let { "$it ms" } ?: "无"}")
                appendLine("模型可用：$modelReadyAfterStartMs ms${if (initialModelLoad.loadedThisRun) " · 本轮冷加载 ${initialModelLoad.elapsedMs} ms" else " · 复用已加载模型"}")
                appendLine("第一段音频完成：$firstAudioReadyMs ms · 首次开播：$firstPlaybackMs ms")
                appendLine("文字流完成：${sourceComplete!!.finishedAfterStartMs} ms · 全部分段生成：${(generationFinishedNs - testStartedNs) / 1_000_000L} ms")
                appendLine("合计音频：${"%.2f".format(totalAudioSeconds)} s · 播放阶段：$playbackWallMs ms")
                appendLine("合计核心推理：$totalCoreMs ms · 聚合 RTF：${"%.3f".format(aggregateRtf)} · 前处理：$totalFrontendMs ms")
                appendLine("文本队列最大深度：${maximumTextQueueDepth.get()} · 音频队列最大深度：${playback.maxQueuedSegments}")
                appendLine("最小缓冲余量：${margins.minOrNull()?.let { "$it ms" } ?: "无"} · 迟到分段：$lateSegments/${margins.size}")
                appendLine("AudioTrack underrun：${playback.underrunCount} · 峰值 PSS：约 ${runs.maxOf { it.result.pssMb }} MB")
                appendLine("从点击到播放完成：$totalWallMs ms")
                sourceComplete!!.apiResult?.let { api ->
                    appendLine("API request id：${api.requestId ?: "无"} · finish：${api.finishReason ?: "无"}")
                    api.usage?.let { appendLine("API tokens：输入 ${it.promptTokens} · 输出 ${it.completionTokens} · 合计 ${it.totalTokens}") }
                }
                runs.forEach { run ->
                    appendLine("\n--- 第 ${run.index}/${runs.size} 段 ---")
                    appendLine("文本：${run.text}")
                    appendLine("拼合：${run.sourceUnitCount} 个自然单元 · 最后单元闭合：点击后 ${run.closedAfterStartMs} ms · 提交时文本队列 ${run.textQueueDepth} 段")
                    appendLine("音频完成：点击后 ${run.audioReadyAfterStartMs} ms · 播放前缓冲余量：${run.bufferMarginMs?.let { "$it ms" } ?: "首段"}")
                    appendLine("前处理：${run.result.frontendMs} ms · 核心：${run.result.totalInferenceMs} ms · 音频：${"%.2f".format(run.result.audioSeconds)} s")
                    appendLine("RTF：${"%.3f".format(run.result.coreRtf)} · Decoder：${run.result.decoderIterations} 次 · PSS：约 ${run.result.pssMb} MB")
                }
                appendLine("\n完整输出：\n${sourceComplete!!.fullText}")
                appendLine("\n安全说明：报告不包含 API Key。")
            }
            dialogueReportLabel = "${source.title} · ${lengthMode.title} · ${language.title} · RTF ${"%.3f".format(aggregateRtf)}"
            postStatus("流式联调完成。请先判断听感和停顿；异常时复制上一次流式联调报告。")
        } catch (cancelled: CancellationException) {
            val queuedSegments = events.count { it is DialogueEvent.Segment } +
                deferredEvents.count { it is DialogueEvent.Segment }
            val abandoned = queuedSegments + if (inFlight) 1 else 0
            val stoppedAfterMs = if (cancelRequestedNs > 0L && cancelAppliedNs >= cancelRequestedNs) {
                (cancelAppliedNs - cancelRequestedNs) / 1_000_000L
            } else null
            dialogueReport = buildString {
                appendLine("===== Genie-TTS v0.7.5 流式对话中断报告 · ${timeStamp()} =====")
                appendLine(deviceLine())
                appendLine("来源：${source.title} · 模式：${lengthMode.title} · TTS：${language.title}")
                appendLine("结果：用户主动中断")
                appendLine("已完成音频：${runs.size} 段 · 废弃推理/排队：$abandoned 段")
                appendLine("文本队列最大深度：${maximumTextQueueDepth.get()}")
                appendLine("停止按钮到播放器/网络取消调用完成：${stoppedAfterMs?.let { "$it ms" } ?: "未记录"}")
                appendLine("第一段文字闭合：${firstSegmentClosedNs.get().takeIf { it > 0L }?.let { (it - testStartedNs) / 1_000_000L } ?: -1L} ms")
                appendLine("中断发生：点击测试后 ${(System.nanoTime() - testStartedNs) / 1_000_000L} ms")
                appendLine("安全说明：报告不包含 API Key。")
            }
            dialogueReportLabel = "已中断 · ${source.title} · ${lengthMode.title} · ${language.title}"
            throw cancelled
        } finally {
            activeDeepSeekClient = null
            deepSeekClient?.cancel()
            producer.interrupt()
            activeStream = null
            player?.close()
        }
    }

    private fun prepareDialogueSegment(root: File, language: DialogueLanguage, text: String): PreparedText {
        val bertDim = engine.readManifest().frontend.bertDim
        return when (language) {
            DialogueLanguage.CHINESE -> frontend.prepare(root, text, ::postStatus)
            DialogueLanguage.ENGLISH -> requireEnglishFrontend().prepare(text, bertDim, ::postStatus)
            DialogueLanguage.JAPANESE -> requireJapaneseFrontend().prepare(text, bertDim, ::postStatus)
        }
    }

    private fun selectedDeepSeekModel(): String {
        val position = deepSeekModelSelector.selectedItemPosition
            .coerceIn(DeepSeekModelCatalog.all.indices)
        return DeepSeekModelCatalog.all[position].id
    }

    private fun dialogueFeatureTitle(language: DialogueLanguage): String = when (language) {
        DialogueLanguage.CHINESE -> "完整 Chinese RoBERTa"
        DialogueLanguage.ENGLISH -> "English CMUdict/ARPAbet"
        DialogueLanguage.JAPANESE -> "Android OpenJTalk 1.11"
    }

    private fun dialogueFeatureDescription(language: DialogueLanguage): String = when (language) {
        DialogueLanguage.CHINESE -> "本地 INT8；非零中文特征"
        DialogueLanguage.ENGLISH -> "运行时 CMUdict；零 BERT"
        DialogueLanguage.JAPANESE -> "运行时 OpenJTalk 音素与韵律；零 BERT"
    }

    private fun postDialogueDelta(delta: String) = runOnUiThread {
        dialogueOutput.append(delta)
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
        val primary = manifest.cases.first()
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
            appendLine("===== Genie-TTS v0.7.5 自动诊断 · ${timeStamp()} =====")
            appendLine(deviceLine())
            appendLine("固定音色：${primary.displayTitle} · ${currentVoicePackage.title}")
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
        postStatus("${item.displayTitle} · ${target.title}：正在生成……")
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS diagnostic v0.7.5", diagnosticReport))
        showCurrent("自动诊断报告已复制。")
    }

    private fun copyLastResultReport() {
        if (lastResultReport.isBlank()) return showCurrent("请先生成一次中文、英语或日语结果。")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS result v0.7.5", lastResultReport))
        showCurrent("上次合成报告已复制。")
    }

    private fun copyLongStreamReport() {
        if (longStreamReport.isBlank()) return showCurrent("请先运行一次中文、英文或日文长文本试听。")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Genie TTS long stream v0.7.5", longStreamReport))
        showCurrent("上一次长文本报告已复制：$longStreamReportLabel")
    }

    private fun ensureAssets(): File = preparedRoot ?: engine.prepareAssets(::postStatus).also { preparedRoot = it }

    private fun requireEnglishFrontend(): EnglishFrontend =
        englishFrontend ?: EnglishFrontend(this).also { englishFrontend = it }

    private fun requireJapaneseFrontend(): NativeJapaneseFrontend =
        japaneseFrontend ?: NativeJapaneseFrontend(this).also { japaneseFrontend = it }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "系统不支持"
        val status = (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "正常"
            PowerManager.THERMAL_STATUS_LIGHT -> "轻微"
            PowerManager.THERMAL_STATUS_MODERATE -> "中等"
            PowerManager.THERMAL_STATUS_SEVERE -> "严重"
            PowerManager.THERMAL_STATUS_CRITICAL -> "临界"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "关机阈值"
            else -> "未知($status)"
        }
    }

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
    private fun mutedPlaybackMessage() = "手机处于静音或振动模式：合成已完成，但 TTS 播放已阻止。"
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
        voicePackageSelector.isEnabled = !value
        runtimePresetSelector.isEnabled = !value
        playbackSpeedSlider.isEnabled = !value
        playbackPitchSlider.isEnabled = !value
        highFrequencySofteningSlider.isEnabled = !value
        if (::dialogueLanguageSelector.isInitialized) dialogueLanguageSelector.isEnabled = !value
        if (::dialogueLengthSelector.isInitialized) dialogueLengthSelector.isEnabled = !value
        if (::dialoguePromptInput.isInitialized) dialoguePromptInput.isEnabled = !value
        if (::deepSeekModelSelector.isInitialized) deepSeekModelSelector.isEnabled = !value
        if (::deepSeekApiKeyInput.isInitialized) deepSeekApiKeyInput.isEnabled = !value
        freeInput.isEnabled = !value && selectedPreset == null
        fun update(view: View) {
            if (view is Button) view.isEnabled = if (::stopButton.isInitialized && view === stopButton) value else !value
            if (view is LinearLayout) for (index in 0 until view.childCount) update(view.getChildAt(index))
        }
        update(buttons)
    }

    override fun onDestroy() {
        cancelRequested = true
        activeDeepSeekClient?.cancel()
        activeStream?.cancel()
        worker.shutdownNow()
        frontend.close()
        japaneseFrontend?.close()
        engine.close()
        super.onDestroy()
    }
}
