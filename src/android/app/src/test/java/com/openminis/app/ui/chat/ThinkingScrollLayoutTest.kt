package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingScrollLayoutTest {
    private fun layout(
        offset: Int = 0,
        max: Int = 100,
        scrolling: Boolean = false,
        dragging: Boolean = false,
        pending: Boolean = false,
    ) = ThinkingScrollLayout(offset, max, scrolling, dragging, pending)

    @Test fun measuredGrowthFollowsTheNewBottom() {
        assertFalse(layout(offset = 100).shouldFollow(true, false))
        assertTrue(layout(offset = 100, max = 140).shouldFollow(true, false))
        assertFalse(layout(offset = 140, max = 140).shouldFollow(true, false))
    }

    @Test fun waitsForInitialMeasurement() {
        assertFalse(layout(max = Int.MAX_VALUE).shouldFollow(true, false))
        assertFalse(layout(max = Int.MAX_VALUE, pending = true).userGestureSettled)
        assertFalse(layout(max = 0).shouldFollow(true, false))
        assertTrue(layout(max = 1).shouldFollow(true, false))
    }

    @Test fun programmaticScrollDoesNotCountAsAUserGesture() {
        val moving = layout(scrolling = true)
        assertFalse(moving.shouldFollow(true, false))
        assertFalse(moving.userGestureSettled)
        val stopped = layout(offset = 80)
        assertFalse(stopped.userGestureSettled)
        assertTrue(stopped.shouldFollow(true, false))
    }

    @Test fun actualDraggingPausesFollowing() {
        val dragging = layout(dragging = true, pending = true)
        assertFalse(dragging.shouldFollow(true, false))
        assertFalse(dragging.userGestureSettled)
    }

    @Test fun waitsForUserFlingToSettle() {
        val fling = layout(scrolling = true, pending = true)
        assertFalse(fling.userGestureSettled)
        assertFalse(fling.shouldFollow(true, false))
        val stopped = layout(offset = 20, pending = true)
        assertTrue(stopped.userGestureSettled)
        assertFalse(stopped.atBottom)
        assertFalse(layout(offset = 20).shouldFollow(true, userScrolledAway = !stopped.atBottom))
    }

    @Test fun returningToBottomResumesFollowing() {
        val returned = layout(offset = 100, pending = true)
        assertTrue(returned.userGestureSettled)
        assertTrue(returned.atBottom)
        assertTrue(layout(offset = 100, max = 140).shouldFollow(true, userScrolledAway = !returned.atBottom))
    }

    @Test fun preservesPauseAcrossContentGrowth() {
        assertFalse(layout(offset = 20, max = 140).shouldFollow(true, true))
        assertFalse(layout(offset = 140, max = 160).shouldFollow(true, true))
    }

    @Test fun completedAndHistoricalThinkingDoesNotAutoScroll() {
        assertFalse(layout().shouldFollow(false, false))
        assertFalse(layout().shouldFollow(false, true))
    }

    @Test fun nearBottomToleranceIsLimitedToUserGestureSettlement() {
        assertTrue(layout(offset = 96, pending = true).atBottom)
        assertFalse(layout(offset = 95, pending = true).atBottom)
        assertTrue(layout(offset = 96).shouldFollow(true, false))
    }
}
