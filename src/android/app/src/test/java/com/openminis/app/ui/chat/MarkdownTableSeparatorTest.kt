package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableSeparatorTest {
    @Test
    fun `plain horizontal rule is not a table separator`() {
        assertFalse("---".matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")))
    }

    @Test
    fun `multi column separator is recognized`() {
        assertTrue("| --- | --- |".matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")))
        assertTrue("| :--- | ---: |".matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")))
    }
}
