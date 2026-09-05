package com.openminis.app.ui.chat

internal data class ThinkingScrollLayout(
    val offset: Int,
    val maxOffset: Int,
    val scrolling: Boolean,
    val userDragging: Boolean,
    val userScrollPending: Boolean,
) {
    private val measured: Boolean get() = maxOffset in 0 until Int.MAX_VALUE
    val atBottom: Boolean get() = measured && maxOffset - offset <= 4
    val userGestureSettled: Boolean
        get() = measured && userScrollPending && !userDragging && !scrolling

    fun shouldFollow(streaming: Boolean, userScrolledAway: Boolean): Boolean =
        measured && streaming && !userScrolledAway && !userDragging &&
            !userScrollPending && !scrolling && offset < maxOffset
}
