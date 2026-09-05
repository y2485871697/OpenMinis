package com.openminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingMarkdownProgressTest {
    // Use a real timeout on a separate dispatcher: the regressed parser is a
    // CPU loop, so a virtual-time test on the same thread cannot interrupt it.
    private fun <T> bounded(timeoutMillis: Long = 5_000, block: suspend () -> T): T = runBlocking {
        withTimeout(timeoutMillis) { withContext(Dispatchers.Default) { block() } }
    }

    private fun withoutWhitespace(text: String): String = text.filterNot { it.isWhitespace() }

    @Test fun bareHeadingsHaveARecognizedLevel() {
        for (level in 1..6) {
            assertEquals(level, markdownHeadingLevel("#".repeat(level)))
            assertEquals(level, markdownHeadingLevel("#".repeat(level) + " heading"))
            assertEquals(level, markdownHeadingLevel("#".repeat(level) + "\theading"))
        }
    }

    @Test fun hashPrefixedProseIsNotAHeading() {
        for (text in listOf("", "plain text", "#tag", "##title", "#42", "#######", "####### heading", "#\u4e2d\u6587")) {
            assertNull(text, markdownHeadingLevel(text))
        }
    }

    @Test(timeout = 10_000) fun everyBareHeadingPrefixTerminatesAndPreservesItsText() {
        bounded {
            for (length in 1..12) {
                val input = "#".repeat(length)
                assertEquals(input, listOf(input), parseMarkdownBlockRawsForTest(input))
            }
        }
    }

    @Test(timeout = 10_000) fun hashPrefixedProseIsConsumedInsteadOfRetried() {
        bounded {
            for (input in listOf("#tag", "##title", "#42", "####### heading", "#\u4e2d\u6587", "#\\path")) {
                assertEquals(input, listOf(input), parseMarkdownBlockRawsForTest(input))
                val paragraph = "before\n$input\nafter"
                assertEquals(listOf(paragraph), parseMarkdownBlockRawsForTest(paragraph))
            }
        }
    }

    @Test(timeout = 10_000) fun completeHeadingsSeparateParagraphs() {
        bounded {
            for (marker in listOf("##", "### title", "######\ttitle")) {
                assertEquals(
                    listOf("before", marker, "after"),
                    parseMarkdownBlockRawsForTest("before\n$marker\nafter"),
                )
            }
        }
    }

    @Test(timeout = 10_000) fun tableFollowedByAnIncompleteHeadingDoesNotBlockTheParser() {
        bounded {
            val table = "| Capability | Description |\n| --- | --- |\n| Browser | Open pages |"
            for (length in 1..8) {
                val tail = "#".repeat(length)
                assertEquals(listOf(table, tail), parseMarkdownBlockRawsForTest("$table\n\n$tail"))
            }
        }
    }

    @Test(timeout = 40_000) fun everyPrefixOfAMultiTableReplyCompletesWithoutLosingText() {
        bounded(30_000) {
            val reply = buildString {
                append("# Capabilities\n\nA streaming reply.\n\n")
                for (section in 1..3) {
                    append("## Section $section\n\n| Capability | Description |\n| --- | --- |\n")
                    for (row in 1..14) {
                        append("| Action $row | Read text, click, and scroll $section/$row |\n")
                    }
                    append("\n---\n\n")
                }
                append("## \u914d\u7f6e\u7ba1\u7406\n\n#literal is ordinary text.\n\nAll done.")
            }
            val nodeBuffer = StreamingMarkdownNodeBuffer()
            for (end in 0..reply.length) {
                val prefix = reply.take(end)
                val expected = withoutWhitespace(prefix)
                val frozen = parseMarkdownBlockRawsForTest(prefix).joinToString("\n")
                assertEquals("frozen prefix $end", expected, withoutWhitespace(frozen))
                val liveBlocks = mutableListOf<String>()
                for (fragment in nodeBuffer.update(prefix)) {
                    liveBlocks += parseMarkdownBlockRawsForTest(fragment)
                }
                assertEquals("live prefix $end", expected, withoutWhitespace(liveBlocks.joinToString("\n")))
            }
        }
    }

    @Test(timeout = 10_000) fun replacementAndResetSnapshotsAlsoMakeProgress() {
        bounded {
            val buffer = StreamingMarkdownNodeBuffer()
            val snapshots = listOf("##", "## heading\n\ntext", "#tag", "#", "", "###\n\nend", "short")
            repeat(2) {
                for (snapshot in snapshots) {
                    val raws = mutableListOf<String>()
                    for (fragment in buffer.update(snapshot)) {
                        raws += parseMarkdownBlockRawsForTest(fragment)
                    }
                    assertEquals(withoutWhitespace(snapshot), withoutWhitespace(raws.joinToString("\n")))
                }
                buffer.reset()
            }
        }
    }
}
