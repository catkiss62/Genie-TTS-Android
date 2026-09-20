package com.catkiss62.geniettsbenchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

data class DeepSeekUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

data class DeepSeekStreamResult(
    val requestId: String?,
    val finishReason: String?,
    val usage: DeepSeekUsage?,
)

/** Minimal official Chat Completions SSE client; it intentionally has no third-party SDK. */
class DeepSeekStreamClient {
    companion object {
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }

    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    fun stream(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        onDelta: (String) -> Unit,
    ): DeepSeekStreamResult {
        require(apiKey.isNotBlank()) { "请先保存 DeepSeek API Key" }
        require(model.isNotBlank()) { "DeepSeek 模型名不能为空" }
        cancelled = false
        val target = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection = target
        try {
            target.requestMethod = "POST"
            target.connectTimeout = 20_000
            target.readTimeout = 180_000
            target.doOutput = true
            target.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            target.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            target.setRequestProperty("Accept", "text/event-stream")
            val body = JSONObject()
                .put("model", model.trim())
                .put("stream", true)
                .put("max_tokens", maxTokens)
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("stream_options", JSONObject().put("include_usage", true))
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)),
                )
            target.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            checkCancelled()
            val responseCode = target.responseCode
            if (responseCode !in 200..299) {
                val errorText = target.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(8_192) }
                    .orEmpty()
                error("DeepSeek HTTP $responseCode：${readableApiError(errorText)}")
            }

            var requestId: String? = null
            var finishReason: String? = null
            var usage: DeepSeekUsage? = null
            BufferedReader(InputStreamReader(target.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    checkCancelled()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    val chunk = JSONObject(payload)
                    requestId = chunk.optString("id").takeIf { it.isNotBlank() } ?: requestId
                    chunk.optJSONObject("usage")?.let {
                        usage = DeepSeekUsage(
                            promptTokens = it.optInt("prompt_tokens"),
                            completionTokens = it.optInt("completion_tokens"),
                            totalTokens = it.optInt("total_tokens"),
                        )
                    }
                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val choice = choices.optJSONObject(0) ?: continue
                    choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let {
                        finishReason = it
                    }
                    val content = choice.optJSONObject("delta")?.optString("content").orEmpty()
                    if (content.isNotEmpty()) onDelta(content)
                }
            }
            checkCancelled()
            return DeepSeekStreamResult(requestId, finishReason, usage)
        } finally {
            connection = null
            target.disconnect()
        }
    }

    private fun readableApiError(raw: String): String {
        return runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.optString("message").orEmpty().ifBlank { raw }
        }.getOrDefault(raw).ifBlank { "服务器没有返回错误说明" }
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("DeepSeek 流式请求已停止")
        }
    }
}
