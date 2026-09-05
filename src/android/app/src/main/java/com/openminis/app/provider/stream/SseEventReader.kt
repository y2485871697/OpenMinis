package com.openminis.app.provider.stream

import java.io.BufferedReader

/**
 * Transport-only Server-Sent Events reader.
 *
 * It follows the SSE field grammar instead of assuming one data line per
 * event. Providers receive complete, transport-independent events and remain
 * responsible for decoding their own JSON protocol. A reader instance is
 * single-use and must not be shared between concurrent responses.
 */
data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String,
    val retryMillis: Long? = null,
)

/**
 * Parses an SSE byte stream into complete events.
 *
 * Blank lines dispatch the current event. Multiple data fields are joined
 * with LF, an optional single space after the colon is removed, comments are
 * ignored, and a final event is dispatched at EOF even without a trailing
 * blank line. This also accepts data:{...}, which is emitted by some
 * OpenAI-compatible gateways.
 */
class SseEventReader(private val reader: BufferedReader) : Iterable<SseEvent> {
    override fun iterator(): Iterator<SseEvent> = sequence {
        var id: String? = null
        var event: String? = null
        var retryMillis: Long? = null
        val data = StringBuilder()

        fun reset() {
            id = null
            event = null
            retryMillis = null
            data.setLength(0)
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    yield(SseEvent(id, event, data.toString(), retryMillis))
                }
                reset()
                continue
            }
            if (line[0] == ':') continue

            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            var value = if (separator < 0) "" else line.substring(separator + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            when (field) {
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
                "event" -> event = value
                "id" -> if (!value.contains('\u0000')) id = value
                "retry" -> value.toLongOrNull()?.takeIf { it >= 0 }?.let { retryMillis = it }
            }
        }

        if (data.isNotEmpty()) {
            yield(SseEvent(id, event, data.toString(), retryMillis))
        }
    }.iterator()
}