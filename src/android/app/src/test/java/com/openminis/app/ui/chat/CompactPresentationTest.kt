package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class CompactPresentationTest {
    @Test fun customProviderLabelTravelsWithItsModel() {
        assertEquals("DeepSeek Flash (My server)", compactModelDisplayLabel("DeepSeek Flash", "flash", "My server", "openai"))
    }
    @Test fun missingDisplayNamesFallBackToIdentifiers() {
        assertEquals("flash (deepseek)", compactModelDisplayLabel(" ", "flash", "", "deepseek"))
    }
    @Test fun absentProviderDoesNotProduceEmptyParentheses() {
        assertEquals("flash", compactModelDisplayLabel("flash", "flash", "", ""))
    }
    @Test fun labelsCannotBreakStoredMetadataHeader() {
        val label = compactModelDisplayLabel(" flash\n", "", "Server\r\nOne", "")
        assertEquals("flash (Server  One)", label)
        assertFalse(label.contains('\n'))
        assertFalse(label.contains('\r'))
    }
    @Test fun sameModelOnDifferentProvidersRemainsDistinct() {
        val a = compactModelDisplayLabel("flash", "flash", "A", "openai")
        val b = compactModelDisplayLabel("flash", "flash", "B", "openai")
        assertEquals(2, linkedSetOf(a, b).size)
    }
    private fun layout(index: Int = 1, offset: Int = 0, dragging: Boolean = false, scrolling: Boolean = false) =
        BottomFollowLayout(index, offset, 5, scrolling, dragging, 0, 700, 20, 4, emptyList())

    @Test fun compactionPinsWithoutChatStreaming() {
        assertTrue(layout().shouldPin(following = false, userScrolledAway = false, compacting = true))
        assertFalse(layout().shouldPin(following = false, userScrolledAway = false))
    }
    @Test fun compactionDoesNotOverrideManualHistoryReading() {
        assertFalse(layout().shouldPin(false, true, compacting = true))
        assertFalse(layout(dragging = true).shouldPin(false, false, compacting = true))
        assertFalse(layout(scrolling = true).shouldPin(false, false, compacting = true))
    }
    @Test fun completionContinuesPinningThroughFinalLayout() {
        assertTrue(layout().shouldPin(following = true, userScrolledAway = false, compacting = false))
        assertTrue(layout(index = 0, offset = 40).shouldPin(true, false))
        assertFalse(layout(index = 0).shouldPin(true, false))
    }
}
