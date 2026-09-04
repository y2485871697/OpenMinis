package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTableDetectionTest {

    @Test
    fun `detects table after intro blank line and heading`() {
        val markdown = """
            好，全量列一下：

            ## 📱 手机系统控制

            | 能力 | 说明 |
            | --- | --- |
            | 设备信息 | 型号、系统版本、电量、存储空间 |
        """.trimIndent()

        assertTrue(looksLikeMarkdownTable(markdown))
    }

    @Test
    fun `does not treat prose containing pipes as a table`() {
        assertFalse(looksLikeMarkdownTable("运行 cat a | grep b，然后继续。"))
    }
}
