package com.openminis.app.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingSnapshotStoreTest {
    @Test fun terminalSnapshotWinsOverPublishedPrefixAndPendingTail() = runTest {
        val visible = MutableStateFlow(mapOf("reply" to "| capability |"))
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("reply", "| capability | description")
        runCurrent()
        var canonical = ""
        store.finish("reply", "| capability | description |\n\nFinal paragraph.") {
            canonical = it
            assertEquals("| capability |", visible.value["reply"])
        }
        store.drainAll { fail("Finished reply must not be overwritten by an old snapshot") }
        advanceTimeBy(400)
        runCurrent()
        assertEquals("| capability | description |\n\nFinal paragraph.", canonical)
        assertTrue(visible.value.isEmpty())
    }

    @Test fun stopBeforeFirstTickStillCommitsAllText() = runTest {
        val visible = MutableStateFlow<Map<String, String>>(emptyMap())
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("reply", "one")
        store.enqueue("reply", "one two three")
        var drained = emptyMap<String, String>()
        store.drainAll { drained = it }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(mapOf("reply" to "one two three"), drained)
        assertTrue(visible.value.isEmpty())
    }

    @Test fun drainPrefersPendingOverAlreadyVisible() = runTest {
        val visible = MutableStateFlow(mapOf("reply" to "old"))
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("reply", "newest")
        var drained = ""
        store.drain("reply") { drained = it }
        advanceTimeBy(100)
        runCurrent()
        assertEquals("newest", drained)
        assertTrue(visible.value.isEmpty())
    }

    @Test fun ticksPublishNewestOnlyAndDoNotLoseIdleTail() = runTest {
        val visible = MutableStateFlow<Map<String, String>>(emptyMap())
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("reply", "a")
        runCurrent()
        store.enqueue("reply", "ab")
        advanceTimeBy(50)
        runCurrent()
        assertEquals("ab", visible.value["reply"])
        store.enqueue("reply", "abc")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals("abc", visible.value["reply"])
    }

    @Test fun oldCompletionCannotClearNextTurnWithSameId() = runTest {
        val visible = MutableStateFlow<Map<String, String>>(emptyMap())
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("reply", "first prefix")
        runCurrent()
        store.finish("reply", "first complete") { }
        store.enqueue("reply", "second turn")
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals("second turn", visible.value["reply"])
    }

    @Test fun resetAndRetainCancelUnpublishedRows() = runTest {
        val visible = MutableStateFlow<Map<String, String>>(emptyMap())
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("keep", "kept")
        store.enqueue("drop", "removed")
        runCurrent()
        store.retain(setOf("keep"))
        advanceTimeBy(50)
        runCurrent()
        assertEquals(mapOf("keep" to "kept"), visible.value)
        store.enqueue("keep", "pending")
        store.clearAll()
        advanceTimeBy(400)
        runCurrent()
        assertTrue(visible.value.isEmpty())
    }

    @Test fun multipleMessagesDrainWithoutOverwritingEachOther() = runTest {
        val visible = MutableStateFlow(mapOf("a" to "a1", "b" to "b1"))
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("a", "a2")
        store.enqueue("c", "c1")
        var result = emptyMap<String, String>()
        store.drainAll { result = it }
        assertEquals(mapOf("a" to "a2", "b" to "b1", "c" to "c1"), result)
    }

    @Test fun replacementCanBeShorterThanOldSnapshot() = runTest {
        val visible = MutableStateFlow(mapOf("reply" to "long obsolete snapshot"))
        val store = StreamingSnapshotStore(visible, backgroundScope, StandardTestDispatcher(testScheduler))
        store.enqueue("reply", "replacement")
        assertEquals("replacement", store.peek("reply"))
        var result = ""
        store.finish("reply", "short") { result = it }
        assertEquals("short", result)
        assertNull(store.peek("reply"))
    }
}