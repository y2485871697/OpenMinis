package com.openminis.app.ui.chat

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CompactModelFallbackTest {
    @Test fun firstSuccessDoesNotCallTheNextModel() = runBlocking {
        val calls = mutableListOf<Int>()
        assertEquals(1 to "summary", tryCompactModels(listOf(1, 2)) { calls.add(it); "summary" })
        assertEquals(listOf(1), calls)
    }
    @Test fun errorsAndEmptyResponsesAdvanceInPriorityOrder() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = tryCompactModels(listOf(1, 2, 3, 4)) {
            calls.add(it)
            when (it) { 1 -> throw IOException("401"); 2 -> " "; else -> "summary" }
        }
        assertEquals(3 to "summary", result)
        assertEquals(listOf(1, 2, 3), calls)
    }
    @Test fun providerTimeoutUsesTheNextModel() = runBlocking {
        val result = tryCompactModels(listOf(1, 2)) {
            if (it == 1) withTimeout(1) { delay(100); "late" } else "summary"
        }
        assertEquals(2 to "summary", result)
    }
    @Test fun userCancellationDoesNotCallTheNextModel() = runBlocking {
        val calls = mutableListOf<Int>()
        try {
            tryCompactModels(listOf(1, 2)) { calls.add(it); throw CancellationException("cancelled") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(1), calls)
    }
    @Test fun outerTimeoutStopsTheWholeChain() = runBlocking {
        val calls = mutableListOf<Int>()
        try {
            withTimeout(100) { tryCompactModels(listOf(1, 2)) { calls.add(it); delay(1000); "late" } }
            fail("Expected timeout")
        } catch (_: CancellationException) { }
        assertEquals(listOf(1), calls)
    }
    @Test fun lastFailureIsPreservedForExistingSplitRetry() = runBlocking {
        val last = IOException("context too large")
        try {
            tryCompactModels(listOf(1, 2)) { throw if (it == 2) last else IOException("503") }
            fail("Expected failure")
        } catch (error: IOException) { assertSame(last, error) }
    }
    @Test fun emptyConfigurationReportsFailure() = runBlocking {
        try {
            tryCompactModels(emptyList<Int>()) { "unused" }
            fail("Expected failure")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("No LLM")) }
    }
}
