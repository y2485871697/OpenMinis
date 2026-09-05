package com.openminis.app.ui.chat

/** ScrollState uses Int.MAX_VALUE until the first layout has measured its range. */
internal fun codeBlockNeedsScrollUnlock(verticalMax: Int): Boolean =
    verticalMax in 1 until Int.MAX_VALUE
