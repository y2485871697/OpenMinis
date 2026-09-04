package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the stream-end "whole reply appears at once" flicker:
 * the live presenter must keep animating the last assistant text block
 * while transport streaming has just ended, rather than jumping straight
 * to the canonical final text.
 *
 * This is a unit-level sanity test for the FlatChatItem key contract.
 * The actual drain timing is exercised by the instrumented rendering tests.
 */
class LiveAssistantTextDrainTest {

    private fun message(text: String, streaming: Boolean) = ChatMessage(
        id = "m1",
        role = "assistant",
        content = text,
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "b1", kind = "text", content = text)),
    )

    @Test
    fun `latest assistant text keeps same stable key across stream end`() {
        val text = """
            ## 手机系统控制

            | 能力 | 说明 |
            | --- | --- |
            | 设备信息 | 型号、系统版本、电量、存储空间 |
            | 闹钟 | 设置一次性/每天/工作日闹钟 |
        """.trimIndent()

        val streaming = buildFlatChatItems(listOf(message(text, true)))
        val finished = buildFlatChatItems(listOf(message(text, false)))

        assertEquals(streaming.map { it.key }, finished.map { it.key })

        val liveText = streaming.filterIsInstance<FlatChatItem.AssistantText>().single()
        val doneText = finished.filterIsInstance<FlatChatItem.AssistantText>().single()
        assertEquals(liveText.key, doneText.key)
        assertEquals(liveText.block.id, doneText.block.id)
    }
}
