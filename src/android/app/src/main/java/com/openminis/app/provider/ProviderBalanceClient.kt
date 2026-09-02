package com.openminis.app.provider

import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

data class ProviderBalanceState(
    val value: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0L,
)

/** Fetches and extracts one provider account balance without retaining credentials. */
internal object ProviderBalanceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(instance: ProviderInstance, apiKey: String?): String = withContext(Dispatchers.IO) {
        val endpoint = instance.balanceApiPath?.trim().orEmpty()
        val jsonPath = instance.balanceJsonPath?.trim().orEmpty()
        require(endpoint.isNotEmpty()) { "Balance API path is empty" }
        require(jsonPath.isNotEmpty()) { "Balance JSON key is empty" }

        val requestBuilder = Request.Builder().url(resolveUrl(instance, endpoint)).get()
        if (!apiKey.isNullOrBlank()) {
            if (instance.azureMode) {
                requestBuilder.header("api-key", apiKey)
            } else if (instance.providerType == ProviderType.anthropic) {
                requestBuilder.header("x-api-key", apiKey)
            } else {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }
        }
        instance.customUserAgent?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("User-Agent", it)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}${body.take(160).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }
            extractDisplayValue(body, jsonPath)
        }
    }

    internal fun extractDisplayValue(json: String, path: String): String {
        var current: Any? = JSONTokener(json).nextValue()
        val tokens = Regex("([^\\[.\\]]+)|\\[(\\d+)]").findAll(path.trim()).toList()
        require(tokens.isNotEmpty()) { "Balance JSON key is empty" }
        tokens.forEach { match ->
            val key = match.groupValues[1]
            val indexText = match.groupValues[2]
            current = if (key.isNotEmpty()) {
                val obj = current as? JSONObject
                    ?: throw IllegalArgumentException("Expected an object before '$key'")
                if (!obj.has(key)) throw IllegalArgumentException("JSON key '$key' was not found")
                obj.get(key)
            } else {
                val array = current as? JSONArray
                    ?: throw IllegalArgumentException("Expected an array before [$indexText]")
                val index = indexText.toInt()
                if (index !in 0 until array.length()) {
                    throw IllegalArgumentException("JSON array index [$index] is out of range")
                }
                array.get(index)
            }
        }
        if (current == null || current === JSONObject.NULL) {
            throw IllegalArgumentException("Balance value is null")
        }
        return when (current) {
            is Number -> runCatching {
                BigDecimal(current.toString()).stripTrailingZeros().toPlainString()
            }.getOrDefault(current.toString())
            is String -> current.trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Balance value is empty")
            else -> throw IllegalArgumentException("Balance value must be a number or string")
        }
    }

    private fun resolveUrl(instance: ProviderInstance, endpoint: String): HttpUrl {
        endpoint.toHttpUrlOrNull()?.let { return it }
        val base = instance.customBaseURL?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultBaseUrl(instance.providerType)
        val normalizedBase = "${base.trimEnd('/')}/".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Provider base URL is invalid")
        return normalizedBase.resolve(endpoint)
            ?: throw IllegalArgumentException("Balance API path is invalid")
    }

    private fun defaultBaseUrl(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com/v1"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.moonshot.cn/v1"
        ProviderType.openAI,
        ProviderType.openAIResponses,
        ProviderType.antigravity,
        ProviderType.unsupported -> "https://api.openai.com/v1"
    }
}