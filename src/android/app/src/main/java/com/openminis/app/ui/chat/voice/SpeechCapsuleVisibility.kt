package com.openminis.app.ui.chat.voice

internal fun speechCapsuleVisible(
    enabled: Boolean,
    hasPlayback: Boolean,
    wasVisible: Boolean,
    autoClose: Boolean = true,
): Boolean = enabled && (hasPlayback || (wasVisible && !autoClose))
