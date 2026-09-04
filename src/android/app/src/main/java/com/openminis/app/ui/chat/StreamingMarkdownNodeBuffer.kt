package com.openminis.app.ui.chat

/**
 * Append-only block boundary tracker for live Markdown.
 *
 * This is intentionally parser-agnostic: it never rewrites already emitted
 * fragments and only returns the stable prefix plus one mutable tail. The
 * renderer can therefore keep stable Compose keys for completed paragraphs,
 * table/fence blocks, and parse only the tail on the next provider update.
 */
internal class StreamingMarkdownNodeBuffer {
    private var previous = ""
    private var stablePrefix = emptyList<String>()

    fun update(snapshot: String): List<String> {
        if (snapshot == previous) return stablePrefix
        if (!snapshot.startsWith(previous)) {
            previous = snapshot
            stablePrefix = splitMarkdownIntoBlockTexts(snapshot)
            return stablePrefix
        }
        previous = snapshot
        val fragments = splitMarkdownIntoBlockTexts(snapshot)
        if (fragments.size <= 1) {
            stablePrefix = fragments
            return fragments
        }
        // Only fragments before the final live fragment are append-only. The
        // final fragment may still be an unfinished paragraph/table/fence.
        val completed = fragments.dropLast(1)
        if (completed.size >= stablePrefix.size) {
            stablePrefix = completed + fragments.last()
        } else {
            // A boundary can be reclassified while a table/fence is forming;
            // keep the newest complete snapshot, never a stale tail.
            stablePrefix = fragments
        }
        return stablePrefix
    }

    fun reset() {
        previous = ""
        stablePrefix = emptyList()
    }
}
