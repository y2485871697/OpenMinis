package com.openminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> tryCompactModels(
    candidates: List<T>,
    onFailure: (T, Exception) -> Unit = { _, _ -> },
    request: suspend (T) -> String,
): Pair<T, String> {
    var lastError: Exception? = null
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        try {
            val text = request(candidate)
            currentCoroutineContext().ensureActive()
            check(text.isNotBlank()) { "Compaction model returned an empty response" }
            return candidate to text
        } catch (error: Exception) {
            // A provider-local timeout can fail over; cancelling the compaction
            // itself must stop the entire chain, including an outer timeout.
            currentCoroutineContext().ensureActive()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            lastError = error
            onFailure(candidate, error)
        }
    }
    throw lastError ?: IllegalStateException("No LLM provider available for compaction")
}
