package com.openminis.app.ui.chat.voice

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SpeechCapsuleVisibilityTest {
    @Test fun defaultClosesAfterPlaybackEnds() {
        assertTrue(speechCapsuleVisible(true, true, false))
        assertFalse(speechCapsuleVisible(true, false, true))
    }
    @Test fun disabledAutoCloseRetainsTheWindow() {
        assertTrue(speechCapsuleVisible(true, false, true, autoClose = false))
    }
    @Test fun idleStartupDoesNotCreateAWindow() {
        assertFalse(speechCapsuleVisible(true, false, false, autoClose = false))
    }
    @Test fun pausedAndSynthesizingPlaybackRemainVisible() {
        for (autoClose in listOf(true, false)) {
            assertTrue(speechCapsuleVisible(true, true, true, autoClose))
        }
    }
    @Test fun manualCloseAndDisablingSpeechAlwaysHide() {
        assertFalse(speechCapsuleVisible(false, true, true, autoClose = false))
        assertFalse(speechCapsuleVisible(true, false, false, autoClose = false))
    }
    @Test fun changingSettingToEnabledHidesAnIdleWindow() {
        assertFalse(speechCapsuleVisible(true, false, true, autoClose = true))
    }
    @Test fun newPlaybackReopensAnAutomaticallyClosedWindow() {
        assertTrue(speechCapsuleVisible(true, true, false, autoClose = true))
    }
    @Test fun preferenceAndBackupUseTheSameDefault() {
        fun source(path: String) = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .map { File(it, "com/openminis/app/$path.kt") }.first { it.isFile }.readText()
        val settings = source("ui/settings/AppearanceScreen")
        assertTrue(settings.contains("getBoolean(KEY_AUTO_CLOSE_READ_ALOUD_CAPSULE, true)"))
        assertTrue(settings.contains("subtitle = stringResource(R.string.appearance_show_provider_balance_subtitle)"))
        val capsule = source("ui/chat/voice/SpeechPlayerCapsule")
        assertTrue(capsule.contains("speechCapsuleVisible("))
        assertTrue(capsule.contains(".padding(horizontal = 23.dp)"))
        assertEquals(2, Regex("modifier = progressModifier,").findAll(capsule).count())
        assertTrue(capsule.contains("Modifier.width(capsuleWidth)"))
        assertFalse(capsule.contains("Modifier.fillMaxWidth().height(2.dp)"))
        assertTrue(capsule.contains("unregisterOnSharedPreferenceChangeListener(listener)"))
        assertTrue(capsule.contains("enabled = activePlayer != null && hasPlayback"))
        assertTrue(source("backup/AppearanceBackup").contains("\"appearance.auto_close_read_aloud_capsule\" to true"))
        val header = source("ui/chat/StandardChatSheet")
        assertTrue(header.contains("Arrangement.spacedBy(2.dp)"))
        assertFalse(header.contains("bottom = 8.dp"))
    }
}
