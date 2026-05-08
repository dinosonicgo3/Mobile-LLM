package com.oracleairescue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LlmClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun sendChat(settings: ModelSettings, messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "請先在設定頁輸入 API Key。" }
        require(settings.baseUrl.isNotBlank()) { "請先在設定頁輸入 Base URL。" }
        require(settings.modelName.isNotBlank()) { "請先在設定頁選擇或輸入 Model Name。" }

        val jsonMessages = JSONArray()
        messages.forEach { message ->
            jsonMessages.put(JSONObject().put("role", message.role).put("content", message.content))
        }

        val payload = JSONObject()
            .put("model", settings.modelName)
            .put("messages", jsonMessages)
            .put("temperature", settings.temperature)
            .put("max_tokens", 4096)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("LLM API 呼叫失敗：HTTP ${response.code}\n$body")
            }
            parseChatContent(body)
        }
    }

    suspend fun listModels(settings: ModelSettings): List<ModelOption> = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "請先輸入 API Key，才能向官方 API 取得可用模型清單。" }
        require(settings.baseUrl.isNotBlank()) { "請先輸入 Base URL。" }

        when (settings.provider) {
            "gemini" -> listGeminiModels(settings)
            "nvidia" -> listOpenAiCompatibleModels(settings)
            else -> listOpenAiCompatibleModels(settings)
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun listGeminiModels(settings: ModelSettings): List<ModelOption> {
        val base = settings.baseUrl.trimEnd('/').removeSuffix("/openai")
        val request = Request.Builder()
            .url("$base/models?key=${URLEncoder.encode(settings.apiKey, "UTF-8")}")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("取得 Gemini 模型清單失敗：HTTP ${response.code}\n$body")
            }
            val root = JSONObject(body)
            val arr = root.optJSONArray("models") ?: return emptyList()
            val result = mutableListOf<ModelOption>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rawName = item.optString("name")
                val id = rawName.removePrefix("models/")
                if (id.isBlank()) continue

                val methods = item.optJSONArray("supportedGenerationMethods")
                val supportsTextGeneration = methods == null || (0 until methods.length()).any { idx ->
                    methods.optString(idx).equals("generateContent", ignoreCase = true)
                }
                if (!supportsTextGeneration) continue

                val displayName = item.optString("displayName").ifBlank { id }
                val descriptionParts = listOfNotNull(
                    item.optString("description").takeIf { it.isNotBlank() },
                    item.optInt("inputTokenLimit", 0).takeIf { it > 0 }?.let { "輸入上限：$it tokens" },
                    item.optInt("outputTokenLimit", 0).takeIf { it > 0 }?.let { "輸出上限：$it tokens" }
                )
                result += ModelOption(id = id, displayName = displayName, description = descriptionParts.joinToString("｜"))
            }
            return result
        }
    }

    private fun listOpenAiCompatibleModels(settings: ModelSettings): List<ModelOption> {
        val request = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/models")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("取得模型清單失敗：HTTP ${response.code}\n$body")
            }
            val root = JSONObject(body)
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
            val result = mutableListOf<ModelOption>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("name") }
                if (id.isBlank()) continue
                val displayName = item.optString("display_name").ifBlank { item.optString("displayName").ifBlank { id } }
                val description = item.optString("description")
                    .ifBlank { item.optString("owned_by") }
                result += ModelOption(id = id, displayName = displayName, description = description)
            }
            return result
        }
    }

    private fun parseChatContent(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: error("API 回應沒有 choices：$body")
        if (choices.length() == 0) error("API 回應 choices 是空的：$body")
        val first = choices.getJSONObject(0)
        val message = first.optJSONObject("message")
        val content = message?.optString("content")
        if (!content.isNullOrBlank()) return content
        val text = first.optString("text")
        if (text.isNotBlank()) return text
        return body
    }
}
