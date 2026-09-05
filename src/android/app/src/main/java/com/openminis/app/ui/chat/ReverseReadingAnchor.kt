package com.openminis.app.ui.chat

internal data class ReverseReadingAnchor(
    val key: Any,
    val size: Int,
    val scrollOffset: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val beforePadding: Int = 0,
    val afterPadding: Int = 0,
) {
    // reverseLayout anchors the bottom of its first visible row. Added height
    // needs an equal positive scroll delta to keep its existing text in place.
    fun growthSince(previous: ReverseReadingAnchor?): Int {
        if (previous == null || key != previous.key ||
            viewportWidth != previous.viewportWidth || viewportHeight != previous.viewportHeight ||
            beforePadding != previous.beforePadding || afterPadding != previous.afterPadding) return 0
        return (size - previous.size).coerceAtLeast(0)
    }
}
