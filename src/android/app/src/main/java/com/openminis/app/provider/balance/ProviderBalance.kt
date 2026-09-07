package com.openminis.app.provider.balance

import android.content.Context
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Account balance fetcher, ported from RikkaHub's provider balance feature
 * (BalanceOption + OpenAIProvider.getBalance). Fetches GET {base}{apiPath}
 * with the instance credential, then reads the value out of the JSON body
 * with a dotted path expression ("data.total_usage", "balance[0].amount", …).
 */
object ProviderBalance {
    private const val TAG = "ProviderBalance"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** In-memory cache: instanceId -> balance string, 2 minutes. */
    private val cache = HashMap<String, Pair<Long, String>>()
    private var cacheTime = 0L
    private val cacheTtlMs = 2 * 60 * 1000L

    /**
     * Fetch (or pull from cache) the account balance for [instance].
     * Returns a display string, or null when it can't be determined
     * (disabled / no credential / request failed / path missing).
     */
    suspend fun fetchBalance(context: Context, instance: ProviderInstance): String? =
        withContext(Dispatchers.IO) {
            if (!instance.balanceEnabled) return@withContext null

            // Cache — key includes the option fields so editing the config
            // invalidates it.
            val key = "${instance.id}|${instance.balanceApiPath}|${instance.balanceResultPath}"
            synchronized(cache) {
                if (cacheTime > 0 && System.currentTimeMillis() - cacheTime < cacheTtlMs) {
                    cache[key]?.let { return@withContext it }
                }
            }

            val token = balanceToken(context, instance) ?: run {
                AppLogger.info(TAG, "No credential for ${instance.id} — balance skipped")
                return@withContext null
            }

            val url = buildURL(instance)
            if (url == null) {
                AppLogger.warning(TAG, "No base URL resolvable for ${instance.id} — balance skipped")
                return@withContext null
            }

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        AppLogger.warning(
                            TAG, "Balance request failed for ${instance.id}: " +
                                "${response.code} url=$url",
                        )
                        return@withContext null
                    }
                    val bodyStr = body ?: return@withContext null
                    val root = JSONObject(bodyStr)
                    val raw = getByPath(root, instance.balanceResultPath)
                        ?: run {
                            AppLogger.warning(
                                TAG, "Balance path '${instance.balanceResultPath}' " +
                                    "not found for ${instance.id}",
                            )
                            return@withContext null
                        }
                    val display = formatValue(raw)
                    AppLogger.info(
                        TAG, "Balance for ${instance.id}: path=" +
                            "'${instance.balanceResultPath}' value=$display",
                    )
                    synchronized(cache) {
                        cache[key] = display
                        cacheTime = System.currentTimeMillis()
                    }
                    display
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Balance fetch error for ${instance.id}: ${e.message}")
                null
            }
        }

    /** Resolve the credential for the balance call: OAuth token or API key. */
    private fun balanceToken(context: Context, instance: ProviderInstance): String? {
        if (instance.credentialType == ProviderCredential.oauth) {
            return try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                manager?.validAccessToken()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "OAuth token refresh failed for ${instance.id}: ${e.message}")
                null
            }
        }
        // Same pattern the UI uses to grab the repo from Compose land; null in
        // safe-mode, which just means "no balance shown".
        return com.openminis.app.MinisApp.instance?.providerRepositoryOrNull
            ?.loadApiKey(instance.id)
    }

    /**
     * Build the request URL: custom API base + apiPath. Falls back to the
     * provider type's official host when no custom base is set.
     */
    private fun buildURL(instance: ProviderInstance): String? {
        val path = instance.balanceApiPath.ifBlank { "/credits" }
        // Absolute path overrides everything (RikkaHub parity).
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = instance.effectiveBaseURL ?: officialBase(instance) ?: return null
        val trimmed = base.trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return "$trimmed$suffix"
    }

    private fun officialBase(instance: ProviderInstance): String? = when (instance.providerType) {
        com.openminis.app.data.model.ProviderType.openAI,
        com.openminis.app.data.model.ProviderType.openAIResponses -> "https://api.openai.com/v1"
        com.openminis.app.data.model.ProviderType.anthropic -> "https://api.anthropic.com/v1"
        com.openminis.app.data.model.ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        com.openminis.app.data.model.ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        com.openminis.app.data.model.ProviderType.xAI -> "https://api.x.ai/v1"
        com.openminis.app.data.model.ProviderType.kimiCode ->
            "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
        com.openminis.app.data.model.ProviderType.antigravity,
        com.openminis.app.data.model.ProviderType.unsupported -> null
    }

    /**
     * Read a value out of [root] by a dotted path with array index support:
     * "data.total_usage", "balance[0].amount", "quota.remaining".
     * Returns null when any segment is missing.
     */
    private fun getByPath(root: JSONObject, path: String): Any? {
        if (path.isBlank()) return null
        var current: Any = root
        for (segment in path.split('.')) {
            if (segment.isBlank()) return null
            // Parse trailing array index(es): "items[0]"
            var name = segment
            val indexes = ArrayList<Int>(1)
            while (true) {
                val open = name.indexOf('[')
                if (open < 0) break
                val close = name.indexOf(']', open)
                if (close < 0) return null
                val idx = name.substring(open + 1, close).toIntOrNull() ?: return null
                indexes.add(idx)
                name = name.substring(0, open)
            }
            val obj = current as? JSONObject ?: return null
            current = obj.opt(name) ?: return null
            for (idx in indexes) {
                val arr = current as? JSONArray ?: return null
                if (idx < 0 || idx >= arr.length()) return null
                current = arr.get(idx)
            }
        }
        return current
    }

    /** Format the extracted value: numbers trim trailing zeros, strings stay. */
    private fun formatValue(value: Any): String {
        return when (value) {
            is String -> value
            is Boolean -> value.toString()
            is Int, is Long -> value.toString()
            is Double -> {
                if (value == Math.floor(value) && !value.isInfinite() &&
                    Math.abs(value) < 1e15
                ) {
                    value.toLong().toString()
                } else {
                    "%.2f".format(value)
                }
            }
            is Float -> formatValue(value.toDouble())
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString()
        }
    }
}
