package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AssistantProfile(
    val id: String,
    val metadata: SoulMetadata,
)

/**
 * Stores multiple assistant personalities while keeping SOUL.md as the active,
 * backwards-compatible runtime contract used by the agent and CLI.
 */
object AssistantProfileStore {
    private const val TAG = "AssistantProfiles"
    private const val DIRECTORY_NAME = "assistants"
    private const val INDEX_NAME = "index.json"
    private const val DEFAULT_ID = "default"

    private val _profiles = MutableStateFlow<List<AssistantProfile>>(emptyList())
    val profiles: StateFlow<List<AssistantProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(DEFAULT_ID)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    @Synchronized
    fun ensure(context: Context) {
        SoulStore.ensureExists(context)
        val directory = directory(context).apply { mkdirs() }
        var index = readIndex(context)
        if (index == null || index.ids.isEmpty()) {
            val canonical = SoulStore.fileLocation(context)
            val initial = if (canonical.isFile) canonical.readText() else SoulStore.DEFAULT_CONTENT
            atomicWrite(profileFile(context, DEFAULT_ID), initial)
            index = ProfileIndex(DEFAULT_ID, listOf(DEFAULT_ID))
            writeIndex(context, index)
        }

        val existingIds = index.ids.filter { profileFile(context, it).isFile }
        val ids = if (existingIds.isEmpty()) {
            atomicWrite(profileFile(context, DEFAULT_ID), SoulStore.DEFAULT_CONTENT)
            listOf(DEFAULT_ID)
        } else {
            existingIds
        }
        val activeId = index.activeId.takeIf { it in ids } ?: ids.first()

        // SOUL.md remains authoritative for the active profile. This also
        // captures edits made by minis-config or restored from an older backup.
        SoulStore.fileLocation(context).takeIf { it.isFile }?.let { canonical ->
            atomicWrite(profileFile(context, activeId), canonical.readText())
        }
        val normalized = ProfileIndex(activeId, ids)
        if (normalized != index) writeIndex(context, normalized)
        publish(context, normalized)
    }

    @Synchronized
    fun loadProfile(context: Context, id: String): SoulFile? {
        ensure(context)
        val file = profileFile(context, id)
        return if (file.isFile) runCatching { SoulMDParser.parse(file.readText()) }.getOrNull() else null
    }

    @Synchronized
    fun createProfile(context: Context): String {
        ensure(context)
        val index = readIndex(context) ?: ProfileIndex(DEFAULT_ID, listOf(DEFAULT_ID))
        val id = UUID.randomUUID().toString()
        val defaultFile = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        val created = defaultFile.copy(
            metadata = defaultFile.metadata.copy(name = "New Assistant"),
        )
        atomicWrite(profileFile(context, id), SoulMDParser.serialize(created))
        val updated = index.copy(ids = index.ids + id)
        writeIndex(context, updated)
        publish(context, updated)
        return id
    }

    @Synchronized
    fun saveProfile(context: Context, id: String, file: SoulFile) {
        ensure(context)
        val index = readIndex(context) ?: error("Assistant profile index is unavailable")
        require(id in index.ids) { "Assistant profile was not found" }
        atomicWrite(profileFile(context, id), SoulMDParser.serialize(file))
        if (id == index.activeId) SoulStore.save(context, file)
        publish(context, index)
    }

    @Synchronized
    fun selectProfile(context: Context, id: String) {
        ensure(context)
        val index = readIndex(context) ?: error("Assistant profile index is unavailable")
        require(id in index.ids) { "Assistant profile was not found" }
        if (id == index.activeId) return

        SoulStore.fileLocation(context).takeIf { it.isFile }?.let { canonical ->
            atomicWrite(profileFile(context, index.activeId), canonical.readText())
        }
        val selected = profileFile(context, id)
        require(selected.isFile) { "Assistant profile file was not found" }
        val parsed = SoulMDParser.parse(selected.readText())
        SoulStore.save(context, parsed)

        val updated = index.copy(activeId = id)
        writeIndex(context, updated)
        publish(context, updated)
    }

    @Synchronized
    fun refreshAfterRestore(context: Context) {
        ensure(context)
        SoulStore.refreshCache(context)
    }

    private fun publish(context: Context, index: ProfileIndex) {
        _profiles.value = index.ids.mapNotNull { id ->
            runCatching {
                AssistantProfile(id, SoulMDParser.parse(profileFile(context, id).readText()).metadata)
            }.onFailure { AppLogger.warning(TAG, "profile $id is unreadable: ${it.message}") }
                .getOrNull()
        }
        _activeProfileId.value = index.activeId
    }

    private fun directory(context: Context): File =
        File(SoulStore.fileLocation(context).parentFile, DIRECTORY_NAME)

    private fun profileFile(context: Context, id: String): File =
        File(directory(context), "$id.md")

    private fun indexFile(context: Context): File = File(directory(context), INDEX_NAME)

    private fun readIndex(context: Context): ProfileIndex? = runCatching {
        val file = indexFile(context)
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText())
        val array = json.optJSONArray("profiles") ?: JSONArray()
        val ids = buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
        ProfileIndex(json.optString("activeId", DEFAULT_ID), ids)
    }.onFailure { AppLogger.warning(TAG, "index read failed: ${it.message}") }.getOrNull()

    private fun writeIndex(context: Context, index: ProfileIndex) {
        val profiles = JSONArray().apply {
            index.ids.forEach { put(JSONObject().put("id", it)) }
        }
        val json = JSONObject()
            .put("version", 1)
            .put("activeId", index.activeId)
            .put("profiles", profiles)
        atomicWrite(indexFile(context), json.toString(2))
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(target)) {
            target.writeText(text)
            temporary.delete()
        }
    }

    private data class ProfileIndex(val activeId: String, val ids: List<String>)
}
