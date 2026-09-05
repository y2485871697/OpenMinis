package com.openminis.app.ui.chat

/** ScrollState uses Int.MAX_VALUE until the first layout has measured its range. */
internal fun codeBlockHasOverflow(horizontalMax: Int, verticalMax: Int): Boolean =
    horizontalMax in 1 until Int.MAX_VALUE || verticalMax in 1 until Int.MAX_VALUE
