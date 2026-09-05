package com.openminis.app.ui.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Serializes delayed UI publication with terminal commits and cancellation drains. */
internal class StreamingSnapshotStore<T : Any>(
    private val published: MutableStateFlow<Map<String, T>>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val intervalMs: Long = 50L,
) {
    private val lock = Any()
    private val pending = mutableMapOf<String, T>()
    private val jobs = mutableMapOf<String, Job>()

    fun enqueue(id: String, value: T) {
        synchronized(lock) {
            pending[id] = value
            if (jobs[id]?.isActive == true) return
            val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                delay(intervalMs)
                val self = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (jobs[id] !== self) return@synchronized
                    jobs.remove(id)
                    pending.remove(id)?.let { latest ->
                        // Publication must share the clear/drain lock. Otherwise a
                        // worker can restore an obsolete overlay AFTER completion.
                        published.value = published.value + (id to latest)
                    }
                }
            }
            jobs[id] = job
            job.start()
        }
    }

    fun peek(id: String): T? = synchronized(lock) { pending[id] ?: published.value[id] }

    fun finish(id: String, final: T, commit: (T) -> Unit) {
        synchronized(lock) {
            // The canonical terminal value wins even when a shorter snapshot is
            // still on screen. Commit before withdrawing the overlay.
            commit(final)
            clearLocked(id)
        }
    }

    fun drain(id: String, commit: (T) -> Unit) {
        synchronized(lock) {
            (pending[id] ?: published.value[id])?.let(commit)
            clearLocked(id)
        }
    }

    fun drainAll(commit: (Map<String, T>) -> Unit) {
        synchronized(lock) {
            val latest = published.value + pending
            if (latest.isNotEmpty()) commit(latest)
            clearAllLocked()
        }
    }

    fun clear(id: String) = synchronized(lock) { clearLocked(id) }

    fun clearAll() = synchronized(lock) { clearAllLocked() }

    fun retain(ids: Set<String>) {
        synchronized(lock) {
            val removed = (pending.keys + published.value.keys + jobs.keys) - ids
            removed.forEach(::clearLocked)
        }
    }

    private fun clearLocked(id: String) {
        jobs.remove(id)?.cancel()
        pending.remove(id)
        published.value = published.value - id
    }

    private fun clearAllLocked() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        pending.clear()
        published.value = emptyMap()
    }
}