package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class FinalChatRowEqualityTest {
    @Test fun equalLengthMarkdownCorrectionsInvalidateRows() {
        fun row(text: String) = FlatChatItem.AssistantMarkdownBlock(
            "reply", "text", text, 0, true, false, text,
        )
        assertNotEquals(row("| a |"), row("| b |"))
        assertEquals(row("| a |"), row("| a |"))
    }

    @Test fun legacyEqualLengthEditsInvalidateRows() {
        assertNotEquals(
            FlatChatItem.AssistantLegacyContent("reply", "before", false),
            FlatChatItem.AssistantLegacyContent("reply", "edited", false),
        )
    }

    @Test fun terminalBodyChangeCannotHideBehindSameMessageMarkdown() {
        fun row(body: String) = FlatChatItem.AssistantText(
            "reply", AssistantBlock("text", "text", body), false, "full message",
        )
        assertNotEquals(row("partial"), row("complete"))
    }

    @Test fun terminalTransitionInvalidatesLiveRowButPreservesKey() {
        val block = AssistantBlock("text", "text", "final")
        val live = FlatChatItem.AssistantText("reply", block, true, "final")
        val final = FlatChatItem.AssistantText("reply", block, false, "final")
        assertNotEquals(live, final)
        assertEquals(live.key, final.key)
    }
}