package com.openminis.app.ui.chat

internal data class ChatScrollGestureLayout(
    val firstIndex: Int,
    val firstOffset: Int,
    val totalItems: Int,
    val scrolling: Boolean,
    val dragging: Boolean,
    val awaitingSettle: Boolean,
) {
    val shouldSettle: Boolean
        get() = totalItems > 0 && awaitingSettle && !dragging && !scrolling

    // Proximity is useful for displaying controls, never for overriding a user's pause.
    val atBottom: Boolean
        get() = firstIndex == 0 && firstOffset == 0
}
