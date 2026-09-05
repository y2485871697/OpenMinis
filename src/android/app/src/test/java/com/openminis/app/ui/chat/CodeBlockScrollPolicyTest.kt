package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockScrollPolicyTest {
    @Test fun fittingCodeNeedsNoScrollControl() {
        assertFalse(codeBlockHasOverflow(0, 0))
    }

    @Test fun horizontalOverflowNeedsScrollControl() {
        assertTrue(codeBlockHasOverflow(120, 0))
    }

    @Test fun verticalOverflowNeedsScrollControl() {
        assertTrue(codeBlockHasOverflow(0, 240))
    }

    @Test fun overflowInBothDirectionsNeedsScrollControl() {
        assertTrue(codeBlockHasOverflow(120, 240))
    }

    @Test fun unmeasuredRangesDoNotFlashTheControl() {
        assertFalse(codeBlockHasOverflow(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(codeBlockHasOverflow(0, Int.MAX_VALUE))
        assertFalse(codeBlockHasOverflow(Int.MAX_VALUE, 0))
    }

    @Test fun oneMeasuredOverflowIsEnough() {
        assertTrue(codeBlockHasOverflow(1, Int.MAX_VALUE))
        assertTrue(codeBlockHasOverflow(Int.MAX_VALUE, 1))
        assertTrue(codeBlockHasOverflow(Int.MAX_VALUE - 1, 0))
    }

    @Test fun resizedViewportCanRemoveOverflow() {
        assertTrue(codeBlockHasOverflow(60, 20))
        assertFalse(codeBlockHasOverflow(0, 0))
        assertTrue(codeBlockHasOverflow(0, 1))
    }

    @Test fun nonPositiveRangesDoNotEnableScrolling() {
        assertFalse(codeBlockHasOverflow(-1, 0))
        assertFalse(codeBlockHasOverflow(0, -1))
    }
}
