package com.openminis.app.ui.chat

internal fun chatSearchAnchorIndex(roles: List<String>, hitIndex: Int): Int {
    if (hitIndex !in roles.indices) return -1
    if (roles[hitIndex] != "assistant") return hitIndex
    var anchor = hitIndex
    for (index in hitIndex - 1 downTo 0) {
        when (roles[index]) {
            "system" -> continue
            "assistant" -> anchor = index
            else -> break
        }
    }
    return anchor
}

internal fun chatSearchDisplayIndex(rowCount: Int, originalIndex: Int, leadingRows: Int): Int =
    if (originalIndex in 0 until rowCount) leadingRows + rowCount - 1 - originalIndex else -1

// Reverse layout measures from the bottom inside BOTH content paddings.
internal fun chatSearchTopOffset(rowSize: Int, viewportHeight: Int, before: Int, after: Int): Int =
    rowSize - (viewportHeight - before - after).coerceAtLeast(0)
