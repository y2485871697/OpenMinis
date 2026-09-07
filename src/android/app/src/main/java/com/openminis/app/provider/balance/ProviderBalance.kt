package com.openminis.app.provider.balance

import android.content.Context
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Account balance fetcher, ported from RikkaHub's provider balance feature
 * (BalanceOption + OpenAIProvider.getBalance). Fetches GET {base}{apiPath}
 * with the instance credential, then reads the value out of the JSON body
 * with a dotted path expression ("data.total_usage", "balance[0].amount", …).
 *
 * Real-time strategy: NO time-based TTL. The last-known-good value is kept
 * so the UI can render instantly on recomposition, and a global
 * [refreshTrigger] StateFlow drives re-fetches — bumped by [invalidate()]
 * whenever consumption may have changed (each streaming turn ends, config
 * edits). Visible [ProviderBalanceText]s collect the trigger and re-fetch,
 * with a 1-second dedup window + per-key Mutex so ten simultaneous
 * recompositions of the same provider fire exactly one HTTP request.
 */
object ProviderBalance {
    private const val TAG = "ProviderBalance"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Bumped by [invalidate]; collectors re-fetch on change. */
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()

    /** Last-known-good value per cache key — survives re-fetch failures. */
    private val lastValue = ConcurrentHashMap<String, String>()
    private val lastFetchTime = ConcurrentHashMap<String, Long>()

    /** Per-key in-flight dedup so concurrent collectors share one request. */
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    /** Window in which a second fetch for the same key reads the cache. */
    private const val DEDUP_WINDOW_MS = 1000L

    /**
     * Ask every visible balance readout to re-fetch. Cheap (bump an Int);
     * the actual requests are deduped in [fetchBalance].
     */
    fun invalidate() {
        _refreshTrigger.value += 1
    }

    /** The last successfully fetched value, if any — for instant first paint. */
    fun lastKnownBalance(instance: ProviderInstance): String? {
        if (!instance.balanceEnabled) return null
        return lastValue[cacheKey(instance)]
    }

    private fun cacheKey(instance: ProviderInstance): String =
        "${instance.id}|${instance.balanceApiPath}|${instance.balanceResultPath}"

    /**
     * Fetch the account balance for [instance] — always live (no TTL), with
     * a 1s dedup window so a trigger burst resolves to one request per key.
     * Returns a display string, or null when it can't be determined
     * (disabled / no credential / request failed / path missing). On failure
     * the previous last-known value is NOT overwritten.
     */
    suspend fun fetchBalance(context: Context, instance: ProviderInstance): String? =
        withContext(Dispatchers.IO) {
            if (!instance.balanceEnabled) return@withContext null
            val key = cacheKey(instance)
            val mutex = fetchLocks.computeIfAbsent(key) { Mutex() }
            mutex.withLock {
                // Dedup window: if an identical fetch just completed (e.g. the
                // top bar and the model picker both recomposed on the same
                // trigger bump), reuse it instead of hitting the API again.
                val fetchedAt = lastFetchTime[key] ?: 0L
                if (System.currentTimeMillis() - fetchedAt < DEDUP_WINDOW_MS) {
                    return@withLock lastValue[key]
                }

                val token = balanceToken(context, instance) ?: run {
                    AppLogger.info(TAG, "No credential for ${instance.id} — balance skipped")
                    return@withLock null
                }

                val url = buildURL(instance)
                if (url == null) {
                    AppLogger.warning(TAG, "No base URL resolvable for ${instance.id} — balance skipped")
                    return@withLock null
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
                            return@withLock null
                        }
                        val bodyStr = body ?: return@withLock null
                        val root = JSONObject(bodyStr)
                        val raw = getByPath(root, instance.balanceResultPath)
                            ?: run {
                                AppLogger.warning(
                                    TAG, "Balance path '${instance.balanceResultPath}' " +
                                        "not found for ${instance.id}",
                                )
                                return@withLock null
                            }
                        val display = formatValue(raw)
                        AppLogger.info(
                            TAG, "Balance for ${instance.id}: path=" +
                                "'${instance.balanceResultPath}' value=$display",
                        )
                        lastValue[key] = display
                        lastFetchTime[key] = System.currentTimeMillis()
                        display
                    }
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "Balance fetch error for ${instance.id}: ${e.message}")
                    null
                }
            }
        }

    /** Resolve the credential for the balance call: OAuth token or API key. */
    private suspend fun balanceToken(context: Context, instance: ProviderInstance): String? {
        if (instance.credentialType == ProviderCredential.oauth) {
            return try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                manager?.validAccessToken()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "OAuth token refresh failed for ${instance.id}: ${e.message}")
                null
            }
        }
        // Read the key straight from the same encrypted store
        // ProviderRepository.loadApiKey uses (apikey_<id> in "provider_secrets")
        // — avoids needing a repository instance from a non-UI object.
        // safeCreate is self-healing on corrupted master keys, so this cannot
        // crash; a null key just means "no balance shown".
        return try {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "provider_secrets")
            prefs.getString("apikey_${instance.id}", null)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Failed to read API key for ${instance.id}: ${e.message}")
            null
        }
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
