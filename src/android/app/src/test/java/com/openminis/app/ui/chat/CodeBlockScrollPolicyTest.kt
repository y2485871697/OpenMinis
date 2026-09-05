package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeBlockScrollPolicyTest {
    @Test fun fittingCodeNeedsNoScrollControl() {
        assertFalse(codeBlockNeedsScrollUnlock(0))
    }

    @Test fun horizontalScrollingIsIndependentOfTheUnlockButton() {
        val source = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { File(it, "com/openminis/app/ui/chat/StreamingMarkdownText.kt") }
            .first { it.isFile }.readText()
        assertTrue(source.contains(".horizontalScroll(hScroll)"))
        assertFalse(source.contains(".horizontalScroll(hScroll, enabled = codeScrollEnabled)"))
        assertFalse(source.contains("hScroll.scrollTo(0)"))
        assertTrue(source.contains("if (hasVerticalOverflow)"))
        assertTrue(source.contains("codeBlockNeedsScrollUnlock(vScroll.maxValue)"))
    }

    @Test fun verticalOverflowNeedsScrollControl() {
        assertTrue(codeBlockNeedsScrollUnlock(240))
    }

    @Test fun onePixelOfVerticalOverflowNeedsScrollControl() {
        assertTrue(codeBlockNeedsScrollUnlock(1))
    }

    @Test fun unmeasuredRangesDoNotFlashTheControl() {
        assertFalse(codeBlockNeedsScrollUnlock(Int.MAX_VALUE))
    }

    @Test fun largeMeasuredVerticalRangeNeedsScrollControl() {
        assertTrue(codeBlockNeedsScrollUnlock(Int.MAX_VALUE - 1))
    }

    @Test fun resizedViewportCanRemoveOverflow() {
        assertTrue(codeBlockNeedsScrollUnlock(20))
        assertFalse(codeBlockNeedsScrollUnlock(0))
        assertTrue(codeBlockNeedsScrollUnlock(1))
    }

    @Test fun nonPositiveRangesDoNotEnableScrolling() {
        assertFalse(codeBlockNeedsScrollUnlock(-1))
        assertFalse(codeBlockNeedsScrollUnlock(Int.MIN_VALUE))
    }
}
