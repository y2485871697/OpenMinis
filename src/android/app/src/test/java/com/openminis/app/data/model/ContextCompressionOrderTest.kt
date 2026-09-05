package com.openminis.app.data.model

import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ContextCompressionOrderTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun config(): ProviderConfig = ProviderConfig(
        instances = mutableListOf(ProviderInstance("p", "Server", ProviderType.openAI, ProviderCredential.apiKey)),
        modelEntries = mutableListOf("a", "b", "c").map {
            ModelEntry("p", LLMModel(it, it, "openai"), uuid = it)
        }.toMutableList(),
        modelGroups = mutableListOf(ModelGroup("g", "Group", mutableListOf("b", "a"))),
        contextCompressionModelEntryIds = mutableListOf("a", "c"),
        contextCompressionGroupIds = mutableListOf("g"),
    )
    @Test fun legacyOrderMatchesTheExistingScreen() {
        assertEquals(listOf("group:g", "entry:a", "entry:c"), config().contextCompressionTargets())
    }
    @Test fun modelsAndGroupsCanBeInterleaved() {
        val config = config().copy(contextCompressionOrder = listOf("entry:c", "group:g", "entry:a"))
        assertEquals(listOf("entry:c", "group:g", "entry:a"), config.contextCompressionTargets())
        assertEquals(listOf("c", "b", "a"), config.contextCompressionCandidates().map { it.id })
    }
    @Test fun memberOrderAndDuplicateModelsAreHandled() {
        val config = config().copy(contextCompressionOrder = listOf("entry:a", "group:g", "entry:c"))
        assertEquals(listOf("a", "b", "c"), config.contextCompressionCandidates().map { it.id })
    }
    @Test fun staleDuplicateAndMissingTargetsAreDiscarded() {
        val config = config().copy(contextCompressionOrder = listOf("entry:missing", "entry:c", "entry:c"))
        config.contextCompressionGroupIds.add("missing")
        assertEquals(listOf("entry:c", "group:g", "entry:a"), config.contextCompressionTargets())
    }
    @Test fun newSelectionsAreAppendedWithoutResettingPriority() {
        val config = config().copy(contextCompressionOrder = listOf("entry:c", "group:g", "entry:a"))
        config.contextCompressionModelEntryIds.add("b")
        assertEquals("entry:b", config.contextCompressionTargets().last())
    }
    @Test fun staleDragCannotRemoveSelectionsOrAddDuplicates() {
        val config = config()
        assertNull(config.reorderedContextCompressionTargets(listOf("entry:a")))
        assertNull(config.reorderedContextCompressionTargets(listOf("entry:a", "entry:c", "group:g", "group:g")))
        assertEquals(listOf("entry:c", "entry:a", "group:g"),
            config.reorderedContextCompressionTargets(listOf("entry:c", "entry:a", "group:g")))
    }
    @Test fun databaseRoundTripKeepsMixedOrderAndCanonicalEntryIds() {
        val config = config().copy(contextCompressionOrder = listOf("entry:c", "group:g", "entry:a"))
        val restored = config.toSnapshot(json).toProviderConfig(json)
        assertEquals(listOf("entry:p/c", "group:g", "entry:p/a"), restored.contextCompressionTargets())
        assertEquals(listOf("p/c", "p/b", "p/a"), restored.contextCompressionCandidates().map { it.id })
    }
    @Test fun backupJsonRoundTripPreservesPriority() {
        val config = config().copy(contextCompressionOrder = listOf("entry:c", "group:g", "entry:a"))
        val restored = json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), config))
        assertEquals(config.contextCompressionTargets(), restored.contextCompressionTargets())
    }
    @Test fun oldAndMalformedDatabaseMetadataFallBackWithoutLosingModels() {
        val snapshot = config().toSnapshot(json)
        val old = snapshot.copy(meta = snapshot.meta.filterNot { it.key == ProviderConfigMetaKeys.CONTEXT_COMPRESSION_ORDER })
        assertEquals(listOf("group:g", "entry:p/a", "entry:p/c"), old.toProviderConfig(json).contextCompressionTargets())
        val broken = snapshot.copy(meta = snapshot.meta.map {
            if (it.key == ProviderConfigMetaKeys.CONTEXT_COMPRESSION_ORDER) it.copy(value = "invalid") else it
        })
        assertEquals(old.toProviderConfig(json).contextCompressionTargets(), broken.toProviderConfig(json).contextCompressionTargets())
    }
}
