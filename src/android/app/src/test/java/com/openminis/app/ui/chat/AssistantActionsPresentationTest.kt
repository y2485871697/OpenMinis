package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class AssistantActionsPresentationTest {
    private fun reply(text: String, streaming: Boolean = false) = ChatMessage(
        id = "reply", role = "assistant", content = text, isStreaming = streaming,
        toolBlocks = if (text.isEmpty()) emptyList() else listOf(AssistantBlock("text", "text", text)),
    )

    private fun actions(vararg messages: ChatMessage) =
        buildFlatChatItems(messages.toList()).filterIsInstance<FlatChatItem.AssistantActions>()

    @Test fun completionUpdatesTheExistingFooterInsteadOfInsertingARow() {
        val live = reply("## Heading\n\nA complete reply", true)
        val before = buildFlatChatItems(listOf(live))
        val after = buildFlatChatItems(listOf(live.copy(isStreaming = false)))
        assertEquals(before.map { it.key to it.contentType }, after.map { it.key to it.contentType })
        val pending = before.filterIsInstance<FlatChatItem.AssistantActions>().single()
        val ready = after.filterIsInstance<FlatChatItem.AssistantActions>().single()
        assertEquals(pending.key, ready.key)
        assertFalse(pending.isReady)
        assertTrue(ready.isReady)
        assertNotEquals(pending, ready)
    }

    @Test fun pendingFooterDoesNotChangeOnEveryTextUpdate() {
        val before = actions(reply("Partial", true)).single()
        val after = actions(reply("Partial reply with more content", true)).single()
        assertEquals("", before.messageMarkdown)
        assertEquals("", after.messageMarkdown)
        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
    }

    @Test fun readinessParticipatesInEqualityEvenWithIdenticalText() {
        val pending = FlatChatItem.AssistantActions("reply", "", null, false)
        val ready = FlatChatItem.AssistantActions("reply", "", null, true)
        assertNotEquals(pending, ready)
        assertEquals(pending.key, ready.key)
        assertEquals(ready, FlatChatItem.AssistantActions("reply", "", null))
    }

    @Test fun historyIsReadyImmediatelyEvenWhileANewerReplyStreams() {
        val old = reply("History")
        val user = ChatMessage("user", "user", "New question")
        val live = reply("New", true).copy(id = "new")
        val rows = actions(old, user, live)
        assertTrue(rows[0].isReady)
        assertEquals("History", rows[0].messageMarkdown)
        assertFalse(rows[1].isReady)
        assertEquals(user.id, rows[1].retryUserMessageId)
    }

    @Test fun MultipleTextBlocksShareOneFooterAndFinalActionsGetFullText() {
        val message = reply("").copy(toolBlocks = listOf(
            AssistantBlock("one", "text", "First"),
            AssistantBlock("two", "text", "Second"),
        ))
        assertEquals(1, actions(message.copy(isStreaming = true)).size)
        val footer = actions(message).single()
        assertEquals("First\n\nSecond", footer.messageMarkdown)
        assertTrue(footer.isReady)
    }

    @Test fun emptyThinkingToolAndSystemMessagesDoNotGainAFalseFooter() {
        for (streaming in listOf(false, true)) {
            for (text in listOf("", " ", "\n")) {
                assertTrue(actions(reply(text, streaming)).isEmpty())
            }
            for (kind in listOf("thinking", "tool_use", "info")) {
                val message = reply("", streaming).copy(
                    toolBlocks = listOf(AssistantBlock("block", kind, "Not reply text")),
                )
                assertTrue(actions(message).isEmpty())
            }
            assertTrue(actions(reply("Notice", streaming).copy(role = "system")).isEmpty())
            assertTrue(actions(ChatMessage("user", "user", "Question")).isEmpty())
        }
    }

    @Test fun legacyContentUsesTheSameStableFooter() {
        val live = reply("Legacy reply", true).copy(toolBlocks = emptyList())
        val pending = actions(live).single()
        val final = actions(live.copy(isStreaming = false)).single()
        assertEquals(pending.key, final.key)
        assertFalse(pending.isReady)
        assertTrue(final.isReady)
        assertEquals("Legacy reply", final.messageMarkdown)
    }

    @Test fun stoppedOrFailedPartialRepliesStillEnableTheirActions() {
        val stopped = reply("Partial")
        assertTrue(actions(stopped).single().isReady)
        val failed = actions(stopped.copy(error = "connection closed")).single()
        assertTrue(failed.isReady)
        assertEquals("Partial", failed.messageMarkdown)
    }

    @Test fun deduplicationPreservesPendingStateAndUniqueKeys() {
        val duplicate = reply("Same", true)
        val rows = actions(duplicate, duplicate)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertTrue(rows.none { it.isReady })
    }

    @Test fun equalLengthFinalEditsStillUpdateActionText() {
        val before = actions(reply("before")).single()
        val after = actions(reply("edited")).single()
        assertEquals(before.key, after.key)
        assertNotEquals(before, after)
        assertEquals("edited", after.messageMarkdown)
    }
}