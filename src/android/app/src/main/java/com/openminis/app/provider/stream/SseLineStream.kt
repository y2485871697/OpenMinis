package com.openminis.app.provider.stream

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [T-rikkahub-sse-port] rikkahub-style asynchronous SSE line transport.
 *
 * Ported from rikkahub's `me.rerere.ai.util.SSEEventSource` (AIChat provider
 * layer, GPL-licensed, https://github.com/rikkahub/rikkahub): instead of a
 * blocking `call.execute()` + `BufferedReader.readLine()` loop running on the
 * flow producer coroutine, the call is **enqueued** and the SSE body is read
 * on OkHttp's own dispatcher thread. Every decoded line is pushed with
 * `trySend` into an UNBOUNDED channel, so the socket is drained at line rate
 * no matter how slow the downstream consumer (parse loop, StateFlow
 * publication, Compose recomposition) happens to be.
 *
 * That is the property the previous implementation lacked, and it is the root
 * cause of the "stuck, then everything at once" streaming symptom: the old
 * read loop called suspending `send()` on a `callbackFlow` whose channel
 * buffers only 64 chunks. Whenever the collector lagged one frame too many —
 * a heavy recomposition of the growing markdown row, the thinking-delta hop
 * through Dispatchers.Main — `send()` suspended, the read loop stopped
 * draining the socket, TCP backpressure built up, and when the UI finally
 * caught up the entire backlog flushed in one burst. rikkahub never exhibits
 * this because its network thread cannot be backpressured by UI speed.
 *
 * Error semantics are preserved exactly:
 *  - non-2xx / header-phase failure: [onHeaders] throws (typically the
 *    provider's `mapHttpError` result); [awaitHeaders] rethrows it in the
 *    producer coroutine at the same point `execute()` used to throw.
 *  - read-phase IOException mid-stream: the channel is closed with the
 *    exception, so the next [readLine] throws it — identical to
 *    `BufferedReader.readLine()` failing inside the old loop.
 *  - EOF: [readLine] returns null.
 *
 * The reader thread ALSO flips the caller's [headersArrived] flag the moment
 * headers land (before [onHeaders] runs), so per-provider TTFB watchdogs
 * keep their exact semantics.
 */
internal class SseLineStream private constructor(
    /** The enqueued call. Exposed so watchdogs / cancellers can cancel it. */
    val call: Call,
    private val lines: Channel<String?>,
    private val headersDone: CompletableDeferred<Unit>,
) {
    /** Set when the header-phase hook threw; rethrown by [awaitHeaders]. */
    @Volatile
    private var headerError: Throwable? = null

    /** Idempotent consumer-side teardown guard. */
    @Volatile
    private var closed = false

    companion object {
        /**
         * Create the call, enqueue it, and return immediately — the body is
         * read on the OkHttp dispatcher thread, not on the caller's.
         *
         * @param headersArrived flipped the moment response headers land
         *   (TTFB watchdog signal); may be null when the caller has none.
         * @param onHeaders provider hook run on the OkHttp thread with the
         *   raw [Response]: status/header logging, LLMRequestLog capture and
         *   non-2xx error materialisation (which throws the mapped
         *   [com.openminis.app.data.model.LLMError]). The transport owns the
         *   response lifecycle — do NOT close it inside the hook.
         */
        fun launch(
            client: OkHttpClient,
            request: Request,
            headersArrived: AtomicBoolean? = null,
            onHeaders: (Response) -> Unit = {},
        ): SseLineStream {
            val lines = Channel<String?>(Channel.UNLIMITED)
            val headersDone = CompletableDeferred<Unit>()
            // rikkahub's SSEEventSource.factory adds the standard SSE Accept
            // header when the request carries none; kept for proxy/CDN parity.
            val actualRequest = if (request.header("Accept") == null) {
                request.newBuilder().header("Accept", "text/event-stream").build()
            } else {
                request
            }
            val stream = SseLineStream(client.newCall(actualRequest), lines, headersDone)
            stream.call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    headersArrived?.set(true)
                    // ── Phase 1: headers ──────────────────────────────────
                    // Run the provider hook (status logging / non-2xx error)
                    // BEFORE the read loop, then open the checkpoint so
                    // awaitHeaders() resumes at the exact moment execute()
                    // used to return.
                    try {
                        onHeaders(response)
                    } catch (t: Throwable) {
                        stream.headerError = t
                        lines.close(t)
                        try { response.close() } catch (_: Throwable) {}
                        headersDone.complete(Unit)
                        return
                    }
                    headersDone.complete(Unit)
                    // ── Phase 2: read the body on THIS OkHttp thread ──────
                    // trySend + unbounded channel = the socket is never
                    // backpressured by consumer speed (rikkahub semantics).
                    try {
                        val body = response.body ?: throw IOException("Response has no body")
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (lines.trySend(line).isClosed) return
                        }
                        lines.close() // EOF
                    } catch (t: Throwable) {
                        lines.close(t)
                    } finally {
                        try { response.close() } catch (_: Throwable) {}
                        headersDone.complete(Unit)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    headersArrived?.set(true)
                    if (stream.headerError == null) stream.headerError = e
                    lines.close(e)
                    headersDone.complete(Unit)
                }
            })
            return stream
        }
    }

    /**
     * Suspend until response headers are validated. Rethrows whatever
     * [onHeaders] threw (mapped provider error) or the OkHttp failure —
     * the producer sees it exactly where `call.execute()` used to throw.
     */
    suspend fun awaitHeaders() {
        headersDone.await()
        headerError?.let { throw it }
    }

    /**
     * Next SSE line, or null at EOF. A mid-stream transport failure throws
     * the original exception, matching `BufferedReader.readLine()` behaviour
     * inside the old loop (the provider's catch maps it via mapError).
     */
    suspend fun readLine(): String? = try {
        lines.receive()
    } catch (e: ClosedReceiveChannelException) {
        null
    }

    /**
     * Tear the stream down: cancels the call (unblocks the reader thread)
     * and closes the line channel. Idempotent; called from the producer's
     * finally and from awaitClose.
     */
    fun close() {
        if (closed) return
        closed = true
        try { call.cancel() } catch (_: Throwable) {}
        lines.close()
    }
}
