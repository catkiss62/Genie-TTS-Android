package com.catkiss62.geniettsbenchmark

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class StreamPlaybackSummary(
    val playbackStartedNs: Long,
    val playbackFinishedNs: Long,
    val framesWritten: Long,
    val underrunCount: Int,
)

/**
 * A single-producer PCM stream. The first generated segment pre-fills one second of audio
 * before playback starts; later segments can be generated while AudioTrack drains the queue.
 */
class StreamingAudioPlayer(
    private val sampleRate: Int,
    private val gainDb: Double,
) : AutoCloseable {
    private sealed interface Command {
        data class Audio(val samples: FloatArray) : Command
        data object Finish : Command
        data object Cancel : Command
    }

    private val queue = LinkedBlockingQueue<Command>()
    private val started = CountDownLatch(1)
    private val completed = CountDownLatch(1)
    @Volatile private var cancelled = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var summary: StreamPlaybackSummary? = null
    @Volatile private var track: AudioTrack? = null
    private val thread = Thread(::runWriter, "Genie-TTS-stream-player").apply {
        isDaemon = true
        start()
    }

    fun enqueue(audio: FloatArray) {
        check(!cancelled) { "流式播放已经停止" }
        check(audio.isNotEmpty()) { "不能加入空音频" }
        queue.put(Command.Audio(audio))
    }

    fun finish() {
        if (!cancelled) queue.put(Command.Finish)
    }

    fun awaitStarted(timeoutSeconds: Long = 10L): Long {
        check(started.await(timeoutSeconds, TimeUnit.SECONDS)) { "流式播放器启动超时" }
        failure?.let { throw it }
        return checkNotNull(summary?.playbackStartedNs) { "流式播放器未能开始" }
    }

    fun awaitCompletion(): StreamPlaybackSummary {
        completed.await()
        failure?.let { throw it }
        return checkNotNull(summary) { "流式播放器没有生成结果" }
    }

    fun cancel() {
        cancelled = true
        queue.offer(Command.Cancel)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    override fun close() {
        cancel()
        if (Thread.currentThread() !== thread) thread.join(2_000L)
    }

    private fun runWriter() {
        var localTrack: AudioTrack? = null
        var playbackStartedNs = 0L
        var framesWritten = 0L
        try {
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 5 * 2)
            val bufferBytes = max(minBufferBytes, sampleRate * 2 * 2)
            localTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = localTrack

            var firstAudio = true
            while (!cancelled) {
                when (val command = queue.take()) {
                    Command.Cancel -> break
                    Command.Finish -> {
                        if (firstAudio) error("流式播放没有收到音频")
                        break
                    }
                    is Command.Audio -> {
                        val pcm = toPcm(command.samples)
                        var offset = 0
                        if (firstAudio) {
                            val prefillSamples = min(pcm.size, sampleRate)
                            offset += writeFully(localTrack, pcm, 0, prefillSamples)
                            localTrack.play()
                            playbackStartedNs = System.nanoTime()
                            summary = StreamPlaybackSummary(playbackStartedNs, 0L, 0L, 0)
                            started.countDown()
                            firstAudio = false
                        }
                        if (offset < pcm.size) {
                            writeFully(localTrack, pcm, offset, pcm.size - offset)
                        }
                        framesWritten += pcm.size
                    }
                }
            }

            if (!cancelled && playbackStartedNs != 0L) {
                while (!cancelled && playbackHeadFrames(localTrack) < framesWritten) {
                    Thread.sleep(20L)
                }
            }
            val finishedNs = System.nanoTime()
            val underruns = if (android.os.Build.VERSION.SDK_INT >= 24) localTrack.underrunCount else -1
            summary = StreamPlaybackSummary(playbackStartedNs, finishedNs, framesWritten, underruns)
            runCatching { localTrack.stop() }
        } catch (error: Throwable) {
            failure = error
            started.countDown()
        } finally {
            runCatching { localTrack?.release() }
            track = null
            completed.countDown()
        }
    }

    private fun writeFully(target: AudioTrack, pcm: ShortArray, start: Int, count: Int): Int {
        var offset = start
        val end = start + count
        while (offset < end && !cancelled) {
            val written = target.write(pcm, offset, end - offset, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { "AudioTrack 写入失败：$written" }
            offset += written
        }
        return offset - start
    }

    private fun toPcm(audio: FloatArray): ShortArray {
        val requestedGain = 10.0.pow(gainDb / 20.0)
        val peak = audio.maxOfOrNull { abs(it).toDouble() } ?: 0.0
        val safeGain = if (peak > 0.0) min(requestedGain, 0.98 / peak) else requestedGain
        return ShortArray(audio.size) { index ->
            (audio[index].toDouble().times(safeGain).coerceIn(-1.0, 1.0) * Short.MAX_VALUE)
                .toInt().toShort()
        }
    }

    private fun playbackHeadFrames(target: AudioTrack): Long =
        target.playbackHeadPosition.toLong() and 0xffff_ffffL
}

object ChineseTextSegmenter {
    private val hardStops = setOf('。', '！', '？', '!', '?', '；', ';', '\n')
    private val softStops = setOf('，', ',', '、', '：', ':')

    fun split(text: String, targetChars: Int = 42, maxChars: Int = 54): List<String> {
        require(targetChars in 16..maxChars)
        require(maxChars <= 70)
        val cleaned = text.trim().replace("\r\n", "\n").replace('\r', '\n')
        require(cleaned.isNotBlank()) { "长文本不能为空" }

        val naturalUnits = ArrayList<String>()
        val current = StringBuilder()
        cleaned.forEach { char ->
            if (char == '\n') {
                if (current.isNotEmpty() && current.last() !in hardStops) current.append('。')
            } else {
                current.append(char)
            }
            if (char in hardStops && current.isNotBlank()) {
                naturalUnits += current.toString().trim()
                current.clear()
            }
        }
        if (current.isNotBlank()) naturalUnits += current.toString().trim()

        val pieces = naturalUnits.flatMap { splitOversized(it, maxChars) }
        val result = ArrayList<String>()
        val packed = StringBuilder()
        pieces.forEach { piece ->
            if (packed.isEmpty()) {
                packed.append(piece)
            } else if (packed.length + piece.length <= targetChars) {
                packed.append(piece)
            } else {
                result += packed.toString()
                packed.clear()
                packed.append(piece)
            }
        }
        if (packed.isNotEmpty()) result += packed.toString()
        check(result.isNotEmpty() && result.all { it.length <= maxChars }) { "长文本分段失败" }
        return result
    }

    private fun splitOversized(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = ArrayList<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            var cut = -1
            for (index in maxChars - 1 downTo maxChars / 2) {
                if (remaining[index] in softStops) {
                    cut = index + 1
                    break
                }
            }
            if (cut < 1) cut = maxChars
            result += remaining.substring(0, cut).trim()
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result
    }
}
