package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchPositionTest {
    @Test fun userHitKeepsItsOwnBubble() {
        assertEquals(2, chatSearchAnchorIndex(listOf("user", "assistant", "user"), 2))
    }
    @Test fun assistantHitUsesItsAvatar() {
        assertEquals(1, chatSearchAnchorIndex(listOf("user", "assistant"), 1))
    }
    @Test fun continuationUsesFirstAssistantHeader() {
        assertEquals(1, chatSearchAnchorIndex(listOf("user", "assistant", "assistant"), 2))
    }
    @Test fun systemRowsDoNotSplitAnAssistantTurn() {
        assertEquals(1, chatSearchAnchorIndex(listOf("user", "assistant", "system", "assistant"), 3))
    }
    @Test fun anchorNeverCrossesUserBoundary() {
        assertEquals(3, chatSearchAnchorIndex(listOf("user", "assistant", "user", "assistant", "assistant"), 4))
    }
    @Test fun missingHitDoesNotJump() {
        assertEquals(-1, chatSearchAnchorIndex(emptyList(), 0))
        assertEquals(-1, chatSearchAnchorIndex(listOf("user"), -1))
        assertEquals(-1, chatSearchDisplayIndex(4, -1, 1))
    }
    @Test fun bannersShiftActualLazyListIndex() {
        assertEquals(2, chatSearchDisplayIndex(5, 2, 0))
        assertEquals(3, chatSearchDisplayIndex(5, 2, 1))
        assertEquals(4, chatSearchDisplayIndex(5, 2, 2))
    }
    @Test fun newRowsDoNotChangeIdentityButDoChangeIndex() {
        assertEquals(2, chatSearchDisplayIndex(5, 2, 0))
        assertEquals(4, chatSearchDisplayIndex(7, 2, 0))
    }
    @Test fun reversedTopRetainsTopPadding() {
        val offset = chatSearchTopOffset(60, 800, 20, 4)
        assertEquals(-716, offset)
        val physicalTop = (800 - 20 - 4) + offset - 60 + 4
        assertEquals(4, physicalTop)
    }
    @Test fun keyboardResizeRequiresNewOffset() {
        assertEquals(-416, chatSearchTopOffset(60, 500, 20, 4))
        assertEquals(-716, chatSearchTopOffset(60, 800, 20, 4))
    }
    @Test fun oversizedUserBubbleAlignsItsStart() {
        assertEquals(424, chatSearchTopOffset(1200, 800, 20, 4))
    }
}
