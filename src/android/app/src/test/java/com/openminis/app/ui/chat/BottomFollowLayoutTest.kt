package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class BottomFollowLayoutTest {
    private val bottom = BottomFollowLayout(
        firstIndex = 0, firstOffset = 0, totalItems = 20,
        scrolling = false, userInteracting = false,
        viewportStart = -20, viewportEnd = 800,
        beforePadding = 20, afterPadding = 4,
        visibleItems = listOf(Triple(0, 0, 48)),
    )

    @Test fun indexZeroWithOffsetStillNeedsAPin() {
        assertTrue(bottom.copy(firstOffset = 60).shouldPin(true, false))
    }

    @Test fun terminalFooterInsertionDoesNotNeedToBeVisibleFirst() {
        val afterFooter = bottom.copy(firstIndex = 1, visibleItems = listOf(Triple(1, 0, 900)))
        assertTrue(afterFooter.shouldPin(true, false))
    }

    @Test fun exactBottomDoesNotScheduleAnotherLayout() {
        assertFalse(bottom.shouldPin(true, false))
        assertFalse(bottom.copy(visibleItems = listOf(Triple(0, 0, 15000))).shouldPin(true, false))
    }

    @Test fun historyReadersAreNeverPulledToBottom() {
        assertFalse(bottom.copy(firstIndex = 12).shouldPin(true, true))
    }

    @Test fun draggingAndFlingSuppressPins() {
        assertFalse(bottom.copy(firstOffset = 60, userInteracting = true).shouldPin(true, false))
        assertFalse(bottom.copy(firstOffset = 60, scrolling = true).shouldPin(true, false))
    }

    @Test fun scrollEndEmitsANewSnapshotAndRetries() {
        val busy = bottom.copy(firstOffset = 60, scrolling = true)
        val idle = busy.copy(scrolling = false)
        assertNotEquals(busy, idle)
        assertFalse(busy.shouldPin(true, false))
        assertTrue(idle.shouldPin(true, false))
    }

    @Test fun emptyAndUnfollowedSessionsDoNotScroll() {
        assertFalse(bottom.copy(totalItems = 0, firstIndex = 1).shouldPin(true, false))
        assertFalse(bottom.copy(firstOffset = 60).shouldPin(false, false))
    }

    @Test fun lateViewportAndPaddingChangesRemainObservable() {
        val resized = bottom.copy(viewportEnd = 600, beforePadding = 80)
        assertNotEquals(bottom, resized)
        assertTrue(resized.copy(firstOffset = 60).shouldPin(true, false))
    }

    @Test fun lateMarkdownMeasurementRemainsObservable() {
        val measured = bottom.copy(visibleItems = listOf(Triple(0, 0, 120), Triple(1, 120, 2500)))
        assertNotEquals(bottom, measured)
        assertFalse(measured.shouldPin(true, false))
        assertTrue(measured.copy(firstIndex = 1).shouldPin(true, false))
    }
}