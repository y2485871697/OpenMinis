package com.openminis.app.backup

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Android appearance preferences embedded in the portable memory category. */
internal object AppearanceBackup {
    const val FILE_NAME = ".android_appearance.json"
    private const val PREFS_NAME = "appearance_prefs"

    private val booleanKeys = setOf(
        "keepScreenAwakeDuringTasks",
        "tool_preview",
        "chat.autoFocusAfterReply",
        "appearance.show_chat_title",
        "chat.autoExpandThinking",
        "chat.streamingHaptics",
        "autoGroupingEnabled",
    )
    private val intKeys = setOf(
        "theme_mode",
        "launch_session",
        "returnKeyBehavior",
        "font_chat_input",
        "font_message",
        "font_app_base",
    )
    private val stringKeys = setOf("app_language")

    fun exportTo(context: Context, destination: File): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val values = JSONObject()
        booleanKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getBoolean(it, false)) }
        intKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getInt(it, 0)) }
        stringKeys.forEach { if (prefs.contains(it)) values.put(it, prefs.getString(it, "")) }
        val root = JSONObject().put("version", 1).put("values", values)
        destination.parentFile?.mkdirs()
        destination.writeText(root.toString(2))
        return true
    }

    fun restoreFrom(context: Context, source: File): Boolean = runCatching {
        val values = JSONObject(source.readText()).getJSONObject("values")
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        booleanKeys.forEach { if (values.has(it)) editor.putBoolean(it, values.getBoolean(it)) }
        intKeys.forEach { if (values.has(it)) editor.putInt(it, values.getInt(it)) }
        stringKeys.forEach { if (values.has(it)) editor.putString(it, values.getString(it)) }
        editor.commit()
    }.getOrDefault(false)
}
