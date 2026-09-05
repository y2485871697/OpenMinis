package com.openminis.app.ui.chat

/** One measured layout, including signals that must retry a deferred bottom pin. */
internal data class BottomFollowLayout(
    val firstIndex: Int,
    val firstOffset: Int,
    val totalItems: Int,
    val scrolling: Boolean,
    val userInteracting: Boolean,
    val viewportStart: Int,
    val viewportEnd: Int,
    val beforePadding: Int,
    val afterPadding: Int,
    val visibleItems: List<Triple<Int, Int, Int>>,
) {
    fun shouldPin(following: Boolean, userScrolledAway: Boolean): Boolean =
        following && !userScrolledAway && !userInteracting && !scrolling &&
            totalItems > 0 && (firstIndex != 0 || firstOffset != 0)
}