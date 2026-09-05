package com.openminis.app.ui.chat

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamingMarkupPresentationTest {
    @Test fun italicOpenersNeverFlashBeforeStyledText() {
        for (marker in listOf("*", "_")) {
            assertEquals("", parseInlinePresentationForTest(marker, true).text)
            for (text in listOf("${marker}word", "${marker}word$marker")) {
                val parsed = parseInlinePresentationForTest(text, true)
                assertEquals("word", parsed.text)
                assertTrue(parsed.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
            }
        }
    }

    @Test fun strikeAndBoldControlPrefixesStayInvisible() {
        for (prefix in listOf("*", "**", "_", "__", "~", "~~")) {
            assertEquals(prefix, "", parseInlinePresentationForTest(prefix, true).text)
        }
        val strike = parseInlinePresentationForTest("~~text", true)
        assertEquals("text", strike.text)
        assertTrue(strike.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test fun unfinishedLinksShowOnlyTheirLabelAndAreNotClickable() {
        for (source in listOf("[", "[l", "[label", "[label]", "[label](", "[label](https://example.com")) {
            val parsed = parseInlinePresentationForTest(source, true)
            assertFalse(source, parsed.text.contains('['))
            assertFalse(source, parsed.text.contains(']'))
            assertFalse(source, parsed.text.contains("https"))
            assertTrue(parsed.getStringAnnotations("url", 0, parsed.length).isEmpty())
        }
        val final = parseInlinePresentationForTest("[label](https://example.com)", true)
        assertEquals("label", final.text)
        assertEquals("https://example.com", final.getStringAnnotations("url", 0, final.length).single().item)
    }

    @Test fun incompleteImagesDoNotExposeTheirSyntaxOrDestination() {
        assertEquals("", parseInlinePresentationForTest("!", true).text)
        for (source in listOf("![alt", "![alt]", "![alt](https://example.com/image.png")) {
            assertEquals("[alt]", parseInlinePresentationForTest(source, true).text)
        }
        assertEquals("Hello!", parseInlinePresentationForTest("Hello!", true).text)
    }

    @Test fun nestedFormattingUsesTheSameStreamingRules() {
        val nested = parseInlinePresentationForTest("**[label](https://example.com", true)
        assertEquals("label", nested.text)
        assertTrue(nested.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals("text", parseInlinePresentationForTest("**_text", true).text)
    }

    @Test fun escapedMarkersCodeAndNumericBracketsRemainLiteral() {
        for (source in listOf("\\*", "\\_", "\\~", "\\[", "name_value", "[1,2", "[123]", "cost $5", "a+b")) {
            assertEquals(source, parseInlinePresentationForTest(source, false).text, parseInlinePresentationForTest(source, true).text)
        }
        for (source in listOf("`[label](url)`", "`*literal*`", "`-`")) {
            assertEquals(parseInlinePresentationForTest(source, false), parseInlinePresentationForTest(source, true))
        }
    }

    @Test fun finalUnmatchedSymbolsRestoreTheirExactLiteralText() {
        for (source in listOf("*word", "_word", "~~word", "[label", "!", "\\")) {
            assertEquals(source, parseInlinePresentationForTest(source, false).text)
        }
    }

    @Test fun taskPrefixesRenderACheckboxBeforeTheClosingSpace() = runBlocking {
        for (source in listOf("- [ ", "- [x", "- [X", "- [ ]", "- [x]", "- [X]", "- [ ] item")) {
            assertEquals(source, "TaskList", markdownBlockPresentationForTest(source, true).single().first)
        }
    }

    @Test fun pipeHeadersAndRulesDoNotFlashTheirSource() = runBlocking {
        for (source in listOf("|", "| A", "| A | B |", "| A | B |\n", "| A | B |\n| --- | --- |")) {
            assertEquals(source, "Table", markdownBlockPresentationForTest(source, true).single().first)
        }
        assertEquals("HorizontalRule", markdownBlockPresentationForTest("--", true).single().first)
        assertEquals("Paragraph", markdownBlockPresentationForTest("--", false).single().first)
        assertEquals("UnorderedList", markdownBlockPresentationForTest("+", true).single().first)
    }

    @Test fun headingsQuotesAndCodeFencesAlreadyKeepStructuralMarkersOutOfProse() = runBlocking {
        for (source in listOf("#", "##", "### Heading")) {
            assertEquals("Heading", markdownBlockPresentationForTest(source, true).single().first)
        }
        assertEquals("Paragraph" to "Text", markdownBlockPresentationForTest("> Text", true).single())
        assertEquals("CodeBlock", markdownBlockPresentationForTest("```kotlin\nval x = 1", true).single().first)
    }

    @Test fun literalFrozenTablesAndTaskPrefixesDoNotInheritPreviewState() = runBlocking {
        for (source in listOf("| A | B |", "- [x", "+", "--")) {
            val before = markdownBlockPresentationForTest(source, false)
            markdownBlockPresentationForTest(source, true)
            assertEquals(before, markdownBlockPresentationForTest(source, false))
        }
    }

    @Test(timeout = 10_000) fun everyInlinePrefixTerminatesWithoutLosingTheFinalResult() {
        for (source in listOf("**bold**", "*italic*", "~~gone~~", "[label](https://example.com)", "![alt](image.png)", "**[label](url)**")) {
            for (end in 0..source.length) parseInlinePresentationForTest(source.take(end), true)
            assertEquals(source, parseInlinePresentationForTest(source, false), parseInlinePresentationForTest(source, true))
        }
    }
}