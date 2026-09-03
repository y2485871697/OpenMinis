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
        val trimmed = json.trimStart()
        if (trimmed.startsWith("<!doctype", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            throw IllegalArgumentException(
                "Balance endpoint returned HTML. Check the provider base URL and balance API path.",
            )
        }
        var current: Any? = unwrapJsonValue(JSONTokener(json).nextValue())
        val normalizedPath = path.trim().removePrefix("$").trimStart('.')
        val tokens = Regex("([^\\[.\\]]+)|\\[(\\d+)]").findAll(normalizedPath).toList()

        // A root path ("$") is useful for providers whose balance endpoint
        // returns a bare number/string instead of a JSON object.
        if (tokens.isEmpty()) return formatDisplayValue(current)

        tokens.forEachIndexed { tokenIndex, match ->
            val key = match.groupValues[1]
            val indexText = match.groupValues[2]
            current = if (key.isNotEmpty()) {
                when (val container = unwrapJsonValue(current)) {
                    is JSONObject -> {
                        if (!container.has(key)) {
                            throw IllegalArgumentException("JSON key '$key' was not found")
                        }
                        container.get(key)
                    }
                    is JSONArray -> {
                        // Several gateways wrap account records in a top-level
                        // array. Resolve a named key from the first matching
                        // object so a simple path such as "remaining" works.
                        val matchObject = (0 until container.length())
                            .asSequence()
                            .mapNotNull { unwrapJsonValue(container.opt(it)) as? JSONObject }
                            .firstOrNull { it.has(key) }
                        when {
                            matchObject != null -> matchObject.get(key)
                            container.length() == 1 &&
                                tokenIndex == 0 &&
                                tokens.size == 1 -> container.get(0)
                            else -> throw IllegalArgumentException(
                                "JSON key '$key' was not found in the response array",
                            )
                        }
                    }
                    else -> {
                        // Some compatible providers return the remaining balance
                        // directly (for example: 9.26 or "9.26"). In that shape
                        // there is no object key to traverse, so a single configured
                        // key names the root value for compatibility.
                        if (tokenIndex == 0 && tokens.size == 1) container
                        else throw IllegalArgumentException("Expected an object before '$key'")
                    }
                }
            } else {
                val array = unwrapJsonValue(current) as? JSONArray
                    ?: throw IllegalArgumentException("Expected an array before [$indexText]")
                val index = indexText.toInt()
                if (index !in 0 until array.length()) {
                    throw IllegalArgumentException("JSON array index [$index] is out of range")
                }
                array.get(index)
            }
            current = unwrapJsonValue(current)
        }
        return formatDisplayValue(current)
    }

    /**
     * Some proxy APIs JSON-encode their payload twice. Unwrap a few bounded
     * layers so "{\"remaining\":9.26}" behaves like a normal object response.
     */
    private fun unwrapJsonValue(value: Any?): Any? {
        var current = value
        repeat(3) {
            val text = current as? String ?: return current
            val trimmed = text.trim()
            val looksLikeJson =
                (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("[") && trimmed.endsWith("]"))
            if (!looksLikeJson) return current
            current = runCatching { JSONTokener(trimmed).nextValue() }
                .getOrElse { return current }
        }
        return current
    }

    private fun formatDisplayValue(value: Any?): String {
        if (value == null || value === JSONObject.NULL) {
            throw IllegalArgumentException("Balance value is null")
        }
        return when (value) {
            is Number -> runCatching {
                BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            }.getOrDefault(value.toString())
            is String -> value.trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Balance value is empty")
            else -> throw IllegalArgumentException("Balance value must be a number or string")
        }
    }

    private fun resolveUrl(instance: ProviderInstance, endpoint: String): HttpUrl {
        endpoint.toHttpUrlOrNull()?.let { return it }
        val base = instance.customBaseURL?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultBaseUrl(instance.providerType)
        return resolveUrl(base, endpoint)
    }

    internal fun resolveUrl(base: String, endpoint: String): HttpUrl {
        val normalizedBase = "${base.trimEnd('/')}/".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Provider base URL is invalid")
        return normalizedBase.resolve(endpoint.trimStart('/'))
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
