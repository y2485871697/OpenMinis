package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAssistantTextItemTest {

    private fun message(text: String, streaming: Boolean) = ChatMessage(
        id = "m1",
        role = "assistant",
        content = text,
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "b1", kind = "text", content = text)),
    )

    @Test
    fun `latest assistant text stays one stable item while table grows`() {
        val prefix = """
            说明

            ## 标题

            | 能力 | 说明 |
            | --- | --- |
            | 设备信息 | 型号
        """.trimIndent()
        val longer = "$prefix、系统版本、电量 |\n| 闹钟 | 设置"

        val first = buildFlatChatItems(listOf(message(prefix, true)))
        val second = buildFlatChatItems(listOf(message(longer, true)))

        assertEquals(2, first.size) // header + one stable text item
        assertEquals(first.map { it.key }, second.map { it.key })
        assertTrue(first.last() is FlatChatItem.AssistantText)
        assertTrue(second.last() is FlatChatItem.AssistantText)
    }

    @Test
    fun `stable text item key survives stream completion`() {
        val text = "| A | B |\n| --- | --- |\n| x | y |"
        val live = buildFlatChatItems(listOf(message(text, true)))
            .filterIsInstance<FlatChatItem.AssistantText>()
            .single()
        val done = buildFlatChatItems(listOf(message(text, false)))
            .filterIsInstance<FlatChatItem.AssistantText>()
            .single()

        assertEquals(live.key, done.key)
    }
}
