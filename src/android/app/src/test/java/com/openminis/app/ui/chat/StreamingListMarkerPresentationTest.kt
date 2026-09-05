package com.openminis.app.ui.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamingListMarkerPresentationTest {
    @Test fun bareMarkersImmediatelyUseTheSameBulletAsTheCompletedPrefix() = runBlocking {
        for (marker in listOf("-", "  -")) {
            assertEquals(listOf("UnorderedList" to marker.trimStart()), markdownBlockPresentationForTest(marker, true))
            for (prefix in listOf("$marker ", "$marker \u5217", "$marker \u5217\u8868")) {
                assertEquals(listOf("UnorderedList" to prefix), markdownBlockPresentationForTest(prefix, true))
            }
        }
    }

    @Test fun nextListItemDoesNotFlashADashBelowExistingBullets() = runBlocking {
        val ready = "- First\n- Second"
        assertEquals(listOf("UnorderedList" to "$ready\n-"), markdownBlockPresentationForTest("$ready\n-", true))
        assertEquals(listOf("UnorderedList" to "$ready\n- Third"), markdownBlockPresentationForTest("$ready\n- Third", true))
    }

    @Test fun paragraphBeforePendingMarkerRemainsVisible() = runBlocking {
        val expected = listOf("Paragraph" to "Examples:", "UnorderedList" to "-")
        assertEquals(expected, markdownBlockPresentationForTest("Examples:\n-", true))
        assertEquals(expected, markdownBlockPresentationForTest("Examples:\n\n-", true))
    }

    @Test fun completionAndCancellationRestoreALiteralFinalMarker() = runBlocking {
        for (marker in listOf("-", "  -")) {
            assertEquals(listOf("UnorderedList" to marker.trimStart()), markdownBlockPresentationForTest(marker, true))
            assertEquals(listOf("Paragraph" to marker), markdownBlockPresentationForTest(marker, false))
            assertEquals(listOf(marker), parseMarkdownBlockRawsForTest(marker))
        }
    }

    @Test fun completedLinesAndEarlierParagraphsAreNotHidden() = runBlocking {
        assertEquals(listOf("Paragraph" to "-"), markdownBlockPresentationForTest("-\n", true))
        assertEquals(listOf("Paragraph" to "-"), markdownBlockPresentationForTest("-\r\n", true))
        assertEquals(listOf("Paragraph" to "-", "Paragraph" to "Later"), markdownBlockPresentationForTest("-\n\nLater", true))
    }

    @Test fun literalsNegativeNumbersAndInlineCodeAreUnchanged() = runBlocking {
        for (raw in listOf("-1", "-0.5", "+", "+2", "*", "**", "a -", "a-b", "\\-", "\\+", "\\*", "`-`", "`-", "--flag", "**bold**")) {
            assertEquals(raw, markdownBlockPresentationForTest(raw, false), markdownBlockPresentationForTest(raw, true))
        }
    }

    @Test fun fencedCodeAndTablesKeepLiteralDashes() = runBlocking {
        for (raw in listOf("```text\n-", "```text\n-\n```", "| A | B |\n| --- | --- |\n| - | + |")) {
            assertEquals(raw, markdownBlockPresentationForTest(raw, false), markdownBlockPresentationForTest(raw, true))
        }
    }

    @Test fun recognizedRulesOrderedAndTaskListsStayUnchanged() = runBlocking {
        for (raw in listOf("---", "***", "1. Item", "- [ ] Item", "- [x] Done")) {
            assertEquals(raw, markdownBlockPresentationForTest(raw, false), markdownBlockPresentationForTest(raw, true))
        }
    }

    @Test fun quotedPendingMarkerUsesTheSamePresentationRule() = runBlocking {
        assertEquals(listOf("UnorderedList" to "-"), markdownBlockPresentationForTest("> -", true))
        assertEquals(listOf("Paragraph" to "Intro", "UnorderedList" to "-"), markdownBlockPresentationForTest("> Intro\n> -", true))
        assertEquals(listOf("Paragraph" to "-"), markdownBlockPresentationForTest("> -", false))
    }

    @Test(timeout = 10_000) fun everyCharacterPrefixOfAMixedReplyMakesProgress() = runBlocking {
        val raw = "## Reply\n\n- One\n- **Two**\n\n```text\n-\n```\n\n- Three"
        for (end in 0..raw.length) {
            val prefix = raw.take(end)
            val canonicalBefore = parseMarkdownBlockRawsForTest(prefix)
            markdownBlockPresentationForTest(prefix, true)
            assertEquals(canonicalBefore, parseMarkdownBlockRawsForTest(prefix))
        }
        assertEquals(markdownBlockPresentationForTest(raw, false), markdownBlockPresentationForTest(raw, true))
    }
}