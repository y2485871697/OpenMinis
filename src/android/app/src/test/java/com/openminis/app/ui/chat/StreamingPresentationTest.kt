package com.openminis.app.ui.chat

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class StreamingPresentationTest {
    private fun reply(id: String, text: String, streaming: Boolean = false) = ChatMessage(
        id = id, role = "assistant", content = text, isStreaming = streaming,
        toolBlocks = if (text.isEmpty()) emptyList() else listOf(AssistantBlock("$id-text", "text", text)),
    )

    @Test fun terminalOverlayRemovalCannotPublishTheOldTypingRow() {
        val old = reply("reply", "", true).copy(isAwaitingModelResponse = true)
        val final = reply("reply", "## Done\n\n**complete**")
        val overlay = mapOf("reply" to canonicalStreamingDelta(final)!!)
        assertFalse(hasStaleStreamingHandoff(listOf(old), overlay, listOf(final)))
        assertTrue(hasStaleStreamingHandoff(listOf(old), emptyMap(), listOf(final)))
        assertFalse(hasStaleStreamingHandoff(listOf(final), emptyMap(), listOf(final)))
        assertEquals(final.content, canonicalStreamingDelta(final)!!.content)
        assertEquals(final.toolBlocks, canonicalStreamingDelta(final)!!.toolBlocks)
    }

    @Test fun initialThinkingIsAllowedButDrainAndRemovalWaitForCanonicalWindow() {
        val old = reply("reply", "", true)
        assertFalse(hasStaleStreamingHandoff(listOf(old), emptyMap(), listOf(old)))
        assertTrue(hasStaleStreamingHandoff(listOf(old), emptyMap(), listOf(reply("reply", "partial", true))))
        assertTrue(hasStaleStreamingHandoff(listOf(old), emptyMap(), emptyList()))
        val cancelled = reply("reply", "partial").copy(error = "cancelled")
        assertFalse(hasStaleStreamingHandoff(listOf(cancelled), emptyMap(), listOf(cancelled)))
    }

    @Test fun splitAndFullListsKeepTheSameMarkdownRowIdentity() {
        val old = reply("old", "## Heading\n\n| A | B |\n| --- | --- |\n| x | y |\n\nEnd")
        val user = ChatMessage("user", "user", "repeat")
        val live = reply("new", "## New\n\nBody", true)
        val full = listOf(old, user, live)
        val prefix = buildFlatChatItems(full.take(2), lastAssistantMessageId = live.id)
        val suffix = buildFlatChatItems(full, fromIndex = 2, seedKeys = prefix.map { it.key }.toSet())
        val splitRows = prefix + suffix
        val fullRows = buildFlatChatItems(full)
        assertEquals(fullRows.map { it.key to it.contentType }, splitRows.map { it.key to it.contentType })
        val finalRows = buildFlatChatItems(listOf(old, user, live.copy(isStreaming = false)))
        assertEquals(
            fullRows.filterIsInstance<FlatChatItem.AssistantText>().map { it.key },
            finalRows.filterIsInstance<FlatChatItem.AssistantText>().map { it.key },
        )
    }

    @Test fun provisionalBoldDoesNotShowSourceMarkers() {
        for (source in listOf("**TTS", "**TTS*", "**TTS**")) {
            val parsed = parseInlinePresentationForTest(source, true)
            assertEquals("TTS", parsed.text)
            assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        }
    }

    @Test fun frozenUnmatchedMarkersStayLiteral() {
        assertEquals("**TTS", parseInlinePresentationForTest("**TTS", false).text)
        assertEquals("TTS", parseInlinePresentationForTest("**TTS**", false).text)
    }

    @Test fun escapedAndCodeMarkersAreNotRemoved() {
        assertEquals("**TTS", parseInlinePresentationForTest("\\*\\*TTS", true).text)
        assertTrue(parseInlinePresentationForTest("`**TTS**`", true).text.contains("**TTS**"))
        assertEquals("**TTS", parseInlinePresentationForTest("`**TTS", true).text)
    }

    @Test fun provisionalSpansDoNotCrossLinesOrWhitespaceOpeners() {
        assertEquals(-1, streamingEmphasisEnd("** TTS", 0, "**", true))
        assertEquals(-1, streamingEmphasisEnd("**TTS\nplain", 0, "**", true))
        assertEquals(-1, streamingEmphasisEnd("name__value", 4, "__", true))
    }

    @Test fun emptyAndPartialDelimitersMakeProgress() {
        for (marker in listOf("**", "***", "__", "~~")) {
            assertEquals("", parseInlinePresentationForTest(marker, true).text)
            assertEquals("value", parseInlinePresentationForTest(marker + "value", true).text)
        }
    }
}