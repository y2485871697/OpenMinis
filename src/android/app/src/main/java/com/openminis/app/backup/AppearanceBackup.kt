package com.openminis.app.backup

import android.content.Context
import com.openminis.app.data.repository.AppIconRepository
import java.io.File
import org.json.JSONObject

/** Android appearance preferences embedded in the portable memory category. */
internal object AppearanceBackup {
    const val FILE_NAME = ".android_appearance.json"
    private const val PREFS_NAME = "appearance_prefs"

    private val booleanDefaults = mapOf(
        "keepScreenAwakeDuringTasks" to false,
        "tool_preview" to true,
        "chat.autoFocusAfterReply" to true,
        "appearance.show_chat_title" to true,
        "appearance.show_provider_balance" to true,
        "appearance.auto_close_read_aloud_capsule" to true,
        "chat.autoExpandThinking" to true,
        "chat.streamingHaptics" to true,
        "autoGroupingEnabled" to true,
    )
    private val intDefaults = mapOf(
        "theme_mode" to 0,
        "launch_session" to 0,
        "returnKeyBehavior" to 0,
        "font_chat_input" to 0,
        "font_message" to 0,
        "font_app_base" to 0,
    )
    private val stringDefaults = mapOf("app_language" to "")

    fun exportTo(context: Context, destination: File): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val values = JSONObject()
        booleanDefaults.forEach { (key, default) -> values.put(key, prefs.getBoolean(key, default)) }
        intDefaults.forEach { (key, default) -> values.put(key, prefs.getInt(key, default)) }
        stringDefaults.forEach { (key, default) -> values.put(key, prefs.getString(key, default)) }
        val root = JSONObject()
            .put("version", 1)
            .put("values", values)
            .put("appIcon", AppIconRepository.current(context).id)
        destination.parentFile?.mkdirs()
        destination.writeText(root.toString(2))
        return true
    }

    fun restoreFrom(context: Context, source: File): Boolean = runCatching {
        val root = JSONObject(source.readText())
        val values = root.getJSONObject("values")
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        booleanDefaults.keys.forEach { if (values.has(it)) editor.putBoolean(it, values.getBoolean(it)) }
        intDefaults.keys.forEach { if (values.has(it)) editor.putInt(it, values.getInt(it)) }
        stringDefaults.keys.forEach { if (values.has(it)) editor.putString(it, values.getString(it)) }
        val committed = editor.commit()
        val icon = AppIconRepository.Variant.fromId(root.optString("appIcon"))
        AppIconRepository.apply(context, icon)
        committed
    }.getOrDefault(false)
}
