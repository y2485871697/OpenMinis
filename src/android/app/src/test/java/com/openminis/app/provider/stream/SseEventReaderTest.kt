package com.openminis.app.provider.stream

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class SseEventReaderTest {
    @Test
    fun acceptsOptionalSpaceAndPreservesEventFields() {
        val input = ": heartbeat\nid: 7\nevent: message\ndata:{\"a\":1}\n\n"
        val event = SseEventReader(BufferedReader(StringReader(input))).single()
        assertEquals("7", event.id)
        assertEquals("message", event.event)
        assertEquals("{\"a\":1}", event.data)
    }

    @Test
    fun joinsMultilineDataAndDispatchesFinalEventAtEof() {
        val input = "data: first\ndata: second"
        val event = SseEventReader(BufferedReader(StringReader(input))).single()
        assertEquals("first\nsecond", event.data)
    }

    @Test
    fun ignoresInvalidRetryAndNullContainingIds() {
        val input = "id: bad\u0000id\nretry: nope\ndata: ok\n\n"
        val event = SseEventReader(BufferedReader(StringReader(input))).single()
        assertEquals(null, event.id)
        assertEquals(null, event.retryMillis)
    }

    @Test
    fun emitsEachEventIndependently() {
        val input = "data: one\n\n data: ignored\n\ndata: two\n\n"
        val events = SseEventReader(BufferedReader(StringReader(input))).toList()
        assertEquals(listOf("one", "two"), events.map { it.data })
    }
}